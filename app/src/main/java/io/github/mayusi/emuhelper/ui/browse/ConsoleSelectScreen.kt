package io.github.mayusi.emuhelper.ui.browse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import io.github.mayusi.emuhelper.data.config.Catalog
import io.github.mayusi.emuhelper.ui.common.Dimens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConsoleSelectScreen(
    onStartScan: (Set<String>) -> Unit,
    onBack: () -> Unit,
    viewModel: ConsoleSelectViewModel = hiltViewModel()
) {
    val consoles = Catalog.DISPLAY_ORDER.filter { it in Catalog.IA_LINKS }
    val selected = remember { mutableStateMapOf<String, Boolean>().also { map -> consoles.forEach { map[it] = false } } }
    val savedConsoles by viewModel.lastSelectedConsoles.collectAsState()

    // Pre-tick consoles from the saved set on first composition.
    LaunchedEffect(savedConsoles) {
        if (savedConsoles.isNotEmpty()) {
            consoles.forEach { console ->
                selected[console] = console in savedConsoles
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Select Consoles", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 4.dp) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.ScreenHorizontal, vertical = Dimens.ItemGap),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { consoles.forEach { selected[it] = true } }) { Text("Select All") }
                        TextButton(onClick = { consoles.forEach { selected[it] = false } }) { Text("Clear") }
                    }
                    val selCount = selected.count { it.value }
                    Button(
                        onClick = {
                            val picked = selected.filter { it.value }.keys.toSet()
                            viewModel.saveSelectedConsoles(picked)
                            onStartScan(picked)
                        },
                        enabled = selCount > 0,
                        modifier = Modifier.height(Dimens.ButtonMinHeight),
                        shape = MaterialTheme.shapes.small,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text("Scan $selCount Console${if (selCount != 1) "s" else ""}", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = Dimens.ScreenHorizontal),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(vertical = Dimens.ItemGap)
        ) {
            item {
                Text(
                    "Select which categories to scan. The app fetches live metadata to find all available files.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }
            items(consoles, key = { it }) { console ->
                val info = Catalog.CONSOLES[console] ?: return@items
                val count = Catalog.IA_LINKS[console]?.size ?: 0
                val checked = selected[console] ?: true

                Card(
                    modifier = Modifier.fillMaxWidth().animateItem(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = MaterialTheme.shapes.small
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(Dimens.CardPadding)
                            .clickable { selected[console] = !checked },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(info.display, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                            Text("${info.emulator}  ·  $count sources", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        // Pass a real onCheckedChange so accessibility services don't read this as disabled
                        Checkbox(checked = checked, onCheckedChange = { selected[console] = it })
                    }
                }
            }
        }
    }
}
