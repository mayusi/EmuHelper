package io.github.mayusi.emuhelper.data.source

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * THE corruption guard for the adaptive (chunk-queue work-stealing) engine.
 *
 * It models exactly what the real worker loop does — minus the network — against an in-memory
 * "source" byte array of known content. N concurrent worker THREADS drain a shared [ChunkQueue];
 * "downloading" a chunk means copying that absolute byte range from the source into a shared
 * output array via idempotent absolute-offset writes, with INJECTED random failures that abort a
 * chunk MID-WAY and requeue the WHOLE chunk (the same idempotent-overwrite property the engine
 * relies on). A chunk is marked DONE only after its FULL range is copied.
 *
 * The invariant proven here: no matter how the work is stolen, retried, or partially written and
 * then requeued, the reconstructed output equals the source at EVERY offset. If the work-stealing
 * model could ever corrupt bytes (a gap, an overlap onto a neighbour, a partial-then-done), this
 * test would catch it.
 */
class ByteCoverageTest {

    /**
     * Run the simulation once for a given (size, chunkSize, workerCount, failureRate, seed) and
     * return the reconstructed output array. The harness mirrors the engine's correctness rules:
     *   - writes are absolute-offset (raf.seek(start)) idempotent overwrites,
     *   - a chunk is DONE only when its full range was written (short copy -> requeue),
     *   - on injected failure the WHOLE chunk is requeued for any worker,
     *   - workers exit only when the queue is drained AND nothing is in flight.
     */
    private fun simulate(
        size: Int,
        chunkSize: Long,
        workerCount: Int,
        failureRate: Double,
        seed: Long,
        // The REAL per-chunk error cap. 10_000 (the old default) HID the bug; the new variant runs
        // with the production value (5) plus stall injection to prove it still completes.
        maxErrorAttempts: Int = 10_000,
        // Probability that an attempt SOFT/HARD-STALLS partway (requeueStall — own large budget,
        // never counts against the error cap). A high stall rate must NOT fail the download.
        stallRate: Double = 0.0
    ): ByteArray {
        // Deterministic "source" content: each byte is a function of its absolute offset, so a
        // misplaced byte (wrong offset) is detectable, not just a missing one.
        val source = ByteArray(size) { i -> ((i * 31 + 7) and 0xFF).toByte() }
        val output = ByteArray(size) { 0 }

        val queue = ChunkQueue(partitionChunks(size.toLong(), chunkSize))
        val failure = AtomicReference<Throwable?>(null)
        val ready = CountDownLatch(workerCount)
        val go = CountDownLatch(1)
        // Each worker gets its own RNG derived from the shared seed for reproducibility.
        val workerSeed = AtomicInteger(0)

        val threads = (0 until workerCount).map {
            Thread {
                val rnd = Random(seed + workerSeed.getAndIncrement() * 1009L)
                ready.countDown()
                go.await()
                try {
                    while (queue.shouldKeepWorking()) {
                        val chunk = queue.poll()
                        if (chunk == null) {
                            // No claimable work right now but peers may requeue; yield and retry.
                            Thread.yield()
                            continue
                        }
                        val start = chunk.start.toInt()
                        val len = chunk.length.toInt()

                        // A STALL aborts the attempt and requeues via the STALL budget (never the
                        // error budget). Decided before an error so stalls dominate the churn — this
                        // is exactly the soft-stall storm that used to exhaust the shared budget.
                        val willStall = rnd.nextDouble() < stallRate
                        // Decide up front whether this attempt will (separately) FAIL partway through.
                        val willFail = !willStall && rnd.nextDouble() < failureRate
                        // If aborting (stall or fail), stop after copying a random prefix.
                        val abortAfter = if (willStall || willFail) rnd.nextInt(len) else len

                        var written = 0
                        var i = 0
                        while (i < len) {
                            if ((willStall || willFail) && written >= abortAfter) break
                            // Idempotent absolute-offset write: output[start + i] = source[start + i].
                            // Copy in small bursts to interleave with other threads.
                            val burst = minOf(1 + rnd.nextInt(64), len - i)
                            System.arraycopy(source, start + i, output, start + i, burst)
                            i += burst
                            written += burst
                        }

                        // DONE only if the FULL range was written; otherwise requeue the WHOLE chunk.
                        if (written == len) {
                            queue.markDone(chunk)
                        } else if (willStall) {
                            // Fix A: a stall requeues via the STALL budget — it must NEVER exhaust
                            // the error cap and so can never fail the download, only migrate.
                            queue.requeueStall(chunk)
                        } else {
                            // A mid-chunk abort models a real transport short-read -> error budget.
                            val exhausted = queue.recordErrorOrFail(chunk, maxAttempts = maxErrorAttempts)
                            if (exhausted) error("chunk ${chunk.index} unexpectedly exhausted")
                        }
                    }
                } catch (t: Throwable) {
                    failure.compareAndSet(null, t)
                }
            }
        }

        threads.forEach { it.isDaemon = true; it.start() }
        // Wait until all workers are parked at the gate, then release them simultaneously to
        // maximise real contention on the shared queue and output array.
        assertTrue(ready.await(10, java.util.concurrent.TimeUnit.SECONDS), "workers failed to start")
        go.countDown()
        threads.forEach { it.join(30_000) }

        failure.get()?.let { throw AssertionError("worker threw", it) }
        assertTrue(queue.allDone(), "queue did not fully complete (size=$size, workers=$workerCount)")
        assertEquals(0, queue.inFlightCount(), "chunks left in flight after completion")
        return output
    }

