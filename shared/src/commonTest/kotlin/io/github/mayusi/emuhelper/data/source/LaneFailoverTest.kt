package io.github.mayusi.emuhelper.data.source

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * v3 LANE FAILOVER + host-pinned byte-correctness.
 *
 * Two properties the laned engine must hold when a mirror dies mid-download:
 *   1. BYTE-CORRECTNESS UNDER PINNING — host-pinned runners still cover [0,size) with no gaps or
 *      overlaps, and confirmedBytes == expectedSize. Pinning the host must not change the coverage
 *      contract the ChunkQueue enforces.
 *   2. MIRROR FAILOVER — if a lane's host is declared dead (its lane exhausts its error streak), its
 *      in-flight chunk is requeued (the normal error path) and SURVIVING lanes complete it; the file
 *      still finishes, the byte total is exact, and NO connection permit leaks.
 *
 * Modelled directly against the real ChunkQueue + the engine's pin/failover bookkeeping (minus the
 * network), the same way ByteCoverageTest models the worker loop.
 */
class LaneFailoverTest {

    /** A live permit gauge that flags any over-cap and reports leaks (current != 0 at the end). */
    private class PermitGauge(val capacity: Int) {
        private val held = AtomicInteger(0)
        val peak = AtomicInteger(0)
        val violations = AtomicInteger(0)
        fun onAcquire() {
            val n = held.incrementAndGet()
            if (n > capacity) violations.incrementAndGet()
            var p = peak.get(); while (n > p && !peak.compareAndSet(p, n)) p = peak.get()
        }
        fun onRelease() { held.decrementAndGet() }
        fun current() = held.get()
    }

    /**
     * Run the laned pinned-runner model against an in-memory source. [deadHost], once it has served
     * [killAfterChunks] chunks, starts failing every attempt (a mirror going down mid-download). The
     * failover bookkeeping mirrors RemoteSource: consecutive real errors on a host bump a streak and,
     * past [AdaptiveEngine.MAX_ERROR_ATTEMPTS] with another host alive, the host is removed from the
     * live set so its runners re-target to a survivor.
     *
     * Returns (reconstructedOutput, confirmedBytes, gauge).
     */
    private fun simulateFailover(
        size: Int,
        mirrors: List<String>,
        budget: Int,
        deadHost: String?,
        killAfterChunks: Int,
        seed: Long
    ): Triple<ByteArray, Long, PermitGauge> = runBlocking {
        val source = ByteArray(size) { i -> ((i * 31 + 7) and 0xFF).toByte() }
        val output = ByteArray(size) { 0 }
        val chunks = partitionChunks(size.toLong())
        val queue = ChunkQueue(chunks)
        val confirmed = AtomicLong(0)
        val gauge = PermitGauge(budget)
        val connBudget = Semaphore(budget.coerceAtLeast(1))

        val lanes = planLanes(mirrors, budget)
        val runnerHosts = lanes.flatMap { lane -> List(lane.streams) { lane.host } }

        // Failover bookkeeping (mirrors RemoteSource.downloadAdaptive).
        val liveHosts = ConcurrentHashMap.newKeySet<String>().apply { addAll(mirrors.distinct()) }
        val hostErrorStreak = ConcurrentHashMap<String, AtomicInteger>()
        val laneDeathThreshold = AdaptiveEngine.MAX_ERROR_ATTEMPTS
        val servedByDead = AtomicInteger(0)

        fun pickLive(pinned: String): String =
            if (pinned in liveHosts) pinned
            else liveHosts.toList().firstOrNull() ?: pinned

        fun onOutcome(host: String, ok: Boolean) {
            if (ok) { hostErrorStreak[host]?.set(0); liveHosts.add(host) }
            else {
                val s = hostErrorStreak.computeIfAbsent(host) { AtomicInteger(0) }.incrementAndGet()
                if (s >= laneDeathThreshold && liveHosts.size > 1) liveHosts.remove(host)
            }
        }

        withContext(Dispatchers.Default) {
            coroutineScope {
                runnerHosts.map { pinnedHost ->
                    async {
                        while (queue.shouldKeepWorking()) {
                            val chunk = queue.poll() ?: continue
                            val host = pickLive(pinnedHost)
                            connBudget.acquire()
                            gauge.onAcquire()
                            try {
                                // A dead host fails every attempt once it has served its quota.
                                val isDeadNow = host == deadHost &&
                                    servedByDead.get() >= killAfterChunks
                                if (host == deadHost && !isDeadNow) servedByDead.incrementAndGet()
                                if (isDeadNow) {
                                    onOutcome(host, false)
                                    // Real error: consume the chunk's error budget; it requeues until
                                    // exhausted. Survivors will pick it up (they pick a live host).
                                    queue.recordErrorOrFail(chunk, AdaptiveEngine.MAX_ERROR_ATTEMPTS)
                                } else {
                                    // Success: copy the full range (idempotent absolute-offset write).
                                    val start = chunk.start.toInt()
                                    val len = chunk.length.toInt()
                                    System.arraycopy(source, start, output, start, len)
                                    onOutcome(host, true)
                                    if (queue.markDone(chunk)) confirmed.addAndGet(chunk.length)
                                }
                            } finally {
                                gauge.onRelease(); connBudget.release()
                            }
                        }
                    }
                }.awaitAll()
            }
        }
        Triple(output, confirmed.get(), gauge)
    }

