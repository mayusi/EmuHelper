package io.github.mayusi.emuhelper

import android.app.Application
import android.os.StrictMode
import android.util.Log
import dagger.hilt.android.HiltAndroidApp
import java.io.File

@HiltAndroidApp
class EmuHelperApplication : Application() {
    override fun onCreate() {
        super.onCreate()

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
    }
}
