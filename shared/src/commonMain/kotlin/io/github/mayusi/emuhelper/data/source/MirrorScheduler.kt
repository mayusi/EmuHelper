package io.github.mayusi.emuhelper.data.source

/**
 * BATCH-LEVEL MIRROR SCHEDULER (v0.8 — the #1 multi-file throughput win) — PURE, Android-free,
 * unit-testable.
 *
 * THE PROBLEM IT SOLVES.
 *   Before this scheduler, every concurrently-downloading file planned its OWN lanes
 *   ([planLanes]) over ALL the workable IA mirrors. So with 2 files in flight, BOTH pinned the same
 *   mirrors and each split that mirror's ~2 MB/s ceiling — two files contending for the same
 *   datacenter at ~1 MB/s apiece instead of each owning a distinct datacenter at ~2 MB/s.
 *
 *   MEASURED FACTS (real on-device IA sweeps) the algorithm encodes:
 *     - IA serves HTTP/2 from 2 INDEPENDENT mirror datacenters per item (ia6xxxxx + ia8xxxxx;
 *       sometimes a 3rd dn7xxxxx.ca). Spreading across the 2 datacenters ~doubles throughput.
 *     - Per-connection throttle ~1.2 MB/s; per-host ceiling ~2 MB/s.
 *     - Past ~4 connections to ONE host, IA SHEDS LOAD (3/8 conns observed failing). The "fails past
 *       4/host" ceiling is about the HOST, not the file: 2 files each putting 2 streams on ia6 = 4
 *       total is the ceiling; 3 files × 2 = 6 on ia6 would shed load. So the cap is BATCH-LEVEL.
 *
 * THE ALGORITHM.
 *   - [assign] takes the set of currently-active files (their fileId, resolved mirror hosts, and
 *     size) and returns one [FileLanePlan] per file: the lanes (host + stream count) that file
 *     should pin RIGHT NOW.
 *   - SINGLE-FILE FALLBACK: when `active.size <= 1`, the lone file gets exactly today's
 *     [planLanes] over ALL its mirrors — byte-identical to the pre-scheduler behaviour (the success
 *     metric: a single file is correct, warm, and spread-across-mirrors as before).
 *   - MULTI-FILE: round-robin assign each file a PRIMARY (owned) mirror — file i owns mirror[i mod
 *     M] of the batch's distinct mirror set. Each file fills its OWNED mirror first up to
 *     [AdaptiveEngine.PREFERRED_STREAMS_PER_HOST] (~2), then spills onto OTHER mirrors only if the
 *     batch budget and the per-host cap allow. Filling is done breadth-first ACROSS files so two
 *     files that own different mirrors each get their distinct datacenter saturated before anyone
 *     piles onto a shared host.
 *
 * THE TWO HARD INVARIANTS (asserted by tests + by [assign] construction):
 *   1. PER-HOST cap: the TOTAL streams across ALL files on any single host never exceeds
 *      [AdaptiveEngine.MAX_STREAMS_PER_HOST] (4). This is the batch-level reading of "IA sheds past
 *      4/host" — 3 files × 2 on one host = 6 is forbidden; the scheduler caps it at 4.
 *   2. GLOBAL budget: the SUM of streams across all plans never exceeds [globalBudget] (24). NOTE
 *      this scheduler is ONLY a host-assignment layer — it decides WHICH host each runner pins. The
 *      runtime 24-connection cap is still enforced by the shared connectionBudget Semaphore in the
 *      engine (every runner/racer acquires a permit, releases in finally). The scheduler NEVER mints
 *      permits; it only ever decides assignment, so it can only REDUCE or RE-ASSIGN, never increase
 *      past the cap.
 *
 * REBALANCE. When a file finishes, the caller re-invokes [assign] with the now-smaller active set;
 * the freed mirror's stream budget flows to the remaining files (e.g. once one of two files
 * completes, the survivor falls back to the single-file plan and re-pins ALL mirrors). Active
 * runners consult the new assignment between chunks.
 */

