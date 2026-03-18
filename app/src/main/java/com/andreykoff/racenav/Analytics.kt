package com.andreykoff.racenav

import android.content.Context
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Lightweight anonymous analytics — sends device info on app launch.
 * No personal data collected. Device ID is the same UUID from LicenseManager.
 */
object Analytics {

    private const val ENDPOINT = "http://87.120.84.254:8090/ping"

    fun sendEvent(context: Context, action: String = "launch", extra: JSONObject? = null) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val deviceId = LicenseManager.getRawDeviceId(context)
                val version = try {
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
                } catch (_: Exception) { "?" }

                val json = JSONObject().apply {
                    put("device_id", deviceId)
                    put("version", version)
                    put("action", action)
                    put("os_version", "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
                    put("device_model", "${Build.MANUFACTURER} ${Build.MODEL}")
                    put("locale", Locale.getDefault().language)
                    extra?.let { e ->
                        val skip = setOf("device_id","version","action","os_version","device_model","locale")
                        val keys = e.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            if (k !in skip) put(k, e.get(k))
                        }
                    }
                }

                val conn = URL(ENDPOINT).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.doOutput = true
                conn.outputStream.use { it.write(json.toString().toByteArray()) }
                conn.responseCode // trigger the request
                conn.disconnect()
            } catch (_: Exception) {
                // Silent fail — analytics should never block the app
            }
        }
    }
}
