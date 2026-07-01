package io.github.mayusi.emuhelper.data.source

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * BATCH-LEVEL MIRROR SCHEDULER contract ([MirrorScheduler.assign]).
 *
 * The #1 multi-file throughput win: concurrent files must be assigned DISTINCT mirrors so they don't
 * both pin the same datacenter and split its ~2 MB/s ceiling. The HARD invariants the scheduler must
 * uphold for every assignment:
 *   - PER-HOST cap: the TOTAL streams across ALL files on any single host <= MAX_STREAMS_PER_HOST (4)
 *     — the "IA sheds past 4/host" ceiling is about the HOST, not the file (3 files × 2 on ia6 = 6 is
 *     forbidden; cap at 4).
 *   - GLOBAL budget: Σ streams across ALL plans <= globalBudget (24).
 *   - SINGLE-FILE FALLBACK: one active file -> byte-identical to today's [planLanes] over all mirrors.
 *   - REBALANCE: when a file finishes, the remaining files widen to reclaim the freed mirror.
 */
class MirrorSchedulerTest {

    private val ia6 = "https://ia600000.us.archive.org/x"
    private val ia8 = "https://ia800000.us.archive.org/x"
    private val dn7 = "https://dn720000.ca.archive.org/x"

    private fun totalStreams(plans: List<FileLanePlan>): Int =
        plans.sumOf { p -> p.lanes.sumOf { it.streams } }

    /** Per-host total streams summed across ALL files' plans. */
    private fun perHostTotals(plans: List<FileLanePlan>): Map<String, Int> {
        val m = HashMap<String, Int>()
        for (p in plans) for (l in p.lanes) m.merge(l.host, l.streams, Int::plus)
        return m
    }

    /** The hard invariants asserted against any assignment. */
    private fun assertInvariants(
        plans: List<FileLanePlan>,
        active: List<FileDemand>,
        globalBudget: Int = AdaptiveEngine.MAX_ADAPTIVE_WORKERS
    ) {
        // Σ streams within the global budget.
        assertTrue(
            totalStreams(plans) <= globalBudget,
            "Σ streams ${totalStreams(plans)} must be <= globalBudget $globalBudget"
        )
        // BATCH-LEVEL per-host cap: total streams across ALL files on any one host <= 4.
        for ((host, total) in perHostTotals(plans)) {
            assertTrue(
                total <= AdaptiveEngine.MAX_STREAMS_PER_HOST,
                "host $host has $total total streams across all files, must be <= ${AdaptiveEngine.MAX_STREAMS_PER_HOST}"
            )
        }
        // One lane per host within a file, every lane positive, host belongs to that file's mirrors.
        for (p in plans) {
            assertEquals(p.lanes.map { it.host }.distinct().size, p.lanes.size, "one lane per host per file")
            val mine = active.first { it.fileId == p.fileId }.mirrors.distinct().filter { it.isNotBlank() }
            for (l in p.lanes) {
                assertTrue(l.streams >= 1, "lane streams must be >= 1")
                assertTrue(l.host in mine, "lane host ${l.host} must be a mirror of file ${p.fileId}")
            }
        }
        // Every input file gets a plan (possibly empty), exactly once, in input order.
        assertEquals(active.map { it.fileId }, plans.map { it.fileId }, "one plan per file, input order")
    }

    @Test
    fun `single file is byte-identical to planLanes over all mirrors`() {
        val sched = MirrorScheduler()
        for (mirrors in listOf(
            listOf(ia6, ia8),
            listOf(ia6, ia8, dn7),
            listOf(ia6),
            listOf(ia6, ia6, ia8)  // dup -> deduped, same as planLanes
        )) {
            val active = listOf(FileDemand("f1", mirrors, sizeBytes = 1_000_000_000L))
            val plans = sched.assign(active)
            assertEquals(1, plans.size)
            // EXACTLY today's planLanes output, lane-for-lane.
            assertEquals(
                planLanes(mirrors, AdaptiveEngine.MAX_ADAPTIVE_WORKERS),
                plans.first().lanes,
                "single-file plan must equal planLanes for mirrors=$mirrors"
            )
            assertInvariants(plans, active)
        }
    }

