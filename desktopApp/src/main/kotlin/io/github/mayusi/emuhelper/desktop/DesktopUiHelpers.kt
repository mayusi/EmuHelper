package io.github.mayusi.emuhelper.desktop

import io.github.mayusi.emuhelper.data.safety.SafetyVerdict
import java.io.File

/**
 * PURE, side-effect-free helpers for the desktop UI, factored out of [App]/[DownloadController] so the
 * file-filter/sort predicate and the quarantine-path builder can be unit-tested without Compose or real
 * downloads. Nothing here touches the network, Compose state, or (except where a File is explicitly
 * passed) the filesystem.
 */

/** How the FILES screen orders its rows — mirrors the Android GamePickerScreen sort options. */
enum class FileSort(val label: String) {
    NAME_ASC("Name A–Z"),
    NAME_DESC("Name Z–A"),
    SIZE_DESC("Largest"),
    SIZE_ASC("Smallest"),
}

/** The selection filter chip's tri-state — mirrors the Android picker's All / Only-selected / Hide-selected. */
enum class SelectionFilter(val label: String) {
    ALL("All"),
    SELECTED("Only selected"),
    UNSELECTED("Hide selected"),
}

/** Minimal shape the filter/sort predicate needs from a file row (kept tiny for testability). */
interface FileRowLike {
    val displayName: String
    val sizeBytes: Long
    val isSelected: Boolean
}

/**
 * Pure filter+sort applied to the FILES list: substring match on the display name (case-insensitive,
 * trimmed), the selection tri-state, then the chosen sort. Stable ordering (name sorts break ties by
 * name so the list never jitters). Extracted so it is unit-testable in isolation.
 */
fun <T : FileRowLike> filterAndSortFiles(
    rows: List<T>,
    query: String,
    sort: FileSort,
    selection: SelectionFilter,
): List<T> {
    val q = query.trim().lowercase()
    val filtered = rows.asSequence()
        .filter { q.isEmpty() || it.displayName.lowercase().contains(q) }
        .filter {
            when (selection) {
                SelectionFilter.ALL -> true
                SelectionFilter.SELECTED -> it.isSelected
                SelectionFilter.UNSELECTED -> !it.isSelected
            }
        }
        .toList()
    return when (sort) {
        FileSort.NAME_ASC -> filtered.sortedBy { it.displayName.lowercase() }
        FileSort.NAME_DESC -> filtered.sortedByDescending { it.displayName.lowercase() }
        FileSort.SIZE_DESC -> filtered.sortedWith(compareByDescending<T> { it.sizeBytes }.thenBy { it.displayName.lowercase() })
        FileSort.SIZE_ASC -> filtered.sortedWith(compareBy<T> { it.sizeBytes }.thenBy { it.displayName.lowercase() })
    }
}

/**
 * Build the quarantine destination for [file]: `<parentDir>/Quarantine/<name>`, disambiguating a
 * name clash by appending " (n)" before the extension so an existing quarantined file is never
 * clobbered. PURE (constructs Files, computes names) — it does NOT move anything; see
 * [quarantineFile] for the actual move. [exists] is injected so the collision logic is testable
 * without touching the disk (defaults to real File.exists()).
 */
fun quarantineTargetFor(file: File, exists: (File) -> Boolean = { it.exists() }): File {
    val dir = File(file.parentFile ?: File("."), "Quarantine")
    val name = file.name
    val dot = name.lastIndexOf('.')
    val base = if (dot > 0) name.substring(0, dot) else name
    val ext = if (dot > 0) name.substring(dot) else ""
    var candidate = File(dir, name)
    var n = 1
    while (exists(candidate)) {
        candidate = File(dir, "$base ($n)$ext")
        n++
    }
    return candidate
}

/**
 * MOVE [file] into its quarantine target (see [quarantineTargetFor]). Tries a fast rename first; if
 * that fails (typically a cross-volume move), falls back to copy-then-delete. NEVER deletes without a
 * successful copy — a quarantine must never lose the user's file. Returns the destination File on
 * success, or null on failure (source left untouched).
 */
fun quarantineFile(file: File): File? {
    if (!file.exists()) return null
    return try {
        val target = quarantineTargetFor(file)
        target.parentFile?.mkdirs()
        if (file.renameTo(target)) {
            target
        } else {
            // Cross-volume (or locked) rename failed — copy, verify, then delete the source.
            file.copyTo(target, overwrite = false)
            if (target.exists() && target.length() == file.length()) {
                file.delete()
                target
            } else {
                // Copy looks incomplete — remove the partial target, keep the source.
                runCatching { target.delete() }
                null
            }
        }
    } catch (_: Throwable) {
        null
    }
}

/**
 * Coarse UI colour band for a scan verdict. Green = clean/verified, amber = limited/unknown/suspicious
 * (review), red = risky. Returned as an ARGB Long so both Compose (Color(long)) and tests can use it.
 */
enum class SafetyBand(val argb: Long) {
    GREEN(0xFFA6E3A1),  // clean — matches the DONE green used elsewhere in the UI
    AMBER(0xFFF9E2AF),  // review — Catppuccin yellow
    RED(0xFFF38BA8),    // risky — matches the FAILED red used elsewhere
}

/** Map a [SafetyVerdict] to its UI [SafetyBand] (best-effort colour coding). */
fun bandForVerdict(verdict: SafetyVerdict): SafetyBand = when (verdict) {
    SafetyVerdict.VERIFIED, SafetyVerdict.LOCAL_CLEAR -> SafetyBand.GREEN
    SafetyVerdict.LIMITED, SafetyVerdict.UNKNOWN, SafetyVerdict.SUSPICIOUS -> SafetyBand.AMBER
    SafetyVerdict.RISKY -> SafetyBand.RED
}

/** True when a verdict warrants offering the "Move to Quarantine" action (amber/red, not green). */
fun isQuarantineWorthy(verdict: SafetyVerdict): Boolean = bandForVerdict(verdict) != SafetyBand.GREEN
