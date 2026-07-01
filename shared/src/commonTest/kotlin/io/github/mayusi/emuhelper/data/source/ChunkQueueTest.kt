package io.github.mayusi.emuhelper.data.source

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [partitionChunks] and [ChunkQueue] — the pure, thread-safe core of the
 * adaptive (chunk-queue work-stealing) download engine.
 *
 * These assert the immutable-partition contract (no gaps/overlaps, full coverage of [0,size))
 * and the queue's done / in-flight accounting that the byte-coverage guarantee relies on.
 */
class ChunkQueueTest {

    // ---- partitionChunks: the immutable-partition contract ------------------

    /**
     * Helper: assert the partition for [size] covers [0,size) with no gaps, no overlaps, the
     * last chunk ends at size-1, indices are 0..count-1, and the lengths sum to size.
     */
    private fun assertValidPartition(size: Long, chunkSize: Long = AdaptiveEngine.CHUNK_SIZE) {
        val chunks = partitionChunks(size, chunkSize)
        assertTrue(chunks.isNotEmpty(), "size=$size should produce at least one chunk")

        // Indices are 0..count-1 contiguous.
        chunks.forEachIndexed { i, c -> assertEquals(i, c.index, "chunk $i has wrong index") }

        // First starts at 0, last ends at size-1.
        assertEquals(0L, chunks.first().start, "first chunk must start at 0")
        assertEquals(size - 1, chunks.last().end, "last chunk must end at size-1")

        // No gaps, no overlaps: each chunk starts exactly where the previous ended + 1.
        for (i in 1 until chunks.size) {
            assertEquals(
                chunks[i - 1].end + 1, chunks[i].start,
                "gap/overlap between chunk ${i - 1} and $i"
            )
        }

        // Sum of lengths == size; every length is positive.
        var sum = 0L
        for (c in chunks) {
            assertTrue(c.length > 0, "chunk ${c.index} has non-positive length ${c.length}")
            assertEquals(c.end - c.start + 1, c.length, "length mismatch on chunk ${c.index}")
            sum += c.length
        }
        assertEquals(size, sum, "sum of chunk lengths must equal size")

        // Every non-final chunk is exactly chunkSize; the last absorbs the remainder.
        for (i in 0 until chunks.size - 1) {
            assertEquals(chunkSize, chunks[i].length, "non-final chunk $i must be exactly chunkSize")
        }
        assertTrue(chunks.last().length <= chunkSize, "last chunk must not exceed chunkSize")
    }

    @Test
    fun `partition covers full range for many size and chunkSize combos`() {
        // A spread of sizes: exact multiples, +1, -1, primes, tiny, and the real 8 MB chunk.
        val chunk = 8L * 1024 * 1024
        assertValidPartition(1, chunk)
        assertValidPartition(chunk - 1, chunk)
        assertValidPartition(chunk, chunk)
        assertValidPartition(chunk + 1, chunk)
        assertValidPartition(2 * chunk, chunk)
        assertValidPartition(2 * chunk + 12345, chunk)
        assertValidPartition(10L * chunk + 1, chunk)
        assertValidPartition(1_073_741_824L, chunk)        // 1 GiB
        assertValidPartition(3_221_225_473L, chunk)        // 3 GiB + 1

        // Small synthetic chunk sizes exercise the boundary math independent of 8 MB.
        for (cs in listOf(1L, 2L, 3L, 7L, 16L, 100L)) {
            for (sz in listOf(1L, 2L, 5L, 7L, 8L, 16L, 17L, 99L, 100L, 101L, 1000L)) {
                assertValidPartition(sz, cs)
            }
        }
    }

    @Test
    fun `partition of zero or negative size is empty`() {
        assertTrue(partitionChunks(0L).isEmpty())
        assertTrue(partitionChunks(-5L).isEmpty())
    }

    @Test
    fun `single-chunk file when size at or below chunkSize`() {
        val chunks = partitionChunks(500L, 1000L)
        assertEquals(1, chunks.size)
        assertEquals(Chunk(0, 0, 499), chunks[0])
        assertEquals(500L, chunks[0].length)
    }

    // ---- ChunkQueue: claim / requeue / done accounting ----------------------

    private fun queueFor(size: Long, chunkSize: Long): ChunkQueue =
        ChunkQueue(partitionChunks(size, chunkSize))

    @Test
    fun `all chunks start queued and pollable`() {
        val q = queueFor(100L, 10L) // 10 chunks
        assertEquals(10, q.count)
        assertEquals(10, q.remaining())
        assertEquals(10, q.unclaimed())
        assertEquals(0, q.inFlightCount())
        assertFalse(q.allDone())
    }

