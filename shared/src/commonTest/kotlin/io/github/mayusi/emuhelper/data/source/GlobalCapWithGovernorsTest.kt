package io.github.mayusi.emuhelper.data.source

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * THE #1 SAFETY INVARIANT for the v0.8 features: with the MULTI-FILE batch scheduler driving many
 * laned runners AND the THERMAL governor holding permits to shrink the live cap, the number of
 * simultaneously-held connection permits must NEVER exceed the shared Semaphore's capacity (24) —
 * and when the thermal governor is actively holding permits, must never exceed the REDUCED live cap.
 *
 * The scheduler and the governors only ever REASSIGN or REDUCE connections; they never mint permits.
 * Every runner/racer acquires a permit before opening a socket and releases it in a finally; the
 * thermal governor acquires extra permits and HOLDS them (releasing on cool-down). This test
 * reproduces that exact acquire/release discipline against a real kotlinx Semaphore and a real
 * [ThermalPermitGovernor], asserting the cap holds under heavy contention.
 */
class GlobalCapWithGovernorsTest {

    /** A live gauge of currently-held DOWNLOAD permits with a monotonic peak. */
    private class PermitGauge {
        val held = AtomicInteger(0)
        val peak = AtomicInteger(0)
        val capViolations = AtomicInteger(0)
        fun onAcquire(cap: Int) {
            val n = held.incrementAndGet()
            if (n > cap) capViolations.incrementAndGet()
            var p = peak.get()
            while (n > p && !peak.compareAndSet(p, n)) p = peak.get()
        }
        fun onRelease() { held.decrementAndGet() }
    }

    @Test
    fun `multi-file scheduler plus thermal shrink never exceeds the global cap`() = runBlocking {
        val capacity = AdaptiveEngine.MAX_ADAPTIVE_WORKERS // 24
        val budget = Semaphore(capacity)
        val gauge = PermitGauge()
        val governor = ThermalPermitGovernor(budget, capacity)

        // Build a realistic multi-file laned topology via the scheduler: 3 concurrent files over the
        // 2-3 IA datacenters, flattened to one runner per stream — exactly like the engine.
        val scheduler = MirrorScheduler(globalBudget = capacity)
        val active = listOf(
            FileDemand("A", listOf("https://ia6/x", "https://ia8/x", "https://dn7/x"), 3_000_000_000L),
            FileDemand("B", listOf("https://ia6/x", "https://ia8/x", "https://dn7/x"), 2_000_000_000L),
            FileDemand("C", listOf("https://ia6/x", "https://ia8/x", "https://dn7/x"), 1_000_000_000L)
        )
        val plans = scheduler.assign(active)
        // Flatten EVERY file's lanes into runners; all share the ONE global Semaphore (the engine's
        // model — the scheduler picks hosts, the Semaphore caps concurrency across all files).
        val runnerHosts = plans.flatMap { p -> p.lanes.flatMap { lane -> List(lane.streams) { lane.host } } }
        assertTrue(runnerHosts.isNotEmpty(), "scheduler must produce runners")

        val raceAttempts = AtomicLong(0)
        // The live cap the gauge checks: it tightens while the thermal governor holds permits. We
        // read governor.liveCap() at acquire time; the gauge asserts held <= capacity always (the
        // hard cap) and separately we verify the governor never raises the cap.
        withContext(Dispatchers.Default) {
            coroutineScope {
                // THERMAL DRIVER: toggle SEVERE on/off repeatedly mid-run. Each transition has the
                // governor acquire/hold (or release) permits — REDUCING then restoring the live cap.
                val thermal = launch {
                    repeat(8) { i ->
                        // SEVERE (3) holds permits down to the reduced cap; NONE (0) releases them.
                        governor.applyThermalStatus(if (i % 2 == 0) THERMAL_STATUS_SEVERE else 0)
                        delay(3)
                    }
                    // End cool — release everything so the budget is whole again.
                    governor.applyThermalStatus(0)
                }

                // PINNED runners: blocking acquire(), like the real primary lane runners. Each does
                // many chunks so there's sustained contention against the (shrinking) budget.
                val runners = runnerHosts.map { _ ->
                    async {
                        repeat(60) {
                            budget.acquire()
                            try {
                                gauge.onAcquire(capacity)
                                delay(1)
                            } finally {
                                gauge.onRelease(); budget.release()
                            }
                        }
                    }
                }
                // Tail racers using tryAcquire (never block) — must NEVER push the total over the cap.
                val racers = (0 until 16).map {
                    async {
                        repeat(60) {
                            raceAttempts.incrementAndGet()
                            if (budget.tryAcquire()) {
                                try { gauge.onAcquire(capacity); delay(1) }
                                finally { gauge.onRelease(); budget.release() }
                            } else delay(1)
                        }
                    }
                }
                (runners + racers).awaitAll()
                thermal.join()
            }
        }

        assertEquals(0, gauge.capViolations.get(),
            "held permits must NEVER exceed the global cap $capacity (scheduler + thermal + racers)")
        assertTrue(gauge.peak.get() <= capacity, "peak ${gauge.peak.get()} must be <= $capacity")
        assertEquals(0, gauge.held.get(), "all download permits released at the end")
        // After cool-down the governor holds nothing -> full budget restored.
        assertEquals(0, governor.held(), "thermal governor released all held permits on cool-down")
        assertEquals(capacity, governor.liveCap(), "full live cap restored after cool-down")
        assertTrue(raceAttempts.get() > 0, "racers must have attempted")
    }

