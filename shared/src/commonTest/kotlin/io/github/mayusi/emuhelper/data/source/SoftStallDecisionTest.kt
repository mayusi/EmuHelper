package io.github.mayusi.emuhelper.data.source

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for the SOFT-STALL DECISION ([RecentRateWindow]) — FIX B.
 *
 * The old soft-stall heuristic compared a chunk's INSTANTANEOUS rate against
 * SOFT_STALL_FRACTION (0.25) × the PEAK EWMA of the single fastest host. On a real device with
 * ~24 connections sharing one pipe, most chunks legitimately run well below 25% of the fastest
 * node's peak, so it tripped a false-positive storm that exhausted the shared retry budget and
 * failed the download.
 *
 * The fix makes the baseline a CURRENT, decaying MEDIAN of recent completed-chunk throughputs
 * (real bytes/ms), requires the slow reading to be SUSTAINED across several consecutive windows,
 * and DISABLES the heuristic entirely until enough real samples exist. These tests feed realistic
 * throughput magnitudes (thousands of bytes/ms — i.e. a few MB/s) and assert:
 *   - a normally-contended chunk (40-60% of the baseline) does NOT soft-stall,
 *   - a genuinely dead chunk (≈0 / far below the bar) DOES, but only once sustained,
 *   - with too few samples the heuristic is OFF (the safe fallback).
 */
class SoftStallDecisionTest {

    /** Fill a window with a realistic spread of completed-chunk rates (bytes/ms). */
    private fun windowWith(vararg ratesBytesPerMs: Double): RecentRateWindow {
        val w = RecentRateWindow()
        for (r in ratesBytesPerMs) w.record(r)
        return w
    }

    // A realistic contended baseline: chunks completing around 2000-4000 B/ms (~2-4 MB/s each).
    private val realisticRates = doubleArrayOf(3200.0, 2800.0, 3500.0, 2600.0, 4000.0, 3000.0, 2900.0, 3300.0)

    @Test
    fun `with too few samples soft-stall is disabled (safe fallback)`() {
        // Fewer than SOFT_STALL_MIN_SAMPLES completed chunks -> baseline untrustworthy -> never stall,
        // no matter how slow the chunk looks. This is the guardrail that makes the heuristic inert
        // until it has real data.
        val w = windowWith(3000.0, 2800.0) // only 2 samples (< SOFT_STALL_MIN_SAMPLES)
        assertTrue(w.sampleCount() < AdaptiveEngine.SOFT_STALL_MIN_SAMPLES)
        // Even a dead-slow chunk over a long elapsed time with many "slow" windows does NOT stall.
        assertFalse(
            w.shouldSoftStall(chunkRate = 1.0, elapsedMs = 10_000, consecutiveSlowWindows = 10),
            "soft-stall must be DISABLED until the baseline has enough samples"
        )
        assertFalse(w.isBelowBar(1.0), "isBelowBar must also be inert without enough samples")
    }

    @Test
    fun `baseline is the median of recent rates`() {
        val w = windowWith(*realisticRates)
        // Median of {2600,2800,2900,3000,3200,3300,3500,4000} = (3000+3200)/2 = 3100.
        assertEquals(3100.0, w.baseline(), 1e-9)
    }

    @Test
    fun `a normally-contended chunk at 40-60 percent of baseline does NOT soft-stall`() {
        val w = windowWith(*realisticRates)
        val baseline = w.baseline() // 3100 B/ms
        // 50% of baseline — totally normal on a shared pipe. The bar is SOFT_STALL_FRACTION (0.15)
        // × baseline = 465 B/ms, far below 1550, so this never trips even when "sustained".
        val contendedRate = 0.50 * baseline // 1550 B/ms
        assertFalse(w.isBelowBar(contendedRate), "50% of baseline is above the 15% bar")
        assertFalse(
            w.shouldSoftStall(contendedRate, elapsedMs = 8000, consecutiveSlowWindows = 99),
            "a normally-contended chunk (50% of baseline) must NOT soft-stall"
        )
        // 40% too.
        val rate40 = 0.40 * baseline
        assertFalse(w.shouldSoftStall(rate40, elapsedMs = 8000, consecutiveSlowWindows = 99))
    }

