package io.github.mayusi.emuhelper.ui.download

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.mayusi.emuhelper.data.model.CuratedGame
import io.github.mayusi.emuhelper.data.model.DownloadStatus
import io.github.mayusi.emuhelper.data.source.DownloadManager
import io.github.mayusi.emuhelper.data.storage.SettingsStore
import io.github.mayusi.emuhelper.ui.browse.ScanStateHolder
import io.github.mayusi.emuhelper.ui.common.Dimens
import io.github.mayusi.emuhelper.ui.common.formatEta
import io.github.mayusi.emuhelper.ui.common.formatSize
import io.github.mayusi.emuhelper.ui.common.formatSpeed
import io.github.mayusi.emuhelper.ui.safety.SafetyBadge
import io.github.mayusi.emuhelper.ui.safety.SafetyDetailsDialog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Thin UI-facing wrapper over the app-scoped [DownloadManager]. The manager owns the
 * actual download work + state on an application-lifetime scope, so downloads keep
 * running when this screen / ViewModel is gone (app backgrounded). This VM just
 * forwards calls and re-exposes the manager's StateFlows for Compose.
 */
@HiltViewModel
class DownloadViewModel @Inject constructor(
    private val manager: DownloadManager,
    private val scanState: ScanStateHolder,
    private val settings: SettingsStore,
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    val queuedGames: StateFlow<List<CuratedGame>> = scanState.downloadQueue
    val tasks = manager.tasks
    val totalProgress = manager.totalProgress
    val totalSpeed = manager.totalSpeed
    val eta = manager.eta
    val statusText = manager.statusText
    val isRunning = manager.isRunning
    val isPaused = manager.isPaused

    // B10: Use stateIn so the persisted folder value is reflected immediately on first
    // frame — no async-init race that briefly shows null while the launch{} completes.
    val customFolder: StateFlow<Uri?> = settings.downloadFolder
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * Per-list folder override (SAF tree URI) set when the user opens a saved list that has
     * [io.github.mayusi.emuhelper.data.model.GameList.customFolderUri] != null.
     * Null means "use the global download folder".
     * Exposed so the Download screen can display "Using list folder: …" and so [start] can
     * forward it once DownloadManager.start() accepts a folderOverride parameter.
     */
    val listFolderOverride: StateFlow<Uri?> =
        scanState.pendingListFolderUri
            .map { uriStr -> uriStr?.let { Uri.parse(it) } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Wi-Fi-only download preference; gated at the call site before starting downloads. */
    val wifiOnly: StateFlow<Boolean> = settings.wifiOnly
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * Non-null message when downloads were automatically paused because the network
     * switched to a metered connection while Wi-Fi-only is enabled.
     * Cleared when the user manually resumes or when the network returns to unmetered.
     */
    private val _wifiPauseMessage = MutableStateFlow<String?>(null)
    val wifiPauseMessage: StateFlow<String?> = _wifiPauseMessage

    /**
     * True when the mid-download network callback performed an automatic pause.
     * Distinguishes auto-pauses from manual pauses so auto-resume only un-does its own
     * pause and never fights a user-initiated pause/resume.
     */
    private var autoPausedByNetworkCallback = false

    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            handleNetworkChange(caps)
        }
        override fun onLost(network: Network) {
            // Treat losing the network as becoming metered — pause to protect data.
            handleNetworkChange(null)
        }
    }

    /**
     * Called by the NetworkCallback whenever the default network's capabilities change.
     * [caps] is null when the network is lost.
     *
     * Rules:
     * - Only acts when wifiOnly is true AND a download is actively running.
     * - Auto-pauses when the network is metered (or lost); auto-resumes when it returns
     *   to unmetered — but ONLY if the callback was the one that paused it, so a user's
     *   manual pause is never overridden.
     */
    private fun handleNetworkChange(caps: NetworkCapabilities?) {
        if (!wifiOnly.value) return          // preference is off — nothing to enforce
        if (!isRunning.value) return         // no download in progress — nothing to pause

        val isMetered = caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)

        if (isMetered && !autoPausedByNetworkCallback && !isPaused.value) {
            // Network became metered / lost while a download is running — auto-pause.
            autoPausedByNetworkCallback = true
            manager.pause()
            _wifiPauseMessage.value = "Paused — on mobile data (Wi-Fi only is on)"
            Log.i("EmuHelper", "Wi-Fi-only: auto-paused (network metered/lost)")
        } else if (!isMetered && autoPausedByNetworkCallback) {
            // Network returned to unmetered — auto-resume only what we auto-paused.
            autoPausedByNetworkCallback = false
            _wifiPauseMessage.value = null
            manager.resume()
            Log.i("EmuHelper", "Wi-Fi-only: auto-resumed (network unmetered)")
        }
    }

    init {
        // Register a default-network callback so we hear about network changes
        // mid-download. Uses the broad "any capability" request so we always get
        // onCapabilitiesChanged; we filter for metered/unmetered inside handleNetworkChange.
        try {
            val request = NetworkRequest.Builder().build()
            connectivityManager?.registerNetworkCallback(request, networkCallback)
        } catch (e: Exception) {
            Log.w("EmuHelper", "Could not register network callback; mid-download Wi-Fi gate disabled", e)
        }
    }

    override fun onCleared() {
        super.onCleared()
        try {
            connectivityManager?.unregisterNetworkCallback(networkCallback)
        } catch (e: Exception) {
            Log.w("EmuHelper", "Error unregistering network callback", e)
        }
    }

    /**
     * True when the Wi-Fi-only preference is on AND the active network is metered
     * (e.g. mobile data). Used to gate downloads before they start. Fails open
     * (returns false) if network state can't be read, so we never block spuriously.
     */
    fun isBlockedByWifiOnly(): Boolean {
        if (!wifiOnly.value) return false
        return isActiveNetworkMetered()
    }

    private fun isActiveNetworkMetered(): Boolean {
        return try {
            val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return false
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            // NOT_METERED present => unmetered (Wi-Fi). Absent => metered (mobile data).
            !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        } catch (e: Exception) {
            Log.w("EmuHelper", "metered-network check failed; allowing download", e)
            false
        }
    }

    /**
     * Start downloading [games].
     *
     * Per-list folder override: when the user opens a saved list that has a
     * [io.github.mayusi.emuhelper.data.model.GameList.customFolderUri], the override is
     * carried via [scanState.pendingListFolderUri] and surfaced in the UI via
     * [listFolderOverride]. It is passed straight through to DownloadManager.start() below,
     * which targets that SAF tree instead of the global Settings download folder. The
     * override is cleared in [clearQueue] when the batch ends.
     */
    fun start(games: List<CuratedGame>) {
        // A per-list custom folder (if the batch came from a saved list that pinned one)
        // overrides the global download folder for this batch.
        val override = scanState.pendingListFolderUri.value?.let { Uri.parse(it) }
        // Per-batch RAR-extraction choice from the preview prompt (null = use global setting).
        val extractRar = scanState.pendingExtractRar.value
        manager.start(games, folderOverride = override, extractRar = extractRar)
    }
    fun cancelAll() {
        // Clear auto-pause tracking on cancel so it doesn't linger.
        autoPausedByNetworkCallback = false
        _wifiPauseMessage.value = null
        manager.cancelAll()
    }
    fun pauseAll() = manager.pause()
    fun resumeAll() {
        // A manual resume clears the auto-pause flag: the user is explicitly overriding
        // the Wi-Fi-only policy for this session, so we won't auto-pause again until the
        // next network event.
        autoPausedByNetworkCallback = false
        _wifiPauseMessage.value = null
        manager.resume()
    }
    fun retryTask(task: io.github.mayusi.emuhelper.data.model.DownloadTask) = manager.retry(task.id)

    /** WARN + QUARANTINE model: move a flagged file's task into the on-disk Quarantine/ subfolder.
     *  Never deletes; see [DownloadManager.quarantine] for the move implementation. */
    fun quarantineTask(task: io.github.mayusi.emuhelper.data.model.DownloadTask) = manager.quarantine(task.id)

    fun clearQueue() {
        scanState.downloadQueue.value = emptyList()
        scanState.pendingListFolderUri.value = null
        scanState.pendingExtractRar.value = null
        manager.clear()
    }

    fun setFolder(uri: Uri?) {
        // B10: customFolder is now driven by stateIn from settings; just persist to DataStore
        // and the StateFlow will update automatically.
        viewModelScope.launch { settings.setDownloadFolder(uri) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadScreen(
    onDone: () -> Unit,
    onBack: () -> Unit,
    onHistory: () -> Unit = {},
    viewModel: DownloadViewModel = hiltViewModel()
) {
    val games by viewModel.queuedGames.collectAsState()
    val tasks by viewModel.tasks.collectAsState()
    val totalProgress by viewModel.totalProgress.collectAsState()
    val totalSpeed by viewModel.totalSpeed.collectAsState()
    val eta by viewModel.eta.collectAsState()
    val statusText by viewModel.statusText.collectAsState()
    val isRunning by viewModel.isRunning.collectAsState()
    val isPaused by viewModel.isPaused.collectAsState()
    val wifiPauseMessage by viewModel.wifiPauseMessage.collectAsState()
    val customFolder by viewModel.customFolder.collectAsState()
    val listFolderOverride by viewModel.listFolderOverride.collectAsState()
    val context = LocalContext.current
    // B11: Snackbar host for transient "open folder" error feedback.
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    // Wi-Fi-only gate: when a download is blocked because the user is on mobile data,
    // hold the action to run if they choose "Download anyway".
    var pendingWifiAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val showWifiBlock = pendingWifiAction != null

    // FIX 1: Cancel-all confirmation — prevents fat-finger nuke of running batch.
    var showCancelConfirm by remember { mutableStateOf(false) }

    // SAFETY REVIEW: which task's scan-details dialog is open (null = none). Holds the task id so
    // the dialog always re-reads the LATEST task from `tasks` (e.g. right after quarantining).
    var safetyDetailsTaskId by remember { mutableStateOf<String?>(null) }

    // Run [action] only if Wi-Fi-only doesn't block it; otherwise stash it and prompt.
    val gateWifiOnly: (() -> Unit) -> Unit = { action ->
        if (viewModel.isBlockedByWifiOnly()) {
            pendingWifiAction = action
        } else {
            action()
        }
    }

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                viewModel.setFolder(uri)
            } catch (e: SecurityException) {
                Log.w("EmuHelper", "Persisting URI permission failed", e)
            }
        }
    }

    // Request POST_NOTIFICATIONS once on Android 13+ so the foreground-download
    // notification isn't silently suppressed. Downloads begin on this screen.
    val notifPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { /* granted or not — downloads proceed regardless */ }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Start only if the manager isn't already working on this batch.
    // Gate on Wi-Fi-only: if the user is on metered data with the toggle on, we don't
    // silently no-op — we surface a dialog (below) offering Wi-Fi or "Download anyway".
    LaunchedEffect(games) {
        if (tasks.isEmpty() && games.isNotEmpty()) gateWifiOnly { viewModel.start(games) }
    }

    // FIX 1: Cancel-all confirmation dialog.
    if (showCancelConfirm) {
        AlertDialog(
            onDismissRequest = { showCancelConfirm = false },
            title = { Text("Cancel all downloads?") },
            text  = { Text("This stops and removes the current batch.") },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.cancelAll(); showCancelConfirm = false },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("Cancel downloads") }
            },
            dismissButton = {
                TextButton(onClick = { showCancelConfirm = false }) { Text("Keep downloading") }
            }
        )
    }

    // Wi-Fi-only block dialog: shown when the user tried to download on mobile data.
    // We never silently no-op — the user can connect to Wi-Fi, open Settings via the
    // explainer, or explicitly proceed with "Download anyway".
    if (showWifiBlock) {
        AlertDialog(
            onDismissRequest = { pendingWifiAction = null },
            title = { Text("On mobile data") },
            text = {
                Text("On mobile data — Wi-Fi-only is on. Connect to Wi-Fi or turn it off in Settings.")
            },
            confirmButton = {
                TextButton(onClick = {
                    val action = pendingWifiAction
                    pendingWifiAction = null
                    action?.invoke()
                }) { Text("Download anyway") }
            },
            dismissButton = {
                TextButton(onClick = { pendingWifiAction = null }) { Text("Cancel") }
            }
        )
    }

    // SAFETY REVIEW: details dialog for whichever task's badge was tapped. Re-derived from `tasks`
    // every recomposition so a "Move to Quarantine" tap immediately reflects in the same dialog.
    safetyDetailsTaskId?.let { id ->
        val task = tasks.firstOrNull { it.id == id }
        val report = task?.scanReport
        if (task != null && report != null) {
            SafetyDetailsDialog(
                report = report,
                alreadyQuarantined = task.quarantined,
                onQuarantine = { viewModel.quarantineTask(task) },
                onDismiss = { safetyDetailsTaskId = null }
            )
        } else {
            safetyDetailsTaskId = null
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Downloads", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    // Back leaves the screen but does NOT cancel: downloads keep running
                    // in the background (foreground service). Only the Cancel action stops them.
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    if (isRunning) {
                        IconButton(onClick = { if (isPaused) viewModel.resumeAll() else viewModel.pauseAll() }) {
                            Icon(
                                if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                contentDescription = if (isPaused) "Resume downloads" else "Pause downloads"
                            )
                        }
                        IconButton(onClick = { showCancelConfirm = true }) {
                            Icon(Icons.Default.Close, "Cancel all downloads")
                        }
                    }
                    IconButton(onClick = { folderPicker.launch(null) }) {
                        Icon(Icons.Default.Folder, "Choose folder")
                    }
                    IconButton(onClick = onHistory) {
                        Icon(Icons.Default.History, "Download history")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.ScreenHorizontal, vertical = Dimens.ItemGap)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(statusText, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                            // Memoised so these O(n) sums don't run on every recomposition
                            // (DownloadManager ticks at ~3×/sec while downloading).
                            val totalBytes by remember(tasks) { derivedStateOf { tasks.sumOf { it.size } } }
                            val downloadedBytes by remember(tasks) { derivedStateOf { tasks.sumOf { it.downloaded } } }
                            Text(
                                "${formatSize(downloadedBytes)} / ${formatSize(totalBytes)}  ·  ${formatSpeed(totalSpeed)}  ·  ETA $eta",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        val done by remember(tasks) { derivedStateOf { tasks.count { it.status == DownloadStatus.DONE } } }
                        Text(
                            "$done / ${tasks.size}",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    // Show the per-list folder override when set; fall back to the global folder.
                    Text(
                        text = run {
                            val lfo = listFolderOverride
                            val cf = customFolder
                            when {
                                lfo != null -> "Folder (list): ${lfo.lastPathSegment ?: "selected"}"
                                cf  != null -> "Folder: ${cf.lastPathSegment ?: "selected"}"
                                else        -> "Folder: app-private (Android/data/.../files/Download/ROMs)"
                            }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (isRunning) {
                        Text(
                            "Downloads keep running if you leave the app. Force-closing it stops them.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    // Mid-download Wi-Fi-only enforcement: show a banner when the network
                    // callback auto-paused downloads because the connection became metered.
                    if (wifiPauseMessage != null) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            wifiPauseMessage!!,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
            val animatedTotalProgress by animateFloatAsState(targetValue = totalProgress, label = "totalProgress")
            LinearProgressIndicator(
                progress = { animatedTotalProgress },
                modifier = Modifier.fillMaxWidth().height(6.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )

            val allTerminal = tasks.isNotEmpty() && tasks.all {
                it.status in setOf(DownloadStatus.DONE, DownloadStatus.FAILED, DownloadStatus.CANCELLED)
            }
            val hasFailures = tasks.any { it.status == DownloadStatus.FAILED }
            if (!isRunning && allTerminal) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(Dimens.ScreenHorizontal),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (hasFailures) {
                        OutlinedButton(
                            onClick = {
                                // Reuse the SAME per-task retry path the row button uses
                                // (manager.retry(id)) so the original task — including its
                                // resolved per-console subfolder — is reused instead of
                                // reconstructing a CuratedGame (which would drop `console`
                                // and re-derive the subfolder, risking the wrong location).
                                // Gate on Wi-Fi-only just like the initial start.
                                gateWifiOnly {
                                    tasks.filter { it.status == DownloadStatus.FAILED }
                                        .forEach { viewModel.retryTask(it) }
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("Retry failed") }
                    }
                    customFolder?.let { folderUri ->
                        OutlinedButton(
                            onClick = {
                                var opened = false
                                try {
                                    val intent = Intent(Intent.ACTION_VIEW).apply {
                                        setDataAndType(folderUri, DocumentsContract.Document.MIME_TYPE_DIR)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(intent)
                                    opened = true
                                } catch (_: Exception) { /* try fallback */ }
                                if (!opened) {
                                    // Fallback: open the system Downloads/Files app.
                                    try {
                                        context.startActivity(
                                            Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS)
                                        )
                                        opened = true
                                    } catch (_: Exception) { /* both paths failed */ }
                                }
                                // B11: Surface a brief message if neither intent resolved.
                                if (!opened) {
                                    coroutineScope.launch {
                                        snackbarHostState.showSnackbar("Couldn't open folder")
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("Open folder") }
                    }
                    Button(
                        onClick = { viewModel.clearQueue(); onDone() },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) { Text("Done") }
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(horizontal = Dimens.ScreenHorizontal, vertical = Dimens.ItemGap)
            ) {
                itemsIndexed(tasks, key = { _, t -> t.id }) { _, task ->
                    val statusLabel = remember(task.status) {
                        task.status.name.lowercase().replace('_', ' ')
                    }
                    val statusColor = when (task.status) {
                        DownloadStatus.DONE -> MaterialTheme.colorScheme.tertiary
                        DownloadStatus.DOWNLOADING -> MaterialTheme.colorScheme.primary
                        DownloadStatus.FAILED -> MaterialTheme.colorScheme.error
                        DownloadStatus.PAUSED -> MaterialTheme.colorScheme.outline
                        DownloadStatus.CANCELLED -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    val icon = when (task.status) {
                        DownloadStatus.DONE -> Icons.Default.CheckCircle
                        DownloadStatus.DOWNLOADING -> Icons.Default.Download
                        DownloadStatus.FAILED -> Icons.Default.Error
                        DownloadStatus.PAUSED -> Icons.Default.PauseCircle
                        else -> Icons.Default.HourglassEmpty
                    }

                    Card(
                        modifier = Modifier.fillMaxWidth().animateItem(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = MaterialTheme.shapes.small
                    ) {
                        Column(modifier = Modifier
                            .fillMaxWidth()
                            .animateContentSize()
                            .padding(Dimens.CardPadding + 2.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val iconDesc = when (task.status) {
                                    DownloadStatus.DONE -> "Done"
                                    DownloadStatus.DOWNLOADING -> "Downloading"
                                    DownloadStatus.FAILED -> "Failed"
                                    DownloadStatus.PAUSED -> "Paused"
                                    else -> "Queued"
                                }
                                Icon(icon, contentDescription = iconDesc, modifier = Modifier.size(20.dp), tint = statusColor)
                                Spacer(Modifier.width(Dimens.ItemGap))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(task.filename, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (task.subfolder.isNotBlank()) {
                                        Text("→ ${task.subfolder}/", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    if (task.error.isNotBlank()) {
                                        Text(task.error, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    // SAFETY REVIEW: best-effort scanner verdict badge, shown once a DONE
                                    // task has been scanned. Tapping it opens the full check breakdown +
                                    // (for a flagged file) the "Move to Quarantine" action. Absent entirely
                                    // when null — task not yet scanned, or the scan errored/was skipped,
                                    // which is never treated as a failure (see DownloadManager.scanDownloadedFile).
                                    task.scanReport?.let { report ->
                                        Spacer(Modifier.height(4.dp))
                                        SafetyBadge(
                                            report = report,
                                            onClick = { safetyDetailsTaskId = task.id }
                                        )
                                    }
                                }
                                Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(start = 8.dp)) {
                                    Text(
                                        if (task.status == DownloadStatus.DOWNLOADING || task.status == DownloadStatus.PAUSED)
                                            "${formatSize(task.downloaded)} / ${formatSize(task.size)}"
                                        else formatSize(task.size),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    if (task.status == DownloadStatus.DOWNLOADING) {
                                        val perTaskEta = if (task.speed > 0.1 && task.size > 0)
                                            formatEta((task.size - task.downloaded) / task.speed)
                                        else "--"
                                        Text(
                                            "${formatSpeed(task.speed)}  ·  $perTaskEta",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            val pct = task.progressPercent
                            if (pct != null) {
                                val animatedPct by animateFloatAsState(targetValue = pct / 100f, label = "taskProgress_${task.id}")
                                LinearProgressIndicator(
                                    progress = { animatedPct },
                                    modifier = Modifier.fillMaxWidth().height(4.dp),
                                    color = statusColor,
                                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                            } else if (task.status == DownloadStatus.DOWNLOADING) {
                                LinearProgressIndicator(
                                    modifier = Modifier.fillMaxWidth().height(4.dp),
                                    color = statusColor,
                                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    pct?.let { "%.0f%%".format(it) } ?: "—",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    statusLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = statusColor
                                )
                                AnimatedVisibility(
                                    visible = task.status == DownloadStatus.FAILED || task.status == DownloadStatus.CANCELLED,
                                    enter = fadeIn() + expandVertically()
                                ) {
                                    TextButton(onClick = { gateWifiOnly { viewModel.retryTask(task) } }) {
                                        Text("Retry", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
