package io.github.mayusi.emuhelper.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mayusi.emuhelper.desktop.DownloadController.DownloadState
import io.github.mayusi.emuhelper.desktop.DownloadController.Screen
import io.github.mayusi.emuhelper.desktop.DownloadController.SearchState
import io.github.mayusi.emuhelper.desktop.DesktopSettingsStore.ThemeMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.awt.Desktop
import java.io.File
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.swing.JFileChooser

/**
 * The whole Phase-3a desktop UI: a single window driven by [DownloadController.state]. It's a lean,
 * purpose-built flow (NOT a port of the 25 Android screens) that proves the Compose UI stack + the
 * shared download engine work together on Windows:
 *
 *   Screen.CONSOLES → grid of consoles from the shared Catalog
 *   Screen.FILES    → scan result: checkable file list + a destination-folder picker + Download
 *   Screen.DOWNLOADS→ live per-file progress bars fed by the engine's onProgress callback
 *
 * Colours use a Catppuccin-ish dark scheme to echo the Android app without importing its theme.
 */

private val DarkColors = darkColorScheme(
    primary = Color(0xFF89B4FA),
    onPrimary = Color(0xFF11111B),
    primaryContainer = Color(0xFF2A2350),
    onPrimaryContainer = Color(0xFFE5E0FF),
    background = Color(0xFF1E1E2E),
    onBackground = Color(0xFFCDD6F4),
    surface = Color(0xFF181825),
    onSurface = Color(0xFFCDD6F4),
    surfaceVariant = Color(0xFF313244),
    onSurfaceVariant = Color(0xFFBAC2DE),
    tertiary = Color(0xFFA6E3A1),
    error = Color(0xFFF38BA8),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF3B5BDB),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDCE3FF),
    onPrimaryContainer = Color(0xFF122062),
    background = Color(0xFFF6F6FB),
    onBackground = Color(0xFF1B1B22),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1B1B22),
    surfaceVariant = Color(0xFFECECF3),
    onSurfaceVariant = Color(0xFF5A5A6A),
    tertiary = Color(0xFF0E9F6E),
    error = Color(0xFFD6204A),
)

@Composable
fun App(controller: DownloadController) {
    val ui by controller.state.collectAsState()
    val themeMode by controller.settings.themeMode.collectAsState()

    // Desktop has no OS dark-mode signal wired in for Phase 3b, so SYSTEM maps to the dark scheme
    // (the app's original look). LIGHT / DARK are honoured explicitly.
    val colors = if (themeMode == ThemeMode.LIGHT) LightColors else DarkColors

    MaterialTheme(colorScheme = colors) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                Header(ui, controller)
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                Box(Modifier.fillMaxSize().padding(16.dp)) {
                    when (ui.screen) {
                        Screen.CONSOLES -> ConsoleGrid(ui, controller)
                        Screen.FILES -> FilesScreen(ui, controller)
                        Screen.DOWNLOADS -> DownloadsScreen(ui, controller)
                        Screen.SETTINGS -> SettingsScreen(controller)
                        Screen.QUEUE -> QueueScreen(ui, controller)
                        Screen.HISTORY -> HistoryScreen(controller)
                        Screen.LOGIN -> LoginScreen(ui, controller)
                        Screen.SEARCH -> SearchScreen(ui, controller)
                        Screen.LIBRARY -> LibraryScreen(ui, controller)
                        Screen.ABOUT -> AboutScreen(controller)
                        Screen.SOURCE_HEALTH -> SourceHealthScreen(ui, controller)
                        Screen.LIBRARY_SCAN -> LibraryScanScreen(controller)
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(ui: DownloadController.UiState, controller: DownloadController) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(16.dp, 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A back affordance for every non-root screen; nav destinations return to their opener.
        if (ui.screen != Screen.CONSOLES) {
            IconButton(onClick = {
                when (ui.screen) {
                    Screen.FILES -> controller.backToConsoles()
                    Screen.DOWNLOADS -> controller.backToFiles()
                    else -> controller.navigateBack()
                }
            }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = MaterialTheme.colorScheme.onSurface)
            }
            Spacer(Modifier.width(8.dp))
        }
        val title = when (ui.screen) {
            Screen.CONSOLES -> "EmuHelper — Choose a console"
            Screen.FILES -> ui.filesTitle ?: ui.selectedConsole?.display ?: "Files"
            Screen.DOWNLOADS -> "Downloads"
            Screen.SETTINGS -> "Settings"
            Screen.QUEUE -> "Download queue"
            Screen.HISTORY -> "Download history"
            Screen.LOGIN -> "Account sign-in"
            Screen.SEARCH -> "Search the Internet Archive"
            Screen.LIBRARY -> "Your lists"
            Screen.ABOUT -> "About EmuHelper"
            Screen.SOURCE_HEALTH -> "Source health"
            Screen.LIBRARY_SCAN -> "Verify local files"
        }
        Text(title, fontWeight = FontWeight.Bold, fontSize = 18.sp,
            color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f))

        // Top-level nav actions (reachable from any screen). A dot on the queue icon signals activity.
        NavIconButton(Icons.Default.Search, "Search", ui.screen == Screen.SEARCH) {
            controller.navigateTo(Screen.SEARCH)
        }
        NavIconButton(Icons.AutoMirrored.Filled.ListAlt, "Your lists", ui.screen == Screen.LIBRARY) {
            controller.navigateTo(Screen.LIBRARY)
        }
        NavIconButton(Icons.Default.Download, "Queue", ui.screen == Screen.QUEUE,
            badge = ui.activeCount > 0) { controller.navigateTo(Screen.QUEUE) }
        NavIconButton(Icons.Default.History, "History", ui.screen == Screen.HISTORY) {
            controller.navigateTo(Screen.HISTORY)
        }
        NavIconButton(Icons.Default.Shield, "Verify local files", ui.screen == Screen.LIBRARY_SCAN) {
            controller.navigateTo(Screen.LIBRARY_SCAN)
        }
        NavIconButton(Icons.Default.NetworkCheck, "Source health", ui.screen == Screen.SOURCE_HEALTH) {
            controller.navigateTo(Screen.SOURCE_HEALTH)
        }
        NavIconButton(Icons.Default.Settings, "Settings", ui.screen == Screen.SETTINGS) {
            controller.navigateTo(Screen.SETTINGS)
        }
        NavIconButton(Icons.Default.Info, "About", ui.screen == Screen.ABOUT) {
            controller.navigateTo(Screen.ABOUT)
        }
        // Account indicator: a filled/outlined person icon whose tint signals login state; opens LOGIN.
        NavIconButton(
            icon = if (ui.loggedIn) Icons.Default.AccountCircle else Icons.Outlined.AccountCircle,
            label = if (ui.loggedIn) "Signed in${if (ui.accountEmail.isNotBlank()) " as ${ui.accountEmail}" else ""}" else "Sign in",
            selected = ui.screen == Screen.LOGIN,
            tintOverride = if (ui.loggedIn) MaterialTheme.colorScheme.tertiary else null,
        ) { controller.navigateTo(Screen.LOGIN) }
    }
}

