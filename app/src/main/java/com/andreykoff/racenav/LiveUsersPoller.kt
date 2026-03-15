package com.andreykoff.racenav

import android.os.Handler
import android.os.Looper
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * Polls /api/live2/devices every N seconds and provides GeoJSON
 * FeatureCollection with user markers for MapLibre.
 */
class LiveUsersPoller(
    private val baseUrl: String,
    private val myDeviceId: String,  // exclude self from markers
    private val onError: ((String) -> Unit)? = null,
    private val onUpdate: (geoJson: String, devices: List<LiveDevice>) -> Unit
) {
    data class LiveDevice(
        val deviceId: Int,
        val name: String,
        val lat: Double,
        val lon: Double,
        val speed: Double,
        val course: Double,
        val battery: Int?,
        val status: String,
        val lastUpdate: String?
    )

    companion object {
        const val POLL_INTERVAL_MS = 4000L
    }

    private val client = OkHttpClient()
    private val handler = Handler(Looper.getMainLooper())
    private var running = false

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            fetchDevices()
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    fun start() {
        if (running) return
        running = true
        handler.post(pollRunnable)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(pollRunnable)
    }

    private fun fetchDevices() {
        val normalizedBaseUrl = baseUrl.trimEnd('/')
        val request = Request.Builder()
            .url("$normalizedBaseUrl/api/live2/devices")
            .get()
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                handler.post { onError?.invoke("Network: ${e.message}") }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    if (!resp.isSuccessful) {
                        handler.post { onError?.invoke("HTTP ${resp.code}") }
                        return
                    }
                    val body = resp.body?.string() ?: return
                    try {
                        val json = JSONObject(body)
                        if (!json.optBoolean("ok", false)) return
                        val devicesArr = json.optJSONArray("devices") ?: return

                        val devices = mutableListOf<LiveDevice>()
                        val features = JSONArray()

                        for (i in 0 until devicesArr.length()) {
                            val d = devicesArr.getJSONObject(i)
                            val deviceId = d.optInt("deviceId", 0)
                            val uniqueId = d.optString("uniqueId", "")
                            // Skip self
                            if (uniqueId == myDeviceId || deviceId.toString() == myDeviceId) continue

                            val lat = d.optDouble("lat", Double.NaN)
                            val lon = d.optDouble("lon", Double.NaN)
                            if (lat.isNaN() || lon.isNaN()) continue

                            val name = d.optString("name", "?")
                            val speed = d.optDouble("speed", 0.0)
                            val course = d.optDouble("course", 0.0)
                            val battery = if (d.isNull("battery")) null else d.optInt("battery")
                            val status = d.optString("status", "unknown")
                            val lastUpdate = if (d.isNull("lastUpdate")) null else d.optString("lastUpdate")

                            devices.add(LiveDevice(deviceId, name, lat, lon, speed, course, battery, status, lastUpdate))

                            val feature = JSONObject()
                                .put("type", "Feature")
                                .put("geometry", JSONObject()
                                    .put("type", "Point")
                                    .put("coordinates", JSONArray().put(lon).put(lat)))
                                .put("properties", JSONObject()
                                    .put("name", name)
                                    .put("course", course)
                                    .put("speed", speed)
                                    .put("status", status)
                                    .put("deviceId", deviceId))
                            features.put(feature)
                        }

                        val geoJson = JSONObject()
                            .put("type", "FeatureCollection")
                            .put("features", features)
                            .toString()

                        handler.post { onUpdate(geoJson, devices) }
                    } catch (e: Exception) {
                        handler.post { onError?.invoke("Parse: ${e.message}") }
                    }
                }
            }
        })
    }
}