    @Test
    fun `poll moves a chunk to in-flight and decrements unclaimed`() {
        val q = queueFor(100L, 10L)
        val c = q.poll()
        assertNotNull(c)
        assertEquals(9, q.unclaimed())
        assertEquals(1, q.inFlightCount())
        assertEquals(10, q.remaining()) // not done yet
    }

    @Test
    fun `markDone removes from in-flight and advances done`() {
        val q = queueFor(30L, 10L) // 3 chunks
        val c = q.poll()!!
        assertTrue(q.markDone(c))
        assertEquals(0, q.inFlightCount())
        assertEquals(2, q.remaining())
        assertTrue(q.isDone(c.index))
    }

    @Test
    fun `markDone is idempotent and rejects unknown indices`() {
        val q = queueFor(20L, 10L) // 2 chunks
        val c = q.poll()!!
        assertTrue(q.markDone(c))
        // Second markDone for the same chunk is a no-op (returns false, accounting unchanged).
        assertFalse(q.markDone(c))
        assertEquals(0, q.inFlightCount())
        // Unknown index is rejected.
        assertFalse(q.markDone(Chunk(999, 0, 0)))
    }

    @Test
    fun `a chunk cannot be done and queued simultaneously`() {
        val q = queueFor(30L, 10L)
        val c = q.poll()!!
        q.markDone(c)
        // Requeueing a done chunk must be a no-op: it must never re-enter the queue.
        q.requeue(c)
        assertTrue(q.isDone(c.index))
        // Drain the rest; the done chunk's index must never be polled again.
        val seen = HashSet<Int>()
        var next = q.poll()
        while (next != null) {
            assertFalse(next.index == c.index, "done chunk ${c.index} was polled again")
            seen.add(next.index)
            q.markDone(next)
            next = q.poll()
        }
        assertTrue(q.allDone())
    }

    @Test
    fun `requeue puts the whole chunk back for any worker`() {
        val q = queueFor(20L, 10L) // 2 chunks
        val c = q.poll()!!
        assertEquals(1, q.inFlightCount())
        q.requeue(c)
        assertEquals(0, q.inFlightCount())
        // It is pollable again (work-stealing): both chunks remain available.
        assertEquals(2, q.unclaimed())
        assertEquals(2, q.remaining())
    }

    @Test
    fun `allDone is true iff every index is done`() {
        val q = queueFor(50L, 10L) // 5 chunks
        val claimed = ArrayList<Chunk>()
        var c = q.poll()
        while (c != null) { claimed.add(c); c = q.poll() }
        assertEquals(5, claimed.size)
        // Mark all but one done -> not allDone.
        for (i in 0 until claimed.size - 1) q.markDone(claimed[i])
        assertFalse(q.allDone())
        q.markDone(claimed.last())
        assertTrue(q.allDone())
        assertEquals(0, q.remaining())
    }

    @Test
    fun `accounting identity claims minus requeues minus dones equals inFlight`() {
        val q = queueFor(100L, 10L) // 10 chunks
        // Drive a messy sequence of polls, requeues, and dones; the identity must always hold.
        fun checkIdentity() {
            val expected = q.claimsCount() - q.requeuesCount() - q.donesCount()
            assertEquals(expected, q.inFlightCount().toLong(), "claims - requeues - dones must equal inFlight")
        }
        checkIdentity()
        val a = q.poll()!!; checkIdentity()
        val b = q.poll()!!; checkIdentity()
        q.requeue(a); checkIdentity()
        q.markDone(b); checkIdentity()
        val c = q.poll()!!; checkIdentity()
        val d = q.poll()!!; checkIdentity()
        q.markDone(c); checkIdentity()
        q.requeue(d); checkIdentity()
        // Drain everything to completion, checking the identity each step.
        var n = q.poll()
        while (n != null) { q.markDone(n); checkIdentity(); n = q.poll() }
        assertTrue(q.allDone())
        assertEquals(0, q.inFlightCount())
    }

    @Test
    fun `shouldKeepWorking is true while work queued or in-flight, false when drained`() {
        val q = queueFor(20L, 10L) // 2 chunks
        assertTrue(q.shouldKeepWorking())          // queued
        val a = q.poll()!!
        val b = q.poll()!!
        assertTrue(q.shouldKeepWorking())          // nothing queued but 2 in flight
        assertNull(q.poll())                       // queue empty right now
        q.markDone(a)
        assertTrue(q.shouldKeepWorking())          // 1 still in flight
        q.markDone(b)
        assertFalse(q.shouldKeepWorking())         // empty AND nothing in flight
        assertTrue(q.allDone())
    }