@Composable
private fun NavIconButton(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    badge: Boolean = false,
    tintOverride: Color? = null,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick) {
        Box {
            Icon(
                icon, label,
                tint = when {
                    selected -> MaterialTheme.colorScheme.primary
                    tintOverride != null -> tintOverride
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            if (badge) {
                Box(
                    Modifier.size(8.dp).align(Alignment.TopEnd)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                )
            }
        }
    }
}

@Composable
private fun ConsoleGrid(ui: DownloadController.UiState, controller: DownloadController) {
    val favorites by controller.settings.favoriteConsoles.collectAsState()
    // Favourited consoles float to the top (stable within each group by the catalog's baked order).
    val ordered = remember(ui.consoles, favorites) {
        ui.consoles.sortedByDescending { it.key in favorites }
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(200.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(4.dp),
    ) {
        items(ordered, key = { it.key }) { console ->
            val isFav = console.key in favorites
            Card(
                onClick = { controller.openConsole(console) },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.height(96.dp).fillMaxWidth(),
            ) {
                Row(Modifier.fillMaxSize().padding(start = 14.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(18.dp).background(Color(console.color), CircleShape))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(console.display, fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface, maxLines = 2,
                            overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                        Spacer(Modifier.height(4.dp))
                        val label = if (console.sourceCount == 0) "no sources"
                            else "${console.sourceCount} source${if (console.sourceCount == 1) "" else "s"}"
                        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { controller.toggleFavoriteConsole(console.key) }) {
                        Icon(
                            if (isFav) Icons.Default.Star else Icons.Default.StarBorder,
                            if (isFav) "Unfavourite" else "Favourite",
                            tint = if (isFav) Color(0xFFF9E2AF) else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FilesScreen(ui: DownloadController.UiState, controller: DownloadController) {
    Column(Modifier.fillMaxSize()) {
        // Destination folder + selection controls.
        DestFolderBar(ui, controller)
        Spacer(Modifier.height(8.dp))

        when {
            ui.scanning -> CenterHint {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(12.dp))
                Text("Scanning ${ui.selectedConsole?.display ?: ""}…",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            ui.scanError != null -> CenterHint {
                Icon(Icons.Default.Error, null, tint = Color(0xFFF38BA8), modifier = Modifier.size(32.dp))
                Spacer(Modifier.height(12.dp))
                Text(ui.scanError, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            ui.files.isEmpty() -> CenterHint {
                Text("No files.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> {
                FilesFilterBar(ui, controller)
                Spacer(Modifier.height(6.dp))
                val visible = ui.visibleFiles
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    val shown = if (visible.size == ui.files.size) "${ui.files.size} file(s)"
                        else "${visible.size} of ${ui.files.size} file(s)"
                    Text("$shown — ${ui.selectedCount} selected",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp,
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = { controller.setAllSelected(true) }) { Text("Select all") }
                    TextButton(onClick = { controller.setAllSelected(false) }) { Text("Clear") }
                }
                if (visible.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text("No files match your filter.", fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(visible, key = { it.file.identifier + "/" + it.file.filename }) { row ->
                            FileRowView(row, controller)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { controller.startDownload() },
                    enabled = ui.selectedCount > 0 && !ui.downloading,
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                ) {
                    Text(if (ui.selectedCount > 0) "Download ${ui.selectedCount} file(s)" else "Select files to download")
                }
            }
        }
    }
}

/**
 * FILES-screen filter bar (A1–A3): a debounced search field, a sort dropdown (Name A–Z / Z–A /
 * Largest / Smallest), and a selection filter chip cycling All → Only selected → Hide selected.
 * Mirrors the Android GamePickerScreen filter row. Search is debounced ~220ms (matching Android)
 * before it's pushed into controller state.
 */
@Composable
private fun FilesFilterBar(ui: DownloadController.UiState, controller: DownloadController) {
    var query by remember { mutableStateOf(ui.fileSearchQuery) }
    var sortMenu by remember { mutableStateOf(false) }
    // Debounce: push the query into controller state ~220ms after the user stops typing.
    LaunchedEffect(query) {
        delay(220)
        controller.setFileSearchQuery(query)
    }

    Column(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search files…") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) { Icon(Icons.Default.Cancel, "Clear search") }
                }
            },
            singleLine = true,
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Sort dropdown.
            Box {
                AssistChip(
                    onClick = { sortMenu = true },
                    label = { Text(ui.fileSort.label) },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.Sort, null, Modifier.size(18.dp)) },
                )
                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                    FileSort.entries.forEach { sort ->
                        DropdownMenuItem(
                            text = { Text(sort.label) },
                            onClick = { controller.setFileSort(sort); sortMenu = false },
                            trailingIcon = {
                                if (sort == ui.fileSort) Icon(Icons.Default.Check, null, Modifier.size(18.dp))
                            },
                        )
                    }
                }
            }
            // Selection filter chip (cycles through the tri-state on click).
            FilterChip(
                selected = ui.selectionFilter != SelectionFilter.ALL,
                onClick = {
                    val next = when (ui.selectionFilter) {
                        SelectionFilter.ALL -> SelectionFilter.SELECTED
                        SelectionFilter.SELECTED -> SelectionFilter.UNSELECTED
                        SelectionFilter.UNSELECTED -> SelectionFilter.ALL
                    }
                    controller.setSelectionFilter(next)
                },
                label = { Text(ui.selectionFilter.label) },
                leadingIcon = { Icon(Icons.Default.Check, null, Modifier.size(18.dp)) },
            )
        }
    }
}

@Composable
private fun FileRowView(row: DownloadController.FileRow, controller: DownloadController) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth().clickable { controller.toggleFile(row) },
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = row.selected, onCheckedChange = { controller.toggleFile(row) })
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(row.file.name, color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(humanSize(row.file.size), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun DestFolderBar(ui: DownloadController.UiState, controller: DownloadController) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Save to", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(ui.destFolder, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            OutlinedButton(onClick = {
                chooseDirectory(ui.destFolder)?.let { controller.setDestFolder(it) }
            }) { Text("Change…") }
        }
    }
}

@Composable
private fun DownloadsScreen(ui: DownloadController.UiState, controller: DownloadController) {
    Column(Modifier.fillMaxSize()) {
        Text("Saving to: ${ui.destFolder}", fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        OverallProgressBar(ui)
        Spacer(Modifier.height(10.dp))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(ui.downloads) { d -> DownloadRowView(d, controller) }
        }
        Spacer(Modifier.height(8.dp))
        DownloadControlsRow(ui, controller, onDone = { controller.backToFiles() }, doneLabel = "Back to files")
    }
}

/** Overall batch progress + a "N of M done" caption; shared by the in-flow and standalone queue views. */
@Composable
private fun OverallProgressBar(ui: DownloadController.UiState) {
    if (ui.downloads.isEmpty()) return
    val done = ui.downloads.count { it.state == DownloadState.DONE }
    val failed = ui.downloads.count { it.state == DownloadState.FAILED || it.state == DownloadState.CANCELLED }
    Column(Modifier.fillMaxWidth()) {
        LinearProgressIndicator(
            progress = { ui.overallProgress },
            modifier = Modifier.fillMaxWidth().height(8.dp),
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(4.dp))
        val suffix = if (failed > 0) "  ·  $failed failed/cancelled" else ""
        // Aggregate live speed across in-flight rows (A7) — sum the per-row rates.
        val aggSpeed = ui.downloads.filter { it.state == DownloadState.DOWNLOADING }.sumOf { it.speedBytesPerSec }
        val speedTxt = if (!ui.paused && aggSpeed > 0) "  ·  ${humanSize(aggSpeed.toLong())}/s" else ""
        Text(
            "Overall: ${(ui.overallProgress * 100).toInt()}%  ·  $done / ${ui.downloads.size} done$suffix$speedTxt" +
                if (ui.paused) "  ·  PAUSED" else "",
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Pause/Resume + Cancel while downloading; a "done" action otherwise. Reused by both progress views. */
@Composable
private fun DownloadControlsRow(
    ui: DownloadController.UiState,
    controller: DownloadController,
    onDone: () -> Unit,
    doneLabel: String,
) {
    val scope = rememberCoroutineScope()
    var confirmCancel by remember { mutableStateOf(false) }

    if (confirmCancel) {
        AlertDialog(
            onDismissRequest = { confirmCancel = false },
            title = { Text("Cancel all downloads?") },
            text = { Text("Stop every queued and in-progress download in this batch? Partially-downloaded files are removed.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmCancel = false
                    scope.launch { controller.cancelDownloads() }
                }) { Text("Cancel all", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmCancel = false }) { Text("Keep downloading") } },
        )
    }

    val failedCount = ui.downloads.count {
        it.state == DownloadState.FAILED || it.state == DownloadState.CANCELLED
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (ui.downloading) {
            if (ui.paused) {
                Button(onClick = { controller.resumeDownloads() }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Resume")
                }
            } else {
                OutlinedButton(onClick = { controller.pauseDownloads() }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Pause, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Pause")
                }
            }
            OutlinedButton(onClick = { confirmCancel = true }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Cancel, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Cancel all")
            }
        } else {
            if (failedCount > 0) {
                OutlinedButton(onClick = { controller.retryFailed() }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                    Text("Retry $failedCount failed")
                }
            }
            Button(onClick = onDone, modifier = Modifier.weight(1f)) { Text(doneLabel) }
        }
    }
}

@Composable
private fun DownloadRowView(d: DownloadController.DownloadRow, controller: DownloadController) {
    var showScan by remember { mutableStateOf(false) }
    if (showScan && d.scan != null) {
        ScanDetailsDialog(d, controller, onDismiss = { showScan = false })
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(d.name, color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                // Safety badge (A4) — clickable, opens the details dialog. Only once a scan exists.
                d.scan?.let { report ->
                    SafetyBadge(report.verdict) { showScan = true }
                    Spacer(Modifier.width(6.dp))
                }
                when (d.state) {
                    DownloadState.DONE -> Icon(Icons.Default.Check, null, tint = Color(0xFFA6E3A1))
                    DownloadState.FAILED -> Icon(Icons.Default.Error, null, tint = Color(0xFFF38BA8))
                    DownloadState.CANCELLED -> Icon(Icons.Default.Cancel, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    DownloadState.PAUSED -> Icon(Icons.Default.Pause, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    else -> {}
                }
            }
            Spacer(Modifier.height(6.dp))
            val frac = if (d.totalBytes > 0) (d.doneBytes.toFloat() / d.totalBytes).coerceIn(0f, 1f)
                else if (d.state == DownloadState.DONE) 1f else 0f
            LinearProgressIndicator(
                progress = { frac },
                modifier = Modifier.fillMaxWidth().height(6.dp),
                color = when (d.state) {
                    DownloadState.DONE -> Color(0xFFA6E3A1)
                    DownloadState.FAILED -> Color(0xFFF38BA8)
                    else -> MaterialTheme.colorScheme.primary
                },
            )
            Spacer(Modifier.height(4.dp))
            val status = when (d.state) {
                DownloadState.QUEUED -> "Queued"
                DownloadState.DOWNLOADING ->
                    "${humanSize(d.doneBytes)} / ${humanSize(d.totalBytes)}" +
                        (if (d.speedBytesPerSec > 0) "  ·  ${humanSize(d.speedBytesPerSec.toLong())}/s" else "")
                DownloadState.PAUSED -> "Paused  ·  ${humanSize(d.doneBytes)} / ${humanSize(d.totalBytes)}"
                DownloadState.DONE -> "Done  ·  ${d.savedPath ?: ""}"
                DownloadState.FAILED -> "Failed  ·  ${d.error ?: ""}"
                DownloadState.CANCELLED -> "Cancelled"
            }
            Text(status, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)

            // Per-row actions (A7): open folder on a finished file; retry a failed/cancelled one.
            val showActions = d.state == DownloadState.DONE ||
                d.state == DownloadState.FAILED || d.state == DownloadState.CANCELLED
            if (showActions) {
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (d.state == DownloadState.DONE) {
                        TextButton(onClick = { controller.openFolder(d.savedPath ?: d.destFolder) }) {
                            Icon(Icons.Default.FolderOpen, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp)); Text("Open folder", fontSize = 12.sp)
                        }
                    } else if (d.source != null && !controller.state.value.downloading) {
                        TextButton(onClick = { controller.redownload(listOf(d.source)) }) {
                            Icon(Icons.Default.Refresh, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp)); Text("Retry", fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

/** A small pill showing the scan verdict, coloured by band; opens the details dialog on click. */
@Composable
private fun SafetyBadge(verdict: io.github.mayusi.emuhelper.data.safety.SafetyVerdict, onClick: () -> Unit) {
    val band = bandForVerdict(verdict)
    val label = verdict.name.lowercase().replaceFirstChar { it.uppercase() }.replace('_', ' ')
    Row(
        Modifier
            .background(Color(band.argb).copy(alpha = 0.22f), RoundedCornerShape(50))
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Shield, "Safety scan", Modifier.size(14.dp), tint = Color(band.argb))
        Spacer(Modifier.width(4.dp))
        Text(label, fontSize = 11.sp, color = Color(band.argb), fontWeight = FontWeight.SemiBold)
    }
}

/**
 * A4 scan-details dialog: the 0–100 score, per-check breakdown (code + status + detail), the
 * best-effort disclaimer, and — for amber/red files — a "Move to Quarantine" button.
 */
@Composable
private fun ScanDetailsDialog(
    d: DownloadController.DownloadRow,
    controller: DownloadController,
    onDismiss: () -> Unit,
) {
    val report = d.scan ?: return
    var actionMsg by remember { mutableStateOf("") }
    val band = bandForVerdict(report.verdict)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Shield, null, Modifier.size(20.dp), tint = Color(band.argb))
                Spacer(Modifier.width(8.dp))
                Text("Security scan")
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(d.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp))
                val verdictLabel = report.verdict.name.lowercase().replaceFirstChar { it.uppercase() }.replace('_', ' ')
                Text("Verdict: $verdictLabel  ·  Score ${report.score.value}/100 (${report.score.band.name.lowercase()})",
                    fontSize = 13.sp, color = Color(band.argb), fontWeight = FontWeight.SemiBold)
                if (report.magicType != null || report.declaredType != null) {
                    Text("Type: detected ${report.magicType ?: "unknown"} · declared ${report.declaredType ?: "?"}",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                report.sha256?.let {
                    Text("SHA-256: $it", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(10.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                Spacer(Modifier.height(8.dp))
                report.checks.forEach { c ->
                    val (icon, tint) = when (c.status) {
                        io.github.mayusi.emuhelper.data.safety.CheckStatus.PASSED ->
                            Icons.Default.Check to Color(0xFFA6E3A1)
                        io.github.mayusi.emuhelper.data.safety.CheckStatus.WARN ->
                            Icons.Default.Error to Color(0xFFF9E2AF)
                        io.github.mayusi.emuhelper.data.safety.CheckStatus.FLAG ->
                            Icons.Default.Error to Color(0xFFF38BA8)
                        io.github.mayusi.emuhelper.data.safety.CheckStatus.NOT_APPLICABLE ->
                            Icons.Default.Cancel to MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Row(Modifier.padding(vertical = 3.dp)) {
                        Icon(icon, c.status.name, Modifier.size(16.dp).padding(top = 2.dp), tint = tint)
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text("${c.code} · ${c.status.name}", fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                            Text(c.detail, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text("This is the best local check we could build — not a guarantee (no offline scan " +
                    "catches everything), but better than nothing. It never blocks a download.",
                    fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (actionMsg.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(actionMsg, fontSize = 11.sp, color = MaterialTheme.colorScheme.tertiary)
                }
            }
        },
        confirmButton = {
            if (isQuarantineWorthy(report.verdict) && d.savedPath != null) {
                TextButton(onClick = {
                    controller.quarantineDownload(d) { actionMsg = it }
                }) {
                    Text("Move to Quarantine", color = MaterialTheme.colorScheme.error)
                }
            } else {
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
        dismissButton = {
            if (isQuarantineWorthy(report.verdict) && d.savedPath != null) {
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
    )
}

// ---- Phase 3b: SETTINGS screen ------------------------------------------------------------------
// Adapts app/ui/settings/SettingsScreen.kt: theme chips, download-speed sliders (segments +
// concurrency) with max-throughput / reset, extract-archives + adaptive-engine toggles, and a
// download-folder chooser (Swing JFileChooser). Omits Android-only bits (wifi-only, SAF, speed test,
// device RAM/CPU readout). State comes from DesktopSettingsStore, not a Hilt ViewModel.

@Composable
private fun SettingsScreen(controller: DownloadController) {
    val s = controller.settings
    val segments by s.segments.collectAsState()
    val concurrency by s.concurrency.collectAsState()
    val extract by s.extractArchives.collectAsState()
    val adaptive by s.adaptiveEngine.collectAsState()
    val theme by s.themeMode.collectAsState()
    val folder by s.downloadFolder.collectAsState()
    val ui by controller.state.collectAsState()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SettingCard("Appearance") {
            Text("Theme", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeMode.entries.forEach { mode ->
                    FilterChip(
                        selected = theme == mode,
                        onClick = { s.setThemeMode(mode) },
                        label = { Text(mode.name.lowercase().replaceFirstChar { it.uppercase() }) },
                    )
                }
            }
        }

        SettingCard("Download speed") {
            Text("Connections per file: $segments", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
            Text("Some sources throttle each connection, so more connections can be faster (up to your link's limit).",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Slider(value = segments.toFloat(), onValueChange = { s.setSegments(it.toInt()) },
                valueRange = 1f..16f, steps = 14)
            Spacer(Modifier.height(8.dp))
            Text("Files at once: $concurrency", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
            Slider(value = concurrency.toFloat(), onValueChange = { s.setConcurrency(it.toInt()) },
                valueRange = 1f..4f, steps = 2)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { s.maxThroughput() }) { Text("Max throughput (16 × 2)") }
                OutlinedButton(onClick = { s.resetDownloadDefaults() }) { Text("Reset to defaults") }
            }
        }

        SettingCard("After download") {
            ToggleRow(
                title = "Extract .zip archives after download",
                subtitle = "When on, a downloaded .zip is unpacked into the download folder and the archive is removed. " +
                    ".rar and other formats are left as-is (desktop has no RAR engine).",
                checked = extract,
                onChange = { s.setExtractArchives(it) },
            )
        }

        SettingCard("Experimental") {
            ToggleRow(
                title = "Faster multi-mirror downloads",
                subtitle = "Reuses warm connections and spreads across Internet Archive mirrors. On by default — turn off to use the simpler single-path downloader if downloads ever misbehave.",
                checked = adaptive,
                onChange = { s.setAdaptiveEngine(it) },
            )
        }

        SettingCard("Storage") {
            Text("Download folder", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Text(ui.destFolder, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    chooseDirectory(ui.destFolder)?.let { controller.setDestFolder(it) }
                }) { Text("Change folder…") }
                if (folder.isNotBlank()) {
                    TextButton(onClick = {
                        // Fall back to the default location (~/Downloads/EmuHelper).
                        val def = File(File(System.getProperty("user.home"), "Downloads"), "EmuHelper").absolutePath
                        controller.setDestFolder("")
                        controller.setDestFolder(def)
                    }) { Text("Use default folder") }
                }
            }
        }
    }
}

@Composable
private fun SettingCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

// ---- Phase 3b: standalone download QUEUE screen -------------------------------------------------
// Adapts app/ui/download/DownloadScreen.kt to the desktop DownloadController state: overall + per-file
// progress with pause/resume/cancel. Distinct from the in-flow DOWNLOADS view (same widgets, reachable
// from the nav so the user can leave/return to a running batch).

@Composable
private fun QueueScreen(ui: DownloadController.UiState, controller: DownloadController) {
    if (ui.downloads.isEmpty()) {
        CenterHint {
            Icon(Icons.Default.Download, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.size(56.dp))
            Spacer(Modifier.height(12.dp))
            Text("No active downloads", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text("Pick a console and start a download to see it here.",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    Column(Modifier.fillMaxSize()) {
        Text("Saving to: ${ui.destFolder}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        OverallProgressBar(ui)
        Spacer(Modifier.height(10.dp))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(ui.downloads) { d -> DownloadRowView(d, controller) }
        }
        Spacer(Modifier.height(8.dp))
        DownloadControlsRow(ui, controller, onDone = { controller.navigateTo(Screen.CONSOLES) },
            doneLabel = "Browse consoles")
    }
}

// ---- Phase 3b: HISTORY screen -------------------------------------------------------------------
// Adapts app/ui/history/HistoryScreen.kt: list of completed/failed downloads with status icon, size,
// time, and a Clear-all action (with confirm dialog). Backed by DesktopHistoryStore (file JSON), not
// the Android DataStore HistoryStore. Omits the per-entry bottom sheet (open folder / re-download —
// those depend on the browse/library batches not yet ported).

private val sdfHistTime = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())

@Composable
private fun HistoryScreen(controller: DownloadController) {
    val entries by controller.history.entries.collectAsState()
    val decodeError by controller.history.decodeError.collectAsState()
    var showClear by remember { mutableStateOf(false) }

    if (showClear) {
        AlertDialog(
            onDismissRequest = { showClear = false },
            title = { Text("Clear history") },
            text = { Text("Remove all download history entries? This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = { controller.history.clear(); showClear = false }) {
                    Text("Clear", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { showClear = false }) { Text("Cancel") } },
        )
    }

    Column(Modifier.fillMaxSize()) {
        if (decodeError) {
            Text("Download history couldn't be read.", fontSize = 12.sp,
                color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 8.dp))
        }
        if (entries.isEmpty()) {
            CenterHint {
                Icon(Icons.Default.History, null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.size(56.dp))
                Spacer(Modifier.height(12.dp))
                Text("No downloads yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("${entries.size} entr${if (entries.size == 1) "y" else "ies"}",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                TextButton(onClick = { showClear = true }) {
                    Icon(Icons.Default.DeleteSweep, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Clear")
                }
            }
            Spacer(Modifier.height(4.dp))
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(entries, key = { "${it.timestampMillis}_${it.filename}" }) { e -> HistoryEntryCard(e, controller) }
            }
        }
    }
}

@Composable
private fun HistoryEntryCard(e: DesktopHistoryStore.DesktopHistoryEntry, controller: DownloadController) {
    val isDone = e.status == "DONE"
    val statusColor = if (isDone) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error
    val statusIcon = if (isDone) Icons.Default.CheckCircle else Icons.Default.Error
    var expanded by remember { mutableStateOf(false) }
    var actionMsg by remember { mutableStateOf("") }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(statusIcon, if (isDone) "Done" else "Failed", Modifier.size(20.dp), tint = statusColor)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(e.name.ifBlank { e.filename }, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val sub = if (e.subfolder.isNotBlank()) "→ ${e.subfolder}/" else e.filename
                    Text(sub, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (!isDone && e.error.isNotBlank()) {
                        Text(e.error, fontSize = 11.sp, color = MaterialTheme.colorScheme.error,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Spacer(Modifier.width(8.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Text(if (e.sizeBytes > 0) humanSize(e.sizeBytes) else "—", fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(sdfHistTime.format(Date(e.timestampMillis)), fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            // Expandable action row (A8): open folder / copy name / re-download.
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (e.destFolder.isNotBlank()) {
                        TextButton(onClick = { controller.openFolder(e.destFolder) }) {
                            Icon(Icons.Default.FolderOpen, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp)); Text("Open folder", fontSize = 12.sp)
                        }
                    }
                    TextButton(onClick = {
                        copyToClipboard(e.name.ifBlank { e.filename }); actionMsg = "Name copied."
                    }) {
                        Icon(Icons.Default.FileUpload, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp)); Text("Copy name", fontSize = 12.sp)
                    }
                    if (e.identifier.isNotBlank()) {
                        TextButton(onClick = {
                            controller.openIdentifier(e.identifier, e.name) { actionMsg = it }
                        }) {
                            Icon(Icons.Default.Download, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp)); Text("Re-download", fontSize = 12.sp)
                        }
                    }
                }
                if (actionMsg.isNotBlank()) {
                    Text(actionMsg, fontSize = 11.sp, color = MaterialTheme.colorScheme.tertiary)
                }
            }
        }
    }
}

@Composable
private fun CenterHint(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) { content() }
    }
}

// ---- helpers ------------------------------------------------------------------------------------

private fun humanSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var v = bytes.toDouble() / 1024
    var i = 0
    while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
    return String.format("%.1f %s", v, units[i])
}

/**
 * Native desktop directory chooser. Uses Swing's [JFileChooser] in DIRECTORIES_ONLY mode (works on
 * Windows/Linux); on macOS the AWT [FileDialog] with apple.awt.fileDialogForDirectories is nicer, but
 * JFileChooser is the portable, dependency-free choice for Phase 3a. Returns the chosen absolute path
 * or null if cancelled. Runs on the calling (UI) thread — the dialog is modal and quick.
 */
private fun chooseDirectory(initial: String): String? {
    return try {
        val chooser = JFileChooser(File(initial).takeIf { it.exists() } ?: File(System.getProperty("user.home")))
        chooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        chooser.dialogTitle = "Choose download folder"
        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
            chooser.selectedFile?.absolutePath
        } else null
    } catch (_: Throwable) {
        null
    }
}

/**
 * Browser handoff — opens [url] in the user's default browser via [java.awt.Desktop]. This is the
 * DESKTOP replacement for the Android in-app WebView flows (account signup, project links, docs):
 * desktop Compose has no WebView, and embedding one would pull a heavy native dependency, so we hand
 * the URL to the OS browser instead. Best-effort: returns false (silently) if unsupported.
 */
private fun openInBrowser(url: String): Boolean = try {
    if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
        Desktop.getDesktop().browse(URI(url)); true
    } else false
} catch (_: Throwable) { false }

/** Copy [text] to the system clipboard (best-effort; silently ignores headless/denied environments). */
private fun copyToClipboard(text: String) {
    runCatching {
        val sel = java.awt.datatransfer.StringSelection(text)
        java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(sel, sel)
    }
}

/** Native "open a .json file" chooser for list import. Returns the chosen file, or null if cancelled. */
private fun chooseJsonFile(): File? = try {
    val chooser = JFileChooser(File(System.getProperty("user.home")))
    chooser.fileSelectionMode = JFileChooser.FILES_ONLY
    chooser.dialogTitle = "Import a saved list (.json)"
    if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
} catch (_: Throwable) { null }

// ---- Phase 3b batch 2: LOGIN screen -------------------------------------------------------------
// Adapts app/ui/login/LoginScreen.kt: email + password fields, show/hide password, "Remember me",
// "Log in" (→ shared RemoteSource.login via controller.login), "Skip login (public access)", plus
// loading + error state. The Android "Create one" path opened an in-app WebView (SignupWebViewScreen);
// desktop has no WebView, so it's a BROWSER HANDOFF to the IA signup page (openInBrowser). When already
// signed in the screen shows the account + a Sign-out button (parity with the Android logged-in state).

private const val IA_SIGNUP_URL = "https://archive.org/account/signup"

@Composable
private fun LoginScreen(ui: DownloadController.UiState, controller: DownloadController) {
    if (ui.loggedIn) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.width(460.dp),
            ) {
                Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.AccountCircle, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.tertiary)
                    Spacer(Modifier.height(12.dp))
                    Text("You're signed in", fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface)
                    if (ui.accountEmail.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(ui.accountEmail, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(20.dp))
                    OutlinedButton(onClick = { controller.logout() }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.AutoMirrored.Filled.Logout, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Sign out")
                    }
                }
            }
        }
        return
    }

    var email by remember(ui.accountEmail) { mutableStateOf(ui.accountEmail) }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var rememberMe by remember { mutableStateOf(true) }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.width(460.dp).verticalScroll(rememberScrollState()),
        ) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Lock, null, Modifier.size(44.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(12.dp))
                Text("Account Sign-In", fontSize = 20.sp, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(4.dp))
                Text("Sign in to access the configured source", fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(20.dp))

                OutlinedTextField(
                    value = email, onValueChange = { email = it },
                    label = { Text("Email address") },
                    supportingText = { Text("Case-sensitive — matches your account exactly", fontSize = 11.sp) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = password, onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (showPassword) "Hide password" else "Show password",
                            )
                        }
                    },
                )
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = rememberMe, onCheckedChange = { rememberMe = it })
                    Text("Remember me", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (ui.loginError.isNotBlank()) {
                    Text(ui.loginError, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(4.dp))
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { controller.login(email, password, rememberMe) },
                    enabled = !ui.loginLoading && email.isNotBlank() && password.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                ) {
                    if (ui.loginLoading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary)
                    else Text("Log In")
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { controller.navigateBack() }) {
                    Text("Skip login (public access)", fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { openInBrowser(IA_SIGNUP_URL) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Don't have an account? Create one", fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary)
                    }
                }
                Text("Opens the Internet Archive signup page in your browser.", fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ---- Phase 3b batch 2: SEARCH screen ------------------------------------------------------------
// Adapts app/ui/search/SearchAllScreen.kt: a debounced search field over the IA advancedsearch API
// (controller.search — same buildSearchUrl/parseSearchResults logic as the Android SearchViewModel),
// a results list, and the persistent "public content isn't vetted" caption (that safety note matters).
// Tapping a result → controller.openIdentifier loads its files into the FILES screen (the desktop
// analogue of the Android "add from search" → shared picker).

@Composable
private fun SearchScreen(ui: DownloadController.UiState, controller: DownloadController) {
    var query by remember { mutableStateOf("") }
    var loadError by remember { mutableStateOf("") }
    var showAddLink by remember { mutableStateOf(false) }

    if (showAddLink) {
        AddFromLinkDialog(controller, onDismiss = { showAddLink = false })
    }

    // Debounce: search ~350ms after the user stops typing (matches the Android screen).
    LaunchedEffect(query) {
        delay(350)
        controller.search(query)
    }

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Search archive.org…") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                singleLine = true,
            )
            OutlinedButton(onClick = { showAddLink = true }) {
                Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp)); Text("Add link")
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Results are from the public Internet Archive and aren't vetted — download what you trust.",
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (loadError.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(loadError, fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(8.dp))

        Box(Modifier.fillMaxSize()) {
            when (val s = ui.searchState) {
                is SearchState.Idle -> CenterHint {
                    Icon(Icons.Default.Search, null, Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                    Spacer(Modifier.height(12.dp))
                    Text("Search the Internet Archive", color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.height(4.dp))
                    Text("Find any public item by name, then pick its files to download.",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                is SearchState.Loading -> CenterHint {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
                is SearchState.Empty -> CenterHint {
                    Icon(Icons.Default.SearchOff, null, Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                    Spacer(Modifier.height(12.dp))
                    Text("No results", color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.height(4.dp))
                    Text("Nothing matched that search. Try different words.",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                is SearchState.Error -> CenterHint {
                    Icon(Icons.Default.SearchOff, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(12.dp))
                    Text(s.message, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                is SearchState.Results -> {
                    val savedLists by controller.lists.lists.collectAsState()
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(s.items, key = { it.identifier }) { item ->
                            var addMenu by remember { mutableStateOf(false) }
                            Card(
                                onClick = { controller.openIdentifier(item.identifier, item.title) { loadError = it } },
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp),
                            ) {
                                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(item.title, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Spacer(Modifier.height(2.dp))
                                        Text(item.identifier, fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            if (item.mediatype.isNotBlank()) {
                                                Text(item.mediatype, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                                            }
                                            item.downloads?.let {
                                                Text("$it downloads", fontSize = 11.sp,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f))
                                            }
                                        }
                                    }
                                    // B3: add this item to a saved list (whole-item; games resolve on open).
                                    Box {
                                        IconButton(onClick = { addMenu = true }) {
                                            Icon(Icons.AutoMirrored.Filled.ListAlt, "Add to list",
                                                tint = MaterialTheme.colorScheme.primary)
                                        }
                                        DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                                            if (savedLists.isEmpty()) {
                                                DropdownMenuItem(
                                                    text = { Text("Create a list first", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                                                    onClick = { addMenu = false },
                                                    enabled = false,
                                                )
                                            } else {
                                                savedLists.forEach { list ->
                                                    DropdownMenuItem(
                                                        text = { Text("Add to \"${list.name}\"") },
                                                        onClick = {
                                                            addMenu = false
                                                            controller.addItemToList(
                                                                list.id,
                                                                DesktopListStore.DesktopCuratedGame(
                                                                    name = item.title,
                                                                    identifier = item.identifier,
                                                                    source = "search",
                                                                ),
                                                            )
                                                            loadError = "Added to \"${list.name}\"."
                                                        },
                                                    )
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
            if (ui.loadingIdentifier) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background.copy(alpha = 0.6f)),
                    contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

/**
 * A10 add-from-link dialog: takes an archive.org identifier or full item URL and funnels it through
 * [DownloadController.openIdentifier] into the FILES picker (the desktop analogue of Android's
 * "add from link"). Validation + fetch happen in the controller; errors surface inline.
 */
@Composable
private fun AddFromLinkDialog(controller: DownloadController, onDismiss: () -> Unit) {
    var input by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add from archive.org link") },
        text = {
            Column {
                Text("Paste an archive.org item link or its identifier to load its files.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = input, onValueChange = { input = it; error = "" },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("https://archive.org/details/… or identifier") },
                    singleLine = true,
                )
                if (error.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(error, fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = input.isNotBlank(),
                onClick = {
                    controller.openIdentifier(input, title = "") { msg -> error = msg }
                    // openIdentifier navigates to FILES on success; close the dialog either way (the
                    // error path re-shows via the inline caption only if it fails synchronously).
                    if (error.isBlank()) onDismiss()
                },
            ) { Text("Load files") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ---- Phase 3b batch 2: LIBRARY (saved lists) screen ---------------------------------------------
// Adapts app/ui/lists/ListLibraryScreen.kt + ListViewModel: a list of the user's saved game-lists,
// backed by the new file-backed DesktopListStore (JSON in ~/.emuhelper/game_lists.json). Opening a
// list → controller.openList loads its games into the FILES screen for download (parity with the
// Android loadForDownload). Supports view + open-and-download + delete + import-from-file. Create /
// rename / export / per-list-folder are DEFERRED (they depend on the not-yet-ported build/picker flow).

private val listDateFmt = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())

@Composable
private fun LibraryScreen(ui: DownloadController.UiState, controller: DownloadController) {
    val saved by controller.lists.lists.collectAsState()
    val decodeError by controller.lists.decodeError.collectAsState()
    var deleteTarget by remember { mutableStateOf<DesktopListStore.DesktopGameList?>(null) }
    var renameTarget by remember { mutableStateOf<DesktopListStore.DesktopGameList?>(null) }
    var showCreate by remember { mutableStateOf(false) }
    var importMsg by remember { mutableStateOf("") }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete list") },
            text = { Text("Delete \"${target.name}\"? This can't be undone.") },
            confirmButton = {
                TextButton(onClick = { controller.deleteList(target.id); deleteTarget = null }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } },
        )
    }

    if (showCreate) {
        ListNameDialog(
            title = "New list", initial = "",
            onConfirm = { controller.createList(it); showCreate = false },
            onDismiss = { showCreate = false },
        )
    }
    renameTarget?.let { target ->
        ListNameDialog(
            title = "Rename list", initial = target.name,
            onConfirm = { controller.renameList(target.id, it); renameTarget = null },
            onDismiss = { renameTarget = null },
        )
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (saved.isEmpty()) "No saved lists yet"
                else "${saved.size} list${if (saved.size == 1) "" else "s"}",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f),
            )
            Button(onClick = { showCreate = true }) {
                Icon(Icons.Default.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("New")
            }
            OutlinedButton(onClick = {
                chooseJsonFile()?.let { f ->
                    importMsg = try { controller.importListFromText(f.readText()) }
                    catch (_: Exception) { "Couldn't open that file." }
                }
            }) {
                Icon(Icons.Default.FileUpload, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Import")
            }
        }
        if (decodeError) {
            Spacer(Modifier.height(4.dp))
            Text("Some saved lists couldn't be read (the saved data may be corrupted).",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
        }
        if (importMsg.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(importMsg, fontSize = 11.sp, color = MaterialTheme.colorScheme.tertiary)
        }
        Spacer(Modifier.height(8.dp))

        if (saved.isEmpty()) {
            CenterHint {
                Icon(Icons.AutoMirrored.Filled.ListAlt, null, Modifier.size(56.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                Spacer(Modifier.height(12.dp))
                Text("No saved lists yet", color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(4.dp))
                Text("Create a new list, or import one from a .json file.",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(saved, key = { it.id }) { list ->
                    var menu by remember { mutableStateOf(false) }
                    Card(
                        onClick = { controller.openList(list) },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                    ) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(list.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    "${list.count} item${if (list.count == 1) "" else "s"} · " +
                                        "${humanSize(list.totalSize)} · ${listDateFmt.format(Date(list.createdAt))}",
                                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                list.customFolderUri?.takeIf { it.isNotBlank() }?.let {
                                    Text("→ $it", fontSize = 10.sp, color = MaterialTheme.colorScheme.primary,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            Box {
                                IconButton(onClick = { menu = true }) {
                                    Icon(Icons.Default.Edit, "List options", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                    DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; renameTarget = list })
                                    DropdownMenuItem(text = { Text("Set folder…") }, onClick = {
                                        menu = false
                                        chooseDirectory(list.customFolderUri ?: "")?.let {
                                            controller.setListFolder(list.id, it)
                                        }
                                    })
                                    if (!list.customFolderUri.isNullOrBlank()) {
                                        DropdownMenuItem(text = { Text("Clear folder") }, onClick = {
                                            menu = false; controller.setListFolder(list.id, null)
                                        })
                                    }
                                    DropdownMenuItem(
                                        text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                                        onClick = { menu = false; deleteTarget = list },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Small single-field dialog used for both "New list" and "Rename list" (B3). Blank input is disabled. */
@Composable
private fun ListNameDialog(title: String, initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name, onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("List name") }, singleLine = true,
            )
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onConfirm(name.trim()) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ---- Phase 3b batch 2: ABOUT screen -------------------------------------------------------------
// Adapts app/ui/about/AboutScreen.kt, trimmed to the desktop scope: app version (from AppInfo) + a
// short description + project links opened in the default browser (openInBrowser). OMITS the Android-
// only bits: device RAM/CPU readout, the in-app APK updater, the logcat-backed error-log screen, and
// root/PServer status (none apply on desktop).

private const val PROJECT_URL = "https://github.com/mayusi/EmuHelper"
private const val ISSUES_URL = "https://github.com/mayusi/EmuHelper/issues"
private const val RELEASES_URL = "https://github.com/mayusi/EmuHelper/releases"
private const val LICENSE_URL = "https://github.com/mayusi/EmuHelper/blob/main/LICENSE"
private const val DISCORD_URL = "https://discord.gg/emuhelper"
private const val IA_URL = "https://archive.org"

@Composable
private fun AboutScreen(controller: DownloadController) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Download, null, Modifier.size(44.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(10.dp))
                Text("EmuHelper", fontSize = 20.sp, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(2.dp))
                Text("Version ${controller.appInfo.versionName} (desktop)", fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                Text(
                    "A helper for downloading public game files from the Internet Archive, with a fast " +
                        "multi-mirror download engine. Downloads are verified by checksum (MD5) for integrity.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        SettingCard("Updates") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Check for updates", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                    Text("Opens the GitHub releases page in your browser.", fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedButton(onClick = { openInBrowser(RELEASES_URL) }) {
                    Icon(Icons.Default.Refresh, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp)); Text("Check")
                }
            }
        }

        SettingCard("Links") {
            AboutLinkRow("Project page", PROJECT_URL)
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
            AboutLinkRow("Releases & changelog", RELEASES_URL)
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
            AboutLinkRow("Report an issue", ISSUES_URL)
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
            AboutLinkRow("License", LICENSE_URL)
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
            AboutLinkRow("Discord community", DISCORD_URL)
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
            AboutLinkRow("Internet Archive", IA_URL)
        }

        Text(
            "Content downloaded from the Internet Archive is not curated or vetted. Download what you trust.",
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AboutLinkRow(label: String, url: String) {
    Row(
        Modifier.fillMaxWidth().clickable { openInBrowser(url) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
            Text(url, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.AutoMirrored.Filled.OpenInNew, "Open in browser", Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.primary)
    }
}

// ---- Windows-port batch: SOURCE HEALTH screen (B1) ----------------------------------------------
// Adapts app/ui/health/SourceHealthScreen.kt: runs the shared SourceHealthChecker over every catalog
// endpoint, shows a progress indicator while probing, then lists each SourceHealth (console, url, a
// green/red alive dot, and the HTTP status / error detail). Cookie-less probing lives in the checker.

@Composable
private fun SourceHealthScreen(ui: DownloadController.UiState, controller: DownloadController) {
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Check which configured Internet Archive sources are reachable.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            val running = ui.healthState is DownloadController.HealthState.Running
            Button(onClick = { controller.runSourceHealthCheck() }, enabled = !running) {
                Icon(Icons.Default.NetworkCheck, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp)); Text(if (running) "Checking…" else "Run check")
            }
        }
        Spacer(Modifier.height(12.dp))
        when (val s = ui.healthState) {
            is DownloadController.HealthState.Idle -> CenterHint {
                Icon(Icons.Default.NetworkCheck, null, Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                Spacer(Modifier.height(12.dp))
                Text("Press \"Run check\" to probe every source.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            is DownloadController.HealthState.Running -> CenterHint {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(12.dp))
                Text(if (s.total > 0) "Probing ${s.done} / ${s.total}…" else "Probing…",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            is DownloadController.HealthState.Error -> CenterHint {
                Icon(Icons.Default.Error, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(12.dp))
                Text(s.message, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            is DownloadController.HealthState.Done -> {
                val alive = s.results.count { it.alive }
                Text("$alive of ${s.results.size} sources reachable", fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(s.results, key = { it.console + "|" + it.url }) { h ->
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp)) {
                            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(12.dp).background(
                                    if (h.alive) Color(0xFFA6E3A1) else Color(0xFFF38BA8), CircleShape))
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(h.console, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface)
                                    Text(h.url, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Spacer(Modifier.width(8.dp))
                                Text(h.detail, fontSize = 12.sp,
                                    color = if (h.alive) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---- Windows-port batch: LIBRARY VERIFY / local scan screen (B2) --------------------------------
// A desktop mirror of the Android on-device library scan: pick a folder, list its top-level files, and
// for each compute a SHA-256 + run the best-effort SecurityScanner, surfacing a safety badge + hash.
// Simplified vs Android: non-recursive; integrity comparison against a recorded hash is a future step
// (history doesn't persist the source checksum yet), so the badge is the primary signal here.

@Composable
private fun LibraryScanScreen(controller: DownloadController) {
    val ui by controller.state.collectAsState()
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Re-scan already-downloaded files for safety and integrity.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            val running = ui.libraryScanState is DownloadController.LibraryScanState.Running
            Button(
                onClick = {
                    chooseDirectory(ui.destFolder)?.let { controller.scanLibraryFolder(it) }
                },
                enabled = !running,
            ) {
                Icon(Icons.Default.Folder, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp)); Text(if (running) "Scanning…" else "Choose folder…")
            }
        }
        Spacer(Modifier.height(12.dp))
        when (val s = ui.libraryScanState) {
            is DownloadController.LibraryScanState.Idle -> CenterHint {
                Icon(Icons.Default.Shield, null, Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                Spacer(Modifier.height(12.dp))
                Text("Choose a folder to verify its files.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            is DownloadController.LibraryScanState.Running -> CenterHint {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(12.dp))
                Text(if (s.total > 0) "Scanning ${s.done} / ${s.total}…" else "Listing files…",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(s.folder, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            is DownloadController.LibraryScanState.Error -> CenterHint {
                Icon(Icons.Default.Error, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(12.dp))
                Text(s.message, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            is DownloadController.LibraryScanState.Done -> {
                if (s.files.isEmpty()) {
                    CenterHint { Text("No files in that folder.", fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant) }
                } else {
                    Text("${s.files.size} file(s) scanned in ${s.folder}", fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(s.files, key = { it.path }) { f -> ScannedFileCard(f) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ScannedFileCard(f: DownloadController.ScannedFile) {
    var showScan by remember { mutableStateOf(false) }
    if (showScan && f.report != null) {
        // Reuse the same details dialog shape by wrapping the report into a synthetic DownloadRow.
        ScannedFileDetailsDialog(f, onDismiss = { showScan = false })
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(f.name, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(humanSize(f.sizeBytes) + (f.sha256?.let { "  ·  ${it.take(16)}…" } ?: ""),
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            f.report?.let { SafetyBadge(it.verdict) { showScan = true } }
        }
    }
}

/** Details dialog for a locally-scanned file (B2) — same layout as [ScanDetailsDialog], no quarantine. */
@Composable
private fun ScannedFileDetailsDialog(f: DownloadController.ScannedFile, onDismiss: () -> Unit) {
    val report = f.report ?: return
    val band = bandForVerdict(report.verdict)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Shield, null, Modifier.size(20.dp), tint = Color(band.argb))
                Spacer(Modifier.width(8.dp)); Text("Security scan")
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(f.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp))
                val verdictLabel = report.verdict.name.lowercase().replaceFirstChar { it.uppercase() }.replace('_', ' ')
                Text("Verdict: $verdictLabel  ·  Score ${report.score.value}/100 (${report.score.band.name.lowercase()})",
                    fontSize = 13.sp, color = Color(band.argb), fontWeight = FontWeight.SemiBold)
                report.sha256?.let {
                    Text("SHA-256: $it", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(10.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                Spacer(Modifier.height(8.dp))
                report.checks.forEach { c ->
                    Row(Modifier.padding(vertical = 3.dp)) {
                        Column {
                            Text("${c.code} · ${c.status.name}", fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                            Text(c.detail, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text("This is the best local check we could build — not a guarantee (no offline scan " +
                    "catches everything), but better than nothing.",
                    fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
