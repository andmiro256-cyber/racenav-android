package com.andreykoff.racenav

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * HTTP client for /api/live2/favorites endpoints.
 * Auth: X-Sync-Email + X-Sync-Key headers (reuses existing syncAuth).
 */
object FavoritesApi {
    private const val TAG = "FavoritesApi"
    private const val ENDPOINT = "/api/live2/favorites"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    private val jsonMedia = "application/json".toMediaType()

    private fun url(): String = BackupManager.BACKUP_SERVER.trimEnd('/') + ENDPOINT

    /** GET — fetch current favorites document. */
    fun fetch(email: String, syncKey: String): FavoritesResult {
        if (email.isBlank() || syncKey.isBlank()) return FavoritesResult.NoSync
        val req = Request.Builder()
            .url(url())
            .header("X-Sync-Email", email)
            .header("X-Sync-Key", syncKey)
            .get()
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                when (resp.code) {
                    200 -> parseSuccess(body)
                    401 -> FavoritesResult.AuthError
                    429 -> FavoritesResult.RateLimit
                    else -> FavoritesResult.NetworkError("HTTP ${resp.code}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetch failed: ${e.message}")
            FavoritesResult.NetworkError(e.message ?: "network error")
        }
    }

    /** PUT — save entire favorites document. */
    fun save(email: String, syncKey: String, doc: FavoritesDocument): FavoritesResult {
        if (email.isBlank() || syncKey.isBlank()) return FavoritesResult.NoSync
        val body = doc.toJson().toString().toRequestBody(jsonMedia)
        val req = Request.Builder()
            .url(url())
            .header("X-Sync-Email", email)
            .header("X-Sync-Key", syncKey)
            .put(body)
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                val respBody = resp.body?.string().orEmpty()
                when (resp.code) {
                    200 -> parsePutSuccess(respBody, doc)
                    401 -> FavoritesResult.AuthError
                    409 -> parseConflict(respBody)
                    429 -> FavoritesResult.RateLimit
                    else -> FavoritesResult.NetworkError("HTTP ${resp.code}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "save failed: ${e.message}")
            FavoritesResult.NetworkError(e.message ?: "network error")
        }
    }

    private fun parseSuccess(body: String): FavoritesResult {
        return try {
            val j = JSONObject(body)
            if (!j.optBoolean("ok", false)) {
                return FavoritesResult.NetworkError("not ok")
            }
            val favObj = j.optJSONObject("favorites") ?: return FavoritesResult.NetworkError("no favorites")
            val doc = FavoritesDocument.fromJson(favObj)
            val limitsObj = j.optJSONObject("limits")
            val limits = if (limitsObj != null) {
                FavoritesLimits(
                    maxGroups = limitsObj.optInt("maxGroups", 10),
                    maxMembersPerGroup = limitsObj.optInt("maxMembersPerGroup", 25)
                )
            } else FavoritesLimits()
            FavoritesResult.Success(doc, limits)
        } catch (e: Exception) {
            FavoritesResult.NetworkError("parse: ${e.message}")
        }
    }

    private fun parsePutSuccess(body: String, sentDoc: FavoritesDocument): FavoritesResult {
        return try {
            val j = JSONObject(body)
            if (!j.optBoolean("ok", false)) return FavoritesResult.NetworkError("not ok")
            val newVersion = j.optInt("version", sentDoc.version + 1)
            val updatedAt = if (j.isNull("updatedAt")) null else j.optString("updatedAt", null)
            val updatedDoc = sentDoc.copy(version = newVersion, updatedAt = updatedAt)
            FavoritesResult.Success(updatedDoc, FavoritesLimits())
        } catch (e: Exception) {
            FavoritesResult.NetworkError("parse: ${e.message}")
        }
    }

    private fun parseConflict(body: String): FavoritesResult {
        return try {
            val j = JSONObject(body)
            val serverVersion = j.optInt("serverVersion", 0)
            FavoritesResult.VersionConflict(serverVersion)
        } catch (e: Exception) {
            FavoritesResult.VersionConflict(0)
        }
    }
}
