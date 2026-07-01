package io.github.mayusi.emuhelper.desktop

import io.github.mayusi.emuhelper.platform.Log
import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream

/**
 * Desktop (plain java.io.File) archive extraction — the desktop analogue of the Android
 * [io.github.mayusi.emuhelper.data.source.DownloadManager.extractZipToDestination] /
 * `extractRarToDestination` pair. Desktop has no NDK, so only ZIP is supported here via the
 * JDK's built-in java.util.zip (free, no extra dependency); RAR stays Android-only (native
 * unrar engine) — see [DesktopArchive.extractIfNeeded] below for the explicit no-op fallback.
 *
 * SECURITY: mirrors the Android zip-slip guard exactly (canonical-path containment check),
 * factored into the pure [isEntryPathSafe] so the traversal logic is unit-testable without
 * touching the filesystem for real.
 */
object DesktopArchive {

    private const val TAG = "EmuHelper"
    private const val BUFFER_SIZE = 1 shl 20 // 1 MB, same as the Android ZIP/RAR publish paths.

    /**
     * Pure zip-slip guard: given a zip entry name and the destination directory's CANONICAL path,
     * resolve where the entry would land and verify it stays inside that directory.
     *
     * Mirrors the Android guard (DownloadManager.extractZipToDestination) verbatim:
     *   - reject absolute-path entries outright (an absolute entry name ignores the destination
     *     entirely when resolved via `File(destDir, entry.name)` semantics on some platforms, so
     *     treat it as unsafe rather than relying on canonicalization alone).
     *   - canonicalize the resolved output file's path and require it to start with
     *     `canonicalDestDir + File.separator` (a single-component entry canonicalizes to a path
     *     equal to, not under, the dest dir — but a same-named entry always adds a separator+name,
     *     so this check is exact for real entries; it also blocks entries that are exactly the
     *     dest dir itself, e.g. a "." entry, which is correct to skip).
     *
     * @param entryName the raw [java.util.zip.ZipEntry.getName] value.
     * @param destDirCanonicalPath the destination directory's OWN canonical path (no trailing separator).
     * @param resolveCanonical resolves `File(destDir, entryName)` to its canonical path; injected so
     *   this stays a pure function testable without real files (tests can stub canonicalization).
     * @return the safe canonical output path, or null if the entry must be skipped.
     */
    fun safeCanonicalOutputPath(
        entryName: String,
        destDirCanonicalPath: String,
        resolveCanonical: (String) -> String,
    ): String? {
        if (entryName.isBlank()) return null
        // Reject absolute-path entries and any raw ".." path segment up front (belt + suspenders
        // ahead of the canonical-path check below, exactly like the Android guard's intent).
        val normalizedSlashes = entryName.replace('\\', '/')
        if (File(entryName).isAbsolute || normalizedSlashes.startsWith("/")) return null
        if (normalizedSlashes.split('/').any { it == ".." }) return null

        val canonicalOut = try {
            resolveCanonical(entryName)
        } catch (e: IOException) {
            return null
        }
        val base = destDirCanonicalPath + File.separator
        return if (canonicalOut.startsWith(base)) canonicalOut else null
    }

    /**
     * Extract [zipFile] into [destDir] using [ZipInputStream], streaming each entry through a
     * bounded 1 MB buffer. Directory entries are skipped (parents are created via mkdirs as needed
     * for file entries). Every entry is validated with [safeCanonicalOutputPath] before any bytes
     * are written; unsafe entries are logged and skipped (never written, never abort the whole
     * extraction — matches the Android guard's per-entry skip behaviour).
     *
     * [isCancelled] is polled between entries (best-effort cooperative cancellation, matching the
     * pause/cancel plumbing DownloadController already threads through the download engine).
     *
     * @return true on a clean extraction (or a cancelled-but-partial one — caller still deletes the
     *   source zip only when this returns true AND not cancelled; see [extractIfNeeded]), false on
     *   any failure (caller must then keep the .zip as-is).
     */
    fun extractZip(
        zipFile: File,
        destDir: File,
        isCancelled: () -> Boolean = { false },
    ): Boolean {
        return try {
            destDir.mkdirs()
            val destDirCanonical = destDir.canonicalPath
            ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (isCancelled()) {
                        Log.w(TAG, "zip: extraction cancelled mid-archive for ${zipFile.name}")
                        return false
                    }
                    if (!entry.isDirectory) {
                        val safePath = safeCanonicalOutputPath(
                            entryName = entry.name,
                            destDirCanonicalPath = destDirCanonical,
                            resolveCanonical = { name -> File(destDir, name).canonicalPath },
                        )
                        if (safePath == null) {
                            Log.w(TAG, "zip: path traversal or unsafe entry blocked for ${entry.name}")
                        } else {
                            val outFile = File(safePath)
                            outFile.parentFile?.mkdirs()
                            outFile.outputStream().buffered().use { os ->
                                zis.copyTo(os, BUFFER_SIZE)
                            }
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "zip: failed to extract ${zipFile.name}, leaving archive as-is", e)
            false
        }
    }

    /**
     * Called from the desktop publish path once a download has landed at [downloadedFile] inside
     * [destDir]. When [extractArchivesEnabled] is on AND the file is a `.zip`, extract it in place
     * and (on success) delete the source zip — mirroring Android, which never leaves the raw
     * archive behind alongside its extracted contents (Android downloads to a private cache file,
     * extracts into the public destination, then always discards the cache copy; desktop has no
     * separate cache step, so we replicate the same end state here by deleting the .zip after a
     * successful extraction).
     *
     * `.rar` (and any other non-zip archive) is intentionally a NO-OP: RAR extraction on Android
     * uses a native NDK unrar engine that has no desktop build here, so a `.rar` is simply left as
     * the downloaded file, exactly like when the setting is off. This is a deliberate scope
     * boundary for this change, not an oversight — do not add a RAR library here.
     *
     * On ANY extraction failure the .zip is left on disk untouched (the download is never lost).
     */
    fun extractIfNeeded(
        downloadedFile: File,
        destDir: File,
        extractArchivesEnabled: Boolean,
        isCancelled: () -> Boolean = { false },
    ) {
        if (!extractArchivesEnabled) return
        val name = downloadedFile.name.lowercase()
        if (!name.endsWith(".zip")) {
            // RAR (and anything else) — desktop has no native unrar engine; save as-is. This mirrors
            // the "extraction unavailable" outcome, not a failure, so no warning log is needed.
            return
        }
        val extracted = extractZip(downloadedFile, destDir, isCancelled)
        if (extracted && !isCancelled()) {
            runCatching { downloadedFile.delete() }
                .onFailure { Log.w(TAG, "zip: extracted OK but could not delete source ${downloadedFile.name}", it) }
        }
        // On failure (or cancellation), downloadedFile is left in place untouched — never lose data.
    }
}
