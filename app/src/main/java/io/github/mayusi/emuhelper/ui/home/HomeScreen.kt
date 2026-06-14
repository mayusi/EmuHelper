package io.github.mayusi.emuhelper.ui.home

import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.mayusi.emuhelper.data.model.CuratedGame
import io.github.mayusi.emuhelper.data.source.AppUpdater
import io.github.mayusi.emuhelper.data.source.LoginResult
import io.github.mayusi.emuhelper.data.source.RemoteSource
import io.github.mayusi.emuhelper.data.source.UpdateChecker
import io.github.mayusi.emuhelper.data.storage.AuthStore
import io.github.mayusi.emuhelper.data.storage.GameListStore
import io.github.mayusi.emuhelper.data.storage.QueueStore
import io.github.mayusi.emuhelper.data.storage.SettingsStore
import io.github.mayusi.emuhelper.di.PersistentCookieJar
import io.github.mayusi.emuhelper.ui.common.Dimens
import io.github.mayusi.emuhelper.ui.common.UpdateDialog
import io.github.mayusi.emuhelper.ui.common.UpdateFlowState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    listStore: GameListStore,
    private val settings: SettingsStore,
    private val authStore: AuthStore,
    private val source: RemoteSource,
    private val cookieJar: PersistentCookieJar,
    private val updateChecker: UpdateChecker,
    private val appUpdater: AppUpdater,
    private val queueStore: QueueStore
) : ViewModel() {

    data class HomeUi(val listCount: Int = 0, val loggedIn: Boolean = false, val email: String = "")

    private val _updateInfo = MutableStateFlow<UpdateChecker.UpdateInfo?>(null)
    val updateInfo: StateFlow<UpdateChecker.UpdateInfo?> = _updateInfo.asStateFlow()

    private val _updateFlow = MutableStateFlow<UpdateFlowState>(UpdateFlowState.Idle)
    val updateFlow: StateFlow<UpdateFlowState> = _updateFlow.asStateFlow()

    // Persisted dismissed tag (loaded from DataStore so it survives restarts).
    val dismissedUpdateTag: StateFlow<String> = settings.dismissedUpdateTag
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    /** The interrupted batch queue, if any. Non-empty only when the app was killed mid-batch. */
    val pendingQueue: StateFlow<List<CuratedGame>> = queueStore.pendingQueue
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** User dismissed the resume banner — clear the persisted queue. */
    fun dismissQueue() {
        viewModelScope.launch { queueStore.clear() }
    }

    private var downloadedApkFile: File? = null
    // B3: Keep a reference to the in-flight download job so cancelDownload() can cancel it.
    private var downloadJob: Job? = null

    val ui: StateFlow<HomeUi> = combine(
        listStore.lists,
        authStore.savedEmail,
        cookieJar.loggedIn
    ) { lists, email, loggedIn ->
        HomeUi(listCount = lists.size, loggedIn = loggedIn, email = email)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HomeUi())

    init {
        // Silent auto-relogin.
        viewModelScope.launch {
            kotlinx.coroutines.delay(400)
            if (cookieJar.loggedIn.value) return@launch
            val remember = authStore.rememberMe.first()
            val email = authStore.savedEmail.first()
            if (!remember || email.isBlank()) return@launch
            val pwd = authStore.getSavedPassword()
            if (pwd.isBlank()) return@launch
            when (source.login(email, pwd)) {
                is LoginResult.Success -> Log.i("EmuHelper", "silent auto-relogin OK")
                is LoginResult.Failed -> Log.w("EmuHelper", "silent auto-relogin failed")
            }
        }

        // Throttled update check: only run if >24h since last check.
        viewModelScope.launch {
            val lastCheck = settings.lastUpdateCheck.first()
            val now = System.currentTimeMillis()
            val twentyFourHours = 24L * 60 * 60 * 1000
            if (now - lastCheck > twentyFourHours) {
                settings.setLastUpdateCheck(now)
                val currentVersion = try {
                    io.github.mayusi.emuhelper.BuildConfig.VERSION_NAME
                } catch (e: Exception) { "" }
                val info = updateChecker.check(currentVersion)
                if (info != null && info.isNewer) {
                    _updateInfo.value = info
                }
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            authStore.clearCredentials()
            cookieJar.clear()
        }
    }

    fun setFolder(uri: Uri?) {
        viewModelScope.launch { settings.setDownloadFolder(uri) }
    }

    fun dismissUpdate(tag: String) {
        viewModelScope.launch { settings.setDismissedUpdateTag(tag) }
    }

    fun startDownload(apkUrl: String, apkSize: Long, expectedSha256: String? = null) {
        if (_updateFlow.value is UpdateFlowState.Downloading) return
        _updateFlow.value = UpdateFlowState.Downloading(0f)
        downloadedApkFile = null
        // B3: Store the Job so cancelDownload() can cancel it.
        downloadJob = viewModelScope.launch {
            val file = try {
                // B3/B7: downloadApk uses withContext(IO); structured cancellation interrupts
                // the read loop. AppUpdater.downloadApk deletes the partial file on its own
                // IOException/Exception paths. On CancellationException the partial file is
                // also cleaned up inside downloadApk's finally block (added in B7 fix).
                appUpdater.downloadApk(apkUrl, apkSize, expectedSha256) { progress ->
                    _updateFlow.value = UpdateFlowState.Downloading(progress)
                }
            } catch (_: kotlinx.coroutines.CancellationException) {
                // Cancelled by cancelDownload() — flow already reset there; just return.
                return@launch
            }
            if (file != null) {
                downloadedApkFile = file
                _updateFlow.value = UpdateFlowState.Installing
            } else {
                _updateFlow.value = UpdateFlowState.Error("Download failed. Check your connection and try again.")
            }
        }
    }

    /** B3: Cancel the in-flight download and reset the dialog to Idle. */
    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        _updateFlow.value = UpdateFlowState.Idle
    }

    fun installDownloadedApk() {
        val file = downloadedApkFile ?: run {
            _updateFlow.value = UpdateFlowState.Error("APK not found. Please download again.")
            return
        }
        when (val result = appUpdater.installApk(file)) {
            is AppUpdater.InstallResult.Launched -> { /* system installer is open */ }
            is AppUpdater.InstallResult.NeedsPermission -> {
                _updateFlow.value = UpdateFlowState.NeedsPermission
            }
            is AppUpdater.InstallResult.Error -> {
                _updateFlow.value = UpdateFlowState.Error(
                    "${result.message}\n\nIf installation keeps failing, try downloading the APK from the release page."
                )
            }
        }
    }

    fun resetUpdateFlow() {
        _updateFlow.value = UpdateFlowState.Idle
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onMakeList: () -> Unit,
    onInstall: () -> Unit,
    onDownload: () -> Unit,
    onSignIn: () -> Unit,
    onSettings: () -> Unit = {},
    onOpenDownloads: () -> Unit = {},
    onEmulatorSetup: () -> Unit = {},
    onAbout: () -> Unit = {},
    /** Called when the user taps "Resume" on the interrupted-batch banner.
     *  The queue is already seeded into [ScanStateHolder.downloadQueue] before this fires. */
    onResume: ((List<CuratedGame>) -> Unit)? = null,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val ui by viewModel.ui.collectAsState()
    val updateInfo by viewModel.updateInfo.collectAsState()
    val updateFlow by viewModel.updateFlow.collectAsState()
    val dismissedTag by viewModel.dismissedUpdateTag.collectAsState()
    val pendingQueue by viewModel.pendingQueue.collectAsState()
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    var showUpdateDialog by remember { mutableStateOf(false) }

    // Compute whether the banner should be visible.
    val info = updateInfo
    val bannerVisible = info != null && info.isNewer && dismissedTag != info.latestTag

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                viewModel.setFolder(uri)
            } catch (e: SecurityException) {
                Log.w("EmuHelper", "Persisting folder URI failed", e)
            }
        }
    }

    // Update dialog
    if (showUpdateDialog && info != null) {
        UpdateDialog(
            info = info,
            flowState = updateFlow,
            onDownload = {
                val url = info.apkUrl ?: return@UpdateDialog
                // B3: thread SHA-256 from UpdateInfo into the download call.
                viewModel.startDownload(url, info.apkSize, info.apkSha256)
            },
            onInstall = { viewModel.installDownloadedApk() },
            onDismiss = {
                // B3: cancel any in-flight download when the dialog is dismissed.
                viewModel.cancelDownload()
                showUpdateDialog = false
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("EmuHelper", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary) },
                actions = {
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Menu")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Downloads") },
                            leadingIcon = { Icon(Icons.Default.Download, null) },
                            onClick = { menuOpen = false; onOpenDownloads() }
                        )
                        if (ui.loggedIn) {
                            DropdownMenuItem(
                                text = { Text("Log out") },
                                leadingIcon = { Icon(Icons.AutoMirrored.Filled.Logout, null) },
                                onClick = { menuOpen = false; viewModel.logout() }
                            )
                        } else {
                            DropdownMenuItem(
                                text = { Text("Sign in") },
                                leadingIcon = { Icon(Icons.AutoMirrored.Filled.Login, null) },
                                onClick = { menuOpen = false; onSignIn() }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Download folder…") },
                            leadingIcon = { Icon(Icons.Default.Folder, null) },
                            onClick = { menuOpen = false; folderPicker.launch(null) }
                        )
                        DropdownMenuItem(
                            text = { Text("Emulator setup") },
                            leadingIcon = { Icon(Icons.Default.PhoneAndroid, null) },
                            onClick = { menuOpen = false; onEmulatorSetup() }
                        )
                        DropdownMenuItem(
                            text = { Text("About") },
                            leadingIcon = { Icon(Icons.Default.Info, null) },
                            onClick = { menuOpen = false; onAbout() }
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Dimens.ScreenHorizontal, vertical = Dimens.SectionGap),
            verticalArrangement = Arrangement.Top,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(Icons.Default.Download, null, modifier = Modifier.size(Dimens.IconLarge), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(6.dp))
            Text("Download Manager", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))

            // Update available banner
            if (bannerVisible) {
                Card(
                    modifier = Modifier.fillMaxWidth().widthIn(max = 600.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    shape = MaterialTheme.shapes.small
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.CardPadding, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.NewReleases,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(Dimens.IconSmall + 4.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Update available: ${info.latestTag}",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                "Tap to see what's new",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                            )
                        }
                        TextButton(onClick = { showUpdateDialog = true }) {
                            Text("Update", color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                        IconButton(
                            onClick = { viewModel.dismissUpdate(info.latestTag) },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Dismiss",
                                tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            // Resume interrupted batch banner — shown when there's a saved queue and no
            // download is currently running (isRunning would mean we're already active).
            if (pendingQueue.isNotEmpty() && onResume != null) {
                Card(
                    modifier = Modifier.fillMaxWidth().widthIn(max = 600.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    shape = MaterialTheme.shapes.small
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.CardPadding, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(Dimens.IconSmall + 4.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Resume interrupted download? (${pendingQueue.size} item${if (pendingQueue.size != 1) "s" else ""})",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Text(
                                "A previous batch was interrupted",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                            )
                        }
                        TextButton(onClick = { onResume(pendingQueue) }) {
                            Text("Resume", color = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                        IconButton(
                            onClick = { viewModel.dismissQueue() },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Dismiss",
                                tint = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            // Two primary actions side by side (compact).
            Row(
                modifier = Modifier.fillMaxWidth().widthIn(max = 600.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                HubTile(
                    modifier = Modifier.weight(1f),
                    icon = Icons.AutoMirrored.Filled.PlaylistAdd,
                    title = "Make a list",
                    subtitle = "Save items to fetch later.",
                    onClick = onMakeList
                )
                HubTile(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.FlashOn,
                    title = "Instant install",
                    subtitle = "Pick & download right now.",
                    onClick = onInstall
                )
            }

            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onDownload,
                modifier = Modifier.fillMaxWidth().widthIn(max = 600.dp).height(Dimens.ButtonMinHeight),
                shape = MaterialTheme.shapes.medium,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer)
            ) {
                Icon(Icons.Default.Download, null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    if (ui.listCount > 0) "Download from a saved list (${ui.listCount})" else "Download from a saved list",
                    style = MaterialTheme.typography.titleSmall
                )
            }
            // U7: When no lists exist, show a subtle hint so the button's purpose is clear.
            if (ui.listCount == 0) {
                Text(
                    "Make a list first to use this option",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().widthIn(max = 600.dp)
                )
            }

            Spacer(Modifier.height(Dimens.Large))
            Text(
                if (ui.listCount == 1) "1 saved list" else "${ui.listCount} saved lists",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(4.dp))

            // U3: Show a more prominent sign-in prompt when not logged in.
            if (!ui.loggedIn) {
                OutlinedCard(
                    onClick = onSignIn,
                    modifier = Modifier.fillMaxWidth().widthIn(max = 600.dp),
                    colors = CardDefaults.outlinedCardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.18f)
                    ),
                    border = CardDefaults.outlinedCardBorder()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Lock,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(Dimens.IconSmall)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Sign in to start downloads",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.error
                            )
                            Text(
                                "Tap here to sign in",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            Icons.AutoMirrored.Filled.Login,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                            modifier = Modifier.size(Dimens.IconSmall)
                        )
                    }
                }
            } else {
                SuggestionChip(
                    onClick = { viewModel.logout() },
                    label = {
                        Text(
                            ui.email.takeIf { it.isNotBlank() }?.let { "Signed in as $it" } ?: "Signed in",
                            style = MaterialTheme.typography.bodySmall
                        )
                    },
                    icon = {
                        Icon(
                            Icons.Default.AccountCircle,
                            contentDescription = null,
                            modifier = Modifier.size(Dimens.IconSmall)
                        )
                    }
                )
            }
            Spacer(Modifier.height(4.dp))
            val appVersion = remember {
                try {
                    val pi = context.packageManager.getPackageInfo(context.packageName, 0)
                    "v${pi.versionName}"
                } catch (e: Exception) { "" }
            }
            Text(
                appVersion,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun HubTile(modifier: Modifier = Modifier, icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(Dimens.CardPadding)) {
            Icon(icon, null, modifier = Modifier.size(28.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
            Spacer(Modifier.height(10.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f))
        }
    }
}
