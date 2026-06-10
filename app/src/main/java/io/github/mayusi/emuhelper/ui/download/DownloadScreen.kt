package io.github.mayusi.emuhelper.ui.download

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
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
import io.github.mayusi.emuhelper.data.model.CuratedGame
import io.github.mayusi.emuhelper.data.model.DownloadStatus
import io.github.mayusi.emuhelper.data.source.DownloadManager
import io.github.mayusi.emuhelper.data.storage.SettingsStore
import io.github.mayusi.emuhelper.ui.browse.ScanStateHolder
import io.github.mayusi.emuhelper.ui.common.Dimens
import io.github.mayusi.emuhelper.ui.common.formatEta
import io.github.mayusi.emuhelper.ui.common.formatSize
import io.github.mayusi.emuhelper.ui.common.formatSpeed
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
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
    private val settings: SettingsStore
) : ViewModel() {

    val queuedGames: StateFlow<List<CuratedGame>> = scanState.downloadQueue
    val tasks = manager.tasks
    val totalProgress = manager.totalProgress
    val totalSpeed = manager.totalSpeed
    val eta = manager.eta
    val statusText = manager.statusText
    val isRunning = manager.isRunning
    val isPaused = manager.isPaused

    val customFolder = MutableStateFlow<Uri?>(null)

    init {
        viewModelScope.launch { customFolder.value = settings.downloadFolder.first() }
    }

    fun start(games: List<CuratedGame>) = manager.start(games)
    fun cancelAll() = manager.cancelAll()
    fun pauseAll() = manager.pause()
    fun resumeAll() = manager.resume()
    fun retryTask(task: io.github.mayusi.emuhelper.data.model.DownloadTask) = manager.retry(task.id)

    fun clearQueue() {
        scanState.downloadQueue.value = emptyList()
        manager.clear()
    }

    fun setFolder(uri: Uri?) {
        customFolder.value = uri
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
    val customFolder by viewModel.customFolder.collectAsState()
    val context = LocalContext.current

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
    LaunchedEffect(games) { if (tasks.isEmpty() && games.isNotEmpty()) viewModel.start(games) }

    Scaffold(
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
                        IconButton(onClick = { viewModel.cancelAll() }) {
                            Icon(Icons.Default.Close, "Cancel")
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
                            val totalBytes = tasks.sumOf { it.size }
                            val downloadedBytes = tasks.sumOf { it.downloaded }
                            Text(
                                "${formatSize(downloadedBytes)} / ${formatSize(totalBytes)}  ·  ${formatSpeed(totalSpeed)}  ·  ETA $eta",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        val done = tasks.count { it.status == DownloadStatus.DONE }
                        Text(
                            "$done / ${tasks.size}",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = customFolder?.let { "Folder: ${it.lastPathSegment ?: "selected"}" }
                            ?: "Folder: app-private (Android/data/.../files/Download/ROMs)",
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
                                tasks.filter { it.status == DownloadStatus.FAILED }
                                    .forEach { viewModel.retryTask(it) }
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("Retry failed") }
                    }
                    customFolder?.let { folderUri ->
                        OutlinedButton(
                            onClick = {
                                try {
                                    val intent = Intent(Intent.ACTION_VIEW).apply {
                                        setDataAndType(folderUri, DocumentsContract.Document.MIME_TYPE_DIR)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    // Fallback: open the system Downloads/Files app.
                                    try {
                                        context.startActivity(
                                            Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS)
                                        )
                                    } catch (_: Exception) {}
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
                                Icon(icon, null, modifier = Modifier.size(20.dp), tint = statusColor)
                                Spacer(Modifier.width(Dimens.ItemGap))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(task.filename, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (task.subfolder.isNotBlank()) {
                                        Text("→ ${task.subfolder}/", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    if (task.error.isNotBlank()) {
                                        Text(task.error, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                                    TextButton(onClick = { viewModel.retryTask(task) }) {
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