    // ---- recordErrorOrFail: bounded REAL-error retries ----------------------

    @Test
    fun `recordErrorOrFail requeues until maxAttempts then signals failure and goes terminal failed`() {
        val q = queueFor(10L, 10L) // 1 chunk
        val c = q.poll()!!
        // attempts 1..4 requeue (false); attempt 5 hits MAX and reports exhaustion (true).
        assertFalse(q.recordErrorOrFail(c, maxAttempts = 5)) // try 1
        assertEquals(1, q.errorAttemptsOf(c.index))
        val c2 = q.poll()!!                                  // re-claimed after requeue
        assertFalse(q.recordErrorOrFail(c2, 5))              // try 2
        val c3 = q.poll()!!
        assertFalse(q.recordErrorOrFail(c3, 5))              // try 3
        val c4 = q.poll()!!
        assertFalse(q.recordErrorOrFail(c4, 5))              // try 4
        val c5 = q.poll()!!
        assertTrue(q.recordErrorOrFail(c5, 5))               // try 5 -> exhausted
        assertEquals(5, q.errorAttemptsOf(c.index))
        // Fix D: the exhausted chunk is now in the explicit terminal FAILED state, NOT requeued.
        assertTrue(q.isFailed(c.index))
        assertTrue(q.anyFailed())
        assertEquals(setOf(c.index), q.failedIndices())
        assertNull(q.poll())
        assertFalse(q.allDone())
        // Fix D: with a chunk failed, workers must stop (definite terminal condition, no hang).
        assertFalse(q.shouldKeepWorking())
    }

    @Test
    fun `recordErrorOrFail on a done chunk is a harmless no-op`() {
        val q = queueFor(20L, 10L)
        val c = q.poll()!!
        q.markDone(c)
        // A late failure report for a chunk that already completed must not requeue or fail.
        assertFalse(q.recordErrorOrFail(c, maxAttempts = 5))
        assertEquals(0, q.errorAttemptsOf(c.index))
        assertTrue(q.isDone(c.index))
        assertFalse(q.isFailed(c.index))
    }

    // ---- FIX A: separated budgets (stalls vs real errors) -------------------

    @Test
    fun `stalls use a separate budget and NEVER fail the chunk or the download`() {
        // THE test that would have caught the bug (ROOT CAUSE #1): a chunk that stalls many times
        // (here 20x) must keep being re-queued and can still complete. Stalls must NOT touch the
        // error budget, so even with the production error cap of 5 the chunk never fails.
        val q = queueFor(10L, 10L) // 1 chunk
        repeat(20) {
            val c = q.poll()!!
            // Each stall requeues via the STALL budget; returns false (never signals failure).
            assertFalse(q.requeueStall(c, maxStallRequeues = AdaptiveEngine.MAX_STALL_REQUEUES))
            // The ERROR budget is untouched the entire time.
            assertEquals(0, q.errorAttemptsOf(c.index), "stalls must not consume the error budget")
            assertFalse(q.isFailed(c.index), "a stalled chunk must never go terminal-failed")
            assertFalse(q.anyFailed())
        }
        assertEquals(20, q.stallRequeuesOf(0))
        // After all that stalling the chunk is still claimable and can finally complete.
        val last = q.poll()!!
        assertTrue(q.markDone(last))
        assertTrue(q.allDone())
        assertFalse(q.anyFailed())
    }

    @Test
    fun `stall requeues and error attempts are independent counters`() {
        // Interleave stalls and real errors on the same chunk; each counter advances on its own.
        val q = queueFor(10L, 10L) // 1 chunk
        var c = q.poll()!!
        assertFalse(q.requeueStall(c))                     // stall 1
        c = q.poll()!!
        assertFalse(q.recordErrorOrFail(c, maxAttempts = 5)) // error 1
        c = q.poll()!!
        assertFalse(q.requeueStall(c))                     // stall 2
        c = q.poll()!!
        assertFalse(q.recordErrorOrFail(c, maxAttempts = 5)) // error 2
        assertEquals(2, q.stallRequeuesOf(0))
        assertEquals(2, q.errorAttemptsOf(0))
        // 50 more stalls still don't move the error counter or fail the chunk.
        repeat(50) {
            val s = q.poll()!!
            q.requeueStall(s)
        }
        assertEquals(2, q.errorAttemptsOf(0), "stalls must never bump the error budget")
        assertFalse(q.isFailed(0))
        // Three more real errors (total 5) finally exhaust the ERROR budget -> terminal failed.
        repeat(2) {
            val e = q.poll()!!
            assertFalse(q.recordErrorOrFail(e, maxAttempts = 5))
        }
        val fifth = q.poll()!!
        assertTrue(q.recordErrorOrFail(fifth, maxAttempts = 5)) // 5th error -> exhausted
        assertTrue(q.isFailed(0))
    }

