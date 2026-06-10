package io.github.mayusi.emuhelper.ui.lists

import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import io.github.mayusi.emuhelper.data.model.GameList
import io.github.mayusi.emuhelper.ui.common.Dimens
import io.github.mayusi.emuhelper.ui.common.formatSize

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SaveListScreen(
    onSaved: () -> Unit,
    onBack: () -> Unit,
    viewModel: ListViewModel = hiltViewModel()
) {
    val games by viewModel.pendingGames.collectAsState()
    val context = LocalContext.current

    // Set true the moment we save/export so the empty-bounce below does NOT fire when
    // persist() clears the staged games — otherwise we'd pop twice (past HOME) and
    // leave an empty NavHost (the "full transparent screen" bug).
    var finishing by remember { mutableStateOf(false) }

    // If we arrive with nothing staged (and we're not mid-finish), bounce back.
    LaunchedEffect(games) { if (games.isEmpty() && !finishing) onBack() }

    var name by remember { mutableStateOf("") }
    val totalSize = remember(games) { games.sumOf { it.size } }

    // Export-after-save: build the list once, write JSON to the chosen file, then finish.
    var exportPayload by remember { mutableStateOf<GameList?>(null) }
    val exporter = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val payload = exportPayload
        if (uri != null && payload != null) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { os ->
                    os.write(viewModel.encodeForExport(payload).toByteArray())
                }
            } catch (e: Exception) {
                Log.w("EmuHelper", "Export failed", e)
            }
        }
        // Whether or not export succeeded, the list was already saved.
        onSaved()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Save list", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(8.dp))
            Text("${games.size} items  ·  ${formatSize(totalSize)}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(20.dp))

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("List name") },
                placeholder = { Text("e.g. PS1 RPGs") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.extraSmall
            )

            Spacer(Modifier.height(24.dp))

            Button(
                onClick = {
                    finishing = true
                    val finalName = name.ifBlank { "List of ${games.size} items" }
                    viewModel.persist(viewModel.buildList(finalName, games), onSaved)
                },
                enabled = games.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().height(Dimens.ButtonMinHeight),
                shape = MaterialTheme.shapes.small,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) { Text("Save list", style = MaterialTheme.typography.titleMedium) }

            Spacer(Modifier.height(10.dp))

            OutlinedButton(
                onClick = {
                    finishing = true
                    val finalName = name.ifBlank { "List of ${games.size} items" }
                    // Build ONE list object; persist it now, then export that same object.
                    val list = viewModel.buildList(finalName, games)
                    viewModel.persist(list) { /* saved; export launcher will finish via onSaved */ }
                    exportPayload = list
                    exporter.launch("${finalName.replace(Regex("[^A-Za-z0-9 _-]"), "")}.json")
                },
                enabled = games.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().height(Dimens.ButtonMinHeight),
                shape = MaterialTheme.shapes.small
            ) { Text("Save & export to file") }

            Spacer(Modifier.height(12.dp))
            Text(
                "Saved lists live in the app. Export writes a .json you can back up or share.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}
