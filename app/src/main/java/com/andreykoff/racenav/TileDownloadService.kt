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
 * Foreground service placeholder for tile downloading.
 * Currently downloads run via TileDownloadManager coroutines while activity is alive.
 * This service can be started to keep the process alive during long downloads.
 */
class TileDownloadService : Service() {

    companion object {
        const val CHANNEL_ID = "tile_download_channel"
        const val NOTIFICATION_ID = 9001
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification("Загрузка карт...")
        startForeground(NOTIFICATION_ID, notification)

        TileDownloadManager.onProgressUpdate = { progress ->
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, buildNotification(
                "Загрузка: ${progress.percent}% (${progress.downloadedTiles}/${progress.totalTiles})"
            ))
        }

        TileDownloadManager.onComplete = {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
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
