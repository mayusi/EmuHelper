package io.github.mayusi.emuhelper.ui.history

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.mayusi.emuhelper.data.storage.HistoryEntry
import io.github.mayusi.emuhelper.data.storage.HistoryStore
import io.github.mayusi.emuhelper.ui.common.Dimens
import io.github.mayusi.emuhelper.ui.common.formatSize
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val historyStore: HistoryStore
) : ViewModel() {

    val entries: StateFlow<List<HistoryEntry>> = historyStore.entries
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun clearHistory() {
        viewModelScope.launch { historyStore.clear() }
    }
}

// ---- date-label helpers --------------------------------------------------

private fun relativeDay(timestampMillis: Long): String {
    val today = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
    val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
    val entryDay = Calendar.getInstance().apply { timeInMillis = timestampMillis; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
    return when {
        !entryDay.before(today)     -> "Today"
        !entryDay.before(yesterday) -> "Yesterday"
        else -> SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(timestampMillis))
    }
}

private fun formatTime(timestampMillis: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestampMillis))

// ---- screen --------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    onBack: () -> Unit,
    viewModel: HistoryViewModel = hiltViewModel()
) {
    val entries by viewModel.entries.collectAsState()
    var showClearDialog by remember { mutableStateOf(false) }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Clear history") },
            text  = { Text("Remove all download history entries? This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = { viewModel.clearHistory(); showClearDialog = false }) {
                    Text("Clear")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("Cancel") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Download History", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    if (entries.isNotEmpty()) {
                        IconButton(onClick = { showClearDialog = true }) {
                            Icon(Icons.Default.DeleteSweep, "Clear history")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        if (entries.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.History,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "No downloads yet",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            // Group entries by relative day label (Today / Yesterday / date)
            val grouped: List<Pair<String, List<HistoryEntry>>> = remember(entries) {
                entries.groupBy { relativeDay(it.timestampMillis) }
                    .entries
                    .map { (k, v) -> Pair(k, v) }
                    // Keep order: Today first, then Yesterday, then older (already newest-first from store)
                    .sortedByDescending { (_, list) -> list.maxOf { it.timestampMillis } }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(horizontal = Dimens.ScreenHorizontal, vertical = Dimens.ItemGap),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                grouped.forEach { (dayLabel, dayEntries) ->
                    item(key = "header_$dayLabel") {
                        Text(
                            dayLabel,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                        )
                    }
                    items(dayEntries, key = { "${it.timestampMillis}_${it.filename}" }) { entry ->
                        HistoryEntryCard(entry, Modifier.animateItem())
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryEntryCard(entry: HistoryEntry, modifier: Modifier = Modifier) {
    val isDone = entry.status == "DONE"
    val isFailed = entry.status == "FAILED"
    val statusColor = when {
        isDone   -> MaterialTheme.colorScheme.tertiary
        isFailed -> MaterialTheme.colorScheme.error
        else     -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val statusIcon = when {
        isDone   -> Icons.Default.CheckCircle
        isFailed -> Icons.Default.Error
        else     -> Icons.Default.Cancel
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.small
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(Dimens.CardPadding + 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(statusIcon, null, modifier = Modifier.size(20.dp), tint = statusColor)
            Spacer(Modifier.width(Dimens.ItemGap))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    entry.filename,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (entry.subfolder.isNotBlank()) {
                    Text(
                        "→ ${entry.subfolder}/",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    if (entry.sizeBytes > 0) formatSize(entry.sizeBytes) else "—",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    formatTime(entry.timestampMillis),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