    /** Assert the reconstructed output equals the source at EVERY offset. */
    private fun assertReconstructs(
        size: Int,
        chunkSize: Long,
        workers: Int,
        failureRate: Double,
        seed: Long,
        maxErrorAttempts: Int = 10_000,
        stallRate: Double = 0.0
    ) {
        val expected = ByteArray(size) { i -> ((i * 31 + 7) and 0xFF).toByte() }
        val output = simulate(size, chunkSize, workers, failureRate, seed, maxErrorAttempts, stallRate)
        assertEquals(size, output.size)
        // Compare every byte; report the first mismatch precisely if any.
        for (i in 0 until size) {
            if (output[i] != expected[i]) {
                throw AssertionError(
                    "byte mismatch at offset $i: got ${output[i]} expected ${expected[i]} " +
                        "(size=$size chunkSize=$chunkSize workers=$workers fail=$failureRate seed=$seed)"
                )
            }
        }
    }

    @Test
    fun `reconstruction is exact with no injected failures`() {
        assertReconstructs(size = 100_000, chunkSize = 1024, workers = 8, failureRate = 0.0, seed = 1)
    }

    @Test
    fun `reconstruction is exact under heavy random mid-chunk failures`() {
        // ~40% of attempts abort partway and requeue; output must still match byte-for-byte.
        assertReconstructs(size = 250_000, chunkSize = 997, workers = 12, failureRate = 0.4, seed = 42)
    }

    @Test
    fun `reconstruction is exact across many size and worker combos`() {
        val combos = listOf(
            // size, chunkSize, workers, failureRate, seed
            Triple(1, 1L, 1), // tiny single byte
            Triple(63, 8L, 4),
            Triple(1024, 16L, 6),
            Triple(50_000, 333L, 16),
            Triple(123_457, 2048L, 24) // prime-ish size, max worker pool
        )
        var seed = 100L
        for ((size, cs, workers) in combos) {
            assertReconstructs(size, cs, workers, failureRate = 0.25, seed = seed++)
            // Also run each with zero failures to isolate pure coverage from retry churn.
            assertReconstructs(size, cs, workers, failureRate = 0.0, seed = seed++)
        }
    }

    @Test
    fun `single worker also reconstructs exactly with failures`() {
        assertReconstructs(size = 40_000, chunkSize = 512, workers = 1, failureRate = 0.5, seed = 7)
    }

    @Test
    fun `completes with REAL maxAttempts of 5 under a heavy stall storm`() {
        // THE regression guard for ROOT CAUSE #1. The old test used maxAttempts=10_000, which HID
        // the bug: soft-stall false positives shared the error budget, so on a real device they
        // exhausted it mid-file and the download FAILED. Here we use the PRODUCTION error cap (5)
        // AND inject a heavy 60% stall storm. Because stalls now use a SEPARATE budget
        // (requeueStall, Fix A) they can never exhaust the error cap — so despite ~60% of every
        // chunk's attempts stalling, the file must still reconstruct byte-for-byte, with NO chunk
        // ever entering the terminal FAILED state. Genuine errors stay rare (10%) so no single
        // chunk realistically piles up 5 REAL errors.
        assertReconstructs(
            size = 200_000, chunkSize = 777, workers = 16,
            failureRate = 0.10, seed = 2024,
            maxErrorAttempts = AdaptiveEngine.MAX_ERROR_ATTEMPTS, // == 5
            stallRate = 0.60
        )
    }

