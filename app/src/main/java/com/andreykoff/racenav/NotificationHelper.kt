package com.andreykoff.racenav

import android.app.*
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

/**
 * Shared notification builder for TrackingService + TraccarService.
 * Both services share one notification (NOTIF_ID = 1001, channel "tracking_channel").
 */
object NotificationHelper {

    const val CHANNEL_ID = "tracking_channel"
    const val NOTIF_ID   = 1001   // Single shared notification ID for both services
    private const val GROUP_KEY = "com.andreykoff.racenav.SERVICES"

    // Current state from each service
    @Volatile var trackingText: String? = null   // e.g. "⏺ Запись: 1.2 км • 50 точек"
    @Volatile var traccarText: String? = null     // e.g. "📡 ✓ Отправлено"

    fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Trophy Navigator",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Сервисы Trophy Navigator в фоне"
            setShowBadge(false)
        }
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    fun buildNotification(context: Context): Notification {
        val parts = mutableListOf<String>()
        trackingText?.let { parts.add(it) }
        traccarText?.let { parts.add(it) }

        val text = if (parts.isEmpty()) "Сервис работает" else parts.joinToString(" • ")

        val openIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("Trophy Navigator")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setSilent(true)
            .setGroup(GROUP_KEY)
            .setGroupSummary(true)
            .setContentIntent(openIntent)
            .build()
    }

    fun update(context: Context) {
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID, buildNotification(context))
    }
}
