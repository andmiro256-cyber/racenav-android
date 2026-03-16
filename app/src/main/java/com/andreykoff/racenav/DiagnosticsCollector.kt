package com.andreykoff.racenav

import android.content.Context
import android.os.Build
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

object DiagnosticsCollector {
    
    private const val TAG = "Diagnostics"
    private const val LOG_FILE = "racenav_diag.log"
    private const val MAX_LOG_LINES = 200
    
    /** Collect device info as JSON */
    fun collectDeviceInfo(context: Context): JSONObject {
        val dm = context.resources.displayMetrics
        val prefs = context.getSharedPreferences(MapFragment.PREFS_NAME, Context.MODE_PRIVATE)
        
        // Get navigation bar height
        val navBarHeight = try {
            val resourceId = context.resources.getIdentifier("navigation_bar_height", "dimen", "android")
            if (resourceId > 0) context.resources.getDimensionPixelSize(resourceId) else 0
        } catch (_: Exception) { 0 }
        
        // Check Google Play Services
        val hasGms = try {
            com.google.android.gms.common.GoogleApiAvailability.getInstance()
                .isGooglePlayServicesAvailable(context) == com.google.android.gms.common.ConnectionResult.SUCCESS
        } catch (_: Exception) { false }
        
        return JSONObject().apply {
            put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
            put("android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            put("screenWidth", dm.widthPixels)
            put("screenHeight", dm.heightPixels)
            put("density", dm.density)
            put("densityDpi", dm.densityDpi)
            put("navBarHeight", navBarHeight)
            put("scaledDensity", dm.scaledDensity)
            put("appVersion", try { context.packageManager.getPackageInfo(context.packageName, 0).versionName } catch (_: Exception) { "?" })
            put("versionCode", try { context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode } catch (_: Exception) { 0 })
            put("hasGooglePlayServices", hasGms)
            put("gpsProvider", if (hasGms) "FusedLocation" else "GPS_PROVIDER")
            put("deviceId", LicenseManager.getDeviceIdForUser(context))
            put("traccarId", prefs.getString(MapFragment.PREF_TRACCAR_DEVICE_ID, "") ?: "")
            put("locale", Locale.getDefault().toString())
            put("timezone", TimeZone.getDefault().id)
            put("timestamp", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
            // Install time for trial calculation on server
            val licPrefs = context.getSharedPreferences("racenav_license", Context.MODE_PRIVATE)
            val backupPrefs = context.getSharedPreferences("rnav_sys", Context.MODE_PRIVATE)
            var instTime = licPrefs.getLong("install_time", 0L)
            if (instTime == 0L) instTime = backupPrefs.getLong("bi", 0L)
            if (instTime > 0L) {
                put("installTime", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(instTime)))
            }
        }
    }
    
    /** Log an event to the diagnostics log file */
    fun logEvent(context: Context, event: String) {
        try {
            val dir = File(context.getExternalFilesDir(null), "logs")
            dir.mkdirs()
            val file = File(dir, LOG_FILE)
            val timestamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            file.appendText("$timestamp $event\n")
            
            // Trim if too long
            if (file.length() > 50_000) {
                val lines = file.readLines()
                file.writeText(lines.takeLast(MAX_LOG_LINES).joinToString("\n") + "\n")
            }
        } catch (e: Exception) {
            Log.w(TAG, "logEvent failed: ${e.message}")
        }
    }
    
    /** Read recent log entries */
    fun getRecentLogs(context: Context, maxLines: Int = 50): String {
        return try {
            val file = File(File(context.getExternalFilesDir(null), "logs"), LOG_FILE)
            if (!file.exists()) return ""
            file.readLines().takeLast(maxLines).joinToString("\n")
        } catch (_: Exception) { "" }
    }
    
    /** Clear old logs (called on app start) */
    fun rotateLog(context: Context) {
        try {
            val file = File(File(context.getExternalFilesDir(null), "logs"), LOG_FILE)
            if (!file.exists()) return
            val lines = file.readLines().takeLast(MAX_LOG_LINES)
            file.writeText(lines.joinToString("\n") + "\n")
        } catch (_: Exception) {}
    }
    
    private const val PENDING_FILE = "diagnostics_pending.json"

    /** Send diagnostics to server. If no network — saves locally, sends next time. */
    fun sendToServer(context: Context) {
        Thread {
            try {
                val deviceInfo = collectDeviceInfo(context)
                val logs = getRecentLogs(context)
                val payload = JSONObject().apply {
                    put("type", "diagnostics")
                    put("device", deviceInfo)
                    put("logs", logs)
                }
                // Always save locally
                val dir = File(context.getExternalFilesDir(null), "logs")
                dir.mkdirs()
                File(dir, PENDING_FILE).writeText(payload.toString())
                // Try to send
                if (!isNetworkAvailable(context)) {
                    Log.d(TAG, "No network, diagnostics saved locally")
                    return@Thread
                }
                if (trySend(payload)) {
                    File(dir, PENDING_FILE).delete()
                    Log.d(TAG, "Diagnostics sent")
                }
            } catch (e: Exception) {
                Log.w(TAG, "sendToServer: ${e.message}")
            }
        }.start()
    }

    /** Send pending diagnostics from previous offline session */
    fun sendPendingIfNeeded(context: Context) {
        Thread {
            try {
                if (!isNetworkAvailable(context)) return@Thread
                val file = File(File(context.getExternalFilesDir(null), "logs"), PENDING_FILE)
                if (!file.exists()) return@Thread
                val payload = JSONObject(file.readText())
                payload.put("logs", getRecentLogs(context))
                if (trySend(payload)) {
                    file.delete()
                    Log.d(TAG, "Pending diagnostics sent")
                }
            } catch (_: Exception) {}
        }.start()
    }

    private fun trySend(payload: JSONObject): Boolean {
        return try {
            val url = java.net.URL("${BackupManager.BACKUP_SERVER}/api/diagnostics")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.outputStream.use { it.write(payload.toString().toByteArray()) }
            val code = conn.responseCode
            conn.disconnect()
            code in 200..299
        } catch (_: Exception) { false }
    }

    private fun isNetworkAvailable(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
        return cm?.activeNetwork != null
    }
}
