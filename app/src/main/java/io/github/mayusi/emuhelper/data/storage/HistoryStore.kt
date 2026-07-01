package io.github.mayusi.emuhelper.data.storage

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private val Context.historyDataStore: DataStore<Preferences> by preferencesDataStore(name = "emuhelper_history")

/** A single completed (or failed) download recorded for display in the History screen. */
@Serializable
data class HistoryEntry(
    val filename: String,
    val subfolder: String,
    val sizeBytes: Long,
    /** String form of [io.github.mayusi.emuhelper.data.model.DownloadStatus], e.g. "DONE" or "FAILED". */
    val status: String,
    val timestampMillis: Long,
    /** Source identifier (e.g. "nointro-snes"). Blank for entries recorded before this field was added.
     *  When blank, re-download is not offered since there is no identifier to reconstruct a URL. */
    val identifier: String = "",
    /** Platform key (e.g. "snes", "gcn"). Blank for legacy entries. */
    val console: String = "",
    /** Human-readable game name. Falls back to [filename] in the UI when blank. */
    val name: String = "",
    /** Lowercase MD5 hex reference hash carried from the source item's metadata
     *  (via [io.github.mayusi.emuhelper.data.model.DownloadTask.md5]). Used by the on-disk
     *  Library screen's integrity re-check to compare against a freshly recomputed digest of
     *  the file on disk. Defaulted to "" for back-compat — same pattern as the identifier/console
     *  additions above, so existing persisted entries decode unchanged.
     *
     *  TODO(DownloadManager): recordBatchHistory() in DownloadManager currently maps each
     *  terminal DownloadTask -> HistoryEntry but does NOT yet pass `md5 = task.md5`. Until that
     *  one-line addition is made (DownloadManager internals are out of scope for this change),
     *  this field stays "" for newly recorded entries, so the Library's integrity re-check shows
     *  "no reference hash" rather than a match/mismatch. The on-disk MD5 is still recomputed and
     *  displayed; only the reference comparison waits on DownloadManager populating this. */
    val md5: String = "",
    /** Best-effort SECURITY SCANNER verdict name (e.g. "LOCAL_CLEAR", "RISKY"), carried from
     *  [io.github.mayusi.emuhelper.data.model.DownloadTask.scanReport]. Blank means "not scanned /
     *  scan unavailable" — never treated as a clean result by the UI. */
    val scanVerdict: String = ""
)

/**
 * Persists a capped list of recent download history entries as a single JSON blob in
 * DataStore, mirroring the pattern used by [GameListStore].
 *
 * - Capped at [MAX_ENTRIES] most recent entries (oldest dropped when the cap is exceeded).
 * - Thread-safe: DataStore serialises all edits internally.
 */
@Singleton
class HistoryStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val json: Json
) {

    companion object {
        private val KEY_HISTORY = stringPreferencesKey("download_history_v1")
        private const val MAX_ENTRIES = 100
    }

    /**
     * FIX 5: Set to true when a decode exception is caught during history restore.
     * Flips true on the first corrupt-JSON read and stays true for the session.
     * UI layers (e.g. HistoryScreen) should observe this and surface a
     * "download history couldn't be read" notice rather than silently showing empty history.
     * The store still returns emptyList() on failure (safe, no crash).
     */
    private val _decodeError = MutableStateFlow(false)
    val decodeError: StateFlow<Boolean> = _decodeError

    /** All history entries, newest first. */
    val entries: Flow<List<HistoryEntry>> = context.historyDataStore.data.map { prefs ->
        decode(prefs[KEY_HISTORY])
    }

    /**
     * Append [newEntries] to the history, then trim to [MAX_ENTRIES] keeping newest.
     * If [newEntries] is empty this is a no-op.
     */
    suspend fun addAll(newEntries: List<HistoryEntry>) {
        if (newEntries.isEmpty()) return
        context.historyDataStore.edit { prefs ->
            val current = decode(prefs[KEY_HISTORY]).toMutableList()
            current.addAll(0, newEntries.sortedByDescending { it.timestampMillis })
            // Keep only the most recent MAX_ENTRIES
            prefs[KEY_HISTORY] = encode(current.take(MAX_ENTRIES))
        }
    }

    /** Wipe the entire history. */
    suspend fun clear() {
        context.historyDataStore.edit { prefs ->
            prefs[KEY_HISTORY] = encode(emptyList())
        }
    }

    // ---- JSON helpers -------------------------------------------------------

    private fun encode(list: List<HistoryEntry>): String =
        json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(HistoryEntry.serializer()),
            list
        )

    private fun decode(raw: String?): List<HistoryEntry> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            json.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(HistoryEntry.serializer()),
                raw
            )
        } catch (e: Exception) {
            Log.w("EmuHelper", "Decoding history failed; returning empty", e)
            // FIX 5: surface the decode failure so UI can warn the user.
            _decodeError.value = true
            emptyList()
        }
    }
}
