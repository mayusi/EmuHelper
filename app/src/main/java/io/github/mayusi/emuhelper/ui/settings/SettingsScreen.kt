package io.github.mayusi.emuhelper.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.mayusi.emuhelper.data.source.DeviceInfo
import io.github.mayusi.emuhelper.data.source.SpeedResult
import io.github.mayusi.emuhelper.data.source.SpeedTester
import io.github.mayusi.emuhelper.data.storage.SettingsStore
import io.github.mayusi.emuhelper.ui.common.Dimens
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsStore,
    private val speedTester: SpeedTester
) : ViewModel() {

    val segments: StateFlow<Int> = settings.segments.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 8)
    val concurrency: StateFlow<Int> = settings.concurrency.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 2)
    val extractArchives: StateFlow<Boolean> = settings.extractArchives.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val _testing = MutableStateFlow(false)
    val testing: StateFlow<Boolean> = _testing
    private val _result = MutableStateFlow<SpeedResult?>(null)
    val result: StateFlow<SpeedResult?> = _result

    val device: DeviceInfo by lazy { speedTester.deviceInfo() }

    fun setSegments(v: Int) { viewModelScope.launch { settings.setSegments(v) } }
    fun setConcurrency(v: Int) { viewModelScope.launch { settings.setConcurrency(v) } }
    fun setExtract(v: Boolean) { viewModelScope.launch { settings.setExtractArchives(v) } }

    fun maxThroughput() {
        viewModelScope.launch {
            // Safe maximum: 16 connections to one file × 2 files = 32 desired, but the
            // DownloadManager hard-caps total at 24. These are the highest SAFE values.
            settings.setSegments(16)
            settings.setConcurrency(2)
        }
    }

    fun runSpeedTest() {
        if (_testing.value) return
        viewModelScope.launch {
            _testing.value = true
            _result.value = null
            try { _result.value = speedTester.run() } finally { _testing.value = false }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val segments by viewModel.segments.collectAsState()
    val concurrency by viewModel.concurrency.collectAsState()
    val extractArchives by viewModel.extractArchives.collectAsState()
    val testing by viewModel.testing.collectAsState()
    val result by viewModel.result.collectAsState()
    val device = remember { viewModel.device }

    val context = LocalContext.current
    val appVersion = remember {
        try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            val code = if (android.os.Build.VERSION.SDK_INT >= 28) pInfo.longVersionCode else @Suppress("DEPRECATION") pInfo.versionCode.toLong()
            "${pInfo.versionName} ($code)"
        } catch (e: Exception) { "—" }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(horizontal = Dimens.ScreenHorizontal, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ---- Download speed controls ----
            SettingCard(title = "Download speed") {
                Text("Connections per file: $segments", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    "Some sources limit each connection's speed, so more connections can be faster (up to your link's limit).",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = segments.toFloat(),
                    onValueChange = { viewModel.setSegments(it.toInt()) },
                    valueRange = 1f..16f, steps = 14
                )
                Spacer(Modifier.height(8.dp))
                Text("Files at once: $concurrency", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                Slider(
                    value = concurrency.toFloat(),
                    onValueChange = { viewModel.setConcurrency(it.toInt()) },
                    valueRange = 1f..8f, steps = 6
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { viewModel.maxThroughput() },
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) { Text("Max throughput (16 conns × 2 files)") }
                Text(
                    "Pushes your connection hard. Helps on fast wifi; won't exceed your link's ceiling.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // ---- After download ----
            SettingCard(title = "After download") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Extract .zip archives", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    Switch(
                        checked = extractArchives,
                        onCheckedChange = { viewModel.setExtract(it) }
                    )
                }
            }

            // ---- Network speed test ----
            SettingCard(title = "Network speed test") {
                Text(
                    "Measure your real download speed so you know whether slow downloads are your wifi or the app.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { viewModel.runSpeedTest() },
                    enabled = !testing,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer)
                ) {
                    if (testing) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        Spacer(Modifier.width(8.dp))
                        Text("Testing…")
                    } else {
                        Icon(Icons.Default.Speed, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Run speed test")
                    }
                }
                result?.let { r ->
                    Spacer(Modifier.height(10.dp))
                    Text("%.1f Mbit/s  (%.2f MB/s)".format(r.mbps, r.bytesPerSec / 1048576.0),
                        style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    Text(r.verdict, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    if (r.minutesPerGb > 0) {
                        Text("≈ %.1f min per GB at this speed".format(r.minutesPerGb),
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // ---- Device info ----
            SettingCard(title = "Device") {
                InfoRow("Model", device.model)
                InfoRow("RAM", "${device.availRamMb} MB free / ${device.totalRamMb} MB")
                InfoRow("CPU cores", device.cpuCores.toString())
                InfoRow("App version", appVersion)
            }

            Text(
                "Download speed depends on the source server and your connection. Multi-connection + your link set the real limit.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SettingCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}
