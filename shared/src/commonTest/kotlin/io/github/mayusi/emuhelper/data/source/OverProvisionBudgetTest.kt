package io.github.mayusi.emuhelper.data.source

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * v2 OVER-PROVISIONING + RACING budget invariant.
 *
 * The #1 safety invariant of the adaptive engine is the GLOBAL connection cap: no matter how many
 * workers a file over-provisions, and no matter how many tail-racers fire, the number of
 * simultaneously-held [Semaphore] permits must NEVER exceed the Semaphore's capacity. The real
 * engine enforces this by having every worker AND every racer acquire a permit before opening a
 * socket and release it in a finally (a racer uses tryAcquire so it never blocks or over-subscribes).
 *
 * These tests reproduce that acquire/release discipline against a real kotlinx [Semaphore] driven
 * by a large over-provisioned worker pool plus racers, and assert a live "currently-held permits"
 * gauge never crosses the capacity — even under heavy contention and with racers using tryAcquire.
 */
class OverProvisionBudgetTest {

    /** A live gauge of currently-held permits with a monotonic peak, asserting the cap each bump. */
    private class PermitGauge(val capacity: Int) {
        private val held = AtomicInteger(0)
        val peak = AtomicInteger(0)
        val violations = AtomicInteger(0)
        fun onAcquire() {
            val n = held.incrementAndGet()
            if (n > capacity) violations.incrementAndGet()
            var p = peak.get()
            while (n > p && !peak.compareAndSet(p, n)) p = peak.get()
        }
        fun onRelease() { held.decrementAndGet() }
        fun current() = held.get()
    }

    @Test
    fun `over-provisioned workers never exceed the semaphore capacity`() = runBlocking {
        val capacity = 24
        val budget = Semaphore(capacity)
        val gauge = PermitGauge(capacity)
        // Over-provision HARD: spawn far more workers than permits. The Semaphore must serialise
        // them down to `capacity` concurrent holders — the extras block on acquire(). Run on a
        // real multithreaded dispatcher so the "currently-held" gauge is a genuine concurrency
        // measurement (not a virtual-time artifact).
        val workers = 200
        val workDone = AtomicLong(0)
        withContext(Dispatchers.Default) {
            coroutineScope {
                (0 until workers).map {
                    async {
                        repeat(20) {
                            budget.acquire()
                            try {
                                gauge.onAcquire()
                                // Hold the permit across a suspension point to maximise overlap.
                                delay(1)
                                workDone.incrementAndGet()
                            } finally {
                                gauge.onRelease()
                                budget.release()
                            }
                        }
                    }
                }.awaitAll()
            }
        }
        assertEquals(0, gauge.violations.get(), "permits held must NEVER exceed capacity")
        assertTrue(gauge.peak.get() <= capacity, "peak ${gauge.peak.get()} must be <= $capacity")
        assertEquals(0, gauge.current(), "all permits released at the end")
        assertEquals(workers.toLong() * 20, workDone.get(), "all work units ran")
    }

    @Test
    fun `workers plus tryAcquire racers never exceed capacity`() = runBlocking {
        // Mixed load: primary workers acquire() (blocking) AND racers tryAcquire() (non-blocking).
        // The combined concurrently-held permit count must still never cross the cap. tryAcquire
        // failing is fine (the racer just skips) — the point is it can NEVER push us over.
        val capacity = 8 // small cap to make contention/violations easy to surface if present
        val budget = Semaphore(capacity)
        val gauge = PermitGauge(capacity)
        val raceAttempts = AtomicLong(0)
        val raceAcquired = AtomicLong(0)
        withContext(Dispatchers.Default) {
            coroutineScope {
                // 50 blocking primaries.
                val primaries = (0 until 50).map {
                    async {
                        repeat(10) {
                            budget.acquire()
                            try {
                                gauge.onAcquire()
                                delay(1)
                            } finally {
                                gauge.onRelease(); budget.release()
                            }
                        }
                    }
                }
                // 50 racers that NEVER block — tryAcquire only. They model tail-racers: if no permit
                // is free they skip the race entirely (never block a permit a fresh chunk could use).
                val racers = (0 until 50).map {
                    async {
                        repeat(20) {
                            raceAttempts.incrementAndGet()
                            if (budget.tryAcquire()) {
                                raceAcquired.incrementAndGet()
                                try {
                                    gauge.onAcquire()
                                    delay(1)
                                } finally {
                                    gauge.onRelease(); budget.release()
                                }
                            } else {
                                delay(1) // skipped the race; yield and try more work later
                            }
                        }
                    }
                }
                (primaries + racers).awaitAll()
            }
        }
        // THE invariant: combined primaries + tryAcquire racers NEVER hold more than `capacity`
        // permits at once. This is the runtime safety cap that over-provisioning + racing rely on.
        assertEquals(0, gauge.violations.get(), "primaries + tryAcquire racers must never exceed the cap")
        assertTrue(gauge.peak.get() <= capacity, "peak ${gauge.peak.get()} must be <= $capacity")
        assertEquals(0, gauge.current(), "all permits released at the end")
        assertTrue(raceAttempts.get() > 0, "racers must have attempted")
        // raceAcquired can be anywhere in [0, attempts]; whatever the split, the cap held — that's
        // the whole point. (We don't assert a specific win count: that's scheduler-dependent and
        // not part of the invariant under test.)
        assertTrue(raceAcquired.get() in 0..raceAttempts.get())
    }

