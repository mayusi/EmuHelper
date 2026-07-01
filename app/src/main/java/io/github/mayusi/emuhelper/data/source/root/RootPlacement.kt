package io.github.mayusi.emuhelper.data.source.root

import java.io.File

/**
 * Pure, side-effect-free helpers that decide whether a finished download can be placed at its final
 * destination via the optional fast path, and that build the exact command strings for it.
 *
 * This is deliberately a small, fully-unit-testable object: the IO that actually moves bytes lives in
 * the caller and is verified on-device, but the *eligibility decision* and the *command construction*
 * are pure logic and are the part most worth pinning down with tests.
 *
 * The fast path is an OPTIONAL accelerator. It is only ever taken for a REAL filesystem destination
 * (a [File]) — never for a SAF content:// tree, which the caller handles separately and which this
 * helper has no knowledge of. Every command produced here is shaped so it passes
 * [PServerCommandGuard] (which the bridge enforces independently): clean absolute paths with no
 * spaces or shell metacharacters, a destination under an allowed write root, and only the
 * guard-approved verbs (mkdir -p, cp, chmod 644).
 *
 * Eligibility intentionally mirrors a SUBSET of the guard's path rules so we can cheaply skip a
 * destination the guard would reject anyway (e.g. a game name containing a space) instead of wasting
 * a transact that would just come back BLOCKED. The guard remains the real authority — this is a
 * fast pre-filter, not a replacement for it.
 */
object RootPlacement {

    /**
     * The characters a path may contain to be fast-path eligible. This is the guard's PATH_RE set:
     * a leading '/', then alphanumerics and a small filename-safe punctuation set. NOTABLY excludes
     * spaces (and every shell metacharacter), so any destination whose absolute path contains a
     * space (very common in ROM filenames) is simply NOT eligible — the caller falls back to the
     * normal copy, which handles spaces fine. We never try to quote/escape around the guard.
     */
    private val PATH_RE = Regex("^/[A-Za-z0-9_./+\\-@=,]*$")

    /**
     * Shared-storage write roots that are the same on every device/build. Each ends with '/' so
     * matching is boundary-safe (`/sdcardX` can't satisfy `/sdcard/`). The app-private roots are NOT
     * hardcoded here — they depend on the runtime package id (which carries a `.debug` suffix on debug
     * builds), so they are supplied per-call by [extraRoots] from the caller's Context. Hardcoding the
     * release package broke the fast path on debug builds (source cache path never matched) and was
     * brittle to any applicationId change.
     */
    private val STATIC_WRITE_ROOTS: List<String> = listOf(
        "/sdcard/",
        "/storage/emulated/0/",
        "/storage/self/primary/"
    )

    /**
     * Normalize an app-private data dir (e.g. `context.applicationInfo.dataDir`,
     * `/data/user/0/<pkg>` or `/data/data/<pkg>`) into a boundary-safe write-root prefix ending in
     * '/'. Returns null if the input isn't a clean absolute path. The caller passes both forms it
     * cares about; we just trust + slash-terminate them (they come from the framework, not the user).
     */
    fun appPrivateRoot(dataDir: String): String? {
        val norm = cleanLexicalPath(dataDir) ?: return null
        return if (norm.endsWith("/")) norm else "$norm/"
    }

    /**
     * Decide whether [dest] is a real-filesystem destination the fast path may target. [extraRoots]
     * are the app-private roots supplied by the caller from its Context (see [appPrivateRoot]).
     * Returns the clean absolute path to use, or null if NOT eligible (relative, space/metachar/
     * traversal, or not under an allowed write root). null means "use the normal copy" — never an error.
     *
     * Uses the absolute (lexical) path, NOT the canonical path: we must not follow symlinks here, and
     * the destination may not exist yet. Rejects any '..' or '.' component defensively.
     */
    fun eligibleDestPath(dest: File, extraRoots: List<String> = emptyList()): String? =
        cleanEligiblePath(dest.absolutePath, extraRoots)

