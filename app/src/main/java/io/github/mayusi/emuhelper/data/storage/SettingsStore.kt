package io.github.mayusi.emuhelper.data.storage

import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.mayusi.emuhelper.ui.theme.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "emuhelper_settings")

@Singleton
class SettingsStore @Inject constructor(@ApplicationContext private val context: Context) {

    companion object {
        private val KEY_DOWNLOAD_FOLDER = stringPreferencesKey("download_folder_uri")
        private val KEY_HAS_ONBOARDED = booleanPreferencesKey("has_onboarded")
        private val KEY_SEEN_COACH = booleanPreferencesKey("has_seen_coach")
        private val KEY_CONCURRENCY = intPreferencesKey("download_concurrency")
        private val KEY_SEGMENTS = intPreferencesKey("download_segments")
        private val KEY_EXTRACT = booleanPreferencesKey("extract_archives")
        private val KEY_SEEN_SETUP_DISCLAIMER = booleanPreferencesKey("seen_setup_disclaimer")
        private val KEY_SETUP_STAGING_FOLDER = stringPreferencesKey("setup_staging_folder_uri")
        val KEY_LAST_UPDATE_CHECK = longPreferencesKey("last_update_check_ts")
        val KEY_LAST_CONSOLES = stringSetPreferencesKey("last_selected_consoles")
        // ---- Theme mode (Feature 1) -------------------------------------------
        private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
    }

    /** Persisted SAF URI for the user-chosen download folder, or null if using app-private dir. */
    val downloadFolder: Flow<Uri?> = context.settingsStore.data.map {
        it[KEY_DOWNLOAD_FOLDER]?.let { s -> Uri.parse(s) }
    }

    val hasOnboarded: Flow<Boolean> = context.settingsStore.data.map { it[KEY_HAS_ONBOARDED] ?: false }

    val hasSeenCoach: Flow<Boolean> = context.settingsStore.data.map { it[KEY_SEEN_COACH] ?: false }

    val concurrency: Flow<Int> = context.settingsStore.data.map { it[KEY_CONCURRENCY] ?: 2 }

    /** Parallel HTTP connections per file. The source host may throttle each connection;
     *  spreading load across multiple connections to fast nodes maximises throughput.
     *  Default 16; allow up to 32 (max throughput). */
    val segments: Flow<Int> = context.settingsStore.data.map { it[KEY_SEGMENTS] ?: 16 }

    val extractArchives: Flow<Boolean> = context.settingsStore.data.map { it[KEY_EXTRACT] ?: false }

    suspend fun setDownloadFolder(uri: Uri?) {
        context.settingsStore.edit {
            if (uri != null) it[KEY_DOWNLOAD_FOLDER] = uri.toString()
            else it.remove(KEY_DOWNLOAD_FOLDER)
        }
    }

    suspend fun setOnboarded(value: Boolean) {
        context.settingsStore.edit { it[KEY_HAS_ONBOARDED] = value }
    }

    suspend fun setSeenCoach(value: Boolean) {
        context.settingsStore.edit { it[KEY_SEEN_COACH] = value }
    }

    suspend fun setConcurrency(value: Int) {
        context.settingsStore.edit { it[KEY_CONCURRENCY] = value.coerceIn(1, 4) }
    }

    suspend fun setSegments(value: Int) {
        context.settingsStore.edit { it[KEY_SEGMENTS] = value.coerceIn(1, 16) }
    }

    suspend fun setExtractArchives(value: Boolean) {
        context.settingsStore.edit { it[KEY_EXTRACT] = value }
    }

    val seenSetupDisclaimer: Flow<Boolean> = context.settingsStore.data.map { it[KEY_SEEN_SETUP_DISCLAIMER] ?: false }

    val setupStagingFolder: Flow<Uri?> = context.settingsStore.data.map {
        it[KEY_SETUP_STAGING_FOLDER]?.let { s -> Uri.parse(s) }
    }

    suspend fun setSeenSetupDisclaimer(value: Boolean) {
        context.settingsStore.edit { it[KEY_SEEN_SETUP_DISCLAIMER] = value }
    }

    suspend fun setSetupStagingFolder(uri: Uri?) {
        context.settingsStore.edit {
            if (uri != null) it[KEY_SETUP_STAGING_FOLDER] = uri.toString()
            else it.remove(KEY_SETUP_STAGING_FOLDER)
        }
    }

    // ---- Update check timestamp -------------------------------------------

    val lastUpdateCheck: Flow<Long> = context.settingsStore.data.map { it[KEY_LAST_UPDATE_CHECK] ?: 0L }

    suspend fun setLastUpdateCheck(timestamp: Long) {
        context.settingsStore.edit { it[KEY_LAST_UPDATE_CHECK] = timestamp }
    }

    // ---- Last selected consoles -------------------------------------------

    val lastSelectedConsoles: Flow<Set<String>> = context.settingsStore.data.map { it[KEY_LAST_CONSOLES] ?: emptySet() }

    suspend fun setLastSelectedConsoles(consoles: Set<String>) {
        context.settingsStore.edit { it[KEY_LAST_CONSOLES] = consoles }
    }

    // ---- Theme mode (Feature 1) -------------------------------------------

    val themeMode: Flow<ThemeMode> = context.settingsStore.data.map { prefs ->
        prefs[KEY_THEME_MODE]?.let { s ->
            try { ThemeMode.valueOf(s) } catch (_: IllegalArgumentException) { ThemeMode.SYSTEM }
        } ?: ThemeMode.SYSTEM
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.settingsStore.edit { it[KEY_THEME_MODE] = mode.name }
    }
}
