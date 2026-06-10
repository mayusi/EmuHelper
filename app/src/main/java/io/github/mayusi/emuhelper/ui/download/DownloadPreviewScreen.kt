package io.github.mayusi.emuhelper.ui.download

import android.content.Context
import android.os.Environment
import android.os.StatFs
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.mayusi.emuhelper.data.model.CuratedGame
import io.github.mayusi.emuhelper.data.source.RemoteSource
import io.github.mayusi.emuhelper.data.storage.SettingsStore
import io.github.mayusi.emuhelper.ui.browse.ScanStateHolder
import io.github.mayusi.emuhelper.ui.common.Dimens
import io.github.mayusi.emuhelper.ui.common.cleanGameName
import io.github.mayusi.emuhelper.ui.common.formatSize
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class DownloadPreviewViewModel @Inject constructor(
    private val scanState: ScanStateHolder,
    private val source: RemoteSource,
    private val settings: SettingsStore,
    @ApplicationContext private val context: Context
) : ViewModel() {

    /** The games loaded from the chosen saved list. */
    val games: StateFlow<List<CuratedGame>> = scanState.downloadQueue

    // Keyed by identifier/filename so equal-named games across consoles stay distinct.
    private fun keyOf(g: CuratedGame) = "${g.identifier}/${g.filename}"

    private val _checked = MutableStateFlow<Set<String>>(emptySet())
    val checked: StateFlow<Set<String>> = _checked

    private var seeded = false
    /** First time we see the list, check everything by default. */
    fun seedIfNeeded(list: List<CuratedGame>) {
        if (!seeded && list.isNotEmpty()) {
            _checked.value = list.map { keyOf(it) }.toSet()
            seeded = true
        }
    }

    fun toggle(g: CuratedGame) {
        val k = keyOf(g)
        val cur = _checked.value.toMutableSet()
        if (k in cur) cur.remove(k) else cur.add(k)
        _checked.value = cur
    }

    fun isChecked(g: CuratedGame) = keyOf(g) in _checked.value

    fun setAll(list: List<CuratedGame>, value: Boolean) {
        _checked.value = if (value) list.map { keyOf(it) }.toSet() else emptySet()
    }

    fun isLoggedIn(): Boolean = source.isLoggedIn()

    /** Narrow the download queue to only the checked games. Returns how many remain. */
    fun confirmSelection(): Int {
        val keep = scanState.downloadQueue.value.filter { keyOf(it) in _checked.value }
        scanState.downloadQueue.value = keep
        return keep.size
    }

    /**
     * Available free bytes on the download destination.
     * Uses StatFs on the external storage volume as a best-effort approximation for both
     * app-private and SAF destinations (SAF doesn't expose a direct path, so we query
     * the primary external volume which is typically where both destinations live).
     * Returns null if unavailable.
     */
    val freeBytes: StateFlow<Long?> = settings.downloadFolder.map { uri ->
        try {
            val path = if (uri == null) {
                // App-private dir — use its actual path
                context.getExternalFilesDir(null)?.absolutePath
                    ?: Environment.getExternalStorageDirectory().absolutePath
            } else {
                // SAF URI — approximate with primary external storage volume
                Environment.getExternalStorageDirectory().absolutePath
            }
            StatFs(path).availableBytes
        } catch (_: Exception) {
            null
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadPreviewScreen(
    onConfirm: (needsLogin: Boolean) -> Unit,
    onBack: () -> Unit,
    viewModel: DownloadPreviewViewModel = hiltViewModel()
) {
    val games by viewModel.games.collectAsState()
    val checked by viewModel.checked.collectAsState()
    val freeBytes by viewModel.freeBytes.collectAsState()

    LaunchedEffect(games) {
        if (games.isEmpty()) onBack() else viewModel.seedIfNeeded(games)
    }

    val checkedGames = remember(games, checked) { games.filter { "${it.identifier}/${it.filename}" in checked } }
    val selCount = checkedGames.size
    val selSize = remember(checkedGames) { checkedGames.sumOf { it.size } }
    // Warn if selection is > 95% of free space (best-effort, may be null)
    val spaceWarning = remember(selSize, freeBytes) {
        val free = freeBytes
        free != null && free > 0 && selSize > (free * 0.95).toLong()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Review download", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 4.dp) {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.ScreenHorizontal, vertical = Dimens.ItemGap)) {
                    // Free-space row (hidden if unavailable)
                    freeBytes?.let { free ->
                        val freeColor = if (spaceWarning) MaterialTheme.colorScheme.error
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Free: ${formatSize(free)}",
                                style = MaterialTheme.typography.labelMedium,
                                color = freeColor
                            )
                            if (spaceWarning) {
                                Text(
                                    "· May not fit",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { viewModel.setAll(games, true) }) { Text("All") }
                            TextButton(onClick = { viewModel.setAll(games, false) }) { Text("None") }
                        }
                        Button(
                            onClick = {
                                val remaining = viewModel.confirmSelection()
                                if (remaining > 0) onConfirm(!viewModel.isLoggedIn())
                            },
                            enabled = selCount > 0,
                            modifier = Modifier.height(Dimens.ButtonMinHeight),
                            shape = MaterialTheme.shapes.small,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) { Text("Download $selCount  ·  ${formatSize(selSize)}", style = MaterialTheme.typography.titleMedium) }
                    }
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            contentPadding = PaddingValues(horizontal = Dimens.ScreenHorizontal, vertical = 4.dp)
        ) {
            item {
                Text(
                    "Uncheck anything you don't want to download right now.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 6.dp)
                )
            }
            items(games, key = { "${it.identifier}/${it.filename}" }) { game ->
                // Read from the tracked `checked` set (collected above) so each row
                // recomposes when selection changes. Calling viewModel.isChecked()
                // here would read state OUTSIDE Compose tracking → rows never update
                // → "can't deselect".
                val isOn = "${game.identifier}/${game.filename}" in checked
                val backgroundColor by animateColorAsState(
                    targetValue = if (isOn) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f) else MaterialTheme.colorScheme.surface,
                    animationSpec = tween(durationMillis = 150)
                )
                Surface(
                    modifier = Modifier.fillMaxWidth().animateItem().clickable { viewModel.toggle(game) },
                    color = backgroundColor,
                    shape = MaterialTheme.shapes.small
                ) {
                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = isOn, onCheckedChange = { viewModel.toggle(game) }, modifier = Modifier.padding(end = 6.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(cleanGameName(game.name.ifBlank { game.filename }), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(game.filename, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text(formatSize(game.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }
}
