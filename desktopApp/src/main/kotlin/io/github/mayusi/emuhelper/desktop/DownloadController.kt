package io.github.mayusi.emuhelper.desktop

import io.github.mayusi.emuhelper.data.config.Catalog
import io.github.mayusi.emuhelper.data.model.GameFile
import io.github.mayusi.emuhelper.data.safety.ScanReport
import io.github.mayusi.emuhelper.data.safety.SecurityScanner
import io.github.mayusi.emuhelper.data.source.LoginResult
import io.github.mayusi.emuhelper.data.source.RemoteSource
import io.github.mayusi.emuhelper.data.source.SourceHealth
import io.github.mayusi.emuhelper.data.source.SourceHealthChecker
import io.github.mayusi.emuhelper.di.PersistentCookieJar
import io.github.mayusi.emuhelper.platform.AppInfo
import io.github.mayusi.emuhelper.platform.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The desktop app's single state holder + engine driver. It is the desktop analogue of the Android
 * ViewModel + DownloadManager, but deliberately lean: it owns ONE [RemoteSource] (the same portable
 * class the Android app and the Phase-2 headless proof use) and exposes an immutable [UiState] as a
 * [StateFlow] that Compose observes. All I/O runs on the supplied [scope] (Dispatchers.IO); the UI
 * thread only reads state.
 *
 * The download flow mirrors the PROVEN Phase-2 path exactly:
 *   RemoteSource.fetchFileList → per-file RemoteSource.mirrorUrls → RemoteSource.downloadFileSegmented
 * writing to a plain java.io.File under the user-chosen destination folder. No SAF, no Android
 * Context, no RAR/root/PServer (Android-only) — a .rar just lands on disk as-is.
 */
class DownloadController(private val scope: CoroutineScope) {

    /** One console the user can browse, projected from the shared [Catalog]. */
    data class ConsoleItem(
        val key: String,
        val display: String,
        val color: Long,
        val sourceCount: Int,
    )

    /** A file in the current scan, plus whether the user has ticked it for download. */
    data class FileRow(
        val file: GameFile,
        val selected: Boolean = false,
    ) : FileRowLike {
        override val displayName: String get() = file.name
        override val sizeBytes: Long get() = file.size
        override val isSelected: Boolean get() = selected
    }

    /** Per-file live download status shown in the progress screen. */
    data class DownloadRow(
        val name: String,
        val totalBytes: Long,
        val doneBytes: Long = 0L,
        val speedBytesPerSec: Double = 0.0,
        val state: DownloadState = DownloadState.QUEUED,
        val error: String? = null,
        val savedPath: String? = null,
        /** Best-effort security scan of the finished file (null until DONE + scanned; stays null on
         *  a scan error — a scan failure never blocks or fails the download). */
        val scan: ScanReport? = null,
        /** The GameFile this row came from, so a failed row can be retried / re-downloaded and a
         *  finished row can offer "open folder". */
        val source: GameFile? = null,
        /** Absolute folder this file was saved into (for the "open folder" action). */
        val destFolder: String? = null,
    )

    enum class DownloadState { QUEUED, DOWNLOADING, PAUSED, DONE, FAILED, CANCELLED }

    /** One row from the Internet Archive advanced-search response (desktop mirror of IaSearchResult). */
    data class SearchResult(
        val identifier: String,
        val title: String,
        val mediatype: String,
        val downloads: Int?,
    )

    /** Distinct UI phases for the search screen (desktop mirror of the Android SearchUiState). */
    sealed interface SearchState {
        data object Idle : SearchState
        data object Loading : SearchState
        data class Results(val items: List<SearchResult>) : SearchState
        data object Empty : SearchState
        data class Error(val message: String) : SearchState
    }

    /**
     * Which screen the single window is showing. CONSOLES / FILES / DOWNLOADS are the original
     * Phase-3a browse→pick→progress flow; SETTINGS / QUEUE / HISTORY are the Phase-3b core-management
     * screens (QUEUE is the standalone live download queue reachable from the nav, distinct from the
     * in-flow DOWNLOADS view which the browse flow lands on). LOGIN / SEARCH / LIBRARY / ABOUT are the
     * Phase-3b-batch-2 screens (SEARCH + LIBRARY funnel their picked item's files into FILES, the same
     * way the Android app funnels search/list selections through the shared picker).
     */
    enum class Screen {
        CONSOLES, FILES, DOWNLOADS, SETTINGS, QUEUE, HISTORY, LOGIN, SEARCH, LIBRARY, ABOUT,
        SOURCE_HEALTH, LIBRARY_SCAN,
    }

    /** UI phases for the source-health screen (mirror of the Android SourceHealth screen states). */
    sealed interface HealthState {
        data object Idle : HealthState
        data class Running(val done: Int, val total: Int) : HealthState
        data class Done(val results: List<SourceHealth>) : HealthState
        data class Error(val message: String) : HealthState
    }