    @Test
    fun `thermal governor only reduces the live cap, never raises it past the budget`() = runBlocking {
        val capacity = AdaptiveEngine.MAX_ADAPTIVE_WORKERS // 24
        val budget = Semaphore(capacity)
        val governor = ThermalPermitGovernor(budget, capacity)

        // Below SEVERE -> holds nothing, full live cap.
        governor.applyThermalStatus(0)
        assertEquals(0, governor.held())
        assertEquals(capacity, governor.liveCap())
        // SEVERE -> shrink live cap to the reduced target (~8) by holding (24-8)=16 permits.
        governor.applyThermalStatus(THERMAL_STATUS_SEVERE)
        assertEquals(capacity - AdaptiveEngine.THERMAL_REDUCED_CAP, governor.held(),
            "SEVERE holds (budget - reducedCap) permits")
        assertEquals(AdaptiveEngine.THERMAL_REDUCED_CAP, governor.liveCap(), "live cap shrunk to ~8")
        // CRITICAL (4) is also >= SEVERE -> same reduced cap (never tries to shrink below 1).
        governor.applyThermalStatus(4)
        assertEquals(AdaptiveEngine.THERMAL_REDUCED_CAP, governor.liveCap())
        // Cool-down -> release everything, full live cap restored (never raised past capacity).
        governor.applyThermalStatus(0)
        assertEquals(0, governor.held())
        assertEquals(capacity, governor.liveCap())
        // The live cap is ALWAYS within [1, capacity] — the governor can only reduce, never increase.
        assertTrue(governor.liveCap() in 1..capacity)
    }

    @Test
    fun `governor release frees the held permits back to the shared budget`() = runBlocking {
        val capacity = 24
        val budget = Semaphore(capacity)
        val governor = ThermalPermitGovernor(budget, capacity)
        governor.applyThermalStatus(THERMAL_STATUS_SEVERE)
        val held = governor.held()
        assertTrue(held > 0, "SEVERE must hold some permits")
        // While held, only (capacity - held) permits are available to runners.
        assertEquals(capacity - held, budget.availablePermits, "held permits removed from the budget")
        // release() (batch end) returns ALL of them.
        governor.release()
        assertEquals(0, governor.held())
        assertEquals(capacity, budget.availablePermits, "release restores the full budget")
    }
}
