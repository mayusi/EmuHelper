package io.github.mayusi.emuhelper.data.source

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * INCREMENTAL MD5 (v0.9) — the in-order-gated accumulator ([IncrementalMd5Accumulator]) +
 * the strategy decision ([decideMd5Strategy]).
 *
 * This optimisation sits ON the delete-the-file gate, so the contract these tests PROVE is absolute:
 *   - an IN-ORDER sequence of chunk completions yields EXACTLY the same digest as a full-file MD5,
 *   - an OUT-OF-ORDER / gapped sequence is handled correctly: while a gap exists the digest is NOT
 *     claimed complete (digestIfComplete() == null -> the caller FALLS BACK to the full pass), and
 *     once the gap fills the final digest STILL matches the full-file MD5 (no wrong digest, ever),
 *   - a missing/incomplete chunk NEVER yields a digest (so a partial download can't be claimed valid),
 *   - a read error latches failure -> full-pass fallback (never a bogus digest),
 *   - a resumed download uses the FULL pass ([decideMd5Strategy] returns FULL_ONLY).
 */
class IncrementalMd5Test {

    /** Reference full-file MD5 (lowercase hex) over [data] — the source of truth to match. */
    private fun fullMd5(data: ByteArray): String {
        val d = MessageDigest.getInstance("MD5").digest(data)
        val sb = StringBuilder(d.size * 2)
        for (b in d) {
            val v = b.toInt() and 0xFF
            sb.append("0123456789abcdef"[v shr 4]).append("0123456789abcdef"[v and 0x0F])
        }
        return sb.toString()
    }

    /** Deterministic pseudo-random file body of [size] bytes. */
    private fun makeData(size: Int, seed: Long = 42L): ByteArray {
        val out = ByteArray(size)
        val rnd = java.util.Random(seed)
        rnd.nextBytes(out)
        return out
    }

    /** A reader over an in-memory buffer (no file/network) — exactly what the accumulator needs. */
    private fun readerOf(data: ByteArray): (Long, Int) -> ByteArray =
        { start, len -> data.copyOfRange(start.toInt(), start.toInt() + len) }

    @Test
    fun `in-order completions yield the same digest as a full-file MD5`() {
        val size = 8 * 1024 * 1024 * 3 + 12345  // 3 full 8 MB chunks + a remainder chunk
        val data = makeData(size)
        val chunks = partitionChunks(size.toLong())
        assertTrue(chunks.size >= 4, "need several chunks to exercise the in-order drain")

        val acc = IncrementalMd5Accumulator(size.toLong(), chunks, readerOf(data))
        // Complete chunks strictly in index order.
        for (c in chunks) {
            // While not all chunks are folded, the digest must NOT be claimed complete.
            if (c.index < chunks.size - 1) assertNull(acc.digestIfComplete())
            acc.onChunkDone(c.index)
        }
        val incremental = acc.digestIfComplete()
        assertNotNull(incremental, "after every chunk folded, the digest must be available")
        assertEquals(fullMd5(data), incremental, "incremental digest must equal the full-file MD5")
    }

    @Test
    fun `out-of-order completions fall back while gapped, then still match once contiguous`() {
        val size = 8 * 1024 * 1024 * 4 + 7  // 4 full chunks + a tiny remainder
        val data = makeData(size, seed = 7L)
        val chunks = partitionChunks(size.toLong())
        val acc = IncrementalMd5Accumulator(size.toLong(), chunks, readerOf(data))

        // Build an explicit order: every chunk EXCEPT the frontier (0) first, in a scrambled order,
        // then 0 last — guarantees a real, sustained gap at the frontier the whole time.
        val nonZeroScrambled = chunks.map { it.index }.filter { it != 0 }
            .shuffled(kotlin.random.Random(99))
        val gapped = nonZeroScrambled + listOf(0)
        var firedZero = false
        for (idx in gapped) {
            // ASSERT THE FALLBACK FIRES: until chunk 0 is reported the frontier can't advance, so the
            // accumulator must NEVER claim completeness (digestIfComplete == null) even though all the
            // OTHER chunks are done. This is the gapped-sequence fallback the caller relies on.
            assertNull(acc.digestIfComplete(),
                "with the frontier chunk still missing, the digest must NOT be claimed complete")
            acc.onChunkDone(idx)
            if (idx == 0) firedZero = true
        }
        assertTrue(firedZero)
        // Once the gap is filled, the drain folds the whole file in order and the digest matches.
        val incremental = acc.digestIfComplete()
        assertNotNull(incremental, "after the gap fills, the contiguous drain completes the digest")
        assertEquals(fullMd5(data), incremental,
            "even via an out-of-order arrival pattern, the final digest must equal the full-file MD5")
    }

    @Test
    fun `partial coverage never yields a digest (a missing chunk can't be claimed valid)`() {
        val size = 8 * 1024 * 1024 * 3
        val data = makeData(size)
        val chunks = partitionChunks(size.toLong())
        val acc = IncrementalMd5Accumulator(size.toLong(), chunks, readerOf(data))
        // Report all but the LAST chunk.
        for (c in chunks.dropLast(1)) acc.onChunkDone(c.index)
        assertNull(acc.digestIfComplete(), "a download missing one chunk must never produce a digest")
        assertTrue(acc.markBytes() < size.toLong(), "the hash high-water mark stops at the gap")
    }

    @Test
    fun `duplicate completion reports are idempotent (no double-feed under racing)`() {
        val size = 8 * 1024 * 1024 * 2 + 100
        val data = makeData(size, seed = 5L)
        val chunks = partitionChunks(size.toLong())
        val acc = IncrementalMd5Accumulator(size.toLong(), chunks, readerOf(data))
        for (c in chunks) {
            acc.onChunkDone(c.index)
            acc.onChunkDone(c.index)  // a duplicate (e.g. a late racer) must be ignored, not re-fed
        }
        assertEquals(fullMd5(data), acc.digestIfComplete(),
            "duplicate reports must not double-feed the digest")
    }

    @Test
    fun `a read error latches failure and forces the full-pass fallback`() {
        val size = 8 * 1024 * 1024 * 2
        val chunks = partitionChunks(size.toLong())
        // Reader that throws on the first read — simulates an I/O error reading the .part.
        val acc = IncrementalMd5Accumulator(size.toLong(), chunks, { _, _ ->
            throw java.io.IOException("simulated read error")
        })
        acc.onChunkDone(0)
        assertTrue(acc.isFailed(), "a read error must latch the failed flag")
        assertNull(acc.digestIfComplete(), "a poisoned accumulator must never yield a digest")
    }

    @Test
    fun `a short read is treated as failure (never a wrong digest)`() {
        val size = 8 * 1024 * 1024 * 2
        val chunks = partitionChunks(size.toLong())
        // Reader that returns FEWER bytes than requested for the first chunk.
        val acc = IncrementalMd5Accumulator(size.toLong(), chunks, { _, len ->
            ByteArray(len - 1)  // short by one byte
        })
        acc.onChunkDone(0)
        assertTrue(acc.isFailed(), "a short read must latch failure")
        assertNull(acc.digestIfComplete())
    }

    @Test
    fun `single-chunk file hashes correctly in order`() {
        val size = 1024 * 1024  // 1 MB -> one chunk
        val data = makeData(size, seed = 3L)
        val chunks = partitionChunks(size.toLong())
        assertEquals(1, chunks.size)
        val acc = IncrementalMd5Accumulator(size.toLong(), chunks, readerOf(data))
        assertNull(acc.digestIfComplete(), "nothing folded yet")
        acc.onChunkDone(0)
        assertEquals(fullMd5(data), acc.digestIfComplete())
    }

    // ---- decideMd5Strategy --------------------------------------------------------------------

    @Test
    fun `decideMd5Strategy uses incremental only on a clean adaptive download with a checksum`() {
        assertEquals(
            Md5Strategy.INCREMENTAL_THEN_FULL,
            decideMd5Strategy(adaptive = true, resumed = false, expectedMd5Blank = false),
            "clean adaptive + known checksum -> incremental (then full fallback)"
        )
    }

    @Test
    fun `decideMd5Strategy falls back to full pass on resume`() {
        assertEquals(
            Md5Strategy.FULL_ONLY,
            decideMd5Strategy(adaptive = true, resumed = true, expectedMd5Blank = false),
            "a resumed download can't reconstruct the partial digest -> full pass"
        )
    }

    @Test
    fun `decideMd5Strategy falls back to full pass on the static path and when no checksum is known`() {
        assertEquals(
            Md5Strategy.FULL_ONLY,
            decideMd5Strategy(adaptive = false, resumed = false, expectedMd5Blank = false),
            "the static path doesn't drive the accumulator -> full pass"
        )
        assertEquals(
            Md5Strategy.FULL_ONLY,
            decideMd5Strategy(adaptive = true, resumed = false, expectedMd5Blank = true),
            "no known checksum -> never build the accumulator (full path is a no-op verify anyway)"
        )
    }
}
