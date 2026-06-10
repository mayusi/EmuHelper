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
    val timestampMillis: Long
)

/**
 * Persists a capped list of recent download history entries as a single JSON blob in
 * DataStore, mirroring the pattern used by [GameListStore].
 *
 * - Capped at [MAX_ENTRIES] most recent entries (oldest dropped when the cap is exceeded).
 * - Thread-safe: DataStore serialises all edits internally.
 */
@Singleton
class HistoryStore @Inject constructor(@ApplicationContext private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    companion object {
        private val KEY_HISTORY = stringPreferencesKey("download_history_v1")
        private const val MAX_ENTRIES = 100
    }

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
            emptyList()
        }
    }
}
