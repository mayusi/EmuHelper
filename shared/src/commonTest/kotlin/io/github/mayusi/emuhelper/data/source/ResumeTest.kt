package io.github.mayusi.emuhelper.data.source

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PARTIAL-BYTE RESUME (v0.8): the pure [ResumeManifest] serialise/validate logic and the
 * [ChunkQueue] pre-seed path.
 *
 * The guarantees proven here:
 *   - A queue pre-seeded with a persisted done-set fetches ONLY the MISSING chunks (the done ones are
 *     never polled), yet still completes with FULL byte coverage of [0,size).
 *   - confirmedBytes (resumed bytes + freshly-fetched bytes) equals the expected size exactly — each
 *     chunk's length counted once.
 *   - A manifest that DOESN'T match the current size/chunking/.part-length is safely DISCARDED
 *     (parseAndValidate returns null) so the download restarts fresh — the safe fallback. The MD5
 *     verify (in DownloadManager) is the final correctness gate regardless.
 */
class ResumeTest {

    private val CHUNK = AdaptiveEngine.CHUNK_SIZE // 8 MB

    // ---- ResumeManifest: serialise / parse / validate -----------------------------------------

    @Test
    fun `serialize then parse round-trips the done-set`() {
        val size = 100L * CHUNK + 12345L  // 100 full chunks + a remainder chunk
        val done = setOf(0, 1, 2, 5, 7, 99)
        val raw = ResumeManifest.serialize(size, CHUNK, done)
        // Validates against the SAME size/chunk and a full-length .part -> returns the same set.
        val parsed = ResumeManifest.parseAndValidate(raw, size, CHUNK, partLength = size)
        assertEquals(done, parsed, "round-trip must preserve the done-set")
    }

    @Test
    fun `empty done-set round-trips`() {
        val size = 10L * CHUNK
        val raw = ResumeManifest.serialize(size, CHUNK, emptySet())
        val parsed = ResumeManifest.parseAndValidate(raw, size, CHUNK, partLength = size)
        assertEquals(emptySet(), parsed, "empty done-set parses to empty, not null")
    }

    @Test
    fun `mismatched expected size discards the manifest`() {
        val size = 10L * CHUNK
        val raw = ResumeManifest.serialize(size, CHUNK, setOf(0, 1, 2))
        // The file we're ABOUT to download is a DIFFERENT size -> old offsets meaningless -> discard.
        assertNull(
            ResumeManifest.parseAndValidate(raw, expectedSize = 11L * CHUNK, chunkSize = CHUNK, partLength = 11L * CHUNK),
            "a size mismatch must discard the resume (start fresh)"
        )
    }

    @Test
    fun `mismatched chunk size discards the manifest`() {
        val size = 10L * CHUNK
        val raw = ResumeManifest.serialize(size, CHUNK, setOf(0, 1))
        // The chunking constant changed (e.g. app update) -> indices map to different ranges -> discard.
        assertNull(
            ResumeManifest.parseAndValidate(raw, expectedSize = size, chunkSize = CHUNK / 2, partLength = size),
            "a chunk-size mismatch must discard the resume"
        )
    }

    @Test
    fun `truncated part file discards the manifest`() {
        val size = 10L * CHUNK
        val raw = ResumeManifest.serialize(size, CHUNK, setOf(0, 1, 2))
        // The .part on disk is SHORTER than the expected full pre-sized length -> can't trust it.
        assertNull(
            ResumeManifest.parseAndValidate(raw, size, CHUNK, partLength = size - 1),
            "a .part shorter than expectedSize must discard the resume"
        )
        // Exactly full length is fine.
        assertNotNull(ResumeManifest.parseAndValidate(raw, size, CHUNK, partLength = size))
    }

    @Test
    fun `malformed or wrong-version manifest discards safely (never throws)`() {
        val size = 10L * CHUNK
        // null / blank / junk / wrong version / out-of-range index all -> null, no throw.
        assertNull(ResumeManifest.parseAndValidate(null, size, CHUNK, size))
        assertNull(ResumeManifest.parseAndValidate("", size, CHUNK, size))
        assertNull(ResumeManifest.parseAndValidate("garbage", size, CHUNK, size))
        assertNull(ResumeManifest.parseAndValidate("v9|$size|$CHUNK|0,1", size, CHUNK, size), "wrong version")
        // An index beyond the valid chunk count for this size must be rejected.
        val chunkCount = partitionChunks(size, CHUNK).size
        assertNull(
            ResumeManifest.parseAndValidate("v1|$size|$CHUNK|0,$chunkCount", size, CHUNK, size),
            "an out-of-range index must discard the manifest"
        )
        // A negative index is rejected too.
        assertNull(ResumeManifest.parseAndValidate("v1|$size|$CHUNK|-1,0", size, CHUNK, size))
    }