    @Test
    fun `a genuinely dead chunk far below the bar DOES soft-stall once sustained`() {
        val w = windowWith(*realisticRates)
        val baseline = w.baseline() // 3100 B/ms; bar = 0.15 * 3100 = 465 B/ms
        val deadRate = 50.0 // ~0.05 MB/s — genuinely throttled/dead, well under the 465 bar.
        assertTrue(w.isBelowBar(deadRate), "a dead chunk must be below the bar")
        // But a SINGLE slow window is not enough — must be SUSTAINED.
        assertFalse(
            w.shouldSoftStall(deadRate, elapsedMs = 8000, consecutiveSlowWindows = 1),
            "one slow window is normal jitter, must not stall"
        )
        assertFalse(
            w.shouldSoftStall(deadRate, elapsedMs = 8000, consecutiveSlowWindows = AdaptiveEngine.SOFT_STALL_WINDOWS - 1),
            "just under the sustained threshold must not stall"
        )
        // Sustained over SOFT_STALL_WINDOWS consecutive windows -> NOW it stalls.
        assertTrue(
            w.shouldSoftStall(deadRate, elapsedMs = 8000, consecutiveSlowWindows = AdaptiveEngine.SOFT_STALL_WINDOWS),
            "a sustained dead chunk must soft-stall"
        )
    }

    @Test
    fun `warm-up gate blocks soft-stall before MIN_SAMPLE_MS`() {
        val w = windowWith(*realisticRates)
        val deadRate = 10.0
        // Even dead-slow and sustained, before the warm-up window we never stall (TCP ramp-up).
        assertFalse(
            w.shouldSoftStall(deadRate, elapsedMs = AdaptiveEngine.MIN_SAMPLE_MS - 1, consecutiveSlowWindows = 99),
            "must not soft-stall during TCP warm-up"
        )
        assertTrue(
            w.shouldSoftStall(deadRate, elapsedMs = AdaptiveEngine.MIN_SAMPLE_MS, consecutiveSlowWindows = 99),
            "past warm-up a sustained dead chunk stalls"
        )
    }

    @Test
    fun `baseline decays as bandwidth gets shared (ring buffer overwrites old peaks)`() {
        // FIX B core property: the baseline must come DOWN as later (shared) chunks report slower
        // rates — unlike the old peak EWMA that only ever climbed. Fill past capacity so the early
        // fast samples are evicted by the ring buffer.
        val w = RecentRateWindow()
        repeat(AdaptiveEngine.RECENT_RATE_WINDOW) { w.record(9000.0) } // early: pipe to ourselves, fast
        val peakBaseline = w.baseline()
        assertEquals(9000.0, peakBaseline, 1e-9)
        // Now bandwidth is shared across many connections; recent chunks are much slower.
        repeat(AdaptiveEngine.RECENT_RATE_WINDOW) { w.record(2000.0) } // overwrites all the fast ones
        val sharedBaseline = w.baseline()
        assertEquals(2000.0, sharedBaseline, 1e-9)
        assertTrue(sharedBaseline < peakBaseline, "baseline must decay as bandwidth is shared")
        // A 2000 B/ms chunk (== current baseline) is NOT slow against the decayed baseline...
        assertFalse(w.isBelowBar(2000.0))
        // ...whereas against the OLD never-decaying peak (9000), 2000 would have been only 22% and
        // tripped the old 25%-of-peak heuristic. The decay is exactly what kills the false positives.
    }

    @Test
    fun `non-positive samples are ignored and do not poison the baseline`() {
        val w = RecentRateWindow()
        w.record(3000.0); w.record(0.0); w.record(-5.0); w.record(3000.0)
        assertEquals(2, w.sampleCount(), "zero/negative samples must be ignored")
        assertEquals(3000.0, w.baseline(), 1e-9)
    }
}
