package io.github.mayusi.emuhelper.data.source

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * v3 LANE-PLANNING contract ([planLanes]).
 *
 * The laned engine's correctness/perf rests on this pure function distributing warm, host-pinned
 * stream runners across the workable IA mirrors. The MEASURED facts it must encode:
 *   - prefer ~2 streams/host (the stable sweet spot — two conns ~saturate a host's ~2 MB/s ceiling),
 *   - NEVER exceed ~4 streams/host (past that IA sheds load — measured 3/8 conns failing),
 *   - keep Σ streams within the global connection budget (the shared 24-permit Semaphore),
 *   - SPREAD across ALL mirrors before deepening any one (spread is the throughput lever: 2+2 across
 *     two datacenters measured +93% vs 4+0 on one),
 *   - always produce ≥1 stream when ≥1 mirror + budget exist.
 */
class LanePlanningTest {

    private fun totalStreams(lanes: List<Lane>): Int = lanes.sumOf { it.streams }

    /** Every invariant the engine relies on, asserted against an arbitrary plan. */
    private fun assertInvariants(lanes: List<Lane>, mirrors: List<String>, budget: Int) {
        val distinct = mirrors.distinct().filter { it.isNotBlank() }
        // Σ streams within the global budget.
        assertTrue(
            totalStreams(lanes) <= budget,
            "Σ streams ${totalStreams(lanes)} must be <= budget $budget"
        )
        // Never more than the hard per-host cap.
        for (l in lanes) {
            assertTrue(
                l.streams in 1..AdaptiveEngine.MAX_STREAMS_PER_HOST,
                "lane ${l.host} has ${l.streams} streams, must be 1..${AdaptiveEngine.MAX_STREAMS_PER_HOST}"
            )
            assertTrue(l.host in distinct, "lane host ${l.host} must be one of the input mirrors")
        }
        // No duplicate host lanes (one Lane per host).
        assertEquals(lanes.map { it.host }.distinct().size, lanes.size, "one lane per host")
        // At least one stream total whenever there is a host and budget.
        if (distinct.isNotEmpty() && budget >= 1) {
            assertTrue(totalStreams(lanes) >= 1, "must allocate >=1 stream when a mirror + budget exist")
        }
    }

    @Test
    fun `two mirrors with ample budget gives the 2+2 sweet spot spread across both`() {
        val mirrors = listOf("https://ia600000.us.archive.org/x", "https://ia800000.us.archive.org/x")
        val lanes = planLanes(mirrors, budget = 24)
        assertInvariants(lanes, mirrors, 24)
        assertEquals(2, lanes.size, "both mirrors must get a lane (spread)")
        // Preferred 2/host -> 2+2 = 4 warm streams, the measured stable topology.
        assertTrue(lanes.all { it.streams == AdaptiveEngine.PREFERRED_STREAMS_PER_HOST },
            "each mirror should get the preferred ~2 streams: $lanes")
        assertEquals(4, totalStreams(lanes), "2 mirrors x 2 streams = 4 warm streams")
    }

    @Test
    fun `three mirrors spread one-each before deepening (spread is the lever)`() {
        val mirrors = listOf(
            "https://ia600000.us.archive.org/x",
            "https://ia800000.us.archive.org/x",
            "https://dn720000.ca.archive.org/x"
        )
        val lanes = planLanes(mirrors, budget = 24)
        assertInvariants(lanes, mirrors, 24)
        assertEquals(3, lanes.size, "all three datacenters must be used")
        assertEquals(6, totalStreams(lanes), "3 mirrors x preferred 2 = 6 warm streams")
        assertTrue(lanes.all { it.streams == 2 }, "balanced 2/host across all three: $lanes")
    }

    @Test
    fun `tight budget spreads breadth-first - never piles all on one mirror`() {
        val mirrors = listOf("https://hostA/x", "https://hostB/x")
        // Budget of 2 with 2 mirrors must give 1+1 (spread), NOT 2+0.
        val lanes = planLanes(mirrors, budget = 2)
        assertInvariants(lanes, mirrors, 2)
        assertEquals(2, lanes.size, "budget 2 over 2 mirrors must put one stream on EACH (spread)")
        assertTrue(lanes.all { it.streams == 1 }, "1+1 spread, never 2+0: $lanes")
    }

    @Test
    fun `budget of 3 over 2 mirrors is 2+1 (breadth-first then top up), never 3+0`() {
        val mirrors = listOf("https://hostA/x", "https://hostB/x")
        val lanes = planLanes(mirrors, budget = 3)
        assertInvariants(lanes, mirrors, 3)
        assertEquals(2, lanes.size)
        assertEquals(3, totalStreams(lanes))
        // Both mirrors get their 1st stream (round 1), then one gets a 2nd (round 2). So {2,1}.
        val counts = lanes.map { it.streams }.sorted()
        assertEquals(listOf(1, 2), counts, "must be a 2+1 split (spread first), never 3+0: $lanes")
        // Hard proof of the spread invariant: no single lane holds the whole budget.
        assertTrue(lanes.none { it.streams == 3 }, "must never pile all 3 streams on one mirror")
    }

    @Test
    fun `single mirror deepens up to the per-host cap but never past it`() {
        val mirrors = listOf("https://only-host/x")
        // Ample budget, one mirror: deepen toward the per-host HARD cap (4), never beyond.
        val lanes = planLanes(mirrors, budget = 24)
        assertInvariants(lanes, mirrors, 24)
        assertEquals(1, lanes.size)
        assertEquals(
            AdaptiveEngine.MAX_STREAMS_PER_HOST, lanes.first().streams,
            "a single mirror caps at the per-host hard limit (~4) — past that IA sheds load"
        )
    }

    @Test
    fun `per-host hard cap is never exceeded even with huge budget`() {
        // Two mirrors, enormous budget: 2 hosts x max 4 = 8 streams max, never more per host.
        val mirrors = listOf("https://hostA/x", "https://hostB/x")
        val lanes = planLanes(mirrors, budget = 100)
        assertInvariants(lanes, mirrors, 100)
        assertTrue(lanes.all { it.streams <= AdaptiveEngine.MAX_STREAMS_PER_HOST })
        assertTrue(
            totalStreams(lanes) <= 2 * AdaptiveEngine.MAX_STREAMS_PER_HOST,
            "2 hosts can never exceed 2x the per-host cap: $lanes"
        )
    }

    @Test
    fun `budget below the global cap bounds total streams`() {
        // Three mirrors but only 4 permits: must spread to 2+1+1 = 4 (breadth-first), within budget.
        val mirrors = listOf("https://a/x", "https://b/x", "https://c/x")
        val lanes = planLanes(mirrors, budget = 4)
        assertInvariants(lanes, mirrors, 4)
        assertEquals(4, totalStreams(lanes), "Σ streams must equal the budget when work allows")
        assertEquals(3, lanes.size, "all three mirrors used before any is deepened")
        val counts = lanes.map { it.streams }.sorted()
        assertEquals(listOf(1, 1, 2), counts, "2+1+1 breadth-first: $lanes")
    }

    @Test
    fun `degenerate inputs are handled - empty mirrors or zero budget yield no lanes`() {
        assertTrue(planLanes(emptyList(), budget = 24).isEmpty(), "no mirrors -> no lanes")
        assertTrue(planLanes(listOf("https://x/y"), budget = 0).isEmpty(), "zero budget -> no lanes")
        assertTrue(planLanes(listOf("", "  "), budget = 24).isEmpty(), "blank-only mirrors -> no lanes")
    }

    @Test
    fun `duplicate mirror entries are deduped to one lane`() {
        val mirrors = listOf("https://dup/x", "https://dup/x", "https://dup/x")
        val lanes = planLanes(mirrors, budget = 24)
        assertEquals(1, lanes.size, "duplicate hosts collapse to a single lane")
        assertEquals(AdaptiveEngine.MAX_STREAMS_PER_HOST, lanes.first().streams)
    }

    @Test
    fun `lanes flattened to runners pin the right host counts`() {
        // The engine flattens lanes into one runner per stream; assert the per-host runner counts.
        val mirrors = listOf("https://hostA/x", "https://hostB/x")
        val lanes = planLanes(mirrors, budget = 24)
        val runners = lanes.flatMap { lane -> List(lane.streams) { lane.host } }
        assertEquals(totalStreams(lanes), runners.size, "one runner per stream")
        val perHost = runners.groupingBy { it }.eachCount()
        for (l in lanes) {
            assertEquals(l.streams, perHost[l.host], "host ${l.host} must have ${l.streams} pinned runners")
        }
    }
}
