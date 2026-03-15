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
    }

    enum class SyncStatus { IDLE, SYNCING, OK, ERROR }

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build()

    private var sendJob: Job? = null
    private var sendCount = 0

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
        val serverUrl = prefs.getString(MapFragment.PREF_TRACCAR_URL, "") ?: ""
        val deviceId = prefs.getString(MapFragment.PREF_TRACCAR_DEVICE_ID, "") ?: ""
        val enabled = prefs.getBoolean(MapFragment.PREF_TRACCAR_ENABLED, false)

        if (!enabled || serverUrl.isBlank() || deviceId.isBlank()) {
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
            if (sendPoint(serverUrl, deviceId, point)) {
                sentIds.add(point.id)
            } else {
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
            Log.w(TAG, "HTTP error: ${e.message}")
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
