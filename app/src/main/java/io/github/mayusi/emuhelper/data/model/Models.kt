package io.github.mayusi.emuhelper.data.model

import io.github.mayusi.emuhelper.data.safety.ScanReport
import java.io.Serializable
import kotlinx.serialization.Serializable as KSerializable

// NOTE: GameFile (the pure scan-result model) now lives in :shared commonMain
// (io.github.mayusi.emuhelper.data.model.GameFile) so the portable RemoteSource can return it on
// both Android and desktop. It is the SAME package, so every existing `import ...data.model.GameFile`
// and unqualified use in :app resolves unchanged. The UI-facing models below stay here.

@KSerializable
data class CuratedGame(
    val name: String,
    val filename: String = "",
    val size: Long = 0,
    val identifier: String = "",
    val source: String = "built_in",
    /** Platform key (e.g. "snes", "gcn") so downloads can be filed into a per-platform
     *  subfolder. Defaulted for backward-compat with lists exported before this field. */
    val console: String = "",
    /** Lowercase MD5 hex carried from [GameFile.md5] so the download task can verify the
     *  finished file's integrity. Defaulted to empty for back-compat with lists exported
     *  before this field (and for sources that don't publish a checksum). */
    val md5: String = ""
) : Serializable {
    /**
     * Stable composite key distinguishing games across consoles.
     * Deduplicated from 3 inline usages:
     *   - DownloadPreviewViewModel.keyOf()  (private fun on line ~51)
     *   - DownloadPreviewScreen ~126        (inline: "${it.identifier}/${it.filename}")
     *   - DownloadPreviewScreen ~207        (LazyColumn key lambda)
     *   - DownloadPreviewScreen ~212        (isOn check)
     * Adopt by replacing `"${g.identifier}/${g.filename}"` with `g.key`.
     */
    val key: String get() = "$identifier/$filename"
}

/**
 * A named, reusable selection of games the user can build once and download later.
 * Persisted as JSON via GameListStore and exportable/importable as a `.json` file.
 */
@KSerializable
data class GameList(
    val id: String,
    val name: String,
    val createdAt: Long,
    val games: List<CuratedGame>,
    /** SAF tree URI (as a string) for the per-list download folder override, or null to use the
     *  global download folder from [io.github.mayusi.emuhelper.data.storage.SettingsStore].
     *  Nullable with a default so existing saved lists (which lack this key) deserialise fine. */
    val customFolderUri: String? = null
) {
    val totalSize: Long get() = games.sumOf { it.size }
    val count: Int get() = games.size
}

enum class DownloadStatus { QUEUED, DOWNLOADING, PAUSED, DONE, FAILED, CANCELLED }

/**
 * Fully immutable so Compose detects per-task changes. Progress updates produce a
 * NEW instance via copy(); the old approach mutated `var` fields that were excluded
 * from data-class equals(), so the UI never saw individual rows change.
 */
data class DownloadTask(
    val id: String = "",
    val url: String,
    val displayPath: String,
    val filename: String,
    val size: Long = 0,
    val identifier: String = "",
    /** Full relative path within the source item (may include subdirs) — used for mirrors. */
    val relativeName: String = "",
    val region: String = "",
    /** Per-platform subfolder name this file is filed under, e.g. "SNES". */
    val subfolder: String = "",
    /** Platform key (e.g. "snes", "gcn") — carried from [CuratedGame.console] so history can
     *  reconstruct a [CuratedGame] for re-download without a separate lookup. */
    val console: String = "",
    /** Human-readable game name — carried from [CuratedGame.name] for display in history. */
    val name: String = "",
    /** Lowercase MD5 hex carried from [CuratedGame.md5] so the completion path can verify
     *  the finished file. Blank means "no checksum known" — verification is skipped. */
    val md5: String = "",
    val downloaded: Long = 0,
    val speed: Double = 0.0,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val error: String = "",
    /**
     * SECURITY SCANNER (best-effort, offline; see [io.github.mayusi.emuhelper.data.safety.SecurityScanner]).
     * Populated once a DONE task's file has been scanned. Null means "not scanned yet" (task still
     * running) OR "scan didn't run / errored" — a scan failure NEVER fails the download itself, it
     * just leaves this null so the UI has nothing to show rather than a false result.
     */
    val scanReport: ScanReport? = null,
    /** True once the user has moved this file into the on-disk Quarantine/ subfolder. */
    val quarantined: Boolean = false
) {
    /** Returns null when size is unknown so the UI can show an indeterminate bar. */
    val progressPercent: Float?
        get() = if (size > 0) (downloaded.toFloat() / size * 100f).coerceIn(0f, 100f) else null
}