    /** One scanned local file in the library-verify screen (B2): name, size, hash, and best-effort scan. */
    data class ScannedFile(
        val name: String,
        val path: String,
        val sizeBytes: Long,
        val sha256: String?,
        val report: ScanReport?,
        /** "OK" if the SHA-256 matches a history-recorded expectation; "MISMATCH"/"UNKNOWN" otherwise. */
        val integrity: String,
    )

    /** UI phases for the library-verify (local scan) screen (B2). */
    sealed interface LibraryScanState {
        data object Idle : LibraryScanState
        data class Running(val done: Int, val total: Int, val folder: String) : LibraryScanState
        data class Done(val folder: String, val files: List<ScannedFile>) : LibraryScanState
        data class Error(val message: String) : LibraryScanState
    }

    data class UiState(
        val screen: Screen = Screen.CONSOLES,
        /** The screen to return to when leaving a nav destination (SETTINGS/HISTORY) via Back. */
        val previousScreen: Screen = Screen.CONSOLES,
        val consoles: List<ConsoleItem> = emptyList(),
        val selectedConsole: ConsoleItem? = null,
        val scanning: Boolean = false,
        val scanError: String? = null,
        val files: List<FileRow> = emptyList(),
        /** Label shown as the FILES-screen title when it was reached from search / a saved list
         *  (rather than a catalog console). Null → fall back to the selected console's name. */
        val filesTitle: String? = null,
        /** Where the FILES-screen Back button returns to (CONSOLES for a console, else SEARCH/LIBRARY). */
        val filesReturnScreen: Screen = Screen.CONSOLES,
        val destFolder: String,
        val downloads: List<DownloadRow> = emptyList(),
        val downloading: Boolean = false,
        val paused: Boolean = false,
        // ---- login / account ----
        val loggedIn: Boolean = false,
        val accountEmail: String = "",
        val loginLoading: Boolean = false,
        val loginError: String = "",
        // ---- search ----
        val searchState: SearchState = SearchState.Idle,
        /** Set while a tapped search / list item is being loaded into the FILES screen. */
        val loadingIdentifier: Boolean = false,
        // ---- FILES-screen filter/sort (A1–A3) ----
        val fileSearchQuery: String = "",
        val fileSort: FileSort = FileSort.NAME_ASC,
        val selectionFilter: SelectionFilter = SelectionFilter.ALL,
        // ---- source health (B1) ----
        val healthState: HealthState = HealthState.Idle,
        // ---- library verify / local scan (B2) ----
        val libraryScanState: LibraryScanState = LibraryScanState.Idle,
    ) {
        val selectedCount: Int get() = files.count { it.selected }

        /** The FILES rows actually rendered after applying the search query, selection filter, and sort. */
        val visibleFiles: List<FileRow>
            get() = filterAndSortFiles(files, fileSearchQuery, fileSort, selectionFilter)

        /** Overall progress across the current batch (0f..1f). Files with unknown size count as
         *  complete only once DONE; in-flight bytes are summed against summed totals. */
        val overallProgress: Float
            get() {
                if (downloads.isEmpty()) return 0f
                val total = downloads.sumOf { if (it.totalBytes > 0) it.totalBytes else 0L }
                if (total <= 0L) {
                    val finished = downloads.count { it.state == DownloadState.DONE }
                    return finished.toFloat() / downloads.size
                }
                val done = downloads.sumOf {
                    when (it.state) {
                        DownloadState.DONE -> if (it.totalBytes > 0) it.totalBytes else 0L
                        else -> it.doneBytes.coerceAtMost(if (it.totalBytes > 0) it.totalBytes else it.doneBytes)
                    }
                }
                return (done.toFloat() / total).coerceIn(0f, 1f)
            }

        val activeCount: Int get() = downloads.count {
            it.state == DownloadState.QUEUED || it.state == DownloadState.DOWNLOADING || it.state == DownloadState.PAUSED
        }
    }

    private val remote: RemoteSource

    /** The shared OkHttp client (retained so the source-health checker can reuse its pool). */
    private val okHttp: OkHttpClient

    /** Best-effort local security scanner, constructed once (bundled hash list). Runs off-thread. */
    private val scanner = SecurityScanner()

    /** The persistent cookie jar (its [PersistentCookieJar.loggedIn] flow drives the account indicator). */
    private val cookieJar: PersistentCookieJar

    /** Desktop file-backed credentials (saved on login for silent re-auth, cleared on logout). */
    private val credentials: FileAuthCredentials

    /**
     * Cookie-LESS client for the public advancedsearch API (must not carry the archive.org session
     * cookies) — same pattern as the Android SearchViewModel. Shares the parent client's pool.
     */
    private val searchClient: OkHttpClient

    private val searchJson = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Desktop-local settings + history + saved-list stores (analogues of the :app DataStore stores). */
    val settings: DesktopSettingsStore
    val history: DesktopHistoryStore
    val lists: DesktopListStore

