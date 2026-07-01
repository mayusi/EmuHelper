package io.github.mayusi.emuhelper.data.model

import java.io.Serializable

/**
 * One downloadable file within a source item (Internet Archive identifier). This is the PURE data
 * model the portable [io.github.mayusi.emuhelper.data.source.RemoteSource] returns from its scan
 * (`fetchFileList`) — no Android dependencies — so it lives in :shared commonMain alongside
 * RemoteSource. The richer, UI-facing models (CuratedGame, GameList, DownloadTask, DownloadStatus)
 * stay in :app under this same package; Kotlin/JVM allows a package to span the two modules.
 */
data class GameFile(
    val name: String,
    val filename: String,
    val size: Long,
    val identifier: String,
    val sourceUrl: String = "",
    /** Lowercase MD5 hex from the source item's metadata, used to verify a finished
     *  download. Defaulted to empty for back-compat and for files whose metadata
     *  omits a checksum (verification is skipped when blank). */
    val md5: String = ""
) : Serializable
