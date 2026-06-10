package io.github.mayusi.emuhelper.ui.browse

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import io.github.mayusi.emuhelper.data.config.Catalog
import io.github.mayusi.emuhelper.data.model.CuratedGame
import io.github.mayusi.emuhelper.data.model.GameFile
import io.github.mayusi.emuhelper.ui.common.Dimens
import io.github.mayusi.emuhelper.ui.common.Region
import io.github.mayusi.emuhelper.ui.common.cleanGameName
import io.github.mayusi.emuhelper.ui.common.detectRegion
import io.github.mayusi.emuhelper.ui.common.formatSize

private enum class SortMode(val label: String) {
    NAME_ASC("Name A–Z"), NAME_DESC("Name Z–A"),
    SIZE_DESC("Largest first"), SIZE_ASC("Smallest first")
}

private enum class SizeBucket(val label: String, val minBytes: Long, val maxBytes: Long) {
    ANY("Any size", 0, Long.MAX_VALUE),
    SMALL("< 500 MB", 0, 500L * 1024 * 1024),
    MEDIUM("500 MB – 2 GB", 500L * 1024 * 1024, 2L * 1024 * 1024 * 1024),
    LARGE("> 2 GB", 2L * 1024 * 1024 * 1024, Long.MAX_VALUE)
}

private enum class SelFilter { ALL, ONLY_SELECTED, HIDE_SELECTED }

