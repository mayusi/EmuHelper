package io.github.mayusi.emuhelper.desktop

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.Properties

/**
 * Desktop (Windows/JVM) settings persistence — the desktop analogue of the Android
 * [io.github.mayusi.emuhelper.data.storage.SettingsStore] (which is DataStore + Context bound and so
 * lives only in :app). This is a plain, file-backed [Properties] store under the app config dir
 * (`~/.emuhelper/settings.properties`), exposing each tunable as a synchronous [StateFlow] Compose can
 * observe — same reactive shape the Android ViewModels consume, minus DataStore/coroutines-on-write.
 *
 * PARITY vs the Android SettingsStore:
 *   • segments / concurrency  — carried over verbatim (same coerce ranges: 1..16, 1..4; defaults 16/2).
 *   • extractArchives         — carried over (desktop just lands the archive; extraction wiring is a
 *                               later batch, so this is persisted-but-not-yet-acted-on, and the UI says so).
 *   • adaptiveEngine          — carried over (the SHARED engine flag; default TRUE, matches Android v0.7+).
 *   • themeMode               — carried over as a plain enum name string (SYSTEM/LIGHT/DARK).
 *   • downloadFolder          — an absolute filesystem path (NOT a SAF tree Uri); "" means the default.
 *
 * OMITTED (Android-only, per the desktop scope): wifiOnly (no metered-network concept on desktop),
 * root / PServer, onboarding/coach/update-nag bookkeeping, favourite consoles, remote-catalog cache.
 *
 * Writes are best-effort and synchronous (the file is tiny); any I/O failure degrades to in-memory
 * only for the session, exactly like the desktop cookie/credential stores.
 */
class DesktopSettingsStore(private val file: File) {

    /** Desktop-local mirror of [io.github.mayusi.emuhelper.ui.theme.ThemeMode] (that enum lives in :app). */
    enum class ThemeMode { SYSTEM, LIGHT, DARK }

    companion object {
        const val DEFAULT_SEGMENTS = 16
        const val DEFAULT_CONCURRENCY = 2

        private const val KEY_SEGMENTS = "download_segments"
        private const val KEY_CONCURRENCY = "download_concurrency"
        private const val KEY_EXTRACT = "extract_archives"
        private const val KEY_ADAPTIVE = "adaptive_download_engine"
        private const val KEY_THEME = "theme_mode"
        private const val KEY_DOWNLOAD_FOLDER = "download_folder"
        private const val KEY_FAVORITE_CONSOLES = "favorite_consoles"
    }

    private val props: Properties = Properties().also { p ->
        try {
            if (file.exists()) file.inputStream().use { p.load(it) }
        } catch (_: Exception) { /* best-effort: start with defaults */ }
    }

    private val _segments = MutableStateFlow(readInt(KEY_SEGMENTS, DEFAULT_SEGMENTS).coerceIn(1, 16))
    val segments: StateFlow<Int> = _segments.asStateFlow()

    private val _concurrency = MutableStateFlow(readInt(KEY_CONCURRENCY, DEFAULT_CONCURRENCY).coerceIn(1, 4))
    val concurrency: StateFlow<Int> = _concurrency.asStateFlow()

    private val _extractArchives = MutableStateFlow(readBool(KEY_EXTRACT, false))
    val extractArchives: StateFlow<Boolean> = _extractArchives.asStateFlow()

    private val _adaptiveEngine = MutableStateFlow(readBool(KEY_ADAPTIVE, true))
    val adaptiveEngine: StateFlow<Boolean> = _adaptiveEngine.asStateFlow()

    private val _themeMode = MutableStateFlow(readTheme())
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    /** Absolute filesystem path of the download folder, or "" to signal "use the default". */
    private val _downloadFolder = MutableStateFlow(props.getProperty(KEY_DOWNLOAD_FOLDER, ""))
    val downloadFolder: StateFlow<String> = _downloadFolder.asStateFlow()

    /** Favourited console keys (pinned to the top of the console grid), mirroring Android's favourites. */
    private val _favoriteConsoles = MutableStateFlow(readFavoriteConsoles())
    val favoriteConsoles: StateFlow<Set<String>> = _favoriteConsoles.asStateFlow()

    // ---- setters (update flow + persist) ----------------------------------------------------------

    fun setSegments(v: Int) { val c = v.coerceIn(1, 16); _segments.value = c; persist(KEY_SEGMENTS, c.toString()) }
    fun setConcurrency(v: Int) { val c = v.coerceIn(1, 4); _concurrency.value = c; persist(KEY_CONCURRENCY, c.toString()) }
    fun setExtractArchives(v: Boolean) { _extractArchives.value = v; persist(KEY_EXTRACT, v.toString()) }
    fun setAdaptiveEngine(v: Boolean) { _adaptiveEngine.value = v; persist(KEY_ADAPTIVE, v.toString()) }
    fun setThemeMode(m: ThemeMode) { _themeMode.value = m; persist(KEY_THEME, m.name) }

    /** Set the download folder path; pass "" (or blank) to fall back to the default. */
    fun setDownloadFolder(path: String) {
        val p = path.trim()
        _downloadFolder.value = p
        persist(KEY_DOWNLOAD_FOLDER, p)
    }

    /** True if [key] is currently favourited. */
    fun isFavorite(key: String): Boolean = key in _favoriteConsoles.value

    /** Toggle [key]'s favourite state and persist. */
    fun toggleFavorite(key: String) {
        val current = _favoriteConsoles.value
        val updated = if (key in current) current - key else current + key
        _favoriteConsoles.value = updated
        persist(KEY_FAVORITE_CONSOLES, updated.joinToString(","))
    }

    /** Reset the download-speed tunables to their documented defaults (mirrors the Android reset). */
    fun resetDownloadDefaults() {
        setSegments(DEFAULT_SEGMENTS)
        setConcurrency(DEFAULT_CONCURRENCY)
    }

    /** Highest SAFE download settings (16 connections × 2 files) — mirrors the Android "Max throughput". */
    fun maxThroughput() {
        setSegments(16)
        setConcurrency(2)
    }

    // ---- helpers ----------------------------------------------------------------------------------

    private fun readInt(key: String, def: Int): Int =
        props.getProperty(key)?.toIntOrNull() ?: def

    private fun readBool(key: String, def: Boolean): Boolean =
        props.getProperty(key)?.toBooleanStrictOrNull() ?: def

    private fun readTheme(): ThemeMode =
        try { ThemeMode.valueOf(props.getProperty(KEY_THEME, ThemeMode.SYSTEM.name)) }
        catch (_: Exception) { ThemeMode.SYSTEM }

    private fun readFavoriteConsoles(): Set<String> =
        props.getProperty(KEY_FAVORITE_CONSOLES, "")
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

    private fun persist(key: String, value: String) {
        props.setProperty(key, value)
        try {
            file.parentFile?.mkdirs()
            file.outputStream().use { props.store(it, "EmuHelper desktop settings") }
        } catch (_: Exception) { /* best-effort: in-memory only for this session */ }
    }
}
