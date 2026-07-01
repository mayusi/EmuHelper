package io.github.mayusi.emuhelper.data.safety

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * THREAT HASH STORE — empty bundled list is NOT_APPLICABLE (never crashes), a seeded list matches on
 * both full-hash and 8-char prefix, case-insensitively.
 */
class ThreatHashStoreTest {

    @Test fun `empty store reports NOT_APPLICABLE`() {
        val store = ThreatHashStore.empty()
        assertEquals(ThreatHashStore.Match.NOT_APPLICABLE, store.checkHash("a".repeat(64)))
    }

    @Test fun `bundled resource loads without crashing (empty list is NOT_APPLICABLE)`() {
        // The shipped resource is an empty-but-valid list; must load and answer NOT_APPLICABLE.
        val store = ThreatHashStore.loadBundled()
        assertEquals(ThreatHashStore.Match.NOT_APPLICABLE, store.checkHash("deadbeef".repeat(8)))
    }

    @Test fun `seeded full-hash matches (case-insensitive)`() {
        val bad = "ABCDEF0123456789".repeat(4).lowercase() // 64 hex chars
        val store = ThreatHashStore.fromList(
            ThreatHashStore.ThreatList(fullHashes = listOf(bad.uppercase())),
        )
        assertEquals(ThreatHashStore.Match.HIT, store.checkHash(bad))
        assertEquals(ThreatHashStore.Match.HIT, store.checkHash(bad.uppercase()))
    }

    @Test fun `clean hash against a non-empty store is CLEAN`() {
        val store = ThreatHashStore.fromList(
            ThreatHashStore.ThreatList(fullHashes = listOf("00".repeat(32))),
        )
        assertEquals(ThreatHashStore.Match.CLEAN, store.checkHash("11".repeat(32)))
    }

    @Test fun `8-char prefix matches`() {
        val store = ThreatHashStore.fromList(
            ThreatHashStore.ThreatList(prefixes = listOf("deadbeef")),
        )
        val h = "deadbeef" + "0".repeat(56)
        assertEquals(ThreatHashStore.Match.HIT, store.checkHash(h))
        assertEquals(ThreatHashStore.Match.CLEAN, store.checkHash("cafef00d" + "0".repeat(56)))
    }

    @Test fun `null or blank hash is NOT_APPLICABLE`() {
        val store = ThreatHashStore.fromList(ThreatHashStore.ThreatList(fullHashes = listOf("aa".repeat(32))))
        assertEquals(ThreatHashStore.Match.NOT_APPLICABLE, store.checkHash(null))
        assertEquals(ThreatHashStore.Match.NOT_APPLICABLE, store.checkHash("   "))
    }
}