    @Test
    fun `host-pinned runners cover the file exactly with no gaps or overlaps`() {
        // No dead host: pure host-pinned coverage. Two mirrors, pinned runners, exact reconstruction.
        val size = 200_000
        val mirrors = listOf("https://hostA/x", "https://hostB/x")
        val (output, confirmed, gauge) =
            simulateFailover(size, mirrors, budget = 24, deadHost = null, killAfterChunks = 0, seed = 1)
        val expected = ByteArray(size) { i -> ((i * 31 + 7) and 0xFF).toByte() }
        assertEquals(size, output.size)
        for (i in 0 until size) {
            if (output[i] != expected[i]) {
                throw AssertionError("byte mismatch at $i under host pinning: ${output[i]} != ${expected[i]}")
            }
        }
        assertEquals(size.toLong(), confirmed, "confirmedBytes must equal expectedSize")
        assertEquals(0, gauge.violations.get(), "permits never exceeded the cap")
        assertEquals(0, gauge.current(), "no permit leak")
    }

    @Test
    fun `a mirror dying mid-download - survivors finish the file with exact bytes and no leak`() {
        // hostA dies after serving a few chunks; hostB (and re-targeted runners) must finish the file.
        val size = 300_000
        val mirrors = listOf("https://hostA/x", "https://hostB/x")
        val (output, confirmed, gauge) = simulateFailover(
            size, mirrors, budget = 24, deadHost = "https://hostA/x", killAfterChunks = 3, seed = 7
        )
        val expected = ByteArray(size) { i -> ((i * 31 + 7) and 0xFF).toByte() }
        // The file still completed despite the dead mirror.
        assertTrue(output.indices.all { output[it] == expected[it] }, "survivors must complete every byte")
        assertEquals(size.toLong(), confirmed, "exact byte total after failover")
        assertEquals(0, gauge.violations.get(), "cap held across failover")
        assertEquals(0, gauge.current(), "no permit leaked across failover")
    }

    @Test
    fun `dying mirror with a third surviving datacenter still completes`() {
        val size = 250_000
        val mirrors = listOf("https://a/x", "https://b/x", "https://c/x")
        val (output, confirmed, gauge) = simulateFailover(
            size, mirrors, budget = 24, deadHost = "https://b/x", killAfterChunks = 2, seed = 11
        )
        val expected = ByteArray(size) { i -> ((i * 31 + 7) and 0xFF).toByte() }
        assertTrue(output.indices.all { output[it] == expected[it] })
        assertEquals(size.toLong(), confirmed)
        assertEquals(0, gauge.violations.get())
        assertEquals(0, gauge.current())
    }
}
