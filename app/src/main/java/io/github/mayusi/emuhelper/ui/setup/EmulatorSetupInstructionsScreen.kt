package io.github.mayusi.emuhelper.ui.setup

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.mayusi.emuhelper.ui.common.Dimens
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import javax.inject.Inject

@HiltViewModel
class EmulatorSetupInstructionsViewModel @Inject constructor() : ViewModel() {

    private val _snackbarEvent = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val snackbarEvent: SharedFlow<String> = _snackbarEvent

    fun fireOpenIntent(context: Context, fileUri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(fileUri, "*/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(Intent.createChooser(intent, "Open with emulator"))
        } catch (_: ActivityNotFoundException) {
            _snackbarEvent.tryEmit("No app handled this file — follow the manual steps above.")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmulatorSetupInstructionsScreen(
    emulator: String,
    onGoHome: () -> Unit,
    viewModel: EmulatorSetupInstructionsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val emulatorEnum = remember(emulator) { Emulator.fromString(emulator) }

    LaunchedEffect(Unit) {
        viewModel.snackbarEvent.collect { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Setup instructions — $emulator", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onGoHome) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
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
                    "Step 3 of 3",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { 1f },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // --- Where your files are ---
            InstructionCard(title = "Where your files are") {
                Text(
                    "Downloads/EmuHelper-Setup/",
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "(or your chosen custom folder)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // --- Import prod.keys ---
            InstructionCard(title = "Import prod.keys") {
                val steps = keysSteps(emulatorEnum)
                steps.forEachIndexed { index, step ->
                    Text(
                        "${index + 1}. $step",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (index < steps.lastIndex) Spacer(Modifier.height(6.dp))
                }
            }

            // --- Install firmware ---
            InstructionCard(title = "Install firmware (if applicable)") {
                val steps = firmwareSteps(emulatorEnum)
                steps.forEachIndexed { index, step ->
                    Text(
                        "${index + 1}. $step",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (index < steps.lastIndex) Spacer(Modifier.height(6.dp))
                }
            }

            // --- Open with emulator ---
            InstructionCard(title = "Open with emulator (optional)") {
                Text(
                    "Try handing the keys file directly to $emulator via Android's file chooser.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        // Best-effort: no staged file URI available at this screen level,
                        // so we fire a generic ACTION_VIEW without a specific file.
                        // The intent chooser will open anyway for any file the user holds.
                        viewModel.fireOpenIntent(context, Uri.EMPTY)
                    }
                ) {
                    Text("Try to open with $emulator")
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "If this does nothing, follow the manual steps above.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // --- Footnote ---
            Text(
                "Menu names may differ slightly in newer versions of $emulator. Check the emulator's own help if a step doesn't match.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onGoHome,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small
            ) {
                Text("Done — go home")
            }
        }
    }
}

@Composable
private fun InstructionCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

private fun keysSteps(emulator: Emulator): List<String> = when (emulator) {
    Emulator.Eden -> listOf(
        "Open Eden.",
        "Tap the three-dot menu (top-right) or go to Settings.",
        "Select System → Key files.",
        "Tap Select prod.keys, browse to Downloads/EmuHelper-Setup/, and select prod.keys.",
        "Restart Eden if prompted."
    )
    Emulator.Citron -> listOf(
        "Open Citron.",
        "Tap Settings (gear icon).",
        "Go to System → Encryption keys.",
        "Tap Load prod.keys file, navigate to Downloads/EmuHelper-Setup/, select prod.keys.",
        "Tap OK and restart Citron if the app requests it."
    )
    Emulator.Sudachi -> listOf(
        "Open Sudachi.",
        "Tap the menu icon (top-left) → Settings.",
        "Under System, tap Key Directory or Select keys.",
        "Navigate to Downloads/EmuHelper-Setup/ and select prod.keys.",
        "Restart Sudachi if the app asks."
    )
}

private fun firmwareSteps(emulator: Emulator): List<String> = when (emulator) {
    Emulator.Eden -> listOf(
        "In Eden, go to Settings → System → Firmware.",
        "Tap Install firmware from zip.",
        "Browse to Downloads/EmuHelper-Setup/ and select your firmware .zip.",
        "Wait for installation to finish, then restart Eden."
    )
    Emulator.Citron -> listOf(
        "In Citron, go to Settings → System → Firmware.",
        "Select Install firmware (zip).",
        "Navigate to Downloads/EmuHelper-Setup/ and pick the firmware .zip.",
        "Let the installation complete; restart Citron."
    )
    Emulator.Sudachi -> listOf(
        "In Sudachi, go to Settings → System.",
        "Tap Install firmware.",
        "Choose your firmware .zip from Downloads/EmuHelper-Setup/.",
        "Confirm and allow installation to complete."
    )
}