    /** App build facts for the About screen (version + debug flag), matching the RemoteSource AppInfo.
     *  debug=false for a shipped build so RemoteSource does not log login-attempt internals (code /
     *  cookie state). This is the sole "debug" gate on desktop — the login probe at RemoteSource.kt. */
    val appInfo: AppInfo = AppInfo(versionName = APP_VERSION, debug = false)

    private val _state: MutableStateFlow<UiState>
    val state: StateFlow<UiState> get() = _state.asStateFlow()

    private var activeDownloadJob: Job? = null
    private var searchJob: Job? = null
    private var loadJob: Job? = null

    /** Cooperative cancel + pause flags read by the engine's isCancelled callback each chunk. */
    private val cancelFlag = AtomicBoolean(false)
    private val pauseFlag = AtomicBoolean(false)

    init {
        // Install the desktop stderr log sink so the shared stack's logging surfaces (idempotent).
        runCatching { Log.install(DesktopLog) }

        // Persistent config dir under the user's home (cookies + optional IA credentials survive runs).
        val configDir = File(System.getProperty("user.home"), ".emuhelper").apply { mkdirs() }
        cookieJar = PersistentCookieJar(FileCookieStore(File(configDir, "cookies.txt")))
        credentials = FileAuthCredentials(File(configDir, "credentials.properties"))
        val client = desktopOkHttpClient(cookieJar)
        okHttp = client
        searchClient = client.newBuilder().cookieJar(CookieJar.NO_COOKIES).build()
        remote = RemoteSource(
            okHttpClient = client,
            cookieJar = cookieJar,
            authStore = credentials,
            appInfo = appInfo,
        )

        settings = DesktopSettingsStore(File(configDir, "settings.properties"))
        history = DesktopHistoryStore(File(configDir, "history.json"))
        lists = DesktopListStore(File(configDir, "game_lists.json"))

        // Default download destination: the persisted folder if set, else ~/Downloads/EmuHelper.
        val defaultDest = File(File(System.getProperty("user.home"), "Downloads"), "EmuHelper")
        val initialDest = settings.downloadFolder.value.ifBlank { defaultDest.absolutePath }
        _state = MutableStateFlow(
            UiState(
                consoles = loadConsoles(),
                destFolder = initialDest,
                loggedIn = cookieJar.hasCookies(),
                accountEmail = credentials.savedEmailNow(),
            )
        )

        // Keep the account indicator in sync with the cookie jar (login / logout / expiry).
        scope.launch {
            cookieJar.loggedIn.collect { logged ->
                _state.value = _state.value.copy(
                    loggedIn = logged,
                    accountEmail = if (logged) credentials.savedEmailNow() else _state.value.accountEmail,
                )
            }
        }
    }

    /** Project the shared [Catalog] into the display list (baked-in order, only mapped consoles). */
    private fun loadConsoles(): List<ConsoleItem> {
        val data = Catalog.catalogFlow.value
        return data.displayOrder.mapNotNull { key ->
            val desc = data.consoles[key] ?: return@mapNotNull null
            ConsoleItem(
                key = key,
                display = desc.display,
                color = data.consoleColors[key] ?: 0xFF6c7086,
                sourceCount = data.iaLinks[key]?.size ?: 0,
            )
        }
    }

    // ---- navigation -------------------------------------------------------------------------------

    fun openConsole(console: ConsoleItem) {
        _state.value = _state.value.copy(
            screen = Screen.FILES,
            selectedConsole = console,
            filesTitle = null, // a catalog console → FILES title falls back to the console name
            filesReturnScreen = Screen.CONSOLES,
            files = emptyList(),
            scanError = null,
            fileSearchQuery = "",
            fileSort = FileSort.NAME_ASC,
            selectionFilter = SelectionFilter.ALL,
        )
        scan(console)
    }

    /** Leave the FILES screen back to whatever opened it (a console → CONSOLES, else SEARCH/LIBRARY). */
    fun backToConsoles() {
        _state.value = _state.value.copy(
            screen = _state.value.filesReturnScreen,
            selectedConsole = null,
        )
    }

    fun showDownloads() {
        _state.value = _state.value.copy(screen = Screen.DOWNLOADS)
    }

    fun backToFiles() {
        _state.value = _state.value.copy(screen = Screen.FILES)
    }

    /** The set of top-level nav destinations (reachable from the header; Back returns to their opener). */
    private val navDestinations = setOf(
        Screen.SETTINGS, Screen.QUEUE, Screen.HISTORY, Screen.LOGIN, Screen.SEARCH, Screen.LIBRARY, Screen.ABOUT,
    )

    /** Open a top-level nav destination, remembering where to return on Back. */
    fun navigateTo(target: Screen) {
        val cur = _state.value.screen
        // Don't record transient nav destinations as a "return to" target for other nav destinations.
        val prev = if (cur in navDestinations) _state.value.previousScreen else cur
        _state.value = _state.value.copy(screen = target, previousScreen = prev)
    }

    /** Leave a nav destination (SETTINGS/HISTORY/QUEUE) back to whatever screen opened it. */
    fun navigateBack() {
        _state.value = _state.value.copy(screen = _state.value.previousScreen)
    }

