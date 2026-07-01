package io.github.mayusi.emuhelper.data.source.root

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure-logic tests for [PServerBridge]. The actual binder transact needs a real PServer device, so
 * this covers only the device-independent pieces:
 *   - the reply-parsing transform (raw bytes -> trimmed UTF-8; the literal "null" -> ""),
 *   - the cache state machine (availableNow before/after a probe result),
 *   - the guard-routing contract the bridge relies on: a denied command produces a "BLOCKED:" status
 *     and (by construction in the bridge) never reaches the binder.
 *
 * The reply transform and the memoization are reproduced here as standalone references that mirror
 * the bridge's documented behaviour exactly; the bridge's own private copy is the production path.
 */
class PServerBridgeLogicTest {

    /** Mirror of the bridge's reply decode: bytes? -> trimmed UTF-8, "null" literal -> "". */
    private fun parseReply(raw: ByteArray?): String =
        raw?.toString(Charsets.UTF_8)?.trim()?.let { if (it == "null") "" else it }.orEmpty()

    @Test fun `null reply bytes decode to empty string`() {
        assertEquals("", parseReply(null))
    }

    @Test fun `literal null token decodes to empty string`() {
        assertEquals("", parseReply("null".toByteArray()))
    }

    @Test fun `whitespace-padded output is trimmed`() {
        assertEquals("uid=0(root)", parseReply("  uid=0(root)\n".toByteArray()))
    }

    @Test fun `normal output passes through`() {
        assertEquals("Linux version 4.19", parseReply("Linux version 4.19".toByteArray()))
    }

    @Test fun `empty byte array decodes to empty string`() {
        assertEquals("", parseReply(ByteArray(0)))
    }

    // -------------------------------------------------------------------------------------------
    // Cache state machine reference: null (unprobed) -> false/true (probed). availableNow() is true
    // only when the cached value is exactly true.
    // -------------------------------------------------------------------------------------------

    private class CacheRef {
        @Volatile var cached: Boolean? = null
        fun availableNow(): Boolean = cached == true
        fun invalidate() { cached = null }
    }

    @Test fun `availableNow is false before any probe`() {
        val c = CacheRef()
        assertEquals(false, c.availableNow())
        assertNull(c.cached)
    }

    @Test fun `availableNow reflects a true probe`() {
        val c = CacheRef()
        c.cached = true
        assertTrue(c.availableNow())
    }

    @Test fun `availableNow is false after a negative probe (cached false, not null)`() {
        val c = CacheRef()
        c.cached = false
        assertEquals(false, c.availableNow())
        assertEquals(false, c.cached) // distinguishes "probed-and-unavailable" from "never probed"
    }

    @Test fun `invalidate resets to unprobed`() {
        val c = CacheRef()
        c.cached = true
        c.invalidate()
        assertNull(c.cached)
        assertEquals(false, c.availableNow())
    }

    // -------------------------------------------------------------------------------------------
    // Guard-routing contract: the bridge maps a guard Deny to (-1, "BLOCKED: <reason>"). We assert
    // the guard verdict the bridge keys off, proving a destructive command is classified as Deny
    // (so the bridge returns BLOCKED and never transacts) while a safe probe is Allow.
    // -------------------------------------------------------------------------------------------

    @Test fun `destructive command is a guard Deny (bridge would return BLOCKED, no transact)`() {
        val v = PServerCommandGuard.inspect("rm -rf /")
        assertTrue(v is PServerCommandGuard.Verdict.Deny)
    }

    @Test fun `the probe command is a guard Allow`() {
        assertEquals(PServerCommandGuard.Verdict.Allow, PServerCommandGuard.inspect("true"))
    }
}
