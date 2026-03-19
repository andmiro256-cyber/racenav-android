package com.andreykoff.racenav

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.*
import android.os.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterNotNull

/**
 * Standalone foreground service for sending GPS data to Traccar.
 * When TrackingService is running — uses its location (same source as camera/cursor).
 * When TrackingService is NOT running — uses own GPS_PROVIDER listener.
 */
class TraccarService : Service() {

    companion object {
        const val ACTION_START = "TRACCAR_START"
        const val ACTION_STOP  = "TRACCAR_STOP"

        const val BROADCAST_TRACCAR_STATUS = "com.andreykoff.racenav.TRACCAR_STATUS"
        const val EXTRA_TRACCAR_STATUS = "traccar_status"

        @Volatile
        var isRunning = false
            private set
    }

    private var locationManager: LocationManager? = null
    private var fusedClient: com.google.android.gms.location.FusedLocationProviderClient? = null
    private var fusedCallback: com.google.android.gms.location.LocationCallback? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null

    private var traccarDb: TraccarLocationDb? = null
    private var traccarSender: TraccarSender? = null

    // Own GPS listener — fallback when TrackingService is NOT running and no FusedLocation
    private val locationListener = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            if (System.currentTimeMillis() - loc.time > 30_000) return
            if (TrackingService.isRunning) return
            savePoint(loc.latitude, loc.longitude, loc.speed, loc.bearing, loc.altitude)
        }
        @Deprecated("Deprecated in API 29")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    private fun isGooglePlayServicesAvailable(): Boolean {
        return try {
            com.google.android.gms.common.GoogleApiAvailability.getInstance()
                .isGooglePlayServicesAvailable(this) == com.google.android.gms.common.ConnectionResult.SUCCESS
        } catch (_: Exception) { false }
    }

    private fun savePoint(lat: Double, lon: Double, speed: Float, bearing: Float, altitude: Double) {
        val batteryLevel = getBatteryLevel()
        traccarDb?.insertPoint(
            lat = lat,
            lon = lon,
            speed = speed,
            bearing = bearing,
            altitude = altitude,
            timestamp = System.currentTimeMillis(),
            battery = batteryLevel
        )
    }

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startTraccar()
            ACTION_STOP  -> stopTraccar()
        }
        return START_NOT_STICKY
    }

    private fun startTraccar() {
        if (isRunning) return
        isRunning = true

        val prefs = getSharedPreferences(MapFragment.PREFS_NAME, Context.MODE_PRIVATE)

        // Acquire WakeLock to keep CPU running in background
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RaceNav::TraccarLive")
            .apply { acquire() }

        NotificationHelper.traccarText = "📡 Ожидание GPS"
        startForeground(NotificationHelper.NOTIF_ID, NotificationHelper.buildNotification(this))

        // Init DB and sender
        traccarDb = TraccarLocationDb(this)
        traccarSender = TraccarSender(this, traccarDb!!, prefs).apply {
            onStatusChanged = { status ->
                sendBroadcast(Intent(BROADCAST_TRACCAR_STATUS).apply {
                    setPackage(packageName)
                    putExtra(EXTRA_TRACCAR_STATUS, status.name)
                })
                val statusText = when (status) {
                    TraccarSender.SyncStatus.OK -> "📡 ✓ Онлайн"
                    TraccarSender.SyncStatus.SYNCING -> "📡 ↑ Отправка..."
                    TraccarSender.SyncStatus.ERROR -> "📡 ✗ Ошибка связи"
                    TraccarSender.SyncStatus.IDLE -> "📡 Ожидание GPS"
                }
                val unsent = traccarDb?.unsentCount() ?: 0
                NotificationHelper.traccarText = statusText + if (unsent > 0) " ($unsent)" else ""
                NotificationHelper.update(this@TraccarService)
            }
            start(serviceScope)
        }

        // Always subscribe to TrackingService flow — syncs position with camera when recording
        serviceScope.launch {
            TrackingService.locationFlow.filterNotNull().collect { loc ->
                if (!isRunning) return@collect
                savePoint(loc.lat, loc.lon, loc.speed, loc.bearing, loc.altitude)
            }
        }

        // Own GPS source as fallback (used when TrackingService is NOT running)
        if (isGooglePlayServicesAvailable()) {
            fusedClient = com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(this)
            val request = com.google.android.gms.location.LocationRequest.Builder(
                com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY, 1000L
            ).setMinUpdateIntervalMillis(500L).build()
            fusedCallback = object : com.google.android.gms.location.LocationCallback() {
                override fun onLocationResult(result: com.google.android.gms.location.LocationResult) {
                    if (TrackingService.isRunning) return
                    result.lastLocation?.let { savePoint(it.latitude, it.longitude, it.speed, it.bearing, it.altitude) }
                }
            }
            try {
                fusedClient?.requestLocationUpdates(request, fusedCallback!!, Looper.getMainLooper())
            } catch (_: SecurityException) { stopTraccar() }
        } else {
            locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            try {
                locationManager?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locationListener, Looper.getMainLooper())
            } catch (_: SecurityException) { stopTraccar() }
        }
    }

    private fun stopTraccar() {
        isRunning = false
        NotificationHelper.traccarText = null
        fusedCallback?.let { fusedClient?.removeLocationUpdates(it) }
        fusedClient = null
        fusedCallback = null
        locationManager?.removeUpdates(locationListener)
        traccarSender?.stop()
        traccarSender = null
        traccarDb?.close()
        traccarDb = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null

        stopForeground(STOP_FOREGROUND_REMOVE)
        if (TrackingService.isRunning) NotificationHelper.update(this)
        stopSelf()

        sendBroadcast(Intent(BROADCAST_TRACCAR_STATUS).apply {
            setPackage(packageName)
            putExtra(EXTRA_TRACCAR_STATUS, "IDLE")
        })
    }

    // onTaskRemoved NOT overridden — Samsung/Vivo call it aggressively
    // even when switching apps. Service stops via ACTION_STOP or onDestroy.

    override fun onDestroy() {
        if (isRunning) stopTraccar()
        serviceScope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    private fun getBatteryLevel(): Int {
        val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (batteryIntent != null) {
            val level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            return if (scale > 0) (level * 100 / scale) else -1
        }
        return -1
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
