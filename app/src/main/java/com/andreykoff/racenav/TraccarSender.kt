package com.andreykoff.racenav

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Sends buffered GPS points to Traccar server using OsmAnd protocol.
 * Runs a coroutine loop every [SEND_INTERVAL_MS] ms, picks unsent points from SQLite, sends them.
 *
 * Endpoint resolution: tries DIRECT (Latvia) first, falls back to RELAY (YC, whitelist-friendly)
 * after [MapFragment.FAILURES_BEFORE_SWITCH] consecutive failures. Last successful endpoint
 * is cached in prefs so subsequent runs skip discovery.
 */
class TraccarSender(
    private val context: Context,
    private val db: TraccarLocationDb,
    private val prefs: SharedPreferences
) {
    companion object {
        private const val TAG = "TraccarSender"
        private const val SEND_INTERVAL_MS = 2000L
        private const val BATCH_SIZE = 20
        private const val PURGE_INTERVAL = 100  // purge every N successful sends

        /** Returns endpoint base URL to use for the next request. */
        fun currentBaseUrl(prefs: SharedPreferences): String {
            val override = prefs.getString(MapFragment.PREF_TRACCAR_URL, "") ?: ""
            if (override.isNotBlank() &&
                override != MapFragment.ENDPOINT_DIRECT &&
                override != MapFragment.ENDPOINT_RELAY
            ) {
                return override
            }
            if (prefs.getBoolean(MapFragment.PREF_RESTRICTED_ZONE_MODE, false)) {
                return MapFragment.ENDPOINT_RELAY
            }
            val cached = prefs.getString(MapFragment.PREF_LAST_TRACCAR_ENDPOINT, null)
            return when (cached) {
                MapFragment.ENDPOINT_RELAY -> MapFragment.ENDPOINT_RELAY
                MapFragment.ENDPOINT_DIRECT -> MapFragment.ENDPOINT_DIRECT
                else -> MapFragment.ENDPOINT_DIRECT
            }
        }

        /** Returns the alternative endpoint to [current] (for switching). */
        fun otherEndpoint(current: String): String =
            if (current == MapFragment.ENDPOINT_DIRECT) MapFragment.ENDPOINT_RELAY
            else MapFragment.ENDPOINT_DIRECT
    }

    enum class SyncStatus { IDLE, SYNCING, OK, ERROR }

    private val client = OkHttpClient.Builder()
        .connectTimeout(MapFragment.ENDPOINT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(MapFragment.ENDPOINT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .writeTimeout(MapFragment.ENDPOINT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    private var sendJob: Job? = null
    private var sendCount = 0

    @Volatile
    private var consecutiveFailures = 0

    @Volatile
    var syncStatus: SyncStatus = SyncStatus.IDLE
        private set

    var onStatusChanged: ((SyncStatus) -> Unit)? = null

    fun start(scope: CoroutineScope) {
        if (sendJob?.isActive == true) return
        sendJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    trySendBatch()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Send error: ${e.message}")
                    updateStatus(SyncStatus.ERROR)
                }
                delay(SEND_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        sendJob?.cancel()
        sendJob = null
        updateStatus(SyncStatus.IDLE)
    }

    private fun trySendBatch() {
        val baseUrl = currentBaseUrl(prefs)
        val deviceId = prefs.getString(MapFragment.PREF_TRACCAR_DEVICE_ID, "") ?: ""
        val enabled = prefs.getBoolean(MapFragment.PREF_TRACCAR_ENABLED, false)

        if (!enabled || baseUrl.isBlank() || deviceId.isBlank()) {
            updateStatus(SyncStatus.IDLE)
            return
        }

        val points = db.getUnsent(BATCH_SIZE)
        if (points.isEmpty()) {
            updateStatus(SyncStatus.OK)
            return
        }

        updateStatus(SyncStatus.SYNCING)

        val sentIds = mutableListOf<Long>()
        for (point in points) {
            if (sendPoint(baseUrl, deviceId, point)) {
                sentIds.add(point.id)
                onSendSuccess(baseUrl)
            } else {
                onSendFailure(baseUrl)
                updateStatus(SyncStatus.ERROR)
                break
            }
        }

        if (sentIds.isNotEmpty()) {
            db.markSent(sentIds)
            sendCount += sentIds.size

            // Periodic purge of old sent data
            if (sendCount >= PURGE_INTERVAL) {
                sendCount = 0
                db.purgeOldSent()
            }
        }

        if (sentIds.size == points.size) {
            updateStatus(SyncStatus.OK)
        }
    }

    private fun onSendSuccess(usedUrl: String) {
        consecutiveFailures = 0
        val cached = prefs.getString(MapFragment.PREF_LAST_TRACCAR_ENDPOINT, null)
        if (cached != usedUrl &&
            (usedUrl == MapFragment.ENDPOINT_DIRECT || usedUrl == MapFragment.ENDPOINT_RELAY)
        ) {
            prefs.edit().putString(MapFragment.PREF_LAST_TRACCAR_ENDPOINT, usedUrl).apply()
        }
    }

    private fun onSendFailure(usedUrl: String) {
        // Only fall back between the two managed endpoints; manual overrides are user-controlled.
        if (usedUrl != MapFragment.ENDPOINT_DIRECT && usedUrl != MapFragment.ENDPOINT_RELAY) return
        // Restricted-zone mode pins RELAY — don't auto-switch back to DIRECT.
        if (prefs.getBoolean(MapFragment.PREF_RESTRICTED_ZONE_MODE, false)) return

        consecutiveFailures++
        if (consecutiveFailures >= MapFragment.FAILURES_BEFORE_SWITCH) {
            val next = otherEndpoint(usedUrl)
            consecutiveFailures = 0
            prefs.edit().putString(MapFragment.PREF_LAST_TRACCAR_ENDPOINT, next).apply()
            Log.i(TAG, "endpoint switched: $usedUrl -> $next, reason: ${MapFragment.FAILURES_BEFORE_SWITCH} consecutive failures")
        }
    }

    /**
     * Send single point using OsmAnd protocol:
     * GET /?id=DEVICE_ID&lat=X&lon=Y&timestamp=EPOCH_SEC&speed=KMH&bearing=DEG&altitude=M
     */
    private fun sendPoint(serverUrl: String, deviceId: String, point: TraccarPoint): Boolean {
        val baseUrl = serverUrl.trimEnd('/')
        val speedKmh = point.speed * 3.6  // m/s → km/h
        val timestampSec = point.timestamp / 1000

        var url = "$baseUrl/?id=$deviceId" +
                "&lat=${point.lat}" +
                "&lon=${point.lon}" +
                "&timestamp=$timestampSec" +
                "&speed=${String.format(java.util.Locale.US, "%.1f", speedKmh)}" +
                "&bearing=${String.format(java.util.Locale.US, "%.0f", point.bearing)}" +
                "&altitude=${String.format(java.util.Locale.US, "%.0f", point.altitude)}"
        if (point.battery in 0..100) {
            url += "&batt=${point.battery}"
        }

        val request = Request.Builder().url(url).get().build()
        return try {
            val response = client.newCall(request).execute()
            response.use { it.isSuccessful }
        } catch (e: Exception) {
            Log.w(TAG, "HTTP error via $baseUrl: ${e.message}")
            false
        }
    }

    private fun updateStatus(status: SyncStatus) {
        if (syncStatus != status) {
            syncStatus = status
            onStatusChanged?.invoke(status)
        }
    }
}
