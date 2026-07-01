package io.github.mayusi.emuhelper.data.source

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [HostScoreboard] — the live EWMA scoreboard that drives adaptive host
 * selection (weighted-by-speed with a load penalty), plus per-host cooldown after a stall.
 *
 * Time and randomness are injected so every assertion is deterministic: [fakeNow] controls the
 * clock for cooldown windows, and a controllable [rng] makes the weighted pick reproducible.
 */
class HostScoreboardTest {

    private var clock = 0L
    private val fakeNow: () -> Long = { clock }

    // ---- EWMA convergence ---------------------------------------------------

    @Test
    fun `ewma moves toward the observed rate`() {
        val board = HostScoreboard(listOf("h1"), seedEwma = mapOf("h1" to 100.0), nowProvider = fakeNow)
        val before = board.ewmaOf("h1")
        assertEquals(100.0, before, 1e-9)
        // Observe a much faster rate; EWMA should rise toward it (ewma*0.7 + observed*0.3).
        board.pickHost()
        board.recordSuccess("h1", observedBytesPerMs = 200.0)
        val after = board.ewmaOf("h1")
        assertEquals(100.0 * 0.7 + 200.0 * 0.3, after, 1e-9)
        assertTrue(after in before..200.0, "EWMA must move from $before toward 200, got $after")
    }

    @Test
    fun `ewma converges over repeated observations`() {
        val board = HostScoreboard(listOf("h1"), seedEwma = mapOf("h1" to 10.0), nowProvider = fakeNow)
        repeat(50) {
            board.pickHost()
            board.recordSuccess("h1", observedBytesPerMs = 500.0)
        }
        // After many samples the EWMA should be very close to the steady observed rate.
        assertTrue(board.ewmaOf("h1") > 490.0, "EWMA should converge near 500, got ${board.ewmaOf("h1")}")
    }

    @Test
    fun `non-positive observed rate does not poison the ewma`() {
        val board = HostScoreboard(listOf("h1"), seedEwma = mapOf("h1" to 123.0), nowProvider = fakeNow)
        board.pickHost()
        board.recordSuccess("h1", observedBytesPerMs = 0.0)
        assertEquals(123.0, board.ewmaOf("h1"), 1e-9)
        // The in-flight slot is still released even though the sample was ignored.
        assertEquals(0, board.inFlightOf("h1"))
    }

    // ---- Cooldown -----------------------------------------------------------

    @Test
    fun `a stalled host is skipped during cooldown then re-eligible after`() {
        val board = HostScoreboard(listOf("slow", "fast"), nowProvider = fakeNow)
        clock = 1_000_000L
        // Stall "slow": it goes on cooldown for HOST_COOLDOWN_MS.
        board.pickHost() // bump some load; pick is irrelevant here
        board.recordStall("slow")
        assertTrue(board.isCooledNow("slow"))
        assertFalse(board.isCooledNow("fast"))

        // While cooled, every pick must avoid "slow" and return "fast".
        repeat(20) {
            assertEquals("fast", board.pickHost())
            board.releaseHost("fast")
        }

        // Advance time just past the cooldown window -> "slow" becomes eligible again.
        clock += AdaptiveEngine.HOST_COOLDOWN_MS
        assertFalse(board.isCooledNow("slow"))
    }

    @Test
    fun `a SOFT stall does NOT cool the host but a hard stall and an error do`() {
        // FIX C: cooling a host on a SOFT stall is what funnels every worker onto one mirror and
        // feeds the throttle loop. A soft stall must only DEMOTE the EWMA (no cooldown); only a
        // HARD stall / real error cools.
        val board = HostScoreboard(
            hosts = listOf("h"),
            seedEwma = mapOf("h" to 1000.0),
            nowProvider = fakeNow
        )
        clock = 1_000_000L
        board.pickHost()
        val before = board.ewmaOf("h")
        // Soft stall: NOT cooled, EWMA demoted by SOFT_DEMOTE_FACTOR.
        board.recordSoftStall("h")
        assertFalse(board.isCooledNow("h"), "a soft stall must NOT put the host on cooldown")
        val afterSoft = board.ewmaOf("h")
        assertTrue(afterSoft < before, "soft stall should demote the EWMA: $before -> $afterSoft")
        assertEquals(before * AdaptiveEngine.SOFT_DEMOTE_FACTOR, afterSoft, 1e-6)
        assertEquals(0, board.inFlightOf("h"), "soft stall still releases the in-flight slot")

        // Hard stall: DOES cool.
        board.pickHost()
        board.recordStall("h")
        assertTrue(board.isCooledNow("h"), "a hard stall must cool the host")
    }

    @Test
    fun `repeated soft stalls keep the host live and pickable`() {
        // FIX C: even after many soft stalls the host must stay eligible (never cooled), so workers
        // can still use it instead of all funneling onto one mirror.
        val board = HostScoreboard(
            hosts = listOf("a", "b"),
            seedEwma = mapOf("a" to 500.0, "b" to 500.0),
            nowProvider = fakeNow
        )
        clock = 50_000L
        repeat(10) {
            board.pickHost()
            board.recordSoftStall("a")
            board.pickHost()
            board.recordSoftStall("b")
        }
        assertFalse(board.isCooledNow("a"))
        assertFalse(board.isCooledNow("b"))
        // A pick must still return one of them (never wedges, never all-cooled).
        assertNotNull(board.pickHost())
    }