    // ---- FIX D: self-healing forceRequeue -----------------------------------

    @Test
    fun `forceRequeue reconciles a leaked in-flight chunk exactly once`() {
        val q = queueFor(20L, 10L) // 2 chunks
        val c = q.poll()!!
        assertEquals(1, q.inFlightCount())
        // Simulate an exit path that neither marked done nor requeued: force-requeue heals it.
        assertTrue(q.forceRequeue(c), "should reconcile a leaked in-flight chunk")
        assertEquals(0, q.inFlightCount())
        assertEquals(2, q.unclaimed())
        // Idempotent: a chunk already back on the queue is not re-added.
        assertFalse(q.forceRequeue(c))
        assertEquals(2, q.unclaimed())
    }

    @Test
    fun `forceRequeue is a no-op for done or failed chunks`() {
        val q = queueFor(20L, 10L)
        val done = q.poll()!!
        q.markDone(done)
        assertFalse(q.forceRequeue(done), "must not resurrect a done chunk")
        assertTrue(q.isDone(done.index))

        // Drive the other chunk to terminal-failed, then assert forceRequeue won't resurrect it.
        var f = q.poll()!!
        while (!q.isFailed(f.index)) {
            if (q.recordErrorOrFail(f, maxAttempts = 5)) break
            f = q.poll()!!
        }
        assertTrue(q.isFailed(f.index))
        assertFalse(q.forceRequeue(f), "must not resurrect a failed chunk")
    }

    // ---- v2 TAIL-CHUNK RACING -----------------------------------------------

    /** A ChunkQueue with an injectable clock so tail-race timing is deterministic. */
    private fun racingQueueFor(size: Long, chunkSize: Long, now: () -> Long): ChunkQueue =
        ChunkQueue(partitionChunks(size, chunkSize), nowProvider = now)

    @Test
    fun `isInTail is false until the queue is drained and only a few chunks remain in flight`() {
        var clock = 0L
        val q = racingQueueFor(60L, 10L) { clock } // 6 chunks
        // Plenty of fresh work queued -> NOT in the tail (an idle worker should claim, not race).
        assertFalse(q.isInTail(), "fresh queued work must disable racing")
        // Claim every chunk so the queue is drained but many are in flight (> threshold of 3).
        val claimed = ArrayList<Chunk>()
        var c = q.poll(); while (c != null) { claimed.add(c); c = q.poll() }
        assertEquals(6, claimed.size)
        assertFalse(q.isInTail(), "queue drained but inFlight (6) > RACE_TAIL_THRESHOLD (3)")
        // Finish chunks until only 3 remain in flight -> NOW we're in the tail.
        q.markDone(claimed[0]); q.markDone(claimed[1]); q.markDone(claimed[2])
        assertEquals(3, q.inFlightCount())
        assertTrue(q.isInTail(), "drained + inFlight==RACE_TAIL_THRESHOLD must be the tail")
        // Finish all -> no longer in tail (nothing to race, all done).
        q.markDone(claimed[3]); q.markDone(claimed[4]); q.markDone(claimed[5])
        assertFalse(q.isInTail(), "fully done is not a tail")
        assertTrue(q.allDone())
    }

    @Test
    fun `pickRaceTarget only returns a chunk in the tail, past min in-flight age, never the freshest`() {
        var clock = 1_000_000L
        val q = racingQueueFor(30L, 10L) { clock } // 3 chunks
        // Not in tail yet: claim one, two still queued.
        val a = q.poll()!!
        assertNull(q.pickRaceTarget(), "fresh queued work -> no race target")
        // Claim the rest so the queue is drained (3 in flight == threshold).
        val b = q.poll()!!
        val c = q.poll()!!
        assertEquals(0, q.unclaimed())
        assertEquals(3, q.inFlightCount())
        // All were just claimed (age 0) -> none is past RACE_MIN_INFLIGHT_MS -> no target yet.
        assertNull(q.pickRaceTarget(), "no in-flight chunk is old enough to race")
        // Age past the min-in-flight window -> the LONGEST-running (a, claimed first) is chosen.
        clock += AdaptiveEngine.RACE_MIN_INFLIGHT_MS
        val target = q.pickRaceTarget()
        assertNotNull(target)
        assertEquals(a.index, target!!.index, "the oldest in-flight chunk must be raced first")
        assertEquals(1, q.racersOf(a.index), "pickRaceTarget reserves a racer slot")
        // Per-chunk racer cap (MAX_RACERS_PER_CHUNK == 1): no second racer on the SAME chunk while
        // the first holds the slot; the next-oldest (b) is offered instead.
        val target2 = q.pickRaceTarget()
        assertNotNull(target2)
        assertEquals(b.index, target2!!.index, "racer cap diverts to the next-oldest tail chunk")
        // Cleanup references so the compiler doesn't warn about unused locals.
        assertEquals(3, q.inFlightCount())
        assertTrue(b.index != c.index)
    }