private data class PickRow(
    val display: String,
    val displayLower: String,
    val region: Region,
    val file: GameFile
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GamePickerScreen(
    instantInstall: Boolean = false,
    onProceed: () -> Unit,
    onBack: () -> Unit,
    viewModel: BrowseViewModel = hiltViewModel()
) {
    val scannedFiles by viewModel.scannedFiles.collectAsState()
    val selectedGames by viewModel.selectedGames.collectAsState()
    val scanState by viewModel.uiState.collectAsState()
    var currentConsole by remember { mutableStateOf("") }
    var searchQuery by remember { mutableStateOf("") }
    // Filter / sort state
    var regionFilter by remember { mutableStateOf(emptySet<Region>()) }
    var selFilter by remember { mutableStateOf(SelFilter.ALL) }
    var sizeBucket by remember { mutableStateOf(SizeBucket.ANY) }
    var sortMode by remember { mutableStateOf(SortMode.NAME_ASC) }
    var sizeMenuOpen by remember { mutableStateOf(false) }
    var sortMenuOpen by remember { mutableStateOf(false) }
    LaunchedEffect(scannedFiles.keys) {
        if (currentConsole.isBlank() || currentConsole !in scannedFiles) {
            currentConsole = scannedFiles.keys.firstOrNull() ?: ""
        }
    }
    val files = scannedFiles[currentConsole] ?: emptyList()

    // Debounce search query to avoid filtering on every keystroke
    var debouncedQuery by remember { mutableStateOf("") }
    LaunchedEffect(searchQuery) {
        kotlinx.coroutines.delay(220)
        debouncedQuery = searchQuery.trim().lowercase()
    }

    // Precompute expensive operations (cleanGameName, detectRegion, lowercase) once per file list
    val precomputed = remember(files) {
        files.map { f ->
            val name = cleanGameName(f.filename)
            PickRow(name, name.lowercase(), detectRegion(f.filename), f)
        }
    }

    if (scannedFiles.isEmpty()) {
        // If a scan is still running (we got here via a stale-state race), show a
        // calm "still scanning" state instead of a scary "No files found" — and do
        // NOT offer a Go Back that strands the user on an in-progress scan.
        val scanning = scanState.isScanning || !scanState.scanComplete
        Box(modifier = Modifier.fillMaxSize().padding(48.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (scanning) {
                    CircularProgressIndicator(modifier = Modifier.size(56.dp), color = MaterialTheme.colorScheme.primary, strokeWidth = 4.dp)
                    Spacer(Modifier.height(20.dp))
                    Text("Still scanning…", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (scanState.totalSources > 0) "${scanState.completedSources}/${scanState.totalSources} sources · ${scanState.totalFiles} files"
                        else "Fetching…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text("No files found", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(12.dp))
                    Text("The scan returned no results.\nCheck your connection or try without login.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(20.dp))
                    OutlinedButton(onClick = onBack, modifier = Modifier.height(Dimens.ButtonMinHeight)) { Text("Go Back") }
                }
            }
        }
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pick Items", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 4.dp) {
                val totalSel = selectedGames.values.sumOf { it.size }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.ScreenHorizontal, vertical = Dimens.ItemGap),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("$totalSel selected", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                        Text("${scannedFiles.values.sumOf { it.size }} total files", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(onClick = { viewModel.selectAll(currentConsole, files) }) { Text("All") }
                        TextButton(onClick = { viewModel.selectNone(currentConsole) }) { Text("None") }
                    }
                    Button(
                        onClick = {
                            val games = mutableListOf<CuratedGame>()
                            for ((c, fl) in scannedFiles) {
                                val sel = selectedGames[c] ?: continue
                                fl.filter { it.filename in sel }.forEach { f ->
                                    games.add(CuratedGame(name = cleanGameName(f.filename), filename = f.name, size = f.size, identifier = f.identifier, source = "scanned", console = c))
                                }
                            }
                            if (games.isNotEmpty()) {
                                if (instantInstall) viewModel.queueDownloads(games) else viewModel.stageForSaving(games)
                                onProceed()
                            }
                        },
                        enabled = totalSel > 0,
                        modifier = Modifier.height(Dimens.ButtonMinHeight),
                        shape = MaterialTheme.shapes.small,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) { Text(if (instantInstall) "Download" else "Save list", style = MaterialTheme.typography.titleMedium) }
                }
            }
        }
    ) { padding ->
        Row(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Console sidebar — a LazyColumn so it SCROLLS when many consoles were
            // scanned (a plain Column overflowed and couldn't scroll past ~5).
            val consoleKeys = remember(scannedFiles.keys) { scannedFiles.keys.sorted() }
            LazyColumn(
                modifier = Modifier.width(Dimens.SidebarWidth).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
                contentPadding = PaddingValues(6.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                items(consoleKeys, key = { it }) { console ->
                    val count = scannedFiles[console]?.size ?: 0
                    val sel = selectedGames[console]?.size ?: 0
                    val active = console == currentConsole
                    val backgroundColor by animateColorAsState(
                        targetValue = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                        animationSpec = tween(durationMillis = 150)
                    )
                    Surface(
                        modifier = Modifier.fillMaxWidth().clickable { currentConsole = console },
                        color = backgroundColor,
                        shape = MaterialTheme.shapes.extraSmall
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
                            Text(Catalog.CONSOLES[console]?.display ?: console, style = MaterialTheme.typography.bodyMedium, color = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface)
                            Text("$sel / $count", style = MaterialTheme.typography.labelSmall, color = if (active) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            Column(modifier = Modifier.fillMaxSize()) {
                OutlinedTextField(
                    value = searchQuery, onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.ScreenHorizontal, vertical = 6.dp),
                    placeholder = { Text("Search...") }, singleLine = true,
                    shape = MaterialTheme.shapes.extraSmall,
                    colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant, unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant)
                )

                // ---- Filter / sort bar (scrolls horizontally) ----
                val selectedSet = selectedGames[currentConsole] ?: emptySet()
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = Dimens.ScreenHorizontal),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Region chips
                    Region.entries.forEach { region ->
                        val on = region in regionFilter
                        FilterChip(
                            selected = on,
                            onClick = {
                                regionFilter = if (on) regionFilter - region else regionFilter + region
                            },
                            label = { Text(when (region) {
                                Region.USA -> "USA"; Region.EUR -> "EUR"; Region.JPN -> "JPN"; Region.OTHER -> "Other"
                            }) },
                            leadingIcon = if (on) { { Icon(Icons.Default.Check, null, Modifier.size(16.dp)) } } else null
                        )
                    }

                    // Selection filter (cycles ALL → only → hide)
                    FilterChip(
                        selected = selFilter != SelFilter.ALL,
                        onClick = {
                            selFilter = when (selFilter) {
                                SelFilter.ALL -> SelFilter.ONLY_SELECTED
                                SelFilter.ONLY_SELECTED -> SelFilter.HIDE_SELECTED
                                SelFilter.HIDE_SELECTED -> SelFilter.ALL
                            }
                        },
                        label = { Text(when (selFilter) {
                            SelFilter.ALL -> "Selected: all"
                            SelFilter.ONLY_SELECTED -> "Only selected"
                            SelFilter.HIDE_SELECTED -> "Hide selected"
                        }) }
                    )

                    // Size bucket menu
                    Box {
                        FilterChip(
                            selected = sizeBucket != SizeBucket.ANY,
                            onClick = { sizeMenuOpen = true },
                            label = { Text(sizeBucket.label) }
                        )
                        DropdownMenu(expanded = sizeMenuOpen, onDismissRequest = { sizeMenuOpen = false }) {
                            SizeBucket.entries.forEach { b ->
                                DropdownMenuItem(text = { Text(b.label) }, onClick = { sizeBucket = b; sizeMenuOpen = false })
                            }
                        }
                    }

                    // Sort menu
                    Box {
                        AssistChip(
                            onClick = { sortMenuOpen = true },
                            label = { Text(sortMode.label) },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.Sort, null, Modifier.size(18.dp)) }
                        )
                        DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { sortMenuOpen = false }) {
                            SortMode.entries.forEach { m ->
                                DropdownMenuItem(text = { Text(m.label) }, onClick = { sortMode = m; sortMenuOpen = false })
                            }
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))

                val filtered = remember(precomputed, debouncedQuery, regionFilter, sizeBucket, selFilter, sortMode, selectedSet) {
                    var seq = precomputed.asSequence()
                        .filter { debouncedQuery.isEmpty() || debouncedQuery in it.displayLower }
                    if (regionFilter.isNotEmpty()) {
                        seq = seq.filter { it.region in regionFilter }
                    }
                    if (sizeBucket != SizeBucket.ANY) {
                        seq = seq.filter { it.file.size >= sizeBucket.minBytes && it.file.size < sizeBucket.maxBytes }
                    }
                    seq = when (selFilter) {
                        SelFilter.ALL -> seq
                        SelFilter.ONLY_SELECTED -> seq.filter { it.file.filename in selectedSet }
                        SelFilter.HIDE_SELECTED -> seq.filter { it.file.filename !in selectedSet }
                    }
                    val list = seq.toList()
                    when (sortMode) {
                        SortMode.NAME_ASC -> list.sortedBy { it.displayLower }
                        SortMode.NAME_DESC -> list.sortedByDescending { it.displayLower }
                        SortMode.SIZE_DESC -> list.sortedByDescending { it.file.size }
                        SortMode.SIZE_ASC -> list.sortedBy { it.file.size }
                    }
                }

                if (filtered.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize().padding(horizontal = Dimens.Large, vertical = Dimens.XLarge),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Default.SearchOff,
                                contentDescription = null,
                                modifier = Modifier.size(Dimens.IconLarge + 24.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                            Spacer(Modifier.height(Dimens.ItemGap))
                            Text(
                                "No items match",
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                textAlign = TextAlign.Center
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Try removing a filter",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                            Spacer(Modifier.height(Dimens.SectionGap))
                            TextButton(onClick = {
                                regionFilter = emptySet()
                                sizeBucket = SizeBucket.ANY
                                selFilter = SelFilter.ALL
                                searchQuery = ""
                            }) {
                                Text("Clear filters")
                            }
                        }
                    }
                } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    contentPadding = PaddingValues(horizontal = Dimens.ScreenHorizontal, vertical = 4.dp)
                ) {
                    items(filtered) { row ->
                        val displayName = row.display
                        val file = row.file
                        val sel = file.filename in (selectedGames[currentConsole] ?: emptySet())
                        val backgroundColor by animateColorAsState(
                            targetValue = if (sel) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f) else MaterialTheme.colorScheme.surface,
                            animationSpec = tween(durationMillis = 150)
                        )
                        Surface(
                            modifier = Modifier.fillMaxWidth().animateItem().clickable { viewModel.toggleGame(currentConsole, file.filename) },
                            color = backgroundColor,
                            shape = MaterialTheme.shapes.small
                        ) {
                            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = sel, onCheckedChange = { viewModel.toggleGame(currentConsole, file.filename) }, modifier = Modifier.padding(end = 6.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(displayName, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(file.filename, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Text(formatSize(file.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp))
                            }
                        }
                    }
                }
                } // end else (filtered.isEmpty)
            }
        }
    }
}