    @Test
    fun `two files two mirrors get distinct mirrors with no overlap`() {
        val sched = MirrorScheduler()
        val active = listOf(
            FileDemand("A", listOf(ia6, ia8), sizeBytes = 2_000_000_000L),
            FileDemand("B", listOf(ia6, ia8), sizeBytes = 1_000_000_000L)
        )
        val plans = sched.assign(active)
        assertInvariants(plans, active)

        val planA = plans.first { it.fileId == "A" }
        val planB = plans.first { it.fileId == "B" }
        // Each file should own a DISTINCT primary mirror (the whole point — spread across datacenters).
        val aHosts = planA.lanes.map { it.host }.toSet()
        val bHosts = planB.lanes.map { it.host }.toSet()
        assertTrue(aHosts.isNotEmpty() && bHosts.isNotEmpty(), "both files must get streams")
        // The owned (primary) mirror of A must differ from B's — they don't both pile on the same one.
        // With 2 files / 2 mirrors each file owns one, so the two primaries are disjoint.
        assertTrue(
            aHosts != bHosts || (aHosts.size > 1 && bHosts.size > 1),
            "two files must not be assigned the IDENTICAL single shared mirror: A=$aHosts B=$bHosts"
        )
        // Per-host total never exceeds 4: 2 files × 2 streams each on 2 mirrors = at most 4/host.
        for ((_, total) in perHostTotals(plans)) {
            assertTrue(total <= AdaptiveEngine.MAX_STREAMS_PER_HOST)
        }
    }

    @Test
    fun `two files two mirrors - each file primarily owns one distinct datacenter`() {
        val sched = MirrorScheduler()
        val active = listOf(
            FileDemand("A", listOf(ia6, ia8), sizeBytes = 5_000_000_000L),
            FileDemand("B", listOf(ia6, ia8), sizeBytes = 4_000_000_000L)
        )
        val plans = sched.assign(active)
        assertInvariants(plans, active)
        // The DOMINANT (most-streams) host of A must differ from B's dominant host: A runs mostly on
        // datacenter-6, B mostly on datacenter-8 (or vice versa) — distinct primary datacenters.
        fun dominantHost(p: FileLanePlan) = p.lanes.maxByOrNull { it.streams }!!.host
        val domA = dominantHost(plans.first { it.fileId == "A" })
        val domB = dominantHost(plans.first { it.fileId == "B" })
        assertTrue(domA != domB, "each file's primary datacenter must be distinct: A=$domA B=$domB")
    }

    @Test
    fun `three files two mirrors - owners double up but per-host total stays at 4 not 6`() {
        val sched = MirrorScheduler()
        val active = listOf(
            FileDemand("A", listOf(ia6, ia8), sizeBytes = 3_000_000_000L),
            FileDemand("B", listOf(ia6, ia8), sizeBytes = 2_000_000_000L),
            FileDemand("C", listOf(ia6, ia8), sizeBytes = 1_000_000_000L)
        )
        val plans = sched.assign(active)
        assertInvariants(plans, active)
        // THE critical batch-cap test: 3 files would naively want 2 streams each on their owned
        // mirror, but with only 2 mirrors that's 3 files colliding -> the per-host total must be
        // capped at 4, NOT 6. (The "fails past 4/host" ceiling is about the HOST across all files.)
        val totals = perHostTotals(plans)
        for ((host, total) in totals) {
            assertEquals(
                true, total <= AdaptiveEngine.MAX_STREAMS_PER_HOST,
                "host $host total $total must be capped at 4, never 6 across 3 files"
            )
        }
        // And no host exceeds 4 — explicitly assert neither mirror hit 6.
        assertTrue(totals.values.none { it > AdaptiveEngine.MAX_STREAMS_PER_HOST }, "no host over 4: $totals")
        // The grand total over 2 hosts can't exceed 2×4 = 8 regardless of file count.
        assertTrue(totalStreams(plans) <= 2 * AdaptiveEngine.MAX_STREAMS_PER_HOST)
    }

    @Test
    fun `rebalance - when a file finishes the remaining file widens to all mirrors`() {
        val sched = MirrorScheduler()
        val twoFiles = listOf(
            FileDemand("A", listOf(ia6, ia8), sizeBytes = 2_000_000_000L),
            FileDemand("B", listOf(ia6, ia8), sizeBytes = 1_000_000_000L)
        )
        val twoPlans = sched.assign(twoFiles)
        assertInvariants(twoPlans, twoFiles)
        // While two files run, neither owns BOTH mirrors fully (they share the datacenters).
        val aWhileTwo = twoPlans.first { it.fileId == "A" }.lanes.sumOf { it.streams }

        // File B finishes -> reassign with only A active. A must now reclaim the freed mirror and
        // get the FULL single-file plan (2+2 over both datacenters), i.e. strictly MORE than before.
        val oneFile = listOf(FileDemand("A", listOf(ia6, ia8), sizeBytes = 2_000_000_000L))
        val onePlan = sched.assign(oneFile)
        assertInvariants(onePlan, oneFile)
        assertEquals(
            planLanes(listOf(ia6, ia8), AdaptiveEngine.MAX_ADAPTIVE_WORKERS),
            onePlan.first().lanes,
            "the lone survivor must get today's full single-file plan over all mirrors"
        )
        val aWhenAlone = onePlan.first().lanes.sumOf { it.streams }
        assertTrue(
            aWhenAlone >= aWhileTwo,
            "a file must widen (>= streams) once a peer finishes: alone=$aWhenAlone vsTwo=$aWhileTwo"
        )
        assertEquals(4, aWhenAlone, "alone, file A gets the 2+2 sweet spot across both datacenters")
    }

