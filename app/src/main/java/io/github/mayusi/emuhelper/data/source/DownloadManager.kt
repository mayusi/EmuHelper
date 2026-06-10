package io.github.mayusi.emuhelper.data.source

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.mayusi.emuhelper.data.config.Catalog
import io.github.mayusi.emuhelper.data.model.CuratedGame
import io.github.mayusi.emuhelper.data.model.DownloadStatus
import io.github.mayusi.emuhelper.data.model.DownloadTask
import io.github.mayusi.emuhelper.data.storage.HistoryEntry
import io.github.mayusi.emuhelper.data.storage.HistoryStore
import io.github.mayusi.emuhelper.data.storage.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-scoped download engine. Lives in the Hilt SingletonComponent and runs work on
 * an application-lifetime CoroutineScope, so downloads keep running when the user
 * leaves the Download screen or backgrounds the app (the foreground DownloadService
 * keeps the process alive). They only stop if the user force-closes the app.
 *
 * Strategy for speed + correctness:
 *  - Download each file with multi-connection ranged requests into APP CACHE (a real
 *    local File supporting random-access seek), then copy the finished file into the
 *    user's chosen folder (SAF) or app-private downloads dir in one sequential pass.
 *  - Files are organised into per-platform subfolders (e.g. Downloads/SNES/).
 */
