package com.andreykoff.racenav

import android.app.*
import android.content.Context
import android.content.Intent
import android.location.*
import android.os.*
import kotlin.math.*

class TrackingService : Service() {

    companion object {
        const val ACTION_START  = "START"
        const val ACTION_STOP   = "STOP"
        const val ACTION_RESUME = "RESUME"  // Resume with existing trackPoints (don't clear)

        const val BROADCAST_LOCATION = "com.andreykoff.racenav.LOCATION"
        const val EXTRA_LAT      = "lat"
        const val EXTRA_LON      = "lon"
        const val EXTRA_SPEED    = "speed"
        const val EXTRA_BEARING  = "bearing"
        const val EXTRA_ALTITUDE = "altitude"
        const val EXTRA_HAS_SPEED    = "has_speed"
        const val EXTRA_HAS_ALTITUDE = "has_altitude"

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

                // Auto-save every 10 points to survive app restarts
                if (trackPoints.size % 10 == 0) autoSaveTrack()
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
        NotificationHelper.createChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START  -> startTracking(clearPoints = true)
            ACTION_RESUME -> startTracking(clearPoints = false)
            ACTION_STOP   -> stopTracking()
        }
        return START_STICKY
    }

    private fun startTracking(clearPoints: Boolean) {
        if (clearPoints) {
            trackPoints.clear()
            trackLengthM = 0.0
        }
        startTimeMs = System.currentTimeMillis()
        isRunning   = true

        // Mark recording active in prefs so app knows to offer resume on restart
        getSharedPreferences(MapFragment.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(MapFragment.PREF_WAS_RECORDING, true).apply()

        NotificationHelper.trackingText = "⏺ Запись трека начата…"
        startForeground(NotificationHelper.NOTIF_ID, NotificationHelper.buildNotification(this))

        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val intervalSec = getSharedPreferences(MapFragment.PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(MapFragment.PREF_TRACK_INTERVAL, 1).coerceIn(1, 60)
        val intervalMs = intervalSec * 1000L
        try {
            locationManager?.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                intervalMs,
                0f,
                locationListener,
                Looper.getMainLooper()
            )
        } catch (e: SecurityException) {
            stopSelf()
        }
    }

    private fun stopTracking() {
        isRunning = false
        NotificationHelper.trackingText = null
        autoSaveTrack()  // Final save before stopping
        locationManager?.removeUpdates(locationListener)

        // If TraccarService is still running, just update notification; otherwise remove
        if (TraccarService.isRunning) {
            NotificationHelper.update(this)
            stopForeground(STOP_FOREGROUND_DETACH)
        } else {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
        stopSelf()
    }

    /** Save current track to temp file (survives app restart) */
    private fun autoSaveTrack() {
        if (trackPoints.isEmpty()) return
        try {
            val file = java.io.File(filesDir, MapFragment.TRACK_TMP_FILENAME)
            file.writeText(GpxParser.writeGpx(trackPoints, "Текущий трек"))
        } catch (_: Exception) {}
    }

    private fun updateNotification() {
        val km   = trackLengthM / 1000.0
        NotificationHelper.trackingText = "⏺ ${String.format("%.1f", km)} км • ${trackPoints.size} точек"
        NotificationHelper.update(this)
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