    @Test
    fun `laned runners plus tail racers never exceed the shared semaphore capacity`() = runBlocking {
        // v3: the laned engine spawns one PINNED runner per planned stream (Σ ~mirrors×2) plus tail
        // racers (tryAcquire). All of them share the global 24-permit Semaphore. This asserts the #1
        // safety invariant holds for the LANED topology specifically: combined runners + racers never
        // hold more than `capacity` permits at once — the same acquire/release discipline the engine
        // uses (runners acquire(); racers tryAcquire()). A handheld thermally shut down once; this is
        // the non-negotiable guard.
        val capacity = 24
        val budget = Semaphore(capacity)
        val gauge = PermitGauge(capacity)
        // Plan lanes for 3 mirrors (the max IA case) — flatten to runners exactly like the engine.
        val mirrors = listOf("https://m1/x", "https://m2/x", "https://m3/x")
        val lanes = planLanes(mirrors, budget = capacity)
        val runnerHosts = lanes.flatMap { lane -> List(lane.streams) { lane.host } }
        assertTrue(runnerHosts.isNotEmpty(), "lane plan must produce runners")

        val raceAttempts = AtomicLong(0)
        withContext(Dispatchers.Default) {
            coroutineScope {
                // PINNED runners: blocking acquire(), like the real primary lane runners.
                val runners = runnerHosts.map { _ ->
                    async {
                        repeat(40) {
                            budget.acquire()
                            try { gauge.onAcquire(); delay(1) }
                            finally { gauge.onRelease(); budget.release() }
                        }
                    }
                }
                // Extra tail racers using tryAcquire — they must NEVER push the total over the cap.
                val racers = (0 until 16).map {
                    async {
                        repeat(40) {
                            raceAttempts.incrementAndGet()
                            if (budget.tryAcquire()) {
                                try { gauge.onAcquire(); delay(1) }
                                finally { gauge.onRelease(); budget.release() }
                            } else delay(1)
                        }
                    }
                }
                (runners + racers).awaitAll()
            }
        }
        assertEquals(0, gauge.violations.get(), "laned runners + racers must NEVER exceed the cap")
        assertTrue(gauge.peak.get() <= capacity, "peak ${gauge.peak.get()} must be <= $capacity")
        assertEquals(0, gauge.current(), "all permits released at the end (no leak)")
        assertTrue(raceAttempts.get() > 0, "racers must have attempted")
    }

    @Test
    fun `worker over-provision count scales toward the budget but is bounded by chunk count`() {
        // Mirror the engine's worker-count formula directly (no network): the pool is over-
        // provisioned UP toward MAX_ADAPTIVE_WORKERS but never exceeds the chunk count, and never
        // drops below the segments floor. This is the pure arithmetic the engine uses.
        fun workerCount(segments: Int, chunkCount: Int): Int =
            segments.coerceAtLeast(1)
                .coerceAtLeast(minOf(chunkCount, AdaptiveEngine.MAX_ADAPTIVE_WORKERS))
                .coerceIn(1, chunkCount)

        // Many chunks, small segment hint -> over-provision up to the global cap (24). This is the
        // key v2 change: v1 would have used `segments`(4) here; v2 uses 24, pulling from more nodes.
        assertEquals(AdaptiveEngine.MAX_ADAPTIVE_WORKERS, workerCount(segments = 4, chunkCount = 1000))
        // Fewer chunks than the cap -> bounded by chunk count (no point exceeding chunks).
        assertEquals(5, workerCount(segments = 4, chunkCount = 5))
        // Segment hint above the over-provision target still honoured as a floor (but capped by chunks).
        assertEquals(10, workerCount(segments = 30, chunkCount = 10))
        // Never below 1.
        assertEquals(1, workerCount(segments = 0, chunkCount = 1))
        // A huge segment hint acts as a floor but the CHUNK COUNT is the hard upper bound: with 50
        // chunks the pool can't exceed 50 workers regardless. (The global 24-permit Semaphore still
        // gates how many of those 50 actually open a socket at once — that's the runtime safety
        // cap, proven separately above; worker COUNT just bounds how many can ever try.)
        assertEquals(50, workerCount(segments = 100, chunkCount = 50))
        // And worker count never exceeds the chunk count even with both hints huge.
        assertTrue(workerCount(100, 50) <= 50)
    }
}
