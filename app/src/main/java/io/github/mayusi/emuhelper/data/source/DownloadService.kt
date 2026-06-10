package io.github.mayusi.emuhelper.data.source

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import io.github.mayusi.emuhelper.MainActivity
import io.github.mayusi.emuhelper.R

/**
 * Lightweight foreground service that keeps the app's process alive at foreground
 * priority while downloads are running. Downloads themselves still run inside
 * DownloadManager — this service exists purely to extend process lifetime past
 * the activity being backgrounded.
 *
 * Also hosts Pause/Resume and Cancel notification actions. The actions send an
 * Intent back to this same service (via [PendingIntent.getService]) with
 * [EXTRA_ACTION] set to [ACTION_PAUSE], [ACTION_RESUME], or [ACTION_CANCEL].
 * Routing actions through the service avoids a separate BroadcastReceiver.
 *
 * DownloadManager is retrieved via a Hilt [EntryPoint] (not @AndroidEntryPoint) to
 * avoid the javac annotation-processor metadata-version issue in the current Hilt version.
 *
 * Started via [start] when a download begins; stopped via [stop] when no work remains.
 */
class DownloadService : Service() {

    /** Hilt entry-point to pull [DownloadManager] from the singleton component. */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface DownloadManagerEntryPoint {
        fun downloadManager(): DownloadManager
    }

    private val downloadManager: DownloadManager by lazy {
        EntryPointAccessors.fromApplication(
            applicationContext,
            DownloadManagerEntryPoint::class.java
        ).downloadManager()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Handle notification action taps routed back to us.
        // ACTION_REFRESH is sent by DownloadManager itself to update the notification label
        // without re-invoking manager (avoids re-entrancy).
        when (intent?.getStringExtra(EXTRA_ACTION)) {
            ACTION_PAUSE    -> { downloadManager.pause();     rebuildNotification(paused = true) }
            ACTION_RESUME   -> { downloadManager.resume();    rebuildNotification(paused = false) }
            ACTION_CANCEL   -> { downloadManager.cancelAll(); return START_NOT_STICKY }
            ACTION_REFRESH  -> { rebuildNotification(paused = downloadManager.isPaused.value) }
            else            -> { /* normal start */ rebuildNotification(paused = downloadManager.isPaused.value) }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    // ---- notification helpers --------------------------------------------

    private fun rebuildNotification(paused: Boolean) {
        ensureChannel(this)
        startForeground(NOTIFICATION_ID, buildNotification(paused))
    }

    private fun buildNotification(paused: Boolean): Notification {
        val openPending = PendingIntent.getActivity(
            this, RC_OPEN,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val pauseResumePending = if (paused) {
            PendingIntent.getService(
                this, RC_RESUME,
                Intent(this, DownloadService::class.java).putExtra(EXTRA_ACTION, ACTION_RESUME),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        } else {
            PendingIntent.getService(
                this, RC_PAUSE,
                Intent(this, DownloadService::class.java).putExtra(EXTRA_ACTION, ACTION_PAUSE),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }

        val cancelPending = PendingIntent.getService(
            this, RC_CANCEL,
            Intent(this, DownloadService::class.java).putExtra(EXTRA_ACTION, ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val pauseResumeIcon = if (paused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause
        val pauseResumeLabel = if (paused) "Resume" else "Pause"
        val contentText = if (paused) "Download paused" else "Downloading in the background"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("EmuHelper")
            .setContentText(contentText)
            .setContentIntent(openPending)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(pauseResumeIcon, pauseResumeLabel, pauseResumePending)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelPending)
            .build()
    }

    companion object {
        private const val CHANNEL_ID      = "downloads"
        const val NOTIFICATION_ID         = 42

        // Intent action-routing extra + values
        private const val EXTRA_ACTION    = "download_action"
        private const val ACTION_PAUSE    = "pause"
        private const val ACTION_RESUME   = "resume"
        private const val ACTION_CANCEL   = "cancel"
        /** Sent by DownloadManager to refresh the notification label without re-invoking the manager. */
        private const val ACTION_REFRESH  = "refresh"

        // Unique request codes per pending intent (avoids PendingIntent collisions)
        private const val RC_OPEN         = 0
        private const val RC_PAUSE        = 1
        private const val RC_RESUME       = 2
        private const val RC_CANCEL       = 3

        fun start(context: Context) {
            val intent = Intent(context, DownloadService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DownloadService::class.java))
        }

        /**
         * Re-post the notification so the Pause/Resume label updates while the service
         * is running. Sends ACTION_REFRESH so the service reads current state from the
         * manager without re-invoking manager (avoids re-entrancy).
         */
        fun updatePausedState(context: Context, @Suppress("UNUSED_PARAMETER") paused: Boolean) {
            val intent = Intent(context, DownloadService::class.java)
                .putExtra(EXTRA_ACTION, ACTION_REFRESH)
            ContextCompat.startForegroundService(context, intent)
        }

        private fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                    nm.createNotificationChannel(
                        NotificationChannel(
                            CHANNEL_ID,
                            "Downloads",
                            NotificationManager.IMPORTANCE_LOW
                        ).apply {
                            description = "Ongoing downloads"
                            setShowBadge(false)
                        }
                    )
                }
            }
        }
    }
}
