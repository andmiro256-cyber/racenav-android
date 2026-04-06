package com.andreykoff.racenav

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * HTTP client for group-share endpoints.
 * POST /api/live2/group-share — send GPX to group
 * GET /api/live2/group-share/inbox — get pending shares
 * GET /api/live2/group-share/:id/data — download GPX blob
 * DELETE /api/live2/group-share/:id/ack — dismiss share
 */
object GroupShareApi {
    private const val TAG = "GroupShareApi"
    private const val BASE = "/api/live2/group-share"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json".toMediaType()
    private fun url(path: String = "") = BackupManager.BACKUP_SERVER.trimEnd('/') + BASE + path

    data class ShareResult(val ok: Boolean, val shareId: String? = null, val deliveredCount: Int = 0, val error: String? = null)
    data class InboxEntry(val shareId: String, val type: String, val name: String, val senderName: String, val timestamp: String, val size: Int)

    /** Send GPX content to a favorites group. Blocking. */
    fun sendToGroup(email: String, syncKey: String, groupId: String, name: String, gpxData: String, type: String = "gpx"): ShareResult {
        if (email.isBlank() || syncKey.isBlank()) return ShareResult(false, error = "no_sync")
        val clientShareId = System.currentTimeMillis().toString(36) + "_" + (Math.random() * 1e6).toLong().toString(36)
        val body = JSONObject().apply {
            put("groupId", groupId)
            put("type", type)
            put("name", name)
            put("data", gpxData)
            put("clientShareId", clientShareId)
        }.toString().toRequestBody(jsonMedia)

        val req = Request.Builder()
            .url(url())
            .header("X-Sync-Email", email)
            .header("X-Sync-Key", syncKey)
            .post(body).build()
        return try {
            client.newCall(req).execute().use { resp ->
                val j = JSONObject(resp.body?.string().orEmpty())
                when (resp.code) {
                    200 -> ShareResult(true, j.optString("shareId"), j.optInt("deliveredCount"))
                    404 -> ShareResult(false, error = "group_not_found")
                    413 -> ShareResult(false, error = "too_large")
                    401, 403 -> ShareResult(false, error = "auth_error")
                    else -> ShareResult(false, error = "HTTP ${resp.code}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "send failed: ${e.message}")
            ShareResult(false, error = e.message)
        }
    }

    /** Get pending inbox entries. Blocking. */
    fun getInbox(email: String, syncKey: String, afterCursor: String? = null): List<InboxEntry> {
        if (email.isBlank() || syncKey.isBlank()) return emptyList()
        val qp = if (afterCursor != null) "?after=$afterCursor" else ""
        val req = Request.Builder()
            .url(url("/inbox$qp"))
            .header("X-Sync-Email", email)
            .header("X-Sync-Key", syncKey)
            .get().build()
        return try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val j = JSONObject(resp.body?.string().orEmpty())
                val arr = j.optJSONArray("shares") ?: JSONArray()
                (0 until arr.length()).map { i ->
                    val s = arr.getJSONObject(i)
                    InboxEntry(
                        shareId = s.optString("shareId"),
                        type = s.optString("type", "gpx"),
                        name = s.optString("name", "?"),
                        senderName = s.optString("senderName", "?"),
                        timestamp = s.optString("timestamp", ""),
                        size = s.optInt("size", 0)
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "inbox failed: ${e.message}")
            emptyList()
        }
    }

    /** Download GPX blob by shareId. Blocking. Returns GPX string or null. */
    fun downloadData(email: String, syncKey: String, shareId: String): String? {
        if (email.isBlank() || syncKey.isBlank()) return null
        val req = Request.Builder()
            .url(url("/$shareId/data"))
            .header("X-Sync-Email", email)
            .header("X-Sync-Key", syncKey)
            .get().build()
        return try {
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string() else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "download failed: ${e.message}")
            null
        }
    }

    /** Acknowledge/dismiss a share from inbox. Blocking. */
    fun ack(email: String, syncKey: String, shareId: String): Boolean {
        if (email.isBlank() || syncKey.isBlank()) return false
        val req = Request.Builder()
            .url(url("/$shareId/ack"))
            .header("X-Sync-Email", email)
            .header("X-Sync-Key", syncKey)
            .delete().build()
        return try {
            client.newCall(req).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            Log.w(TAG, "ack failed: ${e.message}")
            false
        }
    }
}
