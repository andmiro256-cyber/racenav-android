package com.andreykoff.racenav

import android.app.*
import android.content.Context
import android.content.Intent
import android.location.*
import android.os.*
import androidx.core.app.NotificationCompat
import kotlin.math.*

class TrackingService : Service() {

    companion object {
        const val ACTION_START = "START"
        const val ACTION_STOP  = "STOP"

        const val BROADCAST_LOCATION = "com.andreykoff.racenav.LOCATION"
        const val EXTRA_LAT      = "lat"
        const val EXTRA_LON      = "lon"
        const val EXTRA_SPEED    = "speed"
        const val EXTRA_BEARING  = "bearing"
        const val EXTRA_ALTITUDE = "altitude"
        const val EXTRA_HAS_SPEED    = "has_speed"
        const val EXTRA_HAS_ALTITUDE = "has_altitude"

        const val CHANNEL_ID = "tracking_channel"
        const val NOTIF_ID   = 1001

        // Данные трека — читаются из MapFragment
        val trackPoints   = mutableListOf<Pair<Double, Double>>()
        var trackLengthM  = 0.0
        var startTimeMs   = 0L
        var isRunning     = false
    }

    private var locationManager: LocationManager? = null

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            val newPoint = Pair(loc.latitude, loc.longitude)

            // Standstill filter (speed < 1 m/s)
            val isMoving = !loc.hasSpeed() || loc.speed >= 1.0f
            if (isMoving && (trackPoints.isEmpty() || distanceM(trackPoints.last(), newPoint) > 2.0)) {
                if (trackPoints.isNotEmpty()) trackLengthM += distanceM(trackPoints.last(), newPoint)
                trackPoints.add(newPoint)
                updateNotification()
            }

            // Широковещание для MapFragment (обновление UI)
            sendBroadcast(Intent(BROADCAST_LOCATION).apply {
                putExtra(EXTRA_LAT,          loc.latitude)
                putExtra(EXTRA_LON,          loc.longitude)
                putExtra(EXTRA_SPEED,        loc.speed)
                putExtra(EXTRA_BEARING,      loc.bearing)
                putExtra(EXTRA_ALTITUDE,     loc.altitude)
                putExtra(EXTRA_HAS_SPEED,    loc.hasSpeed())
                putExtra(EXTRA_HAS_ALTITUDE, loc.hasAltitude())
            })
        }
        @Deprecated("Deprecated in API 29")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startTracking()
            ACTION_STOP  -> stopTracking()
        }
        return START_STICKY
    }

    private fun startTracking() {
        trackPoints.clear()
        trackLengthM  = 0.0
        startTimeMs   = System.currentTimeMillis()
        isRunning     = true

        startForeground(NOTIF_ID, buildNotification("Запись трека начата…"))

        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val intervalSec = getSharedPreferences(MapFragment.PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(MapFragment.PREF_TRACK_INTERVAL, 1).coerceIn(1, 60)
        val intervalMs = intervalSec * 1000L
        try {
            locationManager?.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                intervalMs,
                0f,      // min distance m
                locationListener,
                Looper.getMainLooper()
            )
        } catch (e: SecurityException) {
            stopSelf()
        }
    }

    private fun stopTracking() {
        isRunning = false
        locationManager?.removeUpdates(locationListener)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun updateNotification() {
        val km   = trackLengthM / 1000.0
        val text = "Запись: ${String.format("%.1f", km)} км • ${trackPoints.size} точек"
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, TrackingService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("RaceNav — запись трека")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_rec)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_media_pause, "Стоп", stopIntent)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Запись трека",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Статус записи трека в фоне"
            setShowBadge(false)
        }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    private fun distanceM(a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
        val R    = 6371000.0
        val lat1 = Math.toRadians(a.first);  val lat2 = Math.toRadians(b.first)
        val dLat = lat2 - lat1;              val dLon = Math.toRadians(b.second - a.second)
        val x    = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLon / 2).pow(2)
        return 2 * R * asin(sqrt(x))
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
