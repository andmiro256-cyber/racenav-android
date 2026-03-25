package com.andreykoff.racenav

import android.annotation.SuppressLint
import android.content.Context
import android.location.GnssStatus
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Monitors GNSS (GPS) satellite status via GnssStatus.Callback.
 * Provides real-time quality assessment including jamming detection.
 *
 * API 24+ required (project minSdk = 24).
 */
object GnssStatusMonitor {

    private const val TAG = "GnssStatusMonitor"

    enum class GpsQuality { GOOD, WEAK, JAMMED, NO_DATA }

    data class GnssInfo(
        val totalSatellites: Int = 0,
        val usedSatellites: Int = 0,
        val avgCn0: Float = 0f,
        val maxCn0: Float = 0f,
        val quality: GpsQuality = GpsQuality.NO_DATA
    )

    private val _gnssInfo = MutableStateFlow(GnssInfo())
    val gnssInfo: StateFlow<GnssInfo> = _gnssInfo

    private var registered = false
    private var locationManager: LocationManager? = null

    private val gnssCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            val total = status.satelliteCount
            var used = 0
            var sumCn0 = 0f
            var maxCn0 = 0f

            for (i in 0 until total) {
                val cn0 = status.getCn0DbHz(i)
                if (status.usedInFix(i)) {
                    used++
                    sumCn0 += cn0
                }
                if (cn0 > maxCn0) maxCn0 = cn0
            }

            val avgCn0 = if (used > 0) sumCn0 / used else 0f

            val quality = when {
                used < 3 || avgCn0 < 12f -> GpsQuality.JAMMED
                used < 4 || avgCn0 < 18f -> GpsQuality.WEAK
                avgCn0 < 22f -> GpsQuality.WEAK
                else -> GpsQuality.GOOD
            }

            _gnssInfo.value = GnssInfo(
                totalSatellites = total,
                usedSatellites = used,
                avgCn0 = avgCn0,
                maxCn0 = maxCn0,
                quality = quality
            )
        }

        override fun onStarted() {
            Log.d(TAG, "GNSS status monitoring started")
        }

        override fun onStopped() {
            Log.d(TAG, "GNSS status monitoring stopped")
            _gnssInfo.value = GnssInfo()
        }
    }

    @SuppressLint("MissingPermission")
    fun start(context: Context) {
        if (registered) return
        try {
            locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            locationManager?.registerGnssStatusCallback(gnssCallback, Handler(Looper.getMainLooper()))
            registered = true
            Log.d(TAG, "Registered GnssStatus callback")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register GnssStatus: ${e.message}")
        }
    }

    fun stop() {
        if (!registered) return
        try {
            locationManager?.unregisterGnssStatusCallback(gnssCallback)
            registered = false
            _gnssInfo.value = GnssInfo()
            Log.d(TAG, "Unregistered GnssStatus callback")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unregister GnssStatus: ${e.message}")
        }
        locationManager = null
    }

    /**
     * Extended jamming detection combining GNSS C/N0 with location accuracy.
     * Call with current accuracy from Location updates.
     */
    fun isLikelyJammed(accuracyMeters: Float): Boolean {
        val info = _gnssInfo.value
        if (info.quality == GpsQuality.NO_DATA) return false
        // Multiple indicators of jamming
        val lowCn0 = info.avgCn0 < 15f
        val fewSats = info.usedSatellites < 3
        val poorAccuracy = accuracyMeters > 100f
        val cn0AccuracyRatio = if (info.avgCn0 > 0) accuracyMeters / info.avgCn0 else 0f
        val badRatio = cn0AccuracyRatio > 5f  // high accuracy error per C/N0 unit
        // Jammed if 2+ indicators
        val score = (if (lowCn0) 1 else 0) + (if (fewSats) 1 else 0) +
                    (if (poorAccuracy) 1 else 0) + (if (badRatio) 1 else 0)
        return score >= 2
    }
}
