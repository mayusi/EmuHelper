package io.github.mayusi.emuhelper

import android.app.Application
import android.os.StrictMode
import android.util.Log
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import io.github.mayusi.emuhelper.data.source.CatalogRepository
import io.github.mayusi.emuhelper.data.storage.CrashLogStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

@HiltAndroidApp
class EmuHelperApplication : Application() {

    /** Hilt entry point used to retrieve [CatalogRepository] inside onCreate. */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface AppEntryPoint {
        fun catalogRepository(): CatalogRepository
        fun crashLogStore(): CrashLogStore
    }

    /** Application-scoped coroutine scope for fire-and-forget background work. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        // Phase 2 (Windows port): the portable download stack in :shared logs through the
        // platform-agnostic io.github.mayusi.emuhelper.platform.Log facade. Install the Android sink
        // (android.util.Log) once, first thing, so every log line from the moved RemoteSource /
        // PersistentCookieJar still reaches logcat exactly as before.
        io.github.mayusi.emuhelper.platform.Log.install(
            object : io.github.mayusi.emuhelper.platform.PlatformLog {
                override fun i(tag: String, msg: String) { Log.i(tag, msg) }
                override fun w(tag: String, msg: String, t: Throwable?) { Log.w(tag, msg, t) }
                override fun e(tag: String, msg: String, t: Throwable?) { Log.e(tag, msg, t) }
            }
        )

        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectDiskReads()
                    .detectDiskWrites()
                    .detectNetwork()
                    .penaltyLog()
                    .build()
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectLeakedClosableObjects()
                    .detectLeakedSqlLiteObjects()
                    .penaltyLog()
                    .build()
            )
        }

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            Log.e("EmuHelper", "Uncaught on ${thread.name}", e)
            // Record the crash to the local (no-network) error log so it can be
            // viewed/exported from About. Best-effort — never let logging itself
            // interfere with the crash propagation below.
            try {
                val ep = EntryPointAccessors.fromApplication(applicationContext, AppEntryPoint::class.java)
                ep.crashLogStore().logSync(thread.name, "${e.javaClass.simpleName}: ${e.message}")
            } catch (_: Throwable) { /* ignore */ }
            previous?.uncaughtException(thread, e)
        }

        // Clean up stale .part files from cancelled/crashed downloads.
        // Cache dir: cacheDir/dl — matches DownloadManager's cache path.
        Thread {
            try {
                val dlCacheDir = File(cacheDir, "dl")
                if (!dlCacheDir.exists()) return@Thread
                val cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000
                val deleted = dlCacheDir.listFiles()
                    ?.filter { it.isFile && it.name.endsWith(".part") && it.lastModified() < cutoff }
                    ?.onEach { it.delete() }
                    ?.size ?: 0
                if (deleted > 0) Log.i("EmuHelper", "Cleaned up $deleted stale .part file(s) from dl cache")
            } catch (e: Exception) {
                Log.w("EmuHelper", "Stale .part cleanup failed", e)
            }
        }.apply { isDaemon = true; start() }

        // Fire-and-forget: load remote catalog overlay on app start.
        // DORMANT by default (placeholder URL) — becomes active once the operator
        // sets a real REMOTE_CATALOG_URL in CatalogRepository. Any failure leaves
        // the baked-in catalog fully intact.
        appScope.launch {
            try {
                val entryPoint = EntryPointAccessors.fromApplication(
                    applicationContext, AppEntryPoint::class.java
                )
                entryPoint.catalogRepository().loadOnStart()
            } catch (e: Exception) {
                Log.w("EmuHelper", "Remote catalog loadOnStart failed (baked-in catalog active)", e)
            }
        }
    }
}