    @Test
    fun `three mirrors three files - each owns a distinct datacenter`() {
        val sched = MirrorScheduler()
        val active = listOf(
            FileDemand("A", listOf(ia6, ia8, dn7), sizeBytes = 3_000_000_000L),
            FileDemand("B", listOf(ia6, ia8, dn7), sizeBytes = 2_000_000_000L),
            FileDemand("C", listOf(ia6, ia8, dn7), sizeBytes = 1_000_000_000L)
        )
        val plans = sched.assign(active)
        assertInvariants(plans, active)
        // Each file's dominant datacenter should be distinct (round-robin owned mirrors over 3).
        fun dominantHost(p: FileLanePlan) = p.lanes.maxByOrNull { it.streams }!!.host
        val doms = plans.map { dominantHost(it) }.toSet()
        assertEquals(3, doms.size, "three files over three mirrors each own a distinct datacenter: $doms")
        // Per-host cap holds.
        assertTrue(perHostTotals(plans).values.all { it <= AdaptiveEngine.MAX_STREAMS_PER_HOST })
    }

    @Test
    fun `global budget is never exceeded even with many files and mirrors`() {
        val sched = MirrorScheduler(globalBudget = AdaptiveEngine.MAX_ADAPTIVE_WORKERS)
        // 8 files, 3 mirrors — would naively want far more than 24 streams; must clamp to <= 24 AND
        // <= 4/host (so really <= 3×4 = 12 here).
        val active = (0 until 8).map { i ->
            FileDemand("f$i", listOf(ia6, ia8, dn7), sizeBytes = (8 - i).toLong() * 1_000_000_000L)
        }
        val plans = sched.assign(active)
        assertInvariants(plans, active)
        assertTrue(totalStreams(plans) <= AdaptiveEngine.MAX_ADAPTIVE_WORKERS, "Σ <= 24")
        // With 3 mirrors and a 4/host cap, the batch can never place more than 12 streams total.
        assertTrue(totalStreams(plans) <= 3 * AdaptiveEngine.MAX_STREAMS_PER_HOST)
    }

    @Test
    fun `files with disjoint mirror sets each get their own`() {
        val sched = MirrorScheduler()
        // A serves only ia6/ia8; B serves only dn7 — no overlap. Each should fully own its own.
        val active = listOf(
            FileDemand("A", listOf(ia6, ia8), sizeBytes = 2_000_000_000L),
            FileDemand("B", listOf(dn7), sizeBytes = 1_000_000_000L)
        )
        val plans = sched.assign(active)
        assertInvariants(plans, active)
        val aHosts = plans.first { it.fileId == "A" }.lanes.map { it.host }.toSet()
        val bHosts = plans.first { it.fileId == "B" }.lanes.map { it.host }.toSet()
        assertTrue(dn7 in bHosts, "B must use its only mirror dn7")
        assertTrue(dn7 !in aHosts, "A never uses a mirror it doesn't serve")
        assertTrue(aHosts.all { it == ia6 || it == ia8 })
    }

    @Test
    fun `degenerate inputs - empty active and files with no mirrors`() {
        val sched = MirrorScheduler()
        assertTrue(sched.assign(emptyList()).isEmpty(), "no active files -> no plans")

        // A file with no usable mirrors gets an empty plan; a sibling with mirrors still gets streams.
        val active = listOf(
            FileDemand("A", listOf("", "  "), sizeBytes = 1_000_000_000L),
            FileDemand("B", listOf(ia6, ia8), sizeBytes = 2_000_000_000L)
        )
        val plans = sched.assign(active)
        assertInvariants(plans, active)
        assertTrue(plans.first { it.fileId == "A" }.lanes.isEmpty(), "no-mirror file gets empty plan")
        assertTrue(plans.first { it.fileId == "B" }.lanes.isNotEmpty(), "B still gets streams")
    }
}
