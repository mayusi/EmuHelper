package io.github.mayusi.emuhelper.data.source

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CONNECTION PRE-WARMING (v0.9) — the pure pre-warm decision ([decidePrewarm]).
 *
 * Pre-warming fires tiny best-effort bytes=0-0 GETs at an item's resolved datacenter hosts so the h2
 * sockets are warm before the first chunk. It must:
 *   - warm at most 3 hosts (one per datacenter) — never a flood of connections,
 *   - SKIP on a metered network when wifiOnly is on (respect the same gate the download honours),
 *   - acquire NO download permit (it is pre-Semaphore and the GETs are trivial), so it can never push
 *     the live connection count over the 24-cap. (This is a structural property of the production call
 *     path — the decision returns only a host LIST, never touches a Semaphore — asserted here by the
 *     decision never returning more than the small cap.)
 */
class PrewarmDecisionTest {

    private val hosts3 = listOf(
        "https://ia600000.us.archive.org/x",
        "https://ia800000.us.archive.org/x",
        "https://dn720000.ca.archive.org/x"
    )

    @Test
    fun `warms up to 3 hosts on an unmetered network`() {
        val d = decidePrewarm(hosts3, wifiOnly = false, isMetered = false)
        assertTrue(d.warm, "an unmetered network with hosts must warm")
        assertEquals(hosts3, d.hosts, "all three datacenters (<=3) are warmed")
        assertTrue(d.hosts.size <= 3, "pre-warming never exceeds the 3-host cap (one per datacenter)")
    }

    @Test
    fun `caps at 3 hosts even when more mirrors are offered`() {
        val many = hosts3 + listOf("https://ia900000.us.archive.org/x", "https://ia910000.us.archive.org/x")
        val d = decidePrewarm(many, wifiOnly = false, isMetered = false)
        assertTrue(d.warm)
        assertEquals(3, d.hosts.size, "never warm more than 3 hosts no matter how many mirrors exist")
    }

    @Test
    fun `skips on metered when wifiOnly is on`() {
        val d = decidePrewarm(hosts3, wifiOnly = true, isMetered = true)
        assertFalse(d.warm, "wifiOnly + metered must NOT warm (respect the Wi-Fi-only gate)")
        assertTrue(d.hosts.isEmpty(), "no hosts to warm when the gate blocks it")
    }

    @Test
    fun `warms on metered when wifiOnly is OFF (user allowed metered downloads)`() {
        val d = decidePrewarm(hosts3, wifiOnly = false, isMetered = true)
        assertTrue(d.warm, "if the user permits metered downloads, warming on metered is fine")
        assertTrue(d.hosts.size <= 3)
    }

    @Test
    fun `warms on unmetered even when wifiOnly is on (Wi-Fi is exactly what wifiOnly allows)`() {
        val d = decidePrewarm(hosts3, wifiOnly = true, isMetered = false)
        assertTrue(d.warm, "wifiOnly + unmetered Wi-Fi is the allowed case -> warm")
    }

    @Test
    fun `no hosts means no warming`() {
        assertFalse(decidePrewarm(emptyList(), wifiOnly = false, isMetered = false).warm)
        assertFalse(decidePrewarm(listOf("", "  "), wifiOnly = false, isMetered = false).warm,
            "blank-only hosts -> nothing to warm")
    }

    @Test
    fun `duplicate hosts are deduped before the cap`() {
        val dupes = listOf("https://a/x", "https://a/x", "https://b/x", "https://b/x")
        val d = decidePrewarm(dupes, wifiOnly = false, isMetered = false)
        assertEquals(listOf("https://a/x", "https://b/x"), d.hosts, "deduped to the distinct datacenters")
    }

    @Test
    fun `the decision returns only a host list - it acquires no permit (cannot breach the 24-cap)`() {
        // Structural proof: the decision's output is a bounded host LIST, never a connection. The
        // production warm-up fires these as permit-free GETs, so the count it could ever open is
        // bounded by this small list — far under the 24-cap, and it holds NO download permits.
        val d = decidePrewarm(hosts3, wifiOnly = false, isMetered = false)
        assertTrue(d.hosts.size <= 3 && d.hosts.size <= AdaptiveEngine.MAX_ADAPTIVE_WORKERS,
            "pre-warm host count is tiny and far under the global connection cap")
    }
}
