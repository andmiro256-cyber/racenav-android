package com.andreykoff.racenav

import android.app.Application
import com.mapbox.mapboxsdk.Mapbox
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class RaceNavApp : Application() {

    override fun onCreate() {
        super.onCreate()

        val prefs = getSharedPreferences("crash_report", MODE_PRIVATE)

        // Install crash handler FIRST
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val trace = throwable.stackTrace.take(10)
                    .joinToString("\n") { "  ${it.className}.${it.methodName}(${it.fileName}:${it.lineNumber})" }
                val msg = "RaceNav CRASH\n${throwable.javaClass.name}: ${throwable.message}\n$trace"
                prefs.edit().putString("last_crash", msg).apply()
            } catch (_: Exception) {}
            defaultHandler?.uncaughtException(thread, throwable)
        }

        // If previous crash exists — send to server via diagnostics endpoint
        val lastCrash = prefs.getString("last_crash", null)
        if (lastCrash != null) {
            prefs.edit().remove("last_crash").apply()
            Thread { try { sendCrashToServer(lastCrash) } catch (_: Exception) {} }.start()
        }

        Mapbox.getInstance(this)

        // Firebase Crashlytics — set device ID and email for crash reports
        try {
            val crashlytics = com.google.firebase.crashlytics.FirebaseCrashlytics.getInstance()
            val deviceId = LicenseManager.getShortDeviceId(this)
            val email = getSharedPreferences("racenav_prefs", MODE_PRIVATE).getString("sync_email", "") ?: ""
            crashlytics.setCustomKey("deviceId", deviceId)
            crashlytics.setUserId(deviceId)
            if (email.isNotEmpty()) crashlytics.setCustomKey("email", email)
        } catch (_: Exception) {}
    }

    private fun sendCrashToServer(text: String) {
        val url = URL("${BackupManager.BACKUP_SERVER}/api/crash-report")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        conn.setRequestProperty("Content-Type", "application/json")
        val deviceId = getSharedPreferences("racenav_prefs", MODE_PRIVATE)
            .getString("device_id", "unknown") ?: "unknown"
        val json = org.json.JSONObject().apply {
            put("deviceId", deviceId)
            put("crash", text)
            put("timestamp", System.currentTimeMillis())
        }
        OutputStreamWriter(conn.outputStream).use { it.write(json.toString()) }
        conn.responseCode
        conn.disconnect()
    }
}
