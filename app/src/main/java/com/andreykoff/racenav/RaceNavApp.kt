package com.andreykoff.racenav

import android.app.Application
import com.mapbox.mapboxsdk.Mapbox
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

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
                val msg = "❌ RaceNav CRASH\n${throwable.javaClass.name}: ${throwable.message}\n$trace"
                prefs.edit().putString("last_crash", msg).apply()
            } catch (_: Exception) {}
            defaultHandler?.uncaughtException(thread, throwable)
        }

        // If previous crash exists — send to Telegram before continuing
        val lastCrash = prefs.getString("last_crash", null)
        if (lastCrash != null) {
            prefs.edit().remove("last_crash").apply()
            try {
                sendToTelegram(lastCrash)
            } catch (_: Exception) {}
        }

        Mapbox.getInstance(this)
    }

    private fun sendToTelegram(text: String) {
        val token = "8404905635:AAHn7QLG2WjCHkD4r6FoEOshO8MEmwdh_R0"
        val chatId = "90113494"
        val url = URL("https://api.telegram.org/bot$token/sendMessage")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        val body = "chat_id=$chatId&text=${URLEncoder.encode(text, "UTF-8")}"
        OutputStreamWriter(conn.outputStream).use { it.write(body) }
        conn.responseCode // send
        conn.disconnect()
    }
}
