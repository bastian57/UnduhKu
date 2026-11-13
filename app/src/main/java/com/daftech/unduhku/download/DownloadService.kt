package com.daftech.unduhku.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.daftech.unduhku.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service download — supaya download tetap jalan + ada
 * notifikasi saat layar mati / app di-background.
 *
 * Service TIDAK menyimpan status sendiri: semua status ada di
 * DownloadEngine (StateFlow). Service hanya: (1) startForeground
 * SEGERA di onStartCommand, (2) menjalankan download di serviceScope,
 * (3) mencerminkan Engine.state ke notifikasi.
 */
class DownloadService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var notificationStarted = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
        serviceScope.launch {
            DownloadEngine.state.collect { state ->
                if (notificationStarted) updateNotification(state)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.getStringExtra(EXTRA_ACTION) ?: ACTION_START
        when (action) {
            ACTION_PAUSE -> DownloadEngine.pauseDownload()
            ACTION_RESUME -> DownloadEngine.resumeDownload()
            ACTION_CANCEL -> DownloadEngine.cancelDownload()
            ACTION_START -> {
                val url = intent?.getStringExtra(EXTRA_URL).orEmpty()
                val fileName = intent?.getStringExtra(EXTRA_FILE_NAME).orEmpty()
                val totalBytes = intent?.getLongExtra(EXTRA_TOTAL_BYTES, -1L) ?: -1L
                val expectedMd5 = intent?.getStringExtra(EXTRA_MD5)
                val supportsRanges = intent?.getBooleanExtra(EXTRA_SUPPORTS_RANGES, false) ?: false
                if (url.isBlank() || fileName.isBlank()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                // startForeground HARUS dipanggil sebelum kerja berat.
                startForeground(NOTIF_ID, buildNotification(DownloadEngine.state.value))
                notificationStarted = true
                val request = MultiChunkDownloader.Request(
                    url = url,
                    fileName = fileName,
                    totalBytes = totalBytes,
                    expectedMd5 = expectedMd5,
                    supportsRanges = supportsRanges
                )
                val started = DownloadEngine.startDownload(this, serviceScope, request)
                if (!started) stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Download UnduhKu",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Progres download file UnduhKu"
                }
            )
        }
    }

    private fun updateNotification(state: DownloadEngine.State) {
        val notification = buildNotification(state)
        try {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIF_ID, notification)
        } catch (e: Exception) { /* abaikan */
        }
        when (state.status) {
            "COMPLETED", "FAILED", "CANCELLED" -> {
                // Notifikasi akhir TETAP tampil; service berhenti.
                try {
                    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
                } catch (e: Exception) { /* abaikan */
                }
                stopSelf()
            }
        }
    }

    private fun buildNotification(state: DownloadEngine.State): android.app.Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(openPending)
            .setOnlyAlertOnce(true)
        when (state.status) {
            "DOWNLOADING" -> {
                val percent = (state.progressFraction * 100).toInt()
                val text = if (state.totalBytes > 0L) {
                    "$percent% • ${LinkUtils.formatBytes(state.downloadedBytes)} / " +
                        "${LinkUtils.formatBytes(state.totalBytes)} • " +
                        LinkUtils.formatSpeed(state.bytesPerSec)
                } else {
                    "${LinkUtils.formatBytes(state.downloadedBytes)} • " +
                        LinkUtils.formatSpeed(state.bytesPerSec)
                }
                builder
                    .setContentTitle("Mengunduh ${state.fileName}")
                    .setContentText(text)
                    .setOngoing(true)
                    .setProgress(100, percent, state.totalBytes <= 0L)
                    .addAction(actionButton("Jeda", ACTION_PAUSE))
                    .addAction(actionButton("Batal", ACTION_CANCEL))
            }
            "PAUSED" -> {
                builder
                    .setContentTitle("Dijeda: ${state.fileName}")
                    .setContentText(
                        "${LinkUtils.formatBytes(state.downloadedBytes)}" +
                            if (state.totalBytes > 0L) " / ${LinkUtils.formatBytes(state.totalBytes)}" else ""
                    )
                    .setOngoing(true)
                    .setProgress(
                        100,
                        (state.progressFraction * 100).toInt(),
                        state.totalBytes <= 0L
                    )
                    .addAction(actionButton("Lanjut", ACTION_RESUME))
                    .addAction(actionButton("Batal", ACTION_CANCEL))
            }
            "COMPLETED" -> {
                builder
                    .setContentTitle("Selesai: ${state.fileName}")
                    .setContentText(state.displayPath)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setOngoing(false)
                    .setProgress(0, 0, false)
            }
            "FAILED" -> {
                builder
                    .setContentTitle("Gagal: ${state.fileName}")
                    .setContentText(state.errorMessage ?: "Download gagal")
                    .setOngoing(false)
                    .setProgress(0, 0, false)
            }
            "CANCELLED" -> {
                builder
                    .setContentTitle("Dibatalkan: ${state.fileName}")
                    .setOngoing(false)
                    .setProgress(0, 0, false)
            }
            else -> {
                builder
                    .setContentTitle("UnduhKu")
                    .setContentText("Siap mengunduh")
                    .setOngoing(true)
            }
        }
        return builder.build()
    }

    private fun actionButton(label: String, action: String): NotificationCompat.Action {
        val intent = Intent(this, DownloadService::class.java).apply {
            putExtra(EXTRA_ACTION, action)
        }
        val pending = PendingIntent.getService(
            this, action.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Action(0, label, pending)
    }

    companion object {
        const val CHANNEL_ID = "unduhku_download"
        const val NOTIF_ID = 1001

        const val ACTION_START = "start"
        const val ACTION_PAUSE = "pause"
        const val ACTION_RESUME = "resume"
        const val ACTION_CANCEL = "cancel"

        const val EXTRA_ACTION = "action"
        const val EXTRA_URL = "url"
        const val EXTRA_FILE_NAME = "file_name"
        const val EXTRA_TOTAL_BYTES = "total_bytes"
        const val EXTRA_MD5 = "md5"
        const val EXTRA_SUPPORTS_RANGES = "supports_ranges"

        fun startIntent(
            context: Context,
            url: String,
            fileName: String,
            totalBytes: Long,
            expectedMd5: String?,
            supportsRanges: Boolean
        ): Intent {
            return Intent(context, DownloadService::class.java).apply {
                putExtra(EXTRA_ACTION, ACTION_START)
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_FILE_NAME, fileName)
                putExtra(EXTRA_TOTAL_BYTES, totalBytes)
                putExtra(EXTRA_MD5, expectedMd5)
                putExtra(EXTRA_SUPPORTS_RANGES, supportsRanges)
            }
        }
    }
}
