package com.andreykoff.racenav

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.*
import android.os.*
import kotlinx.coroutines.*

/**
 * Standalone foreground service for sending GPS data to Traccar.
 * Completely independent from TrackingService (track recording).
 * Starts/stops via toggle in Settings or programmatically.
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
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null

    private var traccarDb: TraccarLocationDb? = null
    private var traccarSender: TraccarSender? = null

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            val batteryLevel = getBatteryLevel()
            traccarDb?.insertPoint(
                lat = loc.latitude,
                lon = loc.longitude,
                speed = loc.speed,
                bearing = loc.bearing,
                altitude = loc.altitude,
                timestamp = System.currentTimeMillis(),
                battery = batteryLevel
            )
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
                // Update shared notification with status
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

        // Start GPS listener — 1 second interval
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try {
            locationManager?.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                1000L,     // 1 second
                0f,        // 0 meters
                locationListener,
                Looper.getMainLooper()
            )
        } catch (e: SecurityException) {
            stopTraccar()
        }
    }

    private fun stopTraccar() {
        isRunning = false
        NotificationHelper.traccarText = null
        locationManager?.removeUpdates(locationListener)
        traccarSender?.stop()
        traccarSender = null
        traccarDb?.close()
        traccarDb = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null

        // If TrackingService is still running, just update notification; otherwise remove
        if (TrackingService.isRunning) {
            NotificationHelper.update(this)
            stopForeground(STOP_FOREGROUND_DETACH)
        } else {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
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