    @Test
    fun `extreme stall rate with maxAttempts 5 still completes (stalls never fail the download)`() {
        // Push stalls to 90%: a chunk may be stall-requeued dozens of times. With the OLD shared
        // budget this would fail almost immediately. With separated budgets it just migrates and
        // eventually completes. Small + single-and-multi worker to stress the convergence.
        assertReconstructs(
            size = 60_000, chunkSize = 512, workers = 8,
            failureRate = 0.0, seed = 99,
            maxErrorAttempts = 5, stallRate = 0.90
        )
        assertReconstructs(
            size = 30_000, chunkSize = 500, workers = 1,
            failureRate = 0.0, seed = 100,
            maxErrorAttempts = 5, stallRate = 0.90
        )
    }

    // ---- v2 TAIL-CHUNK RACING: byte coverage under duplicate concurrent writes ----------

    /**
     * Models the v2 tail-racing behaviour and proves it can't corrupt bytes. Workers drain a
     * shared queue; when there's no fresh chunk to claim AND the download is in its tail, an idle
     * worker RACES an in-flight chunk: it dials a SECOND "connection" for the SAME absolute range
     * and copies the same source bytes to the same output offsets. Both the primary and the racer
     * write IDENTICAL bytes (last-write-wins is byte-safe), each checks `done` before every burst
     * and bails the instant the chunk is finished, and whoever's [ChunkQueue.markDone] returns true
     * is the sole winner. The reconstructed output must equal the source at EVERY offset despite
     * the deliberate duplicate concurrent writes.
     *
     * To FORCE real racing (not just exercise the path), the PRIMARY on each chunk is made
     * artificially slow (tiny bursts + a injected micro-pause) so a racer reliably catches up.
     */
    private fun simulateWithRacing(
        size: Int,
        chunkSize: Long,
        workerCount: Int,
        seed: Long
    ): Triple<ByteArray, Int, Int> { // (output, racesStarted, racesWon)
        val source = ByteArray(size) { i -> ((i * 31 + 7) and 0xFF).toByte() }
        val output = ByteArray(size) { 0 }

        // A shared monotonic clock the queue reads for tail-race age gating. Advancing it well past
        // RACE_MIN_INFLIGHT_MS makes every drained-tail chunk immediately race-eligible.
        val clock = AtomicLong(1_000_000L)
        val queue = ChunkQueue(partitionChunks(size.toLong(), chunkSize), nowProvider = { clock.get() })
        val failure = AtomicReference<Throwable?>(null)
        val racesStarted = AtomicInteger(0)
        val racesWon = AtomicInteger(0)
        val ready = CountDownLatch(workerCount)
        val go = CountDownLatch(1)
        val workerSeed = AtomicInteger(0)

        // Copy a chunk's range in small bursts, checking `done` before each burst. Returns true iff
        // it wrote the FULL range (eligible to markDone). `slow` injects extra delay/finer bursts on
        // the primary so a racer can overtake it. A racer bails early when the chunk goes done.
        fun copyRange(chunk: Chunk, rnd: Random, slow: Boolean, isRace: Boolean): Boolean {
            val start = chunk.start.toInt()
            val len = chunk.length.toInt()
            var i = 0
            while (i < len) {
                // done-check BEFORE writing: the loser bails the moment the winner finishes.
                if (isRace && queue.isDone(chunk.index)) return false
                val maxBurst = if (slow) 8 else 64
                val burst = minOf(1 + rnd.nextInt(maxBurst), len - i)
                System.arraycopy(source, start + i, output, start + i, burst)
                i += burst
                // Advance the clock a touch each burst so tail-age gating stays satisfied; the slow
                // primary advances it more (simulating a slow node), giving racers room to win.
                clock.addAndGet(if (slow) 50L else 1L)
                if (slow) Thread.yield()
            }
            return true
        }

        val threads = (0 until workerCount).map {
            Thread {
                val rnd = Random(seed + workerSeed.getAndIncrement() * 1009L)
                ready.countDown(); go.await()
                try {
                    while (queue.shouldKeepWorking()) {
                        val chunk = queue.poll()
                        if (chunk == null) {
                            // No fresh work: try to RACE a tail chunk (the v2 idle-worker path).
                            val raceChunk = queue.pickRaceTarget()
                            if (raceChunk != null) {
                                racesStarted.incrementAndGet()
                                try {
                                    val full = copyRange(raceChunk, rnd, slow = false, isRace = true)
                                    if (full && queue.markDone(raceChunk)) racesWon.incrementAndGet()
                                } finally {
                                    queue.endRace(raceChunk) // release racer slot on every path
                                }
                            } else {
                                Thread.yield()
                            }
                            continue
                        }
                        // PRIMARY owner: copy slowly so racers can overtake near the tail, then
                        // markDone (a no-op if a racer already won — last-write-wins, idempotent).
                        val full = copyRange(chunk, rnd, slow = true, isRace = false)
                        if (full) queue.markDone(chunk)
                    }
                } catch (t: Throwable) {
                    failure.compareAndSet(null, t)
                }
            }
        }
        threads.forEach { it.isDaemon = true; it.start() }
        assertTrue(ready.await(10, java.util.concurrent.TimeUnit.SECONDS), "workers failed to start")
        go.countDown()
        threads.forEach { it.join(30_000) }

        failure.get()?.let { throw AssertionError("worker threw", it) }
        assertTrue(queue.allDone(), "queue did not fully complete under racing")
        assertEquals(0, queue.inFlightCount(), "chunks left in flight after racing completion")
        return Triple(output, racesStarted.get(), racesWon.get())
    }

