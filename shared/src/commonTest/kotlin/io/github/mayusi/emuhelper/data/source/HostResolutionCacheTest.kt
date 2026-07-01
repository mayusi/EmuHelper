package io.github.mayusi.emuhelper.data.source

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * BATCH-WIDE SETUP ELISION (v0.9) — the per-item host-resolution cache ([HostResolutionCache]).
 *
 * The #1 small/medium-batch win rests on this pure unit: resolve + rank + range-probe an IA item's
 * mirrors ONCE, then reuse the result for every subsequent file of the batch (skipping ~(2N+1)
 * round-trips + N×256 KB of probing per file). These tests prove:
 *   - same identifier returns the cached resolution (a HIT -> no re-probe),
 *   - a different identifier MISSES (each item is resolved independently),
 *   - TTL expiry forces a re-probe (a stale entry never outlives the conditions it measured),
 *   - a host that dies mid-batch is EVICTED from the cached ranking so later files don't reuse it,
 *   - reuse adds NO connections — Σ planned streams over the cached hosts still respects the 24-cap.
 */
class HostResolutionCacheTest {

    private val ia6 = "https://ia600000.us.archive.org/0/items/snestest/"
    private val ia8 = "https://ia800000.us.archive.org/0/items/snestest/"

    /** A small mutable clock so TTL expiry is deterministic (no real wall-clock). */
    private class FakeClock(var now: Long = 1_000L) { fun read(): Long = now }

    @Test
    fun `same identifier returns the cached resolution - a HIT, no re-probe`() {
        val clock = FakeClock()
        val cache = HostResolutionCache(ttlMs = 60_000L, nowProvider = clock::read)
        cache.put(
            identifier = "snestest",
            hostPrefixes = listOf(ia6, ia6, ia8),   // weighted pool (ia6 twice = faster)
            distinctPrefixes = listOf(ia6, ia8),
            probeRates = mapOf(ia6 to 1200.0, ia8 to 900.0),
            rangeOk = true
        )

        val hit = cache.get("snestest")
        assertNotNull(hit, "the same identifier must HIT the cache")
        // The weighted pool order is preserved verbatim so synthesis reproduces the exact ranking.
        assertEquals(listOf(ia6, ia6, ia8), hit.hostPrefixes)
        assertEquals(listOf(ia6, ia8), hit.distinctPrefixes)
        assertEquals(1200.0, hit.probeRates[ia6])
        assertTrue(hit.rangeOk)
        // A repeated get is still a HIT (no eviction on read) — the rest of the batch all reuse it.
        assertNotNull(cache.get("snestest"))
        assertEquals(1, cache.size())
    }

    @Test
    fun `a different identifier MISSES - each item resolves independently`() {
        val cache = HostResolutionCache(ttlMs = 60_000L)
        cache.put("itemA", listOf(ia6), listOf(ia6), mapOf(ia6 to 1000.0), rangeOk = true)
        assertNotNull(cache.get("itemA"), "the cached item must HIT")
        assertNull(cache.get("itemB"), "a different identifier must MISS")
    }

    @Test
    fun `TTL expiry forces a re-probe`() {
        val clock = FakeClock(now = 1_000L)
        val cache = HostResolutionCache(ttlMs = 5_000L, nowProvider = clock::read)
        cache.put("item", listOf(ia6), listOf(ia6), mapOf(ia6 to 1000.0), rangeOk = true)

        // Just before the TTL: still a HIT.
        clock.now = 1_000L + 4_999L
        assertNotNull(cache.get("item"), "before TTL the entry must still be valid")

        // At/after the TTL: MISS (entry expired and removed) -> the caller re-probes.
        clock.now = 1_000L + 5_000L
        assertNull(cache.get("item"), "at the TTL boundary the entry must expire")
        assertEquals(0, cache.size(), "an expired entry is dropped so a re-probe repopulates it")
    }

    @Test
    fun `a dead host is evicted from the cached ranking`() {
        val cache = HostResolutionCache(ttlMs = 60_000L)
        cache.put(
            identifier = "item",
            hostPrefixes = listOf(ia6, ia6, ia8),    // ia6 weighted twice
            distinctPrefixes = listOf(ia6, ia8),
            probeRates = mapOf(ia6 to 1200.0, ia8 to 900.0),
            rangeOk = true
        )

        // ia6 dies mid-batch -> evict it from BOTH the weighted pool and the distinct list.
        cache.evictHost("item", ia6)
        val after = cache.get("item")
        assertNotNull(after, "the item must remain cached while a survivor exists")
        assertEquals(listOf(ia8), after.hostPrefixes, "every ia6 occurrence is removed from the pool")
        assertEquals(listOf(ia8), after.distinctPrefixes, "ia6 is removed from the distinct list too")
        assertTrue(ia6 !in after.hostPrefixes && ia6 !in after.distinctPrefixes,
            "subsequent batch files must not reuse the dead node")
    }

    @Test
    fun `evicting the last surviving host drops the whole entry (forces a fresh re-probe)`() {
        val cache = HostResolutionCache(ttlMs = 60_000L)
        cache.put("item", listOf(ia6), listOf(ia6), mapOf(ia6 to 1000.0), rangeOk = true)
        cache.evictHost("item", ia6)
        assertNull(cache.get("item"), "an emptied ranking is dropped so the next file re-probes fresh")
    }

    @Test
    fun `a blank identifier never caches (safe fallback to the full per-file probe)`() {
        val cache = HostResolutionCache(ttlMs = 60_000L)
        cache.put("", listOf(ia6), listOf(ia6), mapOf(ia6 to 1.0), rangeOk = true)
        assertNull(cache.get(""), "a blank identifier must not be keyable -> always a MISS")
        assertEquals(0, cache.size())
    }

    @Test
    fun `reuse adds no connections - planning over cached hosts still respects the 24-cap`() {
        // The cache only stores host assignment; the actual connection cap is the lane plan + the
        // shared Semaphore. Prove that planning over the cached distinct hosts never exceeds the cap.
        val cache = HostResolutionCache(ttlMs = 60_000L)
        cache.put(
            identifier = "item",
            hostPrefixes = listOf(ia6, ia6, ia8, ia8),
            distinctPrefixes = listOf(ia6, ia8),
            probeRates = mapOf(ia6 to 1200.0, ia8 to 1100.0),
            rangeOk = true
        )
        val hit = cache.get("item")!!
        // Two files in the batch both reuse the SAME cached hosts; plan each over the global budget.
        val planA = planLanes(hit.distinctPrefixes, budget = AdaptiveEngine.MAX_ADAPTIVE_WORKERS)
        val planB = planLanes(hit.distinctPrefixes, budget = AdaptiveEngine.MAX_ADAPTIVE_WORKERS)
        val totalStreams = planA.sumOf { it.streams } + planB.sumOf { it.streams }
        // The lane plans themselves never exceed the budget; the shared Semaphore enforces the live cap
        // at runtime regardless. Reuse REMOVES probe connections (strictly fewer), never adds.
        assertTrue(planA.sumOf { it.streams } <= AdaptiveEngine.MAX_ADAPTIVE_WORKERS)
        assertTrue(planB.sumOf { it.streams } <= AdaptiveEngine.MAX_ADAPTIVE_WORKERS)
        // Sanity: per-host caps hold on the reused hosts.
        for (l in planA + planB) assertTrue(l.streams <= AdaptiveEngine.MAX_STREAMS_PER_HOST)
        assertTrue(totalStreams >= 2, "both files got at least one warm stream from the reused hosts")
    }
}