    /**
     * As [eligibleDestPath] but for an arbitrary path string (used for the source/cache file).
     * [extraRoots] are the per-call app-private write roots (from the caller's Context).
     */
    fun cleanEligiblePath(raw: String, extraRoots: List<String> = emptyList()): String? {
        val norm = cleanLexicalPath(raw) ?: return null
        // Must sit strictly inside an allowed write root (shared-storage OR a supplied app-private one).
        val roots = STATIC_WRITE_ROOTS.asSequence() + extraRoots.asSequence()
        val underRoot = roots.any { root -> root.isNotEmpty() && norm.startsWith(root) && norm.length > root.length }
        return if (underRoot) norm else null
    }

    /** Path syntax check only (absolute, guard char-set, no '.'/'..' components). No root check. */
    private fun cleanLexicalPath(raw: String): String? {
        if (raw.isEmpty() || !raw.startsWith("/")) return null
        if (!PATH_RE.matches(raw)) return null
        val parts = raw.split('/')
        val cleaned = ArrayList<String>(parts.size)
        for (p in parts) {
            when (p) {
                "" -> { /* leading or doubled slash */ }
                ".", ".." -> return null
                else -> cleaned.add(p)
            }
        }
        return "/" + cleaned.joinToString("/")
    }

    /**
     * Build the ordered command list to place [srcPath] at [dstPath] via the fast path:
     *   1. mkdir -p <parent of dst>   (ensure the subfolder exists)
     *   2. cp <src> <dst>             (place the file)
     *   3. chmod 644 <dst>            (make it readable like a normal download)
     *
     * Returns null if either path is not fast-path eligible, or if the destination has no parent
     * under a write root — in which case the caller does NOT attempt the fast path. Every returned
     * command is guard-shaped (clean paths, approved verbs/modes).
     *
     * NOTE: no `rm` of any pre-existing file — rm is guard-denied. `cp` overwrites an existing
     * regular file in place, which is exactly what we want; the normal-copy fallback also overwrites.
     */
    fun buildPlaceCommands(srcPath: String, dstPath: String, extraRoots: List<String> = emptyList()): List<String>? {
        val src = cleanEligiblePath(srcPath, extraRoots) ?: return null
        val dst = cleanEligiblePath(dstPath, extraRoots) ?: return null
        val parent = dst.substringBeforeLast('/', missingDelimiterValue = "")
        // Parent must itself be a non-empty path under a write root (cleanEligiblePath enforces the
        // root); without a valid parent we can't mkdir -p safely, so bail to the normal copy.
        val parentClean = cleanEligiblePath(parent, extraRoots) ?: return null
        return listOf(
            "mkdir -p $parentClean",
            "cp $src $dst",
            "chmod 644 $dst"
        )
    }

    /**
     * Build the ordered command list to MOVE [srcPath] to [dstPath] via the fast path:
     *   1. mkdir -p <parent of dst>   (ensure the subfolder exists)
     *   2. mv <src> <dst>             (rename the file — instant + frees the cache automatically)
     *   3. chmod 644 <dst>            (make it readable like a normal download)
     *
     * This is the DISK-EFFICIENT variant of [buildPlaceCommands]: `mv` within one filesystem is an
     * O(1) inode rename that needs ZERO extra disk and removes the source as it goes, so a 100GB+
     * .pkg never has to exist twice (cache + dest) at the same time. The caller prefers this and falls
     * back to [buildPlaceCommands] / the normal copy if `mv` returns non-zero (e.g. a cross-device
     * EXDEV move). Because mv DELETES its source, the guard requires the SOURCE to also sit under an
     * allowed write root (same as the destination) — and so does eligibility here: both [srcPath] and
     * [dstPath] must be [cleanEligiblePath] (under a shared-storage or app-private write root). A
     * source outside those roots is NOT eligible and yields null, so we never even attempt to `mv`
     * (let alone delete) a system or other-app path. Every returned command is guard-shaped.
     */
    fun buildMoveCommands(srcPath: String, dstPath: String, extraRoots: List<String> = emptyList()): List<String>? {
        val src = cleanEligiblePath(srcPath, extraRoots) ?: return null
        val dst = cleanEligiblePath(dstPath, extraRoots) ?: return null
        val parent = dst.substringBeforeLast('/', missingDelimiterValue = "")
        val parentClean = cleanEligiblePath(parent, extraRoots) ?: return null
        return listOf(
            "mkdir -p $parentClean",
            "mv $src $dst",
            "chmod 644 $dst"
        )
    }
}
