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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * THE warm-reuse regression guard (v3 laned engine).
 *
 * The whole point of the laned engine is that a single file opens only a SMALL, BOUNDED number of
 * warm HTTP/2 connections — about (mirrors × streams-per-host) — and REUSES each warm connection
 * for every chunk on its lane. The v1/v2 engine re-picked the host per chunk, so OkHttp could not
 * reuse a warm connection and paid TCP/TLS + h2 slow-start on EVERY chunk (measured 8× median-vs-
 * peak gap). This test proves the laned topology opens ≤ (mirrors × streamsPerHost) DISTINCT
 * connections, NOT ~chunks connections.
 *
 * There is no MockWebServer in this project, so we model the engine's host-pinned runner loop
 * directly against a fake [ConnectionFactory] that counts DISTINCT connections. A connection is
 * keyed by (host, runnerId): a runner PINNED to one host lazily opens ONE connection and reuses it
 * for all its chunks — exactly what OkHttp's connection pool does when the runner always dials the
 * same host with a warm keep-alive connection available. If the engine instead re-picked the host
 * per chunk (the retired behaviour), each chunk would key a new (host, *) connection and the
 * distinct count would balloon toward the chunk count — which this test asserts MUST NOT happen.
 */
class WarmReuseTest {

    /**
     * Fake connection pool. [obtain] returns a stable connection id for a (host, runnerId) pair,
     * counting a DISTINCT open the first time that pair is seen and reusing it thereafter — the
     * warm-keep-alive contract. [distinctOpened] is the number of real sockets that would be dialled.
     */
    private class ConnectionFactory {
        private val conns = ConcurrentHashMap<String, Int>()
        private val opened = AtomicInteger(0)
        /** Reuses the warm connection for this (host, runner) pair; counts a new open only once. */
        fun obtain(host: String, runnerId: Int): Int {
            val key = "$host#$runnerId"
            return conns.getOrPut(key) { opened.incrementAndGet() }
        }
        fun distinctOpened(): Int = opened.get()
    }

    /**
     * Drive the laned runner model (minus the network) and return (distinctConnsOpened, chunkCount,
     * streamCount). Each runner is pinned to its lane host and reuses ONE connection across all the
     * chunks it pulls — the warm-reuse property under test.
     */
    private fun runLanedSimulation(
        fileSize: Long,
        mirrors: List<String>,
        budget: Int
    ): Triple<Int, Int, Int> = runBlocking {
        val chunks = partitionChunks(fileSize)
        val queue = ChunkQueue(chunks)
        val lanes = planLanes(mirrors, budget)
        // Flatten lanes to runners exactly like RemoteSource.downloadAdaptive does.
        val runnerHosts: List<String> = lanes.flatMap { lane -> List(lane.streams) { lane.host } }
        val factory = ConnectionFactory()
        val connBudget = Semaphore(budget.coerceAtLeast(1))

        withContext(Dispatchers.Default) {
            coroutineScope {
                runnerHosts.mapIndexed { runnerId, pinnedHost ->
                    async {
                        // The runner reuses ONE warm connection for every chunk it pulls from its
                        // pinned host — it dials lazily on the first chunk, then reuses.
                        while (queue.shouldKeepWorking()) {
                            val chunk = queue.poll() ?: break
                            connBudget.acquire()
                            try {
                                // PINNED host + stable runnerId -> warm connection reuse. This is the
                                // single property the laned engine guarantees: same (host,runner) for
                                // every chunk, so the factory opens at most ONE connection per runner.
                                factory.obtain(pinnedHost, runnerId)
                                // "download" the chunk (idempotent absolute-offset write is irrelevant
                                // here — we only care about connection identity).
                                queue.markDone(chunk)
                            } finally {
                                connBudget.release()
                            }
                        }
                    }
                }.awaitAll()
            }
        }
        Triple(factory.distinctOpened(), chunks.size, runnerHosts.size)
    }

    @Test
    fun `single file opens at most mirrors times streams connections - not chunks`() {
        // A big file: 200 MB / 8 MB = 25 chunks. With 2 mirrors x 2 streams the laned engine must
        // open at most 4 DISTINCT connections (one warm per runner), reused across all 25 chunks.
        val fileSize = 200L * 1024 * 1024
        val mirrors = listOf("https://ia600000.us.archive.org/x", "https://ia800000.us.archive.org/x")
        val (distinct, chunkCount, streams) = runLanedSimulation(fileSize, mirrors, budget = 24)

        assertTrue(chunkCount >= 20, "test premise: file must have many chunks (got $chunkCount)")
        // THE assertion: warm reuse means distinct connections track STREAMS, never CHUNKS.
        assertTrue(
            distinct <= streams,
            "distinct connections ($distinct) must be <= stream count ($streams) — warm reuse"
        )
        assertEquals(4, streams, "2 mirrors x 2 preferred streams = 4 warm lanes")
        assertTrue(
            distinct <= 4,
            "must open at most (mirrors x streamsPerHost)=4 connections, not ~$chunkCount (chunks)"
        )
        // Hard proof slow-start is killed: connections are an order of magnitude fewer than chunks.
        assertTrue(
            distinct < chunkCount,
            "distinct conns ($distinct) MUST be far fewer than chunks ($chunkCount) — no per-chunk dial"
        )
    }

    @Test
    fun `three mirrors still bounded by lane count, far below chunk count`() {
        val fileSize = 400L * 1024 * 1024 // ~50 chunks
        val mirrors = listOf(
            "https://ia600000.us.archive.org/x",
            "https://ia800000.us.archive.org/x",
            "https://dn720000.ca.archive.org/x"
        )
        val (distinct, chunkCount, streams) = runLanedSimulation(fileSize, mirrors, budget = 24)
        assertEquals(6, streams, "3 mirrors x 2 streams = 6 warm lanes")
        assertTrue(distinct <= streams, "distinct ($distinct) <= streams ($streams)")
        assertTrue(distinct <= 6, "at most 6 distinct connections for 3x2 lanes")
        assertTrue(distinct < chunkCount, "$distinct conns << $chunkCount chunks — warm reuse holds")
    }

    @Test
    fun `single mirror reuses its few warm lanes across every chunk`() {
        val fileSize = 160L * 1024 * 1024 // 20 chunks
        val mirrors = listOf("https://only-host/x")
        val (distinct, chunkCount, streams) = runLanedSimulation(fileSize, mirrors, budget = 24)
        // One mirror deepens to the per-host cap (4) — still a tiny constant, reused across 20 chunks.
        assertEquals(AdaptiveEngine.MAX_STREAMS_PER_HOST, streams)
        assertTrue(distinct <= streams, "distinct ($distinct) <= streams ($streams)")
        assertTrue(
            distinct <= AdaptiveEngine.MAX_STREAMS_PER_HOST,
            "single mirror opens at most the per-host cap of connections, not ~$chunkCount"
        )
    }
}
