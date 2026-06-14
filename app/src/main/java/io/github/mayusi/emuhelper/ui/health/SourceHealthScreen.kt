package io.github.mayusi.emuhelper.ui.health

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.mayusi.emuhelper.data.source.SourceHealth
import io.github.mayusi.emuhelper.data.source.SourceHealthChecker
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

// ---------------------------------------------------------------------------
// ViewModel
// ---------------------------------------------------------------------------

@HiltViewModel
class SourceHealthViewModel @Inject constructor(
    private val checker: SourceHealthChecker
) : ViewModel() {

    sealed class UiState {
        /** Nothing has been run yet. */
        object Idle : UiState()
        /** Check is running; [done]/[total] drive the progress indicator. */
        data class Running(val done: Int, val total: Int) : UiState()
        /** Check finished; results are available via [results]. */
        data class Done(val results: List<SourceHealth>) : UiState()
    }

    private val _state = MutableStateFlow<UiState>(UiState.Idle)
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var checkJob: Job? = null

    fun startCheck() {
        if (_state.value is UiState.Running) return
        checkJob = viewModelScope.launch {
            _state.value = UiState.Running(done = 0, total = 0)
            val results = checker.checkAll { done, total ->
                _state.value = UiState.Running(done, total)
            }
            _state.value = UiState.Done(results)
        }
    }

    fun cancel() {
        checkJob?.cancel()
        checkJob = null
        // Reset to idle so the user can retry.
        _state.value = UiState.Idle
    }
}

// ---------------------------------------------------------------------------
// Screen
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceHealthScreen(
    onBack: () -> Unit,
    viewModel: SourceHealthViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Source Health Check") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            when (val s = state) {
                is SourceHealthViewModel.UiState.Idle -> {
                    IdleContent(onStart = { viewModel.startCheck() })
                }
                is SourceHealthViewModel.UiState.Running -> {
                    RunningContent(
                        done = s.done,
                        total = s.total,
                        onCancel = { viewModel.cancel() }
                    )
                }
                is SourceHealthViewModel.UiState.Done -> {
                    DoneContent(
                        results = s.results,
                        onRecheck = { viewModel.startCheck() }
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// State sub-composables
// ---------------------------------------------------------------------------

@Composable
private fun IdleContent(onStart: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Spacer(Modifier.height(16.dp))
        Icon(
            Icons.Default.NetworkCheck,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(64.dp)
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "Source Endpoint Check",
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "Pings each configured source endpoint with a HEAD request " +
                    "and reports which are reachable (2xx/3xx) vs unreachable.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp)
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onStart,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        ) {
            Icon(Icons.Default.NetworkCheck, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Check sources")
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun RunningContent(done: Int, total: Int, onCancel: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Spacer(Modifier.height(20.dp))
        CircularProgressIndicator(modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(20.dp))
        Text(
            if (total > 0) "Checking $done / $total endpoints…" else "Starting…",
            style = MaterialTheme.typography.bodyMedium
        )
        if (total > 0) {
            Spacer(Modifier.height(20.dp))
            LinearProgressIndicator(
                progress = { done.toFloat() / total.toFloat() },
                modifier = Modifier.fillMaxWidth()
            )
        }
        Spacer(Modifier.height(20.dp))
        OutlinedButton(onClick = onCancel) {
            Icon(Icons.Default.Cancel, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Cancel")
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun DoneContent(results: List<SourceHealth>, onRecheck: () -> Unit) {
    // Group by console; within each group show alive first then dead.
    val grouped: Map<String, List<SourceHealth>> = results
        .sortedWith(compareBy({ it.console }, { !it.alive }))
        .groupBy { it.console }

    val aliveTotal = results.count { it.alive }
    val deadTotal = results.count { !it.alive }

    Column(modifier = Modifier.fillMaxSize()) {
        // Summary banner
        Surface(
            color = if (deadTotal == 0)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.errorContainer,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    if (deadTotal == 0) Icons.Default.CheckCircle else Icons.Default.Error,
                    contentDescription = null,
                    tint = if (deadTotal == 0)
                        MaterialTheme.colorScheme.onPrimaryContainer
                    else
                        MaterialTheme.colorScheme.onErrorContainer
                )
                Column {
                    Text(
                        "$aliveTotal alive · $deadTotal unreachable out of ${results.size} endpoints",
                        style = MaterialTheme.typography.titleSmall,
                        color = if (deadTotal == 0)
                            MaterialTheme.colorScheme.onPrimaryContainer
                        else
                            MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = onRecheck) { Text("Re-check") }
        }

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            grouped.forEach { (console, items) ->
                val aliveCount = items.count { it.alive }
                item(key = "header_$console") {
                    Text(
                        text = "${console.uppercase()} — $aliveCount/${items.size} alive",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                    )
                    HorizontalDivider()
                }
                items(items, key = { it.url }) { health ->
                    HealthRow(health)
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun HealthRow(health: SourceHealth) {
    val tint = if (health.alive)
        MaterialTheme.colorScheme.tertiary
    else
        MaterialTheme.colorScheme.error

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            if (health.alive) Icons.Default.CheckCircle else Icons.Default.Error,
            contentDescription = if (health.alive) "Alive" else "Dead",
            tint = tint,
            modifier = Modifier.size(18.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                health.url,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = if (health.alive)
                    MaterialTheme.colorScheme.onSurface
                else
                    MaterialTheme.colorScheme.error
            )
            Text(
                health.detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
