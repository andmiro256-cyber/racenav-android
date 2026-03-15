package com.andreykoff.racenav

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object BackupManager {

    const val BACKUP_SERVER = "http://87.120.84.254:9222"
    private const val TAG = "BackupManager"

    // Keys excluded from backup (device-specific, should not be restored)
    private val EXCLUDE_KEYS = setOf(
        "traccar_device_id", "traccar_consent_given"
    )

    data class BackupResult(val ok: Boolean, val message: String)

    // ─── Create backup ────────────────────────────────────────────────────────

    suspend fun createBackup(context: Context, email: String?): BackupResult = withContext(Dispatchers.IO) {
        try {
            val prefs = context.getSharedPreferences(MapFragment.PREFS_NAME, Context.MODE_PRIVATE)
            val deviceId = prefs.getString(MapFragment.PREF_TRACCAR_DEVICE_ID, null)
                ?: android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID)
            val deviceName = prefs.getString(MapFragment.PREF_TRACCAR_DEVICE_NAME, "") ?: ""

            val payload = JSONObject().apply {
                put("deviceId", deviceId)
                put("email", email ?: "")
                put("deviceName", deviceName)
                put("version", context.packageManager.getPackageInfo(context.packageName, 0).versionName)
                put("settings", prefsToJson(prefs))
                put("routes", collectFiles(context, "routes"))
                put("tracks", collectFiles(context, "tracks"))
            }

            val response = postJson("$BACKUP_SERVER/api/backup", payload.toString())
            val json = JSONObject(response)
            if (json.optBoolean("ok")) {
                BackupResult(true, "Бэкап создан: ${json.optString("updatedAt").take(16).replace("T", " ")}")
            } else {
                BackupResult(false, "Ошибка: ${json.optString("error")}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "createBackup failed", e)
            BackupResult(false, "Ошибка: ${e.message}")
        }
    }

    // ─── Restore backup ───────────────────────────────────────────────────────

    suspend fun restoreBackup(context: Context, emailOrDeviceId: String): BackupResult = withContext(Dispatchers.IO) {
        try {
            val url = if (emailOrDeviceId.contains("@")) {
                "$BACKUP_SERVER/api/backup/by-email/${emailOrDeviceId.trim()}"
            } else {
                "$BACKUP_SERVER/api/backup/${emailOrDeviceId.trim()}"
            }

            val response = getJson(url)
            val json = JSONObject(response)

            if (!json.optBoolean("ok")) {
                val err = json.optString("error")
                return@withContext BackupResult(false, when (err) {
                    "email_not_found" -> "Email не найден"
                    "backup_not_found" -> "Бэкап не найден"
                    else -> "Ошибка: $err"
                })
            }

            // Restore settings
            val prefs = context.getSharedPreferences(MapFragment.PREFS_NAME, Context.MODE_PRIVATE)
            val settings = json.optJSONObject("settings")
            if (settings != null) applyPrefs(prefs, settings)

            // Restore files
            restoreFiles(context, "routes", json.optJSONArray("routes"))
            restoreFiles(context, "tracks", json.optJSONArray("tracks"))

            val meta = json.optJSONObject("meta")
            val date = meta?.optString("updatedAt")?.take(16)?.replace("T", " ") ?: "?"
            BackupResult(true, "Восстановлено (бэкап от $date)")
        } catch (e: Exception) {
            Log.e(TAG, "restoreBackup failed", e)
            BackupResult(false, "Ошибка: ${e.message}")
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun prefsToJson(prefs: SharedPreferences): JSONObject {
        val obj = JSONObject()
        prefs.all.forEach { (k, v) ->
            if (k !in EXCLUDE_KEYS) {
                when (v) {
                    is Boolean -> obj.put(k, v)
                    is Int -> obj.put(k, v)
                    is Long -> obj.put(k, v)
                    is Float -> obj.put(k, v.toDouble())
                    is String -> obj.put(k, v)
                    is Set<*> -> obj.put(k, JSONArray(v.toList()))
                }
            }
        }
        return obj
    }

    @Suppress("UNCHECKED_CAST")
    private fun applyPrefs(prefs: SharedPreferences, json: JSONObject) {
        val editor = prefs.edit()
        val existing = prefs.all
        json.keys().forEach { key ->
            if (key in EXCLUDE_KEYS) return@forEach
            val existingVal = existing[key]
            when {
                existingVal is Boolean || json.opt(key) is Boolean ->
                    editor.putBoolean(key, json.optBoolean(key))
                existingVal is Int ->
                    editor.putInt(key, json.optInt(key))
                existingVal is Long ->
                    editor.putLong(key, json.optLong(key))
                existingVal is Float ->
                    editor.putFloat(key, json.optDouble(key).toFloat())
                existingVal is Set<*> -> {
                    val arr = json.optJSONArray(key)
                    if (arr != null) {
                        val set = (0 until arr.length()).map { arr.getString(it) }.toSet()
                        editor.putStringSet(key, set)
                    }
                }
                else -> editor.putString(key, json.optString(key))
            }
        }
        editor.apply()
    }

    private fun collectFiles(context: Context, subdir: String): JSONArray {
        val arr = JSONArray()
        val dir = File(context.getExternalFilesDir(null), subdir)
        if (!dir.exists()) return arr
        dir.listFiles()?.forEach { file ->
            if (file.isFile && file.length() < 5 * 1024 * 1024) { // skip files > 5MB
                try {
                    val content = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
                    arr.put(JSONObject().apply {
                        put("name", file.name)
                        put("content", content)
                    })
                } catch (e: Exception) { Log.w(TAG, "skip file ${file.name}: ${e.message}") }
            }
        }
        return arr
    }

    private fun restoreFiles(context: Context, subdir: String, arr: JSONArray?) {
        if (arr == null || arr.length() == 0) return
        val dir = File(context.getExternalFilesDir(null), subdir)
        if (!dir.exists()) dir.mkdirs()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val name = obj.optString("name")
            val content = obj.optString("content")
            if (name.isNotEmpty() && content.isNotEmpty()) {
                try {
                    File(dir, name).writeBytes(Base64.decode(content, Base64.NO_WRAP))
                } catch (e: Exception) { Log.w(TAG, "restore file $name: ${e.message}") }
            }
        }
    }

    private fun postJson(url: String, body: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.outputStream.use { it.write(body.toByteArray()) }
        return conn.inputStream.bufferedReader().readText()
    }

    private fun getJson(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        return conn.inputStream.bufferedReader().readText()
    }
}