    @Test
    fun `reconstruction is byte-exact under tail-chunk racing with duplicate concurrent writes`() {
        // Many workers, few-enough chunks that the queue drains while several are still in flight —
        // the exact tail condition where racing fires. The duplicate concurrent writes (primary +
        // racer on the same range) must NOT corrupt a single byte.
        val expected = ByteArray(120_000) { i -> ((i * 31 + 7) and 0xFF).toByte() }
        val (output, started, _) = simulateWithRacing(
            size = 120_000, chunkSize = 4096, workerCount = 12, seed = 7
        )
        assertEquals(expected.size, output.size)
        for (i in expected.indices) {
            if (output[i] != expected[i]) {
                throw AssertionError("byte mismatch at offset $i under racing: got ${output[i]} expected ${expected[i]}")
            }
        }
        // Sanity: with 12 workers and a slow primary the tail must actually have raced at least once
        // (otherwise the test wouldn't be exercising the duplicate-write path it claims to).
        assertTrue(started > 0, "expected the tail to race at least once (started=$started)")
    }

    @Test
    fun `racing reconstructs exactly across several size and worker combos`() {
        val combos = listOf(
            // size, chunkSize, workers
            Triple(40_000, 2048L, 8),
            Triple(80_000, 4096L, 16),
            Triple(50_000, 1000L, 24)
        )
        var seed = 500L
        for ((size, cs, workers) in combos) {
            val expected = ByteArray(size) { i -> ((i * 31 + 7) and 0xFF).toByte() }
            val (output, _, _) = simulateWithRacing(size, cs, workers, seed++)
            assertEquals(size, output.size)
            for (i in 0 until size) {
                if (output[i] != expected[i]) {
                    throw AssertionError(
                        "byte mismatch at offset $i (size=$size cs=$cs workers=$workers): " +
                            "got ${output[i]} expected ${expected[i]}"
                    )
                }
            }
        }
    }

    @Test
    fun `a raced chunk is counted exactly once - confirmed bytes never double-count`() {
        // Drive the winner/loser markDone race directly and assert the byte count is added EXACTLY
        // once: markDone returns true for exactly one caller, so a "count on true" rule can never
        // double-count a raced chunk's bytes.
        val clock = AtomicLong(1_000_000L)
        val queue = ChunkQueue(partitionChunks(20L, 10L), nowProvider = { clock.get() }) // 2 chunks
        val confirmed = AtomicLong(0)
        val primary = queue.poll()!!
        queue.poll()!! // drain
        clock.addAndGet(AdaptiveEngine.RACE_MIN_INFLIGHT_MS)
        val racer = queue.pickRaceTarget()!!
        // Both "finish" and try to count bytes; only the winner's markDone returns true.
        if (queue.markDone(racer)) confirmed.addAndGet(racer.length)   // winner
        if (queue.markDone(primary)) confirmed.addAndGet(primary.length) // loser -> no-op
        queue.endRace(racer)
        assertEquals(primary.length, confirmed.get(), "raced chunk bytes counted exactly once")
    }

    @Test
    fun `no chunk is left claimable or in-flight after completion`() {
        // A direct structural check independent of byte content: after a churny run the queue
        // must be fully drained — nothing pollable, nothing in flight, everything done.
        val queue = ChunkQueue(partitionChunks(20_000L, 256L))
        // Drive it single-threaded with forced failures, then completion.
        var c = queue.poll()
        var loops = 0
        while (c != null && loops < 1_000_000) {
            loops++
            // Fail the first time we see each chunk, succeed thereafter.
            if (queue.errorAttemptsOf(c.index) == 0) {
                queue.recordErrorOrFail(c, maxAttempts = 5)
            } else {
                queue.markDone(c)
            }
            c = queue.poll()
        }
        assertTrue(queue.allDone())
        assertEquals(0, queue.inFlightCount())
        assertNull(queue.poll())
    }
}