    // ---- ChunkQueue pre-seed: fetch only missing, full coverage -------------------------------

    @Test
    fun `pre-seeded queue polls ONLY missing chunks and still covers the whole file`() {
        // 10 chunks of 10 bytes (size 100). Pretend chunks {0,1,2,5,7} are already done from a prior run.
        val size = 100L
        val cs = 10L
        val chunks = partitionChunks(size, cs)
        assertEquals(10, chunks.size)
        val preDone = setOf(0, 1, 2, 5, 7)
        val q = ChunkQueue(chunks, preDone = preDone)

        // The done chunks are NOT queued and report done immediately.
        assertEquals(preDone, q.doneIndices())
        assertEquals(preDone.size, q.doneCount())
        assertEquals(chunks.size - preDone.size, q.remaining(), "only missing chunks remain")
        assertEquals(chunks.size - preDone.size, q.unclaimed(), "only missing chunks are pollable")
        for (i in preDone) assertTrue(q.isDone(i), "pre-seeded chunk $i must be done")

        // Drain the queue — only the MISSING chunks should ever be polled.
        val polled = HashSet<Int>()
        var c = q.poll()
        while (c != null) {
            assertFalse(c.index in preDone, "a pre-done chunk ${c.index} must never be polled")
            polled.add(c.index)
            q.markDone(c)
            c = q.poll()
        }
        // Exactly the missing set was fetched; together with the pre-done set that's full coverage.
        assertEquals(setOf(3, 4, 6, 8, 9), polled, "exactly the missing chunks were fetched")
        assertTrue(q.allDone(), "pre-done + fetched must cover every chunk index")
        assertEquals(0, q.remaining())

        // BYTE COVERAGE: the union (pre-done ∪ fetched) covers [0,size) with no gaps/overlaps.
        val covered = (preDone + polled).sorted()
        assertEquals((0 until chunks.size).toList(), covered, "every index covered exactly once")
        // confirmedBytes-equivalent: sum of all chunk lengths == size.
        val totalBytes = chunks.sumOf { it.length }
        assertEquals(size, totalBytes)
        // Resumed bytes + fetched bytes == size (each chunk counted once).
        val resumedBytes = preDone.sumOf { chunks[it].length }
        val fetchedBytes = polled.sumOf { chunks[it].length }
        assertEquals(size, resumedBytes + fetchedBytes, "confirmedBytes must equal expectedSize")
    }

    @Test
    fun `pre-seed with the full set means nothing to fetch and instantly complete`() {
        val size = 50L
        val cs = 10L
        val chunks = partitionChunks(size, cs) // 5 chunks
        val all = (0 until chunks.size).toSet()
        val q = ChunkQueue(chunks, preDone = all)
        assertTrue(q.allDone(), "a fully pre-seeded queue is already complete")
        assertEquals(0, q.remaining())
        assertNull(q.poll(), "nothing left to fetch")
        assertFalse(q.shouldKeepWorking(), "workers should exit immediately")
    }

    @Test
    fun `pre-seed ignores invalid indices defensively`() {
        val size = 30L
        val cs = 10L
        val chunks = partitionChunks(size, cs) // 3 chunks: indices 0,1,2
        // Includes out-of-range indices (99, -1) which must be ignored, not crash or corrupt state.
        val q = ChunkQueue(chunks, preDone = setOf(0, 99, -1))
        assertEquals(setOf(0), q.doneIndices(), "only valid indices are pre-seeded")
        assertEquals(2, q.remaining(), "chunks 1 and 2 still missing")
        // Drain and confirm full coverage.
        var c = q.poll()
        while (c != null) { q.markDone(c); c = q.poll() }
        assertTrue(q.allDone())
    }

    @Test
    fun `a fresh queue (empty pre-seed) is byte-identical to the old behaviour`() {
        val size = 100L
        val cs = 10L
        val chunks = partitionChunks(size, cs)
        val fresh = ChunkQueue(chunks)                       // old constructor path
        val explicit = ChunkQueue(chunks, preDone = emptySet()) // new param, empty
        assertEquals(fresh.count, explicit.count)
        assertEquals(fresh.remaining(), explicit.remaining())
        assertEquals(fresh.unclaimed(), explicit.unclaimed())
        assertEquals(0, fresh.doneCount())
        assertEquals(0, explicit.doneCount())
    }
}
