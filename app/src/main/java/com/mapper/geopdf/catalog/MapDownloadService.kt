package com.mapper.geopdf.catalog

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.mapper.geopdf.R
import com.mapper.geopdf.state.MapController
import com.mapper.geopdf.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Short-lived foreground service keeping active USFS GeoPDF downloads alive with an
 * ongoing progress notification even if the user backgrounds the app.
 */
class MapDownloadService : Service() {

    companion object {
        private const val CHANNEL_ID = "geopdf_map_downloads"
        private const val NOTIFICATION_ID = 4102
        private const val ACTION_START = "com.mapper.geopdf.action.START_DOWNLOAD_SERVICE"
        private const val ACTION_CANCEL = "com.mapper.geopdf.action.CANCEL_DOWNLOAD"

        fun startSafely(context: Context) {
            val intent = Intent(context, MapDownloadService::class.java).apply {
                action = ACTION_START
            }
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (_: Exception) {
                // Background start restrictions may block FGS when screen-off without Auto;
                // MapDownloadManager still runs and resumes cleanly via .part on reopen.
            }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var collectorJob: Job? = null
    private lateinit var notificationManager: NotificationManager
    private var lastNotifyMs: Long = 0L
    private var lastNotifyKey: String = ""

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val controller = MapController.getInstance(this)
        if (intent?.action == ACTION_CANCEL) {
            controller.cancelActiveDownload()
            stopSelfCleanly()
            return START_NOT_STICKY
        }

        val initialDownload = controller.downloadManager.activeDownload.value
        val notification = buildNotification(initialDownload)
        val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, serviceType)

        if (initialDownload == null) {
            stopSelfCleanly()
            return START_NOT_STICKY
        }

        startObservingDownloads(controller)
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        MapController.getInstance(this).cancelActiveDownload()
        stopSelfCleanly()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startObservingDownloads(controller: MapController) {
        if (collectorJob?.isActive == true) return
        collectorJob = serviceScope.launch {
            controller.downloadManager.activeDownload.collect { dl ->
                if (dl == null) {
                    stopSelfCleanly()
                    return@collect
                }
                val pct = dl.progressPercent
                val key = "${dl.mapId}|$pct"
                val now = System.currentTimeMillis()
                if (key != lastNotifyKey && (now - lastNotifyMs >= 500L || pct == 100 || lastNotifyKey.isEmpty())) {
                    lastNotifyKey = key
                    lastNotifyMs = now
                    notificationManager.notify(NOTIFICATION_ID, buildNotification(dl))
                }
            }
        }
    }

    private fun stopSelfCleanly() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun ensureNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Map Downloads",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Progress notifications for offline GeoPDF map downloads"
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildNotification(dl: ActiveDownload?): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val cancelIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, MapDownloadService::class.java).apply { action = ACTION_CANCEL },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (dl != null) "Downloading ${dl.mapName}" else "Preparing map download..."
        val dlMb = (dl?.bytesDownloaded ?: 0L) / (1024.0 * 1024.0)
        val totalMb = dl?.totalBytes?.let { it / (1024.0 * 1024.0) }
        val pct = dl?.progressPercent
        val contentText = when {
            dl == null -> "Connecting..."
            pct != null && totalMb != null ->
                String.format(Locale.US, "%d%% (%.1f / %.1f MB)", pct, dlMb, totalMb)
            else -> String.format(Locale.US, "%.1f MB downloaded", dlMb)
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_map)
            .setContentTitle(title)
            .setContentText(contentText)
            .setContentIntent(openAppIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .addAction(0, getString(R.string.cancel_download), cancelIntent)

        if (pct != null) {
            builder.setProgress(100, pct, false)
        } else {
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }
}