/**
 * What ONE active file wants from the scheduler: its stable [fileId], the distinct workable mirror
 * hosts it can pull from ([mirrors], already resolved by the caller), and its [sizeBytes] (used only
 * as a tiebreaker hint — larger files are assigned first so they grab a clean owned mirror).
 */
/* portable: was internal */ data class FileDemand(
    val fileId: String,
    val mirrors: List<String>,
    val sizeBytes: Long
)

/**
 * The scheduler's decision FOR ONE FILE: the [lanes] (host + stream count) that file should pin
 * right now. [fileId] matches the input [FileDemand.fileId]. An empty [lanes] list means the file
 * was given no streams this round (only happens when the batch budget is fully consumed by peers —
 * the file simply waits and is picked up on the next rebalance).
 */
/* portable: was internal */ data class FileLanePlan(
    val fileId: String,
    val lanes: List<Lane>
)

/**
 * Batch-level mirror scheduler. ONE instance per batch (constructed by DownloadManager). Stateless
 * between calls — [assign] is a pure function of its argument and [globalBudget], so it is trivially
 * thread-safe and re-callable on every rebalance. [globalBudget] defaults to the engine's global
 * connection ceiling (24) so the batch's total planned streams never exceed it.
 */
/* portable: was internal */ class MirrorScheduler(
    private val globalBudget: Int = AdaptiveEngine.MAX_ADAPTIVE_WORKERS,
    private val preferredPerHost: Int = AdaptiveEngine.PREFERRED_STREAMS_PER_HOST,
    private val maxPerHost: Int = AdaptiveEngine.MAX_STREAMS_PER_HOST
) {

    /**
     * Assign distinct mirrors/lane-budgets across the currently-[active] files so concurrent files
     * spread across the independent datacenters instead of overlapping on one.
     *
     * Returns one [FileLanePlan] per input file (same order, same fileIds). Guarantees:
     *   - single file (`active.size <= 1`) -> today's [planLanes] over ALL its mirrors, byte-identical,
     *   - per-host total streams across ALL plans <= [maxPerHost],
     *   - Σ streams across ALL plans <= [globalBudget],
     *   - one [Lane] per host within a file's plan (no duplicate-host lanes).
     */
    fun assign(active: List<FileDemand>): List<FileLanePlan> {
        if (active.isEmpty()) return emptyList()

        // SINGLE-FILE FALLBACK: byte-identical to the pre-scheduler per-file plan. This is the
        // critical contract — a lone file must pin ALL its mirrors exactly as planLanes did before.
        if (active.size == 1) {
            val only = active.first()
            return listOf(FileLanePlan(only.fileId, planLanes(only.mirrors, globalBudget)))
        }

        // ---- MULTI-FILE: round-robin owned mirrors, breadth-first fill across files. ----

        // Per-file cleaned mirror list (distinct, non-blank), preserving input order. Files with no
        // usable mirror get an empty plan.
        val cleanedMirrors: Map<String, List<String>> = active.associate { d ->
            d.fileId to d.mirrors.distinct().filter { it.isNotBlank() }
        }

        // The batch's distinct mirror set, in first-seen order, for round-robin ownership.
        val batchMirrors = LinkedHashSet<String>()
        for (d in active) cleanedMirrors[d.fileId]!!.forEach { batchMirrors.add(it) }
        val mirrorList = batchMirrors.toList()

        // Per-host running total of streams across ALL files (the batch-level per-host cap state).
        val hostTotal = HashMap<String, Int>()
        // Per-file -> per-host stream allocation we are building up.
        val perFileStreams: MutableMap<String, MutableMap<String, Int>> =
            active.associate { it.fileId to HashMap<String, Int>() }.toMutableMap()
        var globalAssigned = 0

        // Assign larger files first so a big download grabs a clean owned mirror before small ones
        // nibble at the shared budget. Ties keep input order (stable). Index in THIS ordered list is
        // the file's round-robin slot for owned-mirror selection.
        val ordered = active.sortedWith(
            compareByDescending<FileDemand> { it.sizeBytes }.thenBy { active.indexOf(it) }
        )

        // Helper: can we add one stream to [host] for [fileId] right now? (host cap + global budget +
        // this file doesn't already hold maxPerHost on that host).
        fun canAdd(fileId: String, host: String): Boolean {
            if (globalAssigned >= globalBudget) return false
            if ((hostTotal[host] ?: 0) >= maxPerHost) return false
            if ((perFileStreams[fileId]!![host] ?: 0) >= maxPerHost) return false
            return true
        }

        fun add(fileId: String, host: String) {
            perFileStreams[fileId]!!.merge(host, 1, Int::plus)
            hostTotal.merge(host, 1, Int::plus)
            globalAssigned++
        }

        // PHASE 0 — OWNED MIRROR, breadth-first to the preference. Each file owns mirror[slot mod M]
        // of the batch mirror set (M = batch's distinct mirror count). We do `preferredPerHost`
        // rounds; each round gives every file ONE more stream on its owned mirror (if the file
        // actually has that mirror and the host cap allows). Breadth-first ACROSS files means two
        // files owning different datacenters each saturate their own before either spills — the
        // spread that doubles throughput. The per-host cap naturally limits files that COLLIDE on the
        // same owned mirror (only happens when files > mirrors, e.g. 3 files / 2 mirrors).
        if (mirrorList.isNotEmpty()) {
            repeat(preferredPerHost) {
                ordered.forEachIndexed { slot, d ->
                    val mine = cleanedMirrors[d.fileId]!!
                    if (mine.isEmpty()) return@forEachIndexed
                    // This file's owned mirror: round-robin over the batch mirror set, but it must be
                    // a mirror THIS file actually serves. If its round-robin pick isn't in its own
                    // mirror list, fall back to its first own mirror that still has host headroom.
                    val owned = mirrorList[slot % mirrorList.size]
                    val target = if (owned in mine && canAdd(d.fileId, owned)) {
                        owned
                    } else {
                        mine.firstOrNull { canAdd(d.fileId, it) }
                    }
                    if (target != null) add(d.fileId, target)
                }
            }
        }

        // PHASE 1 — SPILL onto shared mirrors, breadth-first, only if budget + host cap allow. After
        // every file has filled its owned datacenter to the preference, spare global budget is spread
        // across the OTHER mirrors each file serves (still capped per host across the whole batch). A
        // file deepens past the preference on a shared host only when there is real spare budget AND
        // that host hasn't hit the batch cap — so we never pile past 4/host or over the global 24.
        var progressed = true
        while (globalAssigned < globalBudget && progressed) {
            progressed = false
            for (d in ordered) {
                if (globalAssigned >= globalBudget) break
                val mine = cleanedMirrors[d.fileId]!!
                if (mine.isEmpty()) continue
                // Prefer the mirror with the most batch headroom (lowest current host total) so spill
                // spreads rather than stacking — keeps the per-host distribution even.
                val target = mine
                    .filter { canAdd(d.fileId, it) }
                    .minByOrNull { hostTotal[it] ?: 0 }
                if (target != null) {
                    add(d.fileId, target)
                    progressed = true
                }
            }
        }

        // Materialise plans in the ORIGINAL input order (callers index by fileId, but stable order is
        // nice for tests/logs). Lanes are emitted host-by-host in the file's own mirror order, with
        // only positive-stream hosts.
        return active.map { d ->
            val streams = perFileStreams[d.fileId]!!
            val mine = cleanedMirrors[d.fileId]!!
            val lanes = mine.mapNotNull { host ->
                val s = streams[host] ?: 0
                if (s > 0) Lane(host, s) else null
            }
            FileLanePlan(d.fileId, lanes)
        }
    }
}
