package io.github.mayusi.emuhelper.ui.home

import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhoneAndroid
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
import io.github.mayusi.emuhelper.data.source.ArchiveOrgSource
import io.github.mayusi.emuhelper.data.source.LoginResult
import io.github.mayusi.emuhelper.data.storage.AuthStore
import io.github.mayusi.emuhelper.data.storage.GameListStore
import io.github.mayusi.emuhelper.data.storage.SettingsStore
import io.github.mayusi.emuhelper.di.IaCookieJar
import io.github.mayusi.emuhelper.ui.common.Dimens
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    listStore: GameListStore,
    private val settings: SettingsStore,
    private val authStore: AuthStore,
    private val source: ArchiveOrgSource,
    private val cookieJar: IaCookieJar
) : ViewModel() {

    data class HomeUi(val listCount: Int = 0, val loggedIn: Boolean = false, val email: String = "")

    val ui: StateFlow<HomeUi> = combine(
        listStore.lists,
        authStore.savedEmail,
        // Observe the cookie jar's reactive login state. It flips true once the
        // (async, off-main) disk restore completes on cold start — so a saved
        // session shows as signed-in without the user touching anything.
        cookieJar.loggedIn
    ) { lists, email, loggedIn ->
        HomeUi(listCount = lists.size, loggedIn = loggedIn, email = email)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HomeUi())

    init {
        // Silent auto-relogin: if we have no live session but the user saved their
        // credentials, sign in transparently in the background. This covers the case
        // where the persisted cookies expired — the user never has to tap "Sign in".
        viewModelScope.launch {
            // Give the cookie jar's async disk-restore a moment to publish state.
            kotlinx.coroutines.delay(400)
            if (cookieJar.loggedIn.value) return@launch
            val remember = authStore.rememberMe.first()
            val email = authStore.savedEmail.first()
            if (!remember || email.isBlank()) return@launch
            val pwd = authStore.getSavedPassword()
            if (pwd.isBlank()) return@launch
            when (source.login(email, pwd)) {
                is LoginResult.Success -> Log.i("EmuHelper", "silent auto-relogin OK for $email")
                is LoginResult.Failed -> Log.w("EmuHelper", "silent auto-relogin failed for $email")
            }
            // login() populates the cookie jar, which flips cookieJar.loggedIn reactively.
        }
    }

    /** No-op kept for callers; login state is now reactive via cookieJar.loggedIn. */
    fun refreshLogin() { /* cookieJar.loggedIn updates itself */ }

    fun logout() {
        viewModelScope.launch {
            authStore.clearCredentials()
            cookieJar.clear() // flips cookieJar.loggedIn -> false reactively
        }
    }

    fun setFolder(uri: Uri?) {
        viewModelScope.launch { settings.setDownloadFolder(uri) }
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
    viewModel: HomeViewModel = hiltViewModel()
) {
    val ui by viewModel.ui.collectAsState()
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }

    // Re-check login whenever Home becomes the active screen again.
    LaunchedEffect(Unit) { viewModel.refreshLogin() }

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
                Log.w("EmuHelper", "Persisting folder URI failed", e)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("EmuHelper", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary) },
                actions = {
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
                            text = { Text("Settings") },
                            leadingIcon = { Icon(Icons.Default.Settings, null) },
                            onClick = { menuOpen = false; onSettings() }
                        )
                        DropdownMenuItem(
                            text = { Text("Emulator setup") },
                            leadingIcon = { Icon(Icons.Default.PhoneAndroid, null) },
                            onClick = { menuOpen = false; onEmulatorSetup() }
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
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(Icons.Default.Download, null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(6.dp))
            Text("Download Manager", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(20.dp))

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
            // Download from a previously saved list — a primary action, full-width.
            Button(
                onClick = onDownload,
                modifier = Modifier.fillMaxWidth().widthIn(max = 600.dp).height(Dimens.ButtonMinHeight),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer)
            ) {
                Icon(Icons.Default.Download, null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    if (ui.listCount > 0) "Download from a saved list (${ui.listCount})" else "Download from a saved list",
                    style = MaterialTheme.typography.titleSmall
                )
            }

            Spacer(Modifier.height(20.dp))
            Text(
                buildString {
                    append(if (ui.listCount == 1) "1 saved list" else "${ui.listCount} saved lists")
                    append("  ·  ")
                    append(if (ui.loggedIn) (ui.email.takeIf { it.isNotBlank() }?.let { "signed in as $it" } ?: "signed in") else "not signed in")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
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
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            Icon(icon, null, modifier = Modifier.size(28.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
            Spacer(Modifier.height(10.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f))
        }
    }
}