    @Test
    fun `when all hosts are cooled it falls back to least-recently-stalled`() {
        val board = HostScoreboard(listOf("a", "b"), nowProvider = fakeNow)
        clock = 5_000L
        board.recordStall("a")        // a stalled at t=5000
        clock = 6_000L
        board.recordStall("b")        // b stalled at t=6000 (more recent)
        // Both are within cooldown -> all cooled. Fallback = least-recently-stalled = "a".
        clock = 6_500L
        val pick = board.pickHost()
        assertEquals("a", pick, "all-cooled fallback must pick the least-recently-stalled host")
    }

    @Test
    fun `never returns null when at least one host exists`() {
        val board = HostScoreboard(listOf("only"), nowProvider = fakeNow)
        // Even after stalling the sole host, a pick must still return it (progress can't wedge).
        board.recordStall("only")
        clock = 1L
        assertEquals("only", board.pickHost())
    }

    @Test
    fun `empty host set returns null`() {
        val board = HostScoreboard(emptyList(), nowProvider = fakeNow)
        assertNull(board.pickHost())
        assertEquals(0.0, board.bestLiveEwma(), 1e-9)
    }

    // ---- Load penalty spreads picks -----------------------------------------

    @Test
    fun `weighted selection spreads picks across hosts, favouring the faster one`() {
        // EWMA-weighted choice with a seeded RNG: over many releases (load stays low so we test
        // the pure speed weighting) the fast host should take MORE picks than the slow host, but
        // the slow host must still receive SOME — work is spread, not piled 100% on the fastest
        // (Internet Archive throttles a single hammered node).
        val rnd = java.util.Random(12345)
        val board = HostScoreboard(
            hosts = listOf("fast", "slow"),
            seedEwma = mapOf("fast" to 400.0, "slow" to 100.0),
            nowProvider = fakeNow,
            rng = { bound -> rnd.nextDouble() * bound }
        )
        val counts = HashMap<String, Int>()
        repeat(2000) {
            val h = board.pickHost()!!
            counts[h] = (counts[h] ?: 0) + 1
            board.recordSuccess(h, board.ewmaOf(h)) // release; keep EWMA roughly stable
        }
        val fast = counts["fast"] ?: 0
        val slow = counts["slow"] ?: 0
        assertTrue(fast > slow, "faster host should win more picks: fast=$fast slow=$slow")
        assertTrue(slow > 0, "slower host must still get some picks (work is spread): slow=$slow")
        // Roughly proportional to the 4:1 speed ratio (loose bounds to avoid flakiness).
        assertTrue(fast > slow * 2, "fast should dominate ~4:1: fast=$fast slow=$slow")
    }

    @Test
    fun `in-flight load penalty reduces a busy host's effective weight`() {
        // Directly exercise the load penalty with a FIXED probe point at 60% of the total weight.
        // Iteration order is [fast, slow]; the cumulative walk returns "fast" while fast's weight
        // covers >=60% of the total, otherwise "slow".
        //   Unloaded: fast=300, slow=100, total=400 -> fast covers 75% -> 60% probe selects FAST.
        //   Fast loaded with 3 in-flight: fast=300/4=75, slow=100, total=175 -> fast covers 43%
        //     -> 60% probe now falls through to SLOW. Same probe, different outcome: the penalty.
        val board = HostScoreboard(
            hosts = listOf("fast", "slow"),
            seedEwma = mapOf("fast" to 300.0, "slow" to 100.0),
            nowProvider = fakeNow,
            rng = { bound -> bound * 0.6 }
        )
        // Unloaded, the 60% probe selects the fast host (fast covers 75% of the total weight).
        assertEquals("fast", board.pickHost(), "unloaded, the 60% probe should select the fast host")
        // Keep picking WITHOUT releasing. As fast accumulates in-flight load its effective weight
        // (300 / (1+inFlight)) shrinks; within a few picks the SAME 60% probe must fall through to
        // the slow host. Without the load penalty, the fast host would be chosen every time.
        val picks = (0 until 5).map { board.pickHost()!! }
        assertTrue(
            picks.contains("slow"),
            "load penalty must divert at least one pick to the slow host as the fast host saturates: $picks"
        )
        // The fast host should still have taken the lion's share of the in-flight load.
        assertTrue(board.inFlightOf("fast") >= board.inFlightOf("slow"),
            "fast should still carry most of the load")
    }

    @Test
    fun `picking increments in-flight and recording releases it`() {
        val board = HostScoreboard(listOf("h"), seedEwma = mapOf("h" to 50.0), nowProvider = fakeNow)
        assertEquals(0, board.inFlightOf("h"))
        val p1 = board.pickHost(); assertNotNull(p1)
        assertEquals(1, board.inFlightOf("h"))
        val p2 = board.pickHost()
        assertEquals(2, board.inFlightOf("h"))
        board.recordSuccess("h", 60.0)
        assertEquals(1, board.inFlightOf("h"))
        board.recordStall("h")
        assertEquals(0, board.inFlightOf("h"))
    }

    // ---- bestLiveEwma -------------------------------------------------------

    @Test
    fun `bestLiveEwma reports the fastest non-cooled host`() {
        val board = HostScoreboard(
            hosts = listOf("a", "b", "c"),
            seedEwma = mapOf("a" to 100.0, "b" to 300.0, "c" to 200.0),
            nowProvider = fakeNow
        )
        assertEquals(300.0, board.bestLiveEwma(), 1e-9)
        // Cool the best host -> bestLiveEwma should drop to the next fastest live one.
        clock = 10L
        board.recordStall("b")
        assertEquals(200.0, board.bestLiveEwma(), 1e-9)
        // Advance past cooldown -> "b" is live again and best once more.
        clock += AdaptiveEngine.HOST_COOLDOWN_MS
        assertEquals(300.0, board.bestLiveEwma(), 1e-9)
    }
}
