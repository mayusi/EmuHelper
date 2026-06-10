package io.github.mayusi.emuhelper.ui.setup

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.mayusi.emuhelper.data.storage.SettingsStore
import io.github.mayusi.emuhelper.ui.common.Dimens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class EmulatorSetupKeysViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val settings: SettingsStore
) : ViewModel() {

    private val _pickedFileUri = MutableStateFlow<Uri?>(null)
    val pickedFileUri: StateFlow<Uri?> = _pickedFileUri

    val stagingFolderUri: StateFlow<Uri?> = settings.setupStagingFolder
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _copyState = MutableStateFlow<CopyState>(CopyState.Idle)
    val copyState: StateFlow<CopyState> = _copyState

    fun onFilePicked(uri: Uri) {
        try {
            appContext.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: SecurityException) { }
        _pickedFileUri.value = uri
        _copyState.value = CopyState.Idle
    }

    fun onStagingFolderPicked(uri: Uri) {
        try {
            appContext.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: SecurityException) { }
        viewModelScope.launch { settings.setSetupStagingFolder(uri) }
    }

    fun copyToStaging(context: Context) {
        val pickedUri = _pickedFileUri.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _copyState.value = CopyState.Copying(0f)
            try {
                val displayName = DocumentFile.fromSingleUri(context, pickedUri)?.name ?: "prod.keys"
                val fileSize = DocumentFile.fromSingleUri(context, pickedUri)?.length() ?: 0L

                val inputStream = context.contentResolver.openInputStream(pickedUri)
                    ?: run {
                        _copyState.value = CopyState.Error("Could not read the selected file. Has the file been deleted?")
                        return@launch
                    }

                val treeUri = stagingFolderUri.value
                val outputStream = if (treeUri != null) {
                    val tree = DocumentFile.fromTreeUri(context, treeUri)
                    // Case-insensitive match: SAF's findFile is case-sensitive, but folders/files
                    // made by other tools (e.g. EmuTran) are often lowercase, so reuse/overwrite by name.
                    val existing = tree?.listFiles()?.firstOrNull { it.name?.equals(displayName, ignoreCase = true) == true }
                    existing?.delete()
                    tree?.createFile("application/octet-stream", displayName)?.uri?.let { destUri ->
                        context.contentResolver.openOutputStream(destUri)
                    }
                } else {
                    openMediaStoreOutputStream(context, displayName)
                }

                if (outputStream == null) {
                    inputStream.close()
                    _copyState.value = CopyState.Error("Could not open destination for writing.")
                    return@launch
                }

                val buffer = ByteArray(256 * 1024)
                var copied = 0L
                var read: Int
                while (inputStream.read(buffer).also { read = it } != -1) {
                    outputStream.write(buffer, 0, read)
                    copied += read
                    if (fileSize > 0) {
                        _copyState.value = CopyState.Copying(copied.toFloat() / fileSize)
                    }
                }
                inputStream.close()
                outputStream.close()

                val dest = if (treeUri != null) "your chosen folder" else "Downloads/EmuHelper-Setup/"
                _copyState.value = CopyState.Done(dest)
            } catch (e: Exception) {
                _copyState.value = CopyState.Error(e.localizedMessage ?: "Copy failed")
            }
        }
    }

    private fun openMediaStoreOutputStream(context: Context, displayName: String) =
        try {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/EmuHelper-Setup")
            }
            val insertUri = context.contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
            ) ?: return null
            context.contentResolver.openOutputStream(insertUri)
        } catch (_: Exception) { null }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmulatorSetupKeysScreen(
    emulator: String,
    onBack: () -> Unit,
    onNext: (String) -> Unit,
    onSkipFirmware: (String) -> Unit,
    viewModel: EmulatorSetupKeysViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val pickedUri by viewModel.pickedFileUri.collectAsState()
    val stagingUri by viewModel.stagingFolderUri.collectAsState()
    val copyState by viewModel.copyState.collectAsState()
    var showInfoDialog by remember { mutableStateOf(false) }

    val keysPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) viewModel.onFilePicked(uri) }

    val stagingFolderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri -> if (uri != null) viewModel.onStagingFolderPicked(uri) }

    if (showInfoDialog) {
        AlertDialog(
            onDismissRequest = { showInfoDialog = false },
            title = { Text("What is prod.keys?") },
            text = {
                Text(
                    "prod.keys is a small file containing cryptographic keys specific to your Nintendo Switch console. Without it, Switch emulators cannot decrypt game data.\n\n" +
                        "You must dump this file yourself using tools like Lockpick_RCM run on your own console. No legitimate source on the internet provides this file legally.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = { showInfoDialog = false }) { Text("OK") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Import keys — $emulator", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
                .padding(horizontal = Dimens.ScreenHorizontal, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Step indicator
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Step 1 of 3",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { 1f / 3f },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            // --- What is this? ---
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = MaterialTheme.shapes.medium
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "What is this?",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "prod.keys is a file you must dump from your own Nintendo Switch console. This app only helps you move the file into your emulator — it does not provide the file.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(onClick = { showInfoDialog = true }) {
                        Text("What is prod.keys?")
                    }
                }
            }

            // --- File picker ---
            val hasFile = pickedUri != null
            Card(
                onClick = { keysPicker.launch(arrayOf("*/*")) },
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (hasFile) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant
                ),
                shape = MaterialTheme.shapes.medium
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Pick your prod.keys file",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (pickedUri != null) {
                            val name = remember(pickedUri) {
                                DocumentFile.fromSingleUri(context, pickedUri!!)?.name ?: pickedUri.toString()
                            }
                            val size = remember(pickedUri) {
                                DocumentFile.fromSingleUri(context, pickedUri!!)?.length() ?: 0L
                            }
                            Text(
                                "$name  (${formatFileSize(size)})",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Text(
                                "Tap to pick prod.keys from your device",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // --- Staging folder ---
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = MaterialTheme.shapes.medium
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "Staging folder",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (stagingUri != null) stagingUri.toString()
                        else "Downloads/EmuHelper-Setup/  (default)",
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { stagingFolderPicker.launch(null) }) {
                        Text("Change staging folder…")
                    }
                }
            }

            // --- Copy button + progress ---
            Button(
                onClick = { viewModel.copyToStaging(context) },
                enabled = pickedUri != null && copyState !is CopyState.Copying,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small
            ) {
                Text("Copy prod.keys to staging folder")
            }

            when (val state = copyState) {
                is CopyState.Copying -> {
                    LinearProgressIndicator(
                        progress = { state.progress },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("Copying…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                is CopyState.Done -> {
                    Text(
                        "Done! File is in ${state.dest}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(4.dp))
                    Button(
                        onClick = { onNext(emulator) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Next: Import firmware")
                    }
                    OutlinedButton(
                        onClick = { onSkipFirmware(emulator) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Skip firmware — show instructions")
                    }
                }
                is CopyState.Error -> {
                    Text(
                        "Error: ${state.msg}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                else -> Unit
            }
        }
    }
}