    fun setDestFolder(path: String) {
        _state.value = _state.value.copy(destFolder = path)
        settings.setDownloadFolder(path)
    }

    // ---- login / account (adapts app/ui/login/LoginScreen.kt's LoginViewModel) --------------------
    // The Android LoginViewModel: remoteSource.login(email,password) → on Success, authStore
    // .saveCredentials(...). Desktop mirrors that: RemoteSource.login (already shared) then
    // FileAuthCredentials.save so the cookie jar + silent re-auth work exactly as on Android.
    // The Android "create account" path uses a WebView (SignupWebViewScreen); desktop has no WebView,
    // so createAccount() is a BROWSER HANDOFF (Desktop.browse to the IA signup page) instead.

    /** Attempt an IA login; on success persist creds (for re-auth) and return to the previous screen. */
    fun login(email: String, password: String, rememberMe: Boolean) {
        val e = email.trim()
        if (e.isBlank() || password.isBlank()) {
            _state.value = _state.value.copy(loginError = "Enter email and password.")
            return
        }
        _state.value = _state.value.copy(loginLoading = true, loginError = "")
        scope.launch {
            when (val r = remote.login(e, password)) {
                is LoginResult.Success -> {
                    credentials.save(e, password, rememberMe)
                    // cookieJar.loggedIn collector flips loggedIn; set email + leave the login screen.
                    _state.value = _state.value.copy(
                        loginLoading = false,
                        loginError = "",
                        loggedIn = true,
                        accountEmail = e,
                        screen = _state.value.previousScreen,
                    )
                }
                is LoginResult.Failed ->
                    _state.value = _state.value.copy(loginLoading = false, loginError = r.message)
            }
        }
    }

    /** Sign out: clear cookies + stored credentials so the next session starts fresh. */
    fun logout() {
        cookieJar.clear()
        credentials.clear()
        _state.value = _state.value.copy(loggedIn = false, accountEmail = "")
    }

    // ---- search the Internet Archive (adapts app/ui/search/SearchAllScreen.kt) ---------------------
    // The Android search logic is pure + lives in SearchViewModel's companion (buildSearchUrl /
    // parseSearchResults) — replicated verbatim below so behaviour is identical. Tapping a result
    // funnels through openIdentifier() (the desktop analogue of BrowseViewModel.loadIdentifierIntoPicker):
    // fetch the item's file list → show the existing FILES screen so the user picks + downloads.

    fun search(query: String) {
        val q = query.trim()
        searchJob?.cancel()
        if (q.isEmpty()) {
            _state.value = _state.value.copy(searchState = SearchState.Idle)
            return
        }
        _state.value = _state.value.copy(searchState = SearchState.Loading)
        searchJob = scope.launch {
            try {
                val body = withContext(Dispatchers.IO) {
                    val req = Request.Builder()
                        .url(buildSearchUrl(q))
                        .header("Accept", "application/json")
                        .header("User-Agent", "EmuHelper")
                        .build()
                    searchClient.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                        resp.body?.string() ?: throw IOException("Empty response")
                    }
                }
                val results = parseSearchResults(body, searchJson)
                _state.value = _state.value.copy(
                    searchState = if (results.isEmpty()) SearchState.Empty else SearchState.Results(results),
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("EmuHelper", "IA search failed", e)
                _state.value = _state.value.copy(
                    searchState = SearchState.Error(
                        if (e is IOException) "Couldn't reach the Internet Archive. Check your connection."
                        else "Search failed. Try a different query.",
                    ),
                )
            }
        }
    }

    fun clearSearch() {
        searchJob?.cancel()
        _state.value = _state.value.copy(searchState = SearchState.Idle)
    }

