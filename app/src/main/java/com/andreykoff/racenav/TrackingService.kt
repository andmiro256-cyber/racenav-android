package com.andreykoff.racenav

import android.app.*
import android.content.Context
import android.content.Intent
import android.location.*
import android.os.*
import android.util.Log
import kotlin.math.*
import kotlinx.coroutines.*

data class LocationUpdate(
    val lat: Double, val lon: Double,
    val speed: Float, val bearing: Float, val altitude: Double,
    val hasSpeed: Boolean, val hasAltitude: Boolean,
    val accuracy: Float = 0f
)

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

        val locationFlow = kotlinx.coroutines.flow.MutableStateFlow<LocationUpdate?>(null)

        // Данные трека — читаются из MapFragment
        val trackPoints   = mutableListOf<Pair<Double, Double>>()
        var trackLengthM  = 0.0
        var startTimeMs   = 0L
        var isRunning     = false
    }

    private var locationManager: LocationManager? = null
    private var fusedClient: com.google.android.gms.location.FusedLocationProviderClient? = null
    private var fusedCallback: com.google.android.gms.location.LocationCallback? = null
    private var wakeLock: android.os.PowerManager.WakeLock? = null

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            handleLocation(loc)
        }
        @Deprecated("Deprecated in API 29")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    private fun handleLocation(loc: Location) {
        // Re-acquire WakeLock on each location update (Vivo/Samsung kill GPS after timeout)
        // acquire() with timeout is legal to call repeatedly — just resets the timeout
        wakeLock?.acquire(30 * 60 * 1000L)

        // Read filter settings
        val prefs = getSharedPreferences(MapFragment.PREFS_NAME, android.content.Context.MODE_PRIVATE)
        val minAccuracy = prefs.getInt(MapFragment.PREF_TRACK_MIN_ACCURACY, 50).toFloat()
        val minDistance = prefs.getInt(MapFragment.PREF_TRACK_MIN_DISTANCE, 2).toDouble()
        val onlyMoving = prefs.getBoolean(MapFragment.PREF_TRACK_ONLY_MOVING, false)

        // Skip points with poor accuracy
        if (loc.hasAccuracy() && loc.accuracy > minAccuracy) return

        // Skip stationary points if "only moving" enabled (< 1 km/h)
        if (onlyMoving && loc.hasSpeed() && loc.speed < 0.3f) return

        val newPoint = Pair(loc.latitude, loc.longitude)

        // Distance filter
        if (trackPoints.isEmpty() || distanceM(trackPoints.last(), newPoint) > minDistance) {
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

        // StateFlow update (works even when broadcast is blocked, e.g. Vivo/Android 16)
        locationFlow.value = LocationUpdate(
            loc.latitude, loc.longitude, loc.speed, loc.bearing, loc.altitude,
            loc.hasSpeed(), loc.hasAltitude(), loc.accuracy
        )
    }

    private fun isGooglePlayServicesAvailable(): Boolean {
        return try {
            val status = com.google.android.gms.common.GoogleApiAvailability.getInstance()
                .isGooglePlayServicesAvailable(this)
            status == com.google.android.gms.common.ConnectionResult.SUCCESS
        } catch (_: Exception) { false }
    }

    private fun startFusedTracking(intervalMs: Long) {
        fusedClient = com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(this)
        val request = com.google.android.gms.location.LocationRequest.Builder(
            com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY, intervalMs
        ).setMinUpdateIntervalMillis(intervalMs / 2).build()

        fusedCallback = object : com.google.android.gms.location.LocationCallback() {
            override fun onLocationResult(result: com.google.android.gms.location.LocationResult) {
                result.lastLocation?.let { handleLocation(it) }
            }
        }
        try {
            fusedClient?.requestLocationUpdates(request, fusedCallback!!, Looper.getMainLooper())
        } catch (e: SecurityException) {
            Log.w("TrackingService", "FusedLocation SecurityException, falling back to GPS_PROVIDER", e)
            startGpsTracking(intervalMs)
        }
    }

    private fun startGpsTracking(intervalMs: Long) {
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
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
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (isRunning) stopTracking()
        super.onDestroy()
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

        // Acquire WakeLock to prevent GPS stopping in deep sleep
        val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "RaceNav::GPS")
        wakeLock?.acquire(30 * 60 * 1000L) // 30 min timeout, re-acquired on next location update

        val intervalSec = getSharedPreferences(MapFragment.PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(MapFragment.PREF_TRACK_INTERVAL, 1).coerceIn(1, 60)
        val intervalMs = intervalSec * 1000L

        if (isGooglePlayServicesAvailable()) {
            startFusedTracking(intervalMs)
            Log.d("TrackingService", "Using FusedLocation")
        } else {
            startGpsTracking(intervalMs)
            Log.d("TrackingService", "Using GPS_PROVIDER (no Play Services)")
        }
    }

    private fun stopTracking() {
        isRunning = false
        NotificationHelper.trackingText = null
        autoSaveTrack()  // Final save before stopping

        // Release WakeLock
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null

        // Remove location listeners
        fusedCallback?.let { fusedClient?.removeLocationUpdates(it) }
        locationManager?.removeUpdates(locationListener)

        stopForeground(STOP_FOREGROUND_REMOVE)
        if (TraccarService.isRunning) NotificationHelper.update(this)
        stopSelf()
    }

    /** Save current track to temp file (survives app restart). Synchronous — must complete before stopSelf. */
    private fun autoSaveTrack() {
        if (trackPoints.isEmpty()) return
        try {
            val file = java.io.File(filesDir, MapFragment.TRACK_TMP_FILENAME)
            file.writeText(GpxParser.writeGpx(trackPoints.toList(), "Текущий трек"))
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

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (isRunning) stopTracking()
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
