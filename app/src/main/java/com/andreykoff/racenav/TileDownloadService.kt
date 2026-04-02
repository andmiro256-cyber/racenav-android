package com.andreykoff.racenav

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Foreground service that keeps the process alive during tile downloads.
 * Started by TileDownloadManager.startDownload(), stopped when download completes or is cancelled.
 */
class TileDownloadService : Service() {

    companion object {
        const val CHANNEL_ID = "tile_download_channel"
        const val NOTIFICATION_ID = 9001
    }

    private var progressHandler: android.os.Handler? = null
    private var progressRunnable: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification("Загрузка карт...")
        startForeground(NOTIFICATION_ID, notification)

        // Poll progress every 2 seconds to update notification (don't override TileDownloadManager callbacks)
        progressHandler = android.os.Handler(android.os.Looper.getMainLooper())
        progressRunnable = object : Runnable {
            override fun run() {
                val progress = TileDownloadManager.getProgress()
                if (progress.isRunning) {
                    val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                    nm.notify(NOTIFICATION_ID, buildNotification(
                        "Загрузка: ${progress.percent}% (${progress.downloadedTiles}/${progress.totalTiles})"
                    ))
                    progressHandler?.postDelayed(this, 2000)
                } else {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        progressHandler?.postDelayed(progressRunnable!!, 2000)

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        progressRunnable?.let { progressHandler?.removeCallbacks(it) }
        progressHandler = null
        progressRunnable = null
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Загрузка карт",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("RaceNav")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .build()
    }
}
