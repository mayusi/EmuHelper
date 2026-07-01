package io.github.mayusi.emuhelper.desktop

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Desktop (Windows/JVM) download-history persistence — the desktop analogue of the Android
 * [io.github.mayusi.emuhelper.data.storage.HistoryStore] (DataStore + Context bound, so :app-only).
 * Records completed / failed downloads as a JSON array in the app config dir
 * (`~/.emuhelper/history.json`), newest-first, capped at [MAX_ENTRIES].
 *
 * [DesktopHistoryEntry] is a desktop-local @Serializable mirror of the Android `HistoryEntry`
 * (which lives in :app): the fields relevant on desktop are kept (filename, subfolder, sizeBytes,
 * status, timestampMillis, name); SAF/identifier/console/md5 are omitted for now (no re-download flow
 * on desktop yet — that arrives with the browse/library batches). Same "single JSON blob, capped,
 * decode-error tolerant" contract as the Android store.
 *
 * Best-effort I/O: a read failure yields empty history (+ [decodeError] flips true so the UI can warn);
 * a write failure keeps the in-memory list so the current session's History screen still populates.
 */
class DesktopHistoryStore(private val file: File) {

    @Serializable
    data class DesktopHistoryEntry(
        val filename: String,
        val subfolder: String = "",
        val sizeBytes: Long = 0L,
        /** "DONE" or "FAILED" — string form, matching the Android entry's status field. */
        val status: String,
        val timestampMillis: Long,
        /** Human-readable game name; falls back to [filename] in the UI when blank. */
        val name: String = "",
        /** Failure detail for FAILED entries (desktop-only convenience; blank otherwise). */
        val error: String = "",
        /** IA identifier the file was downloaded from; needed to support "re-download". Default "" for old entries. */
        val identifier: String = "",
        /** Console key/label the entry belongs to. Default "" for old entries. */
        val console: String = "",
        /** Absolute folder the file was saved into; needed to support "open folder". Default "" for old entries. */
        val destFolder: String = "",
    )

    companion object {
        private const val MAX_ENTRIES = 100
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _decodeError = MutableStateFlow(false)
    /** True when the persisted history JSON was corrupt and could not be decoded. */
    val decodeError: StateFlow<Boolean> = _decodeError.asStateFlow()

    private val _entries = MutableStateFlow(load())
    /** All history entries, newest first. */
    val entries: StateFlow<List<DesktopHistoryEntry>> = _entries.asStateFlow()

    /** Append [newEntries] (newest-first), trim to [MAX_ENTRIES], and persist. No-op if empty. */
    fun addAll(newEntries: List<DesktopHistoryEntry>) {
        if (newEntries.isEmpty()) return
        val merged = (newEntries.sortedByDescending { it.timestampMillis } + _entries.value)
            .take(MAX_ENTRIES)
        _entries.value = merged
        persist(merged)
    }

    /** Convenience for a single terminal entry. */
    fun add(entry: DesktopHistoryEntry) = addAll(listOf(entry))

    /**
     * Convenience for recording a single terminal entry with the re-download / open-folder metadata
     * populated (identifier, console, destFolder), avoiding a long positional/named [DesktopHistoryEntry]
     * literal at call sites.
     */
    fun record(
        filename: String,
        name: String,
        sizeBytes: Long,
        status: String,
        identifier: String = "",
        console: String = "",
        destFolder: String = "",
        subfolder: String = "",
        error: String = "",
        timestampMillis: Long = System.currentTimeMillis(),
    ) = add(
        DesktopHistoryEntry(
            filename = filename,
            subfolder = subfolder,
            sizeBytes = sizeBytes,
            status = status,
            timestampMillis = timestampMillis,
            name = name,
            error = error,
            identifier = identifier,
            console = console,
            destFolder = destFolder,
        )
    )

    /** Wipe the entire history. */
    fun clear() {
        _entries.value = emptyList()
        persist(emptyList())
    }

    // ---- JSON I/O ---------------------------------------------------------------------------------

    private fun load(): List<DesktopHistoryEntry> {
        return try {
            if (!file.exists()) return emptyList()
            val raw = file.readText()
            if (raw.isBlank()) return emptyList()
            json.decodeFromString(ListSerializer(DesktopHistoryEntry.serializer()), raw)
        } catch (_: Exception) {
            _decodeError.value = true
            emptyList()
        }
    }

    private fun persist(list: List<DesktopHistoryEntry>) {
        try {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(ListSerializer(DesktopHistoryEntry.serializer()), list))
        } catch (_: Exception) { /* best-effort: in-memory only for this session */ }
    }
}