@Singleton
class DownloadManager @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val source: RemoteSource,
    private val settings: SettingsStore,
    private val historyStore: HistoryStore
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks: StateFlow<List<DownloadTask>> = _tasks
    private val _totalProgress = MutableStateFlow(0f)
    val totalProgress: StateFlow<Float> = _totalProgress
    private val _totalSpeed = MutableStateFlow(0.0)
    val totalSpeed: StateFlow<Double> = _totalSpeed
    private val _eta = MutableStateFlow("--")
    val eta: StateFlow<String> = _eta
    private val _statusText = MutableStateFlow("Preparing...")
    val statusText: StateFlow<String> = _statusText
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning
    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused

    @Volatile private var cancelRequested = false
    private var batchJob: Job? = null
    /** Guards against double-recording history for the same batch. */
    @Volatile private var batchHistoryRecorded = false

    /** Caller-tunable. Loaded from settings on each start(). */
    @Volatile private var segmentsPerFile = 4
    @Volatile private var concurrentFiles = 2
    @Volatile private var extractArchives = false

    // HARD SAFETY CEILING on simultaneous HTTP connections across ALL files+segments.
    // Without this, concurrentFiles(16) × segmentsPerFile(32) = 512 sockets + 128 MB of
    // buffers could peg the CPU/network and thermally force-shutdown a handheld (which
    // is exactly what happened). 24 simultaneous connections is plenty for typical use.
    private val MAX_TOTAL_CONNECTIONS = 24
    private val connectionBudget = kotlinx.coroutines.sync.Semaphore(MAX_TOTAL_CONNECTIONS)

    /** Smoothed speed for stable ETA calculations. Prevents jitter when concurrent files start/stop. */
    @Volatile private var smoothedSpeed = 0.0

    fun start(games: List<CuratedGame>) {
        if (_isRunning.value) return
        if (games.isEmpty()) return
        _isRunning.value = true
        _isPaused.value = false
        cancelRequested = false
        batchHistoryRecorded = false
        DownloadService.start(appContext)

        batchJob = scope.launch {
            val chosenUri = settings.downloadFolder.first()
            // Clamp to SAFE ceilings. Files-at-once is the dangerous multiplier, so cap
            // it low (downloads are network-bound; 2-3 files saturates the source host).
            concurrentFiles = settings.concurrency.first().coerceIn(1, 4)
            segmentsPerFile = settings.segments.first().coerceIn(1, 16)
            // Ensure files × segments can never exceed the global connection budget.
            if (concurrentFiles * segmentsPerFile > MAX_TOTAL_CONNECTIONS) {
                segmentsPerFile = (MAX_TOTAL_CONNECTIONS / concurrentFiles).coerceAtLeast(1)
            }
            extractArchives = settings.extractArchives.first()

            val customRoot = chosenUri?.let { DocumentFile.fromTreeUri(appContext, it) }
            val defaultRoot = File(appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "ROMs")
                .apply { mkdirs() }

            // Upfront SAF permission check: if the user picked a custom folder but the
            // persisted grant was revoked (or the folder is no longer writable), every
            // file would otherwise download fully to cache and only THEN fail at the
            // copy step with "Could not create file in chosen folder". Detect that here
            // and abort the whole batch with one clear message instead of N late failures.
            if (chosenUri != null) {
                val grantHeld = try {
                    appContext.contentResolver.persistedUriPermissions.any {
                        it.uri == chosenUri && it.isWritePermission
                    }
                } catch (e: Exception) {
                    Log.w("EmuHelper", "Checking persisted URI permissions failed", e)
                    false
                }
                val writable = try {
                    grantHeld && customRoot?.canWrite() == true
                } catch (e: Exception) {
                    Log.w("EmuHelper", "Checking folder writability failed", e)
                    false
                }
                if (!writable) {
                    _statusText.value =
                        "Storage folder access was lost — please re-pick your download folder in the menu."
                    _isRunning.value = false
                    DownloadService.stop(appContext)
                    return@launch
                }
            }

            val taskList = games.map { g ->
                val safeName = File(g.filename).name
                val url = source.buildDownloadUrl(g.identifier, g.filename)
                val consoleKey = g.console.ifBlank { Catalog.consoleForIdentifier(g.identifier) ?: "" }
                val subfolder = Catalog.folderForConsole(consoleKey)
                val displayPath = customRoot?.uri?.toString()?.let { "$it / $subfolder / $safeName" }
                    ?: File(File(defaultRoot, subfolder), safeName).absolutePath
                DownloadTask(
                    id = "${g.identifier}/${g.filename}",
                    url = url, displayPath = displayPath, filename = safeName,
                    size = g.size, identifier = g.identifier, relativeName = g.filename, subfolder = subfolder
                )
            }
            _tasks.value = taskList.sortedBy { it.filename }
            _statusText.value = "Downloading ${taskList.size} files..."
            recomputeAggregates()

            val sem = Semaphore(concurrentFiles)
            _tasks.value.map { it.id }.map { id ->
                launch {
                    sem.withPermit {
                        if (!cancelRequested) downloadOne(id, customRoot, defaultRoot)
                    }
                }
            }.forEach { it.join() }

            val done = _tasks.value.count { it.status == DownloadStatus.DONE }
            val failed = _tasks.value.count { it.status == DownloadStatus.FAILED }
            _statusText.value = when {
                failed == 0 -> "Complete: $done done"
                done == 0 -> "All $failed downloads failed"
                else -> "Done: $done · Failed: $failed"
            }
            recordBatchHistory(_tasks.value)
            _isRunning.value = false
            DownloadService.stop(appContext)
        }
    }

    fun retry(taskId: String) {
        updateTask(taskId) {
            if (it.status != DownloadStatus.FAILED && it.status != DownloadStatus.CANCELLED) it
            else it.copy(status = DownloadStatus.QUEUED, downloaded = 0, speed = 0.0, error = "")
        }
        val t = _tasks.value.firstOrNull { it.id == taskId } ?: return
        if (t.status != DownloadStatus.QUEUED) return

        if (!_isRunning.value) { _isRunning.value = true; cancelRequested = false; DownloadService.start(appContext) }
        scope.launch {
            val chosenUri = settings.downloadFolder.first()
            val customRoot = chosenUri?.let { DocumentFile.fromTreeUri(appContext, it) }
            val defaultRoot = File(appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "ROMs").apply { mkdirs() }
            try {
                downloadOne(taskId, customRoot, defaultRoot)
            } finally {
                if (_tasks.value.none { it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.QUEUED }) {
                    _isRunning.value = false
                    DownloadService.stop(appContext)
                }
            }
        }
    }

    fun pause() {
        _isPaused.value = true
        if (_isRunning.value) DownloadService.updatePausedState(appContext, paused = true)
    }
    fun resume() {
        _isPaused.value = false
        if (_isRunning.value) DownloadService.updatePausedState(appContext, paused = false)
    }
    fun cancelAll() {
        cancelRequested = true
        batchJob?.cancel()
        _isPaused.value = false
        _isRunning.value = false
        DownloadService.stop(appContext)
    }

    /** Reset visible state when the user leaves a finished batch. */
    fun clear() {
        if (_isRunning.value) return
        // Record any terminal tasks not yet captured at batch-complete time (e.g. partial
        // retry batches, or if the user cancels mid-way and taps Done).
        val snapshot = _tasks.value
        if (snapshot.isNotEmpty()) {
            scope.launch { recordBatchHistory(snapshot) }
        }
        _tasks.value = emptyList()
        _statusText.value = "Preparing..."
        _totalProgress.value = 0f
        _totalSpeed.value = 0.0
        _eta.value = "--"
        smoothedSpeed = 0.0
        batchHistoryRecorded = false
    }

    // ---- one file ---------------------------------------------------------

    private suspend fun downloadOne(taskId: String, customRootBase: DocumentFile?, defaultRootBase: File) {
        val base = _tasks.value.firstOrNull { it.id == taskId } ?: return
        val filename = base.filename
        val expected = base.size
        val url = base.url
        val subfolder = base.subfolder

        updateTask(taskId) { it.copy(status = DownloadStatus.DOWNLOADING, error = "", speed = 0.0) }

        // If the final file already exists at the destination and looks complete, skip.
        if (destinationHasComplete(customRootBase, defaultRootBase, subfolder, filename, expected)) {
            updateTask(taskId) { it.copy(downloaded = expected, status = DownloadStatus.DONE, speed = 0.0) }
            return
        }

        // Download into app cache (real File, supports random-access multi-connection).
        val cacheDir = File(appContext.cacheDir, "dl").apply { mkdirs() }
        val cacheFile = File(cacheDir, "${taskId.hashCode()}_$filename.part")

        // Gather mirror hosts for this file (primary + d1/d2 + CDN nodes) so the
        // segmented downloader can spread load and fail over between them.
        val mirrors = runCatching { source.mirrorUrls(base.identifier, base.relativeName) }
            .getOrDefault(listOf(url))
            .ifEmpty { listOf(url) }

        try {
            val total = source.downloadFileSegmented(
                candidateUrls = mirrors,
                expectedSize = expected,
                destFile = cacheFile,
                segments = segmentsPerFile,
                onProgress = { bytes, bps ->
                    // honour pause: block here while paused
                    if (_isPaused.value) {
                        updateTask(taskId) { it.copy(status = DownloadStatus.PAUSED, speed = 0.0) }
                        while (_isPaused.value && !cancelRequested) delay(200)
                        if (!cancelRequested) updateTask(taskId) { it.copy(status = DownloadStatus.DOWNLOADING) }
                    }
                    updateTask(taskId) { it.copy(downloaded = bytes, speed = bps) }
                },
                isCancelled = { cancelRequested }
            )

            // Validate size, then publish into the destination folder.
            val ok = if (expected > 0) total >= expected * 99 / 100 else total > 0
            if (!ok) throw java.io.IOException("Incomplete: $total / $expected")

            _statusText.value = "Saving $filename…"
            if (extractArchives && filename.lowercase().endsWith(".zip")) {
                extractZipToDestination(cacheFile, customRootBase, defaultRootBase, subfolder)
            } else {
                copyToDestination(cacheFile, customRootBase, defaultRootBase, subfolder, filename)
            }
            cacheFile.delete()
            updateTask(taskId) { it.copy(downloaded = if (expected > 0) expected else total, status = DownloadStatus.DONE, speed = 0.0) }
        } catch (c: CancellationException) {
            cacheFile.delete()
            updateTask(taskId) { it.copy(status = DownloadStatus.CANCELLED, speed = 0.0) }
        } catch (e: Exception) {
            Log.w("EmuHelper", "Download failed: $filename", e)
            cacheFile.delete()
            updateTask(taskId) { it.copy(status = DownloadStatus.FAILED, speed = 0.0, error = e.message ?: e.javaClass.simpleName) }
        }
    }

    private fun destinationHasComplete(
        customRootBase: DocumentFile?, defaultRootBase: File, subfolder: String, filename: String, expected: Long
    ): Boolean {
        if (expected <= 0) return false
        return try {
            if (customRootBase != null) {
                val dir = findChildCI(customRootBase, subfolder)?.takeIf { it.isDirectory }
                val f = dir?.let { findChildCI(it, filename) }
                f != null && f.length() >= expected * 99 / 100
            } else {
                val f = File(File(defaultRootBase, subfolder), filename)
                f.exists() && f.length() >= expected * 99 / 100
            }
        } catch (e: Exception) { false }
    }

    /** Copy the finished cache file into the chosen folder (SAF) or app-private dir. */
    private fun copyToDestination(
        cacheFile: File, customRootBase: DocumentFile?, defaultRootBase: File, subfolder: String, filename: String
    ) {
        if (customRootBase != null) {
            val dir = resolveSafSubdir(customRootBase, subfolder)
            findChildCI(dir, filename)?.delete()
            val out = dir.createFile("application/octet-stream", filename)
                ?: throw java.io.IOException("Could not create file in chosen folder")
            appContext.contentResolver.openOutputStream(out.uri, "w")?.use { os ->
                cacheFile.inputStream().use { it.copyTo(os, 1 shl 20) }
            } ?: throw java.io.IOException("Could not open destination stream")
        } else {
            val dir = File(defaultRootBase, subfolder).apply { mkdirs() }
            val dest = File(dir, filename)
            if (dest.exists()) dest.delete()
            cacheFile.copyTo(dest, overwrite = true)
        }
    }

    /** Extract .zip archive into destination folder. Falls back to copyToDestination on failure. */
    private fun extractZipToDestination(
        zipFile: File, customRootBase: DocumentFile?, defaultRootBase: File, subfolder: String
    ) {
        try {
            ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        // Sanitize entry name to prevent zip-slip and keep it safe
                        val entryName = File(entry.name).name  // Use only basename for simplicity

                        if (customRootBase != null) {
                            // SAF path: flatten all entries into the console subfolder
                            val dir = resolveSafSubdir(customRootBase, subfolder)
                            findChildCI(dir, entryName)?.delete()
                            val outFile = dir.createFile("application/octet-stream", entryName)
                                ?: throw java.io.IOException("Could not create file: $entryName")
                            appContext.contentResolver.openOutputStream(outFile.uri, "w")?.use { os ->
                                zis.copyTo(os, 1 shl 20)
                            } ?: throw java.io.IOException("Could not open output stream for: $entryName")
                        } else {
                            // App-private File path: preserve nested directories
                            val relativePath = entry.name.split('/').filter { it.isNotEmpty() && it != ".." }
                                .joinToString("/")
                            val outFile = File(File(defaultRootBase, subfolder), relativePath)
                            outFile.parentFile?.mkdirs()
                            outFile.outputStream().buffered().use { os ->
                                zis.copyTo(os, 1 shl 20)
                            }
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        } catch (e: Exception) {
            Log.w("EmuHelper", "Failed to extract zip, falling back to copy", e)
            // Fall back to copying the zip file as-is
            copyToDestination(zipFile, customRootBase, defaultRootBase, subfolder, zipFile.name)
        }
    }

    /** Case-insensitive child lookup; SAF's findFile is case-sensitive, but folders made by
     *  other tools (e.g. EmuTran) are often lowercase. Returns the first name match or null. */
    private fun findChildCI(parent: DocumentFile, displayName: String): DocumentFile? =
        parent.listFiles().firstOrNull { it.name?.equals(displayName, ignoreCase = true) == true }

    private val safCache = java.util.concurrent.ConcurrentHashMap<String, DocumentFile>()
    @Synchronized
    private fun resolveSafSubdir(parent: DocumentFile, name: String): DocumentFile {
        if (name.isBlank()) return parent
        // Case-insensitive cache key + lookup: reuse an existing folder even if its case
        // differs (tools like EmuTran create lowercase folders, e.g. "psp" vs our "PSP"),
        // so we don't create a duplicate "PSP (1)" next to it. SAF's findFile is case-sensitive.
        val key = "${parent.uri}/${name.lowercase()}"
        safCache[key]?.let { if (it.exists()) return it }
        val existing = parent.listFiles().firstOrNull { it.isDirectory && it.name?.equals(name, ignoreCase = true) == true }
        val dir = if (existing != null) existing else parent.createDirectory(name) ?: parent
        safCache[key] = dir
        return dir
    }

    // ---- history recording -----------------------------------------------

    /**
     * Snapshot terminal [DownloadTask]s into [HistoryStore]. Uses [batchHistoryRecorded] as a
     * guard so the same batch is never written twice (once at batch-complete, once at clear()).
     * Records only DONE/FAILED/CANCELLED tasks; skips QUEUED/DOWNLOADING/PAUSED.
     */
    private suspend fun recordBatchHistory(tasks: List<DownloadTask>) {
        if (batchHistoryRecorded) return
        val terminal = tasks.filter {
            it.status == DownloadStatus.DONE ||
            it.status == DownloadStatus.FAILED ||
            it.status == DownloadStatus.CANCELLED
        }
        if (terminal.isEmpty()) return
        batchHistoryRecorded = true
        val now = System.currentTimeMillis()
        val entries = terminal.map { task ->
            HistoryEntry(
                filename = task.filename,
                subfolder = task.subfolder,
                sizeBytes = task.size,
                status = task.status.name,
                timestampMillis = now
            )
        }
        historyStore.addAll(entries)
    }

    // ---- state plumbing ---------------------------------------------------

    @Synchronized
    private fun updateTask(id: String, transform: (DownloadTask) -> DownloadTask) {
        val all = _tasks.value
        val idx = all.indexOfFirst { it.id == id }
        if (idx < 0) return
        val updated = all.toMutableList().apply { this[idx] = transform(this[idx]) }
        _tasks.value = updated
        recomputeAggregates(updated)
    }

    private fun recomputeAggregates(all: List<DownloadTask> = _tasks.value) {
        // Compute aggregate remaining bytes over all non-terminal tasks
        val td = all.sumOf { it.downloaded.toDouble() }
        val ts = all.sumOf { it.size.toDouble() }
        val sp = all.filter { it.status == DownloadStatus.DOWNLOADING }.sumOf { it.speed }
        val remaining = (ts - td).coerceAtLeast(0.0)

        _totalProgress.value = if (ts > 0) (td / ts).toFloat().coerceIn(0f, 1f) else 0f

        // Update smoothed speed: exponential moving average for stability
        smoothedSpeed = if (sp > 0.1) {
            if (smoothedSpeed <= 0) sp else smoothedSpeed * 0.6 + sp * 0.4
        } else {
            0.0
        }

        _totalSpeed.value = sp
        _eta.value = if (smoothedSpeed > 0.1) io.github.mayusi.emuhelper.ui.common.formatEta(remaining / smoothedSpeed) else "--"
    }
}