    @Test
    fun `a raced chunk completing via the racer marks done and the loser is an idempotent no-op`() {
        var clock = 1_000_000L
        val q = racingQueueFor(20L, 10L) { clock } // 2 chunks
        val primary = q.poll()!!
        q.poll()!! // drain the queue (2 in flight)
        clock += AdaptiveEngine.RACE_MIN_INFLIGHT_MS
        // A racer picks the primary's chunk.
        val raced = q.pickRaceTarget()!!
        assertEquals(primary.index, raced.index)
        assertEquals(1, q.racersOf(primary.index))
        // The RACER finishes first: its markDone wins (true). inFlight drops by exactly 1.
        val before = q.inFlightCount()
        assertTrue(q.markDone(raced), "racer's markDone must win")
        assertEquals(before - 1, q.inFlightCount(), "winning markDone decrements inFlight once")
        // The PRIMARY (loser) finishing late is a no-op: false, no double-decrement, no double-count.
        assertFalse(q.markDone(primary), "loser's late markDone must be a no-op")
        assertEquals(before - 1, q.inFlightCount(), "loser must NOT decrement inFlight again")
        // The racer slot was cleared by the winning markDone (in-flight entry removed).
        assertEquals(0, q.racersOf(primary.index))
        // endRace after the chunk is gone is a harmless no-op (racer accounting can't leak).
        q.endRace(raced)
        assertEquals(0, q.racersOf(primary.index))
    }

    @Test
    fun `endRace releases a racer slot and never drives inFlight negative or leaks`() {
        var clock = 1_000_000L
        val q = racingQueueFor(20L, 10L) { clock } // 2 chunks
        val primary = q.poll()!!
        q.poll()!!
        clock += AdaptiveEngine.RACE_MIN_INFLIGHT_MS
        val raced = q.pickRaceTarget()!!
        assertEquals(1, q.racersOf(primary.index))
        val inFlightBefore = q.inFlightCount()
        // Racer LOSES / abandons: endRace clears its slot WITHOUT touching the primary's inFlight.
        q.endRace(raced)
        assertEquals(0, q.racersOf(primary.index), "endRace clears the racer slot")
        assertEquals(inFlightBefore, q.inFlightCount(), "endRace must NOT change inFlight")
        // Double endRace is a no-op (never negative).
        q.endRace(raced)
        assertEquals(0, q.racersOf(primary.index))
        // The primary still owns the chunk and can complete it normally.
        assertTrue(q.markDone(primary))
        assertEquals(inFlightBefore - 1, q.inFlightCount())
    }

    @Test
    fun `racing preserves the accounting identity claims minus requeues minus dones equals inFlight`() {
        // Racers must NOT perturb the core identity — they're tracked in a SEPARATE counter and
        // never touch claims/requeues/dones/inFlight except via the single winning markDone.
        var clock = 1_000_000L
        val q = racingQueueFor(30L, 10L) { clock } // 3 chunks
        fun checkIdentity() {
            val expected = q.claimsCount() - q.requeuesCount() - q.donesCount()
            assertEquals(expected, q.inFlightCount().toLong(), "identity must hold even with racers")
        }
        val a = q.poll()!!; val b = q.poll()!!; val c = q.poll()!!
        checkIdentity()
        clock += AdaptiveEngine.RACE_MIN_INFLIGHT_MS
        // Start racers on two chunks — identity is unchanged (racers aren't claims).
        val r1 = q.pickRaceTarget()!!; checkIdentity()
        q.endRace(r1); checkIdentity()
        // Winner completes a; loser endRace; identity holds throughout.
        assertTrue(q.markDone(a)); checkIdentity()
        q.markDone(b); checkIdentity()
        q.markDone(c); checkIdentity()
        assertTrue(q.allDone())
        assertEquals(0, q.inFlightCount())
    }
}
