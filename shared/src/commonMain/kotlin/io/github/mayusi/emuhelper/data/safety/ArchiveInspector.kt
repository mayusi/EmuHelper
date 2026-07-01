package io.github.mayusi.emuhelper.data.safety

import java.io.File
import java.io.RandomAccessFile
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * STATIC ARCHIVE INSPECTION — walk a ZIP's structure and sample a little content WITHOUT a full
 * extraction. Uses only java.util.zip + a raw local-file-header pass (no new dep, cross-platform on
 * both JVM targets).
 *
 * The philosophy mirrors the rest of the engine: don't cry wolf. Games and tools LEGITIMATELY contain
 * .exe/.dll entries, so mere presence of a dangerous-extension entry is NOT flagged — we only FLAG on
 * clustered malicious BEHAVIOUR found by sampling entry contents through [IndicatorScan]. What we DO
 * hard-flag are structural attacks that have no legitimate excuse: path traversal, and zip-bomb caps.
 * Encryption is a scan LIMITATION (we can't read the content), reported honestly as such.
 *
 * RAR / 7z are NOT extracted here — native RAR is Android-only and out of scope for this engine — so
 * those return [Result.limited] ([inspectionComplete] == false), yielding a LIMITED verdict rather
 * than a false "clean".
 *
 * Encryption is detected via the ZIP LOCAL FILE HEADER general-purpose bit 0, read directly from the
 * bytes (java.util.zip's high-level API does not surface the GP flags on ZipEntry), scanning for the
 * local-file-header signature "PK\3\4" (0x04034b50).
 */
object ArchiveInspector {

    // Zip-bomb caps.
    private const val MAX_ENTRIES = 10_000
    private const val MAX_TOTAL_EXPANDED = 150L * 1024 * 1024 * 1024  // 150 GB
    private const val MAX_NESTING_DEPTH = 12

    // Content-sampling caps (keep it cheap on huge archives).
    private const val PER_ENTRY_SAMPLE = 512 * 1024      // ~512 KB per entry
    private const val TOTAL_SAMPLE_BUDGET = 4 * 1024 * 1024  // ~4 MB across the whole archive

    // ZIP local-file-header signature "PK\3\4" (little-endian 0x04034b50).
    private val LFH_SIG = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
    // How far into the file we scan for local headers when probing encryption (cheap, front-loaded).
    private const val HEADER_PROBE_LIMIT = 4L * 1024 * 1024

    /** Dangerous entry extensions — COUNTED for context, but presence alone never flags. */
    private val DANGEROUS_EXTENSIONS = setOf(
        "exe", "dll", "scr", "bat", "cmd", "ps1", "vbs", "js", "jse", "wsf", "hta", "msi", "com",
        "pif", "reg", "inf", "vbe", "lnk",
    )

    /**
     * @param inspectionComplete  false when we couldn't fully inspect (RAR/7z/encrypted/error) — the
     *                            caller should treat this as LIMITED, not clean.
     * @param pathTraversal       an entry escaped the extraction root (../, absolute, drive prefix).
     * @param encrypted           at least one entry is encrypted (content unscannable).
     * @param zipBomb             a zip-bomb cap tripped (too many entries / too much expansion / nesting).
     * @param contentThreat       a sampled entry's content produced a high-confidence IndicatorScan cluster.
     * @param reasons             human-readable summaries for the SafetyChecks.
     * @param dangerousEntryCount count of dangerous-extension entries (informational).
     * @param entryCount          total entries seen.
     */
    data class Result(
        val inspectionComplete: Boolean,
        val pathTraversal: Boolean,
        val encrypted: Boolean,
        val zipBomb: Boolean,
        val contentThreat: Boolean,
        val reasons: List<String>,
        val dangerousEntryCount: Int,
        val entryCount: Int,
    ) {
        /** A structural/behavioural red flag worth escalating on (not merely a limitation). */
        val hasRedFlag: Boolean get() = pathTraversal || zipBomb || contentThreat

        companion object {
            /** For RAR/7z/unsupported: we didn't fail, we just couldn't look inside. */
            fun limited(reason: String) = Result(
                inspectionComplete = false, pathTraversal = false, encrypted = false, zipBomb = false,
                contentThreat = false, reasons = listOf(reason), dangerousEntryCount = 0, entryCount = 0,
            )
        }
    }

    /**
     * Inspect [file] as a ZIP. Never throws — any error yields a LIMITED result.
     */
    fun inspectZip(file: File): Result {
        val reasons = ArrayList<String>()
        var pathTraversal = false
        var encrypted = false
        var zipBomb = false
        var contentThreat = false
        var dangerousCount = 0
        var entryCount = 0
        var complete = true

        // Encryption probe: scan raw local file headers for GP-flag bit 0. Cheap and reliable.
        try {
            if (anyEncryptedLocalHeader(file)) {
                encrypted = true
            }
        } catch (_: Throwable) {
            // Non-fatal; the central-directory pass below still runs.
        }

        // Structure pass via the central directory (ZipFile): entry names, sizes, nesting, extensions.
        try {
            ZipFile(file).use { zf ->
                val entries = zf.entries()
                var seen = 0
                var totalExpanded = 0L
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    seen++
                    if (seen > MAX_ENTRIES) {
                        zipBomb = true
                        reasons.add("zip-bomb: more than $MAX_ENTRIES entries")
                        break
                    }
                    val name = entry.name
                    if (isPathTraversal(name)) {
                        pathTraversal = true
                        reasons.add("path traversal entry: '$name'")
                    }
                    if (nestingDepth(name) > MAX_NESTING_DEPTH) {
                        zipBomb = true
                        reasons.add("zip-bomb: nesting depth > $MAX_NESTING_DEPTH ('$name')")
                    }
                    val declaredSize = entry.size
                    if (declaredSize > 0) {
                        totalExpanded += declaredSize
                        if (totalExpanded > MAX_TOTAL_EXPANDED) {
                            zipBomb = true
                            reasons.add("zip-bomb: expanded size exceeds ${MAX_TOTAL_EXPANDED / (1024L*1024*1024)} GB")
                            break
                        }
                    }
                    if (!entry.isDirectory) {
                        val ext = extensionOf(name)
                        if (ext in DANGEROUS_EXTENSIONS) dangerousCount++
                    }
                }
                entryCount = seen
            }
        } catch (_: Throwable) {
            complete = false
            reasons.add("archive could not be fully read (may be corrupt or unsupported)")
        }

        if (encrypted) {
            complete = false
            reasons.add("encrypted entries present — contents cannot be scanned")
        }

        // Content-sampling pass: sample uncompressed content through IndicatorScan, within budget.
        // Skipped if we already found a decisive structural flag or the archive is encrypted.
        if (complete && !zipBomb && !pathTraversal && !encrypted) {
            try {
                ZipFile(file).use { zf ->
                    var budget = TOTAL_SAMPLE_BUDGET.toLong()
                    val entries = zf.entries()
                    while (entries.hasMoreElements() && budget > 0) {
                        val e = entries.nextElement()
                        if (e.isDirectory) continue
                        val toRead = minOf(PER_ENTRY_SAMPLE.toLong(), budget).toInt()
                        val sample = try {
                            zf.getInputStream(e).use { it.readNBytesSafe(toRead) }
                        } catch (_: Throwable) {
                            null
                        } ?: continue
                        budget -= sample.size
                        // A ZIP entry could itself be a PE or a script; run both clusters.
                        val findings = IndicatorScan.scan(sample, cap = sample.size, treatAsPe = true)
                        if (findings.highConfidence) {
                            contentThreat = true
                            reasons.add("entry '${e.name}': ${findings.detail}")
                            break
                        }
                    }
                }
            } catch (_: Throwable) {
                // Sampling failure is not fatal; structure pass already ran, but note the gap.
                complete = false
            }
        }

        if (dangerousCount > 0) {
            reasons.add("$dangerousCount executable-type entr${if (dangerousCount == 1) "y" else "ies"} present (informational; not flagged by presence alone)")
        }

        return Result(
            inspectionComplete = complete,
            pathTraversal = pathTraversal,
            encrypted = encrypted,
            zipBomb = zipBomb,
            contentThreat = contentThreat,
            reasons = reasons,
            dangerousEntryCount = dangerousCount,
            entryCount = entryCount,
        )
    }

    // ---- helpers ------------------------------------------------------------------------------

    /**
     * Scan raw local file headers for the encryption bit (general-purpose bit flag, byte offset 6 of
     * each local header, bit 0). Returns true if ANY local header has it set. Front-loaded scan capped
     * at [HEADER_PROBE_LIMIT] bytes — real ZIPs interleave headers with data, so we walk header-to-data
     * using the compressed-size field. Defensive: bad offsets stop the scan (returns what it found).
     */
    private fun anyEncryptedLocalHeader(file: File): Boolean {
        RandomAccessFile(file, "r").use { raf ->
            val len = raf.length()
            val limit = minOf(len, HEADER_PROBE_LIMIT)
            var pos = 0L
            val hdr = ByteArray(30)
            var scanned = 0
            while (pos + 30 <= limit && scanned < MAX_ENTRIES) {
                raf.seek(pos)
                raf.readFully(hdr)
                // Must be a local file header signature to trust the structured walk.
                if (hdr[0] != LFH_SIG[0] || hdr[1] != LFH_SIG[1] ||
                    hdr[2] != LFH_SIG[2] || hdr[3] != LFH_SIG[3]
                ) {
                    // Not at a local header (hit central dir / data / padding) — stop the structured walk.
                    break
                }
                val gpFlag = u16(hdr, 6)
                if ((gpFlag and 0x1) != 0) return true
                val compSize = u32(hdr, 18)
                val nameLen = u16(hdr, 26)
                val extraLen = u16(hdr, 28)
                // If a data descriptor is used (bit 3), the local header size is 0 and we can't skip
                // reliably — bail out of the structured walk (encryption bit would already be caught).
                if ((gpFlag and 0x8) != 0) break
                pos += 30L + nameLen + extraLen + compSize
                scanned++
            }
        }
        return false
    }

    private fun isPathTraversal(name: String): Boolean {
        val n = name.replace('\\', '/')
        if (n.startsWith("/")) return true                       // absolute unix path
        if (n.length >= 2 && n[1] == ':' && n[0].isLetter()) return true // drive prefix "C:"
        if (n == ".." || n.startsWith("../") || n.contains("/../")) return true
        return false
    }

    private fun nestingDepth(name: String): Int =
        name.replace('\\', '/').count { it == '/' }

    private fun extensionOf(name: String): String {
        val slash = maxOf(name.lastIndexOf('/'), name.lastIndexOf('\\'))
        val base = if (slash >= 0) name.substring(slash + 1) else name
        val dot = base.lastIndexOf('.')
        return if (dot < 0 || dot == base.length - 1) "" else base.substring(dot + 1).lowercase()
    }

    /** Read up to [max] bytes; tolerant of short reads / EOF; never throws past the caller's try. */
    private fun java.io.InputStream.readNBytesSafe(max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(minOf(max, 64 * 1024))
        val chunk = ByteArray(16 * 1024)
        var remaining = max
        while (remaining > 0) {
            val want = minOf(chunk.size, remaining)
            val r = this.read(chunk, 0, want)
            if (r <= 0) break
            out.write(chunk, 0, r)
            remaining -= r
        }
        return out.toByteArray()
    }

    // Little-endian readers over a small header buffer.
    private fun u16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private fun u32(b: ByteArray, off: Int): Long =
        (b[off].toInt() and 0xFF).toLong() or
            ((b[off + 1].toInt() and 0xFF).toLong() shl 8) or
            ((b[off + 2].toInt() and 0xFF).toLong() shl 16) or
            ((b[off + 3].toInt() and 0xFF).toLong() shl 24)
}