    /**
     * Load one archive.org item's files into the FILES screen (the desktop analogue of the Android
     * loadIdentifierIntoPicker). Reuses the SAME RemoteSource.getIdentifier + fetchFileList as scan.
     * On success it navigates to FILES with a custom title; on failure it reports via [onError] and
     * stays put. Used by both a tapped search result and a saved-list game.
     */
    fun openIdentifier(input: String, title: String, onError: (String) -> Unit) {
        val raw = input.trim()
        if (raw.isEmpty()) { onError("Enter an archive.org item link or identifier."); return }
        val identifier = remote.getIdentifier(raw)
        if (identifier.isBlank() || identifier.contains(' ') || identifier.contains('/')) {
            onError("That doesn't look like a valid archive.org link or identifier."); return
        }
        loadJob?.cancel()
        _state.value = _state.value.copy(loadingIdentifier = true)
        loadJob = scope.launch {
            try {
                val files = remote.fetchFileList(identifier)
                if (files.isEmpty()) {
                    _state.value = _state.value.copy(loadingIdentifier = false)
                    onError("No downloadable files found for that item.")
                    return@launch
                }
                val rows = files.sortedBy { it.name.lowercase() }.map { FileRow(it) }
                _state.value = _state.value.copy(
                    loadingIdentifier = false,
                    screen = Screen.FILES,
                    selectedConsole = null,
                    filesTitle = title.ifBlank { identifier },
                    filesReturnScreen = Screen.SEARCH,
                    files = rows,
                    scanning = false,
                    scanError = null,
                    fileSearchQuery = "",
                    fileSort = FileSort.NAME_ASC,
                    selectionFilter = SelectionFilter.ALL,
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(loadingIdentifier = false)
                onError("${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    // ---- saved lists (adapts app/ui/lists/ListLibraryScreen.kt + ListViewModel) --------------------
    // A saved list's games (identifier + filename + size + md5) map 1:1 onto GameFile, so opening a
    // list just projects its games into the FILES screen for pick + download — the desktop analogue of
    // ListViewModel.loadForDownload → the picker/preview flow. CRUD beyond view/delete/import is deferred.

    /** Load a saved list's games into the FILES screen (all games pre-selected) for download. */
    fun openList(list: DesktopListStore.DesktopGameList) {
        val rows = list.games
            .filter { it.filename.isNotBlank() && it.identifier.isNotBlank() }
            .map { g ->
                FileRow(
                    file = GameFile(
                        name = g.name.ifBlank { g.filename },
                        filename = g.filename,
                        size = g.size,
                        identifier = g.identifier,
                        md5 = g.md5,
                    ),
                    selected = true,
                )
            }
        _state.value = _state.value.copy(
            screen = Screen.FILES,
            selectedConsole = null,
            filesTitle = list.name,
            filesReturnScreen = Screen.LIBRARY,
            files = rows,
            scanning = false,
            scanError = if (rows.isEmpty()) "This list has no downloadable items." else null,
            fileSearchQuery = "",
            fileSort = FileSort.NAME_ASC,
            selectionFilter = SelectionFilter.ALL,
        )
    }

    fun deleteList(id: String) = lists.delete(id)

    // ---- list management (B3): create / rename / set-folder / add-item ----------------------------

    /** Create a new empty saved list with the given name; returns the new list's id (blank name → no-op, ""). */
    fun createList(name: String): String {
        val n = name.trim()
        if (n.isEmpty()) return ""
        val id = java.util.UUID.randomUUID().toString()
        lists.save(
            DesktopListStore.DesktopGameList(
                id = id, name = n, createdAt = System.currentTimeMillis(), games = emptyList(),
            )
        )
        return id
    }

    fun renameList(id: String, newName: String) = lists.rename(id, newName)
    fun setListFolder(id: String, folderPath: String?) = lists.setListFolder(id, folderPath)
    fun addItemToList(id: String, item: DesktopListStore.DesktopCuratedGame) = lists.addItemToList(id, item)

    // ---- favourite consoles (A6) -----------------------------------------------------------------

    fun toggleFavoriteConsole(key: String) = settings.toggleFavorite(key)

    // ---- source health (B1) ----------------------------------------------------------------------

    private var healthJob: Job? = null

    /** Run the shared [SourceHealthChecker] over every configured endpoint, streaming progress into state. */
    fun runSourceHealthCheck() {
        healthJob?.cancel()
        _state.value = _state.value.copy(healthState = HealthState.Running(0, 0))
        healthJob = scope.launch {
            try {
                val checker = SourceHealthChecker(okHttp)
                val results = checker.checkAll { done, total ->
                    _state.value = _state.value.copy(healthState = HealthState.Running(done, total))
                }
                _state.value = _state.value.copy(
                    healthState = HealthState.Done(results.sortedWith(compareBy({ it.console }, { it.url }))),
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    healthState = HealthState.Error("${e.javaClass.simpleName}: ${e.message}"),
                )
            }
        }
    }

    // ---- library verify / local scan (B2) --------------------------------------------------------

    private var libraryScanJob: Job? = null

    /**
     * Scan every regular file directly under [folderPath]: compute its SHA-256 (via the same scanner),
     * run the best-effort [SecurityScanner], and compare the hash against any history-recorded hash for
     * the same filename to flag tampering. Streams progress into [UiState.libraryScanState].
     *
     * Simplifications vs the Android library scan: non-recursive (top-level files only), and integrity
     * comparison is by SHA-256 recorded in history — history currently records size/status but NOT the
     * source MD5, so "OK/MISMATCH" is only meaningful once a hash is known; otherwise it reads "UNKNOWN".
     */
    fun scanLibraryFolder(folderPath: String) {
        libraryScanJob?.cancel()
        val dir = File(folderPath)
        if (!dir.isDirectory) {
            _state.value = _state.value.copy(libraryScanState = LibraryScanState.Error("Not a folder: $folderPath"))
            return
        }
        _state.value = _state.value.copy(libraryScanState = LibraryScanState.Running(0, 0, folderPath))
        libraryScanJob = scope.launch {
            try {
                val files = withContext(Dispatchers.IO) {
                    dir.listFiles()?.filter { it.isFile }?.sortedBy { it.name.lowercase() } ?: emptyList()
                }
                val total = files.size
                _state.value = _state.value.copy(libraryScanState = LibraryScanState.Running(0, total, folderPath))
                val out = ArrayList<ScannedFile>(total)
                for ((i, f) in files.withIndex()) {
                    val report = runCatching { scanner.scan(f) }.getOrNull()
                    val sha = report?.sha256
                    val integrity = "UNKNOWN" // history has no recorded source hash to compare against yet
                    out.add(
                        ScannedFile(
                            name = f.name,
                            path = f.absolutePath,
                            sizeBytes = f.length(),
                            sha256 = sha,
                            report = report,
                            integrity = integrity,
                        )
                    )
                    _state.value = _state.value.copy(
                        libraryScanState = LibraryScanState.Running(i + 1, total, folderPath),
                    )
                }
                _state.value = _state.value.copy(
                    libraryScanState = LibraryScanState.Done(folderPath, out),
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    libraryScanState = LibraryScanState.Error("${e.javaClass.simpleName}: ${e.message}"),
                )
            }
        }
    }

    /** Parse + save an exported list file's text (import-from-file). Returns a user-facing message. */
    fun importListFromText(text: String): String {
        val parsed = lists.decodeOne(text) ?: return "Couldn't read that list file."
        val copy = parsed.copy(
            id = java.util.UUID.randomUUID().toString(),
            createdAt = System.currentTimeMillis(),
        )
        lists.save(copy)
        return "Imported \"${copy.name}\" (${copy.count} items)."
    }

    // ---- scan (browse a console → list its files) -------------------------------------------------

    private fun scan(console: ConsoleItem) {
        val urls = Catalog.catalogFlow.value.iaLinks[console.key].orEmpty()
        if (urls.isEmpty()) {
            _state.value = _state.value.copy(
                scanning = false,
                scanError = "No sources configured for ${console.display}. " +
                    "Add endpoint URLs to the private Catalog.kt to browse this console.",
                files = emptyList(),
            )
            return
        }
        _state.value = _state.value.copy(scanning = true, scanError = null, files = emptyList())
        scope.launch {
            val collected = LinkedHashMap<String, GameFile>() // de-dup by identifier/filename
            var lastError: String? = null
            for (url in urls) {
                try {
                    for (gf in remote.fetchFileList(url)) {
                        collected["${gf.identifier}/${gf.filename}"] = gf
                    }
                } catch (e: Exception) {
                    lastError = "${e.javaClass.simpleName}: ${e.message}"
                }
            }
            val rows = collected.values.sortedBy { it.name.lowercase() }.map { FileRow(it) }
            _state.value = _state.value.copy(
                scanning = false,
                files = rows,
                scanError = if (rows.isEmpty()) (lastError ?: "No files found.") else null,
            )
        }
    }

    fun toggleFile(row: FileRow) {
        _state.value = _state.value.copy(
            files = _state.value.files.map {
                if (it.file === row.file) it.copy(selected = !it.selected) else it
            }
        )
    }

    fun setAllSelected(selected: Boolean) {
        _state.value = _state.value.copy(files = _state.value.files.map { it.copy(selected = selected) })
    }

    // ---- FILES-screen filter / sort / selection (A1–A3) -------------------------------------------

    fun setFileSearchQuery(q: String) { _state.value = _state.value.copy(fileSearchQuery = q) }
    fun setFileSort(sort: FileSort) { _state.value = _state.value.copy(fileSort = sort) }
    fun setSelectionFilter(filter: SelectionFilter) { _state.value = _state.value.copy(selectionFilter = filter) }

    // ---- download (the PROVEN Phase-2 path, per selected file) ------------------------------------

    fun startDownload() {
        val selected = _state.value.files.filter { it.selected }.map { it.file }
        if (selected.isEmpty() || _state.value.downloading) return

        val destDir = File(_state.value.destFolder).apply { mkdirs() }
        val console = _state.value.selectedConsole?.key ?: _state.value.filesTitle.orEmpty()
        val rows = selected.map {
            DownloadRow(name = it.filename, totalBytes = it.size, source = it, destFolder = destDir.absolutePath)
        }
        cancelFlag.set(false)
        pauseFlag.set(false)
        _state.value = _state.value.copy(
            screen = Screen.DOWNLOADS,
            downloading = true,
            paused = false,
            downloads = rows,
        )

        // Read the user's tuning once at batch start (segments + adaptive engine), mirroring Android.
        val segments = settings.segments.value
        val adaptive = settings.adaptiveEngine.value

        activeDownloadJob = scope.launch {
            for ((index, gf) in selected.withIndex()) {
                if (cancelFlag.get()) {
                    updateRow(index) { it.copy(state = DownloadState.CANCELLED) }
                    continue
                }
                updateRow(index) { it.copy(state = DownloadState.DOWNLOADING) }
                try {
                    val mirrors = remote.mirrorUrls(gf.identifier, gf.name)
                    val candidates = mirrors.ifEmpty {
                        listOf(remote.buildDownloadUrl(gf.identifier, gf.name))
                    }
                    val dest = File(destDir, gf.filename)
                    if (dest.exists()) dest.delete()

                    remote.downloadFileSegmented(
                        candidateUrls = candidates,
                        expectedSize = gf.size,
                        destFile = dest,
                        segments = segments,
                        onProgress = { done, speed ->
                            // Reflect a live pause in the row status without tearing down the transfer.
                            val paused = pauseFlag.get()
                            updateRow(index) {
                                it.copy(
                                    doneBytes = done,
                                    speedBytesPerSec = if (paused) 0.0 else speed,
                                    state = if (paused && it.state == DownloadState.DOWNLOADING)
                                        DownloadState.PAUSED else it.state,
                                )
                            }
                        },
                        // Pause = keep the transfer alive but stop feeding it (busy-wait on the pause
                        // flag); cancel = abort. The engine polls this each chunk boundary.
                        isCancelled = {
                            while (pauseFlag.get() && !cancelFlag.get()) Thread.sleep(120)
                            cancelFlag.get()
                        },
                        adaptive = adaptive,
                    )

                    if (cancelFlag.get()) {
                        updateRow(index) { it.copy(state = DownloadState.CANCELLED) }
                        runCatching { if (dest.exists()) dest.delete() }
                        recordHistory(gf, "FAILED", destDir, console, error = "Cancelled")
                    } else {
                        // Snapshot the size BEFORE extraction — a successful .zip extraction deletes
                        // the source archive (mirrors Android, which never leaves the raw archive
                        // behind alongside its extracted contents), so dest.length() would read 0
                        // afterwards. .rar (and anything else) is untouched by extractIfNeeded.
                        val finalSize = if (gf.size > 0) gf.size else dest.length()

                        // BEST-EFFORT security scan of the file AS DOWNLOADED (before extraction, since a
                        // successful .zip extraction deletes the source archive). Wrapped so any scan
                        // error is swallowed — a scan NEVER fails or blocks the download.
                        val report = runCatching {
                            scanner.scan(dest, declaredName = gf.name.ifBlank { gf.filename })
                        }.getOrNull()

                        DesktopArchive.extractIfNeeded(
                            downloadedFile = dest,
                            destDir = destDir,
                            extractArchivesEnabled = settings.extractArchives.value,
                            isCancelled = { cancelFlag.get() },
                        )
                        updateRow(index) {
                            it.copy(
                                state = DownloadState.DONE,
                                doneBytes = finalSize,
                                speedBytesPerSec = 0.0,
                                savedPath = dest.absolutePath,
                                scan = report,
                            )
                        }
                        recordHistory(gf, "DONE", destDir, console)
                    }
                } catch (e: Exception) {
                    val msg = "${e.javaClass.simpleName}: ${e.message}"
                    updateRow(index) { it.copy(state = DownloadState.FAILED, error = msg) }
                    recordHistory(gf, "FAILED", destDir, console, error = msg)
                }
            }
            _state.value = _state.value.copy(downloading = false, paused = false)
        }
    }

    /** Pause the active batch: the in-flight file keeps its socket but stops advancing; queued files wait. */
    fun pauseDownloads() {
        if (!_state.value.downloading) return
        pauseFlag.set(true)
        _state.value = _state.value.copy(paused = true)
    }

    /** Resume a paused batch. */
    fun resumeDownloads() {
        if (!_state.value.downloading) return
        pauseFlag.set(false)
        _state.value = _state.value.copy(
            paused = false,
            downloads = _state.value.downloads.map {
                if (it.state == DownloadState.PAUSED) it.copy(state = DownloadState.DOWNLOADING) else it
            },
        )
    }

    suspend fun cancelDownloads() {
        cancelFlag.set(true)
        pauseFlag.set(false) // release any pause wait so the engine can observe the cancel
        activeDownloadJob?.cancelAndJoin()
        activeDownloadJob = null
        // Mark any still-active rows as cancelled.
        _state.value = _state.value.copy(
            downloading = false,
            paused = false,
            downloads = _state.value.downloads.map {
                when (it.state) {
                    DownloadState.QUEUED, DownloadState.DOWNLOADING, DownloadState.PAUSED ->
                        it.copy(state = DownloadState.CANCELLED, speedBytesPerSec = 0.0)
                    else -> it
                }
            },
        )
    }

    private fun recordHistory(
        gf: GameFile,
        status: String,
        destDir: File,
        console: String,
        error: String = "",
    ) {
        history.record(
            filename = gf.filename,
            name = gf.name,
            sizeBytes = gf.size,
            status = status,
            identifier = gf.identifier,
            console = console,
            destFolder = destDir.absolutePath,
            subfolder = destDir.name,
            error = error,
        )
    }

    // ---- post-download actions: quarantine / open folder / retry (A4, A7, A8) ---------------------

    /**
     * MOVE a downloaded file into a `<destFolder>/Quarantine/` subdir (never deletes — see
     * [quarantineFile]). Runs off-thread; on success updates the row's savedPath to the new location.
     * Called from the safety-details dialog for amber/red files. [onResult] reports a user-facing
     * message (success path or failure) back to the UI.
     */
    fun quarantineDownload(row: DownloadRow, onResult: (String) -> Unit) {
        val path = row.savedPath ?: run { onResult("This file is no longer on disk."); return }
        scope.launch {
            val moved = withContext(Dispatchers.IO) { quarantineFile(File(path)) }
            if (moved != null) {
                _state.value = _state.value.copy(
                    downloads = _state.value.downloads.map {
                        if (it.savedPath == path) it.copy(savedPath = moved.absolutePath) else it
                    },
                )
                onResult("Moved to Quarantine: ${moved.name}")
            } else {
                onResult("Couldn't move the file to Quarantine (it was left in place).")
            }
        }
    }

    /** Open a folder in the OS file manager (best-effort; [onError] reports if unsupported/missing). */
    fun openFolder(path: String?, onError: (String) -> Unit = {}) {
        val dir = path?.takeIf { it.isNotBlank() }?.let { File(it) }
        val target = when {
            dir == null -> null
            dir.isDirectory -> dir
            dir.parentFile?.isDirectory == true -> dir.parentFile
            else -> null
        }
        if (target == null) { onError("That folder isn't available."); return }
        runCatching {
            if (java.awt.Desktop.isDesktopSupported() &&
                java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.OPEN)
            ) {
                java.awt.Desktop.getDesktop().open(target)
            } else onError("Opening folders isn't supported on this system.")
        }.onFailure { onError("Couldn't open the folder.") }
    }

    /**
     * Re-download the failed/cancelled rows in the current batch (A7 "retry failed"). Rebuilds the
     * FILES selection from the rows' [DownloadRow.source] GameFiles and re-runs [startDownload].
     * No-op if a batch is already running or nothing failed.
     */
    fun retryFailed() {
        if (_state.value.downloading) return
        val toRetry = _state.value.downloads.filter {
            (it.state == DownloadState.FAILED || it.state == DownloadState.CANCELLED) && it.source != null
        }.mapNotNull { it.source }
        if (toRetry.isEmpty()) return
        redownload(toRetry)
    }

    /** Re-download a single file (used by a per-row retry button and by History re-download). */
    fun redownload(files: List<GameFile>) {
        if (_state.value.downloading || files.isEmpty()) return
        // Project the requested files back into the FILES selection, then reuse the proven path.
        _state.value = _state.value.copy(
            files = files.map { FileRow(it, selected = true) },
        )
        startDownload()
    }

    private fun updateRow(index: Int, transform: (DownloadRow) -> DownloadRow) {
        val list = _state.value.downloads.toMutableList()
        if (index in list.indices) {
            list[index] = transform(list[index])
            _state.value = _state.value.copy(downloads = list)
        }
    }

    companion object {
        /** Marketing version shown on the About screen. Bump alongside the app's release version. */
        const val APP_VERSION = "1.0.0"

        private const val SEARCH_ROWS = 50

        /** A fresh IO scope for the whole app lifetime (cancelled when the window closes). */
        fun newScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /**
         * Build the public advanced-search URL — replicated verbatim from the Android
         * SearchViewModel.buildSearchUrl. Content-neutral (no mediatype filter → searches ALL public IA).
         */
        fun buildSearchUrl(query: String): String {
            val encoded = java.net.URLEncoder.encode(query, "UTF-8")
            return "https://archive.org/advancedsearch.php?q=$encoded" +
                "&fl[]=identifier&fl[]=title&fl[]=mediatype&fl[]=downloads" +
                "&rows=$SEARCH_ROWS&page=1&output=json"
        }

        /**
         * Parse the advancedsearch JSON ({ response: { docs: [...] } }) into [SearchResult]s —
         * replicated from the Android SearchViewModel.parseSearchResults. Rows missing an identifier
         * are skipped; title falls back to the identifier.
         */
        fun parseSearchResults(body: String, json: Json): List<SearchResult> {
            val root = json.parseToJsonElement(body).jsonObject
            val docs = root["response"]?.jsonObject?.get("docs")?.jsonArray ?: return emptyList()
            fun str(obj: kotlinx.serialization.json.JsonObject, key: String): String? =
                (obj[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.trim()
            return docs.mapNotNull { el ->
                val obj = el.jsonObject
                val id = str(obj, "identifier")
                if (id.isNullOrBlank()) return@mapNotNull null
                val title = str(obj, "title")?.takeIf { it.isNotEmpty() } ?: id
                val mediatype = str(obj, "mediatype") ?: ""
                val downloads = (obj["downloads"] as? kotlinx.serialization.json.JsonPrimitive)?.intOrNull
                SearchResult(identifier = id, title = title, mediatype = mediatype, downloads = downloads)
            }
        }
    }
}
