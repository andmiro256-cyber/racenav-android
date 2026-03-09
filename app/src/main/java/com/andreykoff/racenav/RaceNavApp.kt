package com.andreykoff.racenav

import android.app.Application
import com.mapbox.mapboxsdk.Mapbox

class RaceNavApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // Install crash reporter BEFORE anything else
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val prefs = getSharedPreferences("crash_report", MODE_PRIVATE)
                val trace = throwable.stackTrace.take(8).joinToString("\n  ") { "${it.className}.${it.methodName}(${it.fileName}:${it.lineNumber})" }
                val msg = "${throwable.javaClass.name}: ${throwable.message}\n  $trace"
                prefs.edit().putString("last_crash", msg).apply()
            } catch (_: Exception) {}
            defaultHandler?.uncaughtException(thread, throwable)
        }

        Mapbox.getInstance(this)
    }
}
