package com.andreykoff.racenav

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object MonitoringApi {
    private const val TAG = "MonitoringApi"
    private const val STATUS_BASE = "/api/live2/status"
    private const val MESSAGES_BASE = "/api/live2/messages"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json".toMediaType()
    private fun url(path: String = "") = BackupManager.BACKUP_SERVER.trimEnd('/') + path

    data class StatusResult(val ok: Boolean, val supported: Boolean = true, val error: String? = null)
    data class SendResult(
        val ok: Boolean,
        val supported: Boolean = true,
        val messageId: String? = null,
        val clientMessageId: String? = null,
        val error: String? = null
    )
    data class InboxResult(
        val ok: Boolean,
        val supported: Boolean = true,
        val cursor: Long? = null,
        val messages: List<MonitoringMessage> = emptyList(),
        val error: String? = null
    )

    fun pushStatus(email: String, syncKey: String, status: MonitoringPresenceStatus): StatusResult {
        if (email.isBlank() || syncKey.isBlank()) return StatusResult(false, error = "no_sync")
        val body = JSONObject().apply {
            put("statusCode", status.code)
            put("emoji", status.emoji)
            put("label", status.label)
            put("timestamp", MonitoringTime.nowIsoUtc())
        }.toString().toRequestBody(jsonMedia)
        val req = Request.Builder()
            .url(url(STATUS_BASE))
            .header("X-Sync-Email", email)
            .header("X-Sync-Key", syncKey)
            .put(body)
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                when {
                    resp.isSuccessful -> StatusResult(true)
                    resp.code in listOf(404, 405, 501) -> StatusResult(false, supported = false, error = "unsupported")
                    resp.code == 401 || resp.code == 403 -> StatusResult(false, error = "auth_error")
                    else -> StatusResult(false, error = "HTTP ${resp.code}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "pushStatus failed: ${e.message}")
            StatusResult(false, error = e.message)
        }
    }

    fun sendDirectMessage(
        email: String,
        syncKey: String,
        targetUniqueId: String,
        targetName: String,
        text: String,
        senderStatusCode: String?,
        clientMessageId: String = createClientMessageId()
    ): SendResult {
        return sendMessage(
            email = email,
            syncKey = syncKey,
            path = "$MESSAGES_BASE/direct",
            payload = JSONObject().apply {
                put("clientMessageId", clientMessageId)
                put("targetUniqueId", targetUniqueId)
                put("targetName", targetName)
                put("text", text)
                if (!senderStatusCode.isNullOrBlank()) put("senderStatusCode", senderStatusCode)
            },
            clientMessageId = clientMessageId
        )
    }

    fun sendGroupMessage(
        email: String,
        syncKey: String,
        groupId: String,
        groupName: String,
        text: String,
        senderStatusCode: String?,
        clientMessageId: String = createClientMessageId()
    ): SendResult {
        return sendMessage(
            email = email,
            syncKey = syncKey,
            path = "$MESSAGES_BASE/group",
            payload = JSONObject().apply {
                put("clientMessageId", clientMessageId)
                put("groupId", groupId)
                put("groupName", groupName)
                put("text", text)
                if (!senderStatusCode.isNullOrBlank()) put("senderStatusCode", senderStatusCode)
            },
            clientMessageId = clientMessageId
        )
    }

    fun fetchInbox(
        email: String,
        syncKey: String,
        myUniqueId: String,
        afterCursor: Long? = null
    ): InboxResult {
        if (email.isBlank() || syncKey.isBlank()) return InboxResult(false, error = "no_sync")
        val qp = afterCursor?.takeIf { it > 0L }?.let { "?after=$it" }.orEmpty()
        val req = Request.Builder()
            .url(url("$MESSAGES_BASE/inbox$qp"))
            .header("X-Sync-Email", email)
            .header("X-Sync-Key", syncKey)
            .get()
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                when {
                    resp.isSuccessful -> {
                        val body = resp.body?.string().orEmpty()
                        val json = JSONObject(body.ifBlank { "{}" })
                        val arr = json.optJSONArray("messages")
                            ?: json.optJSONArray("items")
                            ?: JSONArray()
                        val messages = mutableListOf<MonitoringMessage>()
                        var maxCursor = afterCursor ?: 0L
                        for (i in 0 until arr.length()) {
                            val obj = arr.optJSONObject(i) ?: continue
                            val parsed = parseMessage(obj, myUniqueId) ?: continue
                            if (parsed.sentAt > maxCursor) maxCursor = parsed.sentAt
                            messages.add(parsed)
                        }
                        InboxResult(
                            ok = true,
                            cursor = json.optLong("cursor", maxCursor).takeIf { it > 0L } ?: maxCursor,
                            messages = messages
                        )
                    }
                    resp.code in listOf(404, 405, 501) -> InboxResult(false, supported = false, error = "unsupported")
                    resp.code == 401 || resp.code == 403 -> InboxResult(false, error = "auth_error")
                    else -> InboxResult(false, error = "HTTP ${resp.code}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetchInbox failed: ${e.message}")
            InboxResult(false, error = e.message)
        }
    }

    private fun sendMessage(
        email: String,
        syncKey: String,
        path: String,
        payload: JSONObject,
        clientMessageId: String
    ): SendResult {
        if (email.isBlank() || syncKey.isBlank()) return SendResult(false, clientMessageId = clientMessageId, error = "no_sync")
        val req = Request.Builder()
            .url(url(path))
            .header("X-Sync-Email", email)
            .header("X-Sync-Key", syncKey)
            .post(payload.toString().toRequestBody(jsonMedia))
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                val json = try { JSONObject(body.ifBlank { "{}" }) } catch (_: Exception) { JSONObject() }
                when {
                    resp.isSuccessful -> SendResult(true, messageId = json.optString("messageId", "").ifBlank { null }, clientMessageId = clientMessageId)
                    resp.code in listOf(404, 405, 501) -> SendResult(false, supported = false, clientMessageId = clientMessageId, error = "unsupported")
                    resp.code == 401 || resp.code == 403 -> SendResult(false, clientMessageId = clientMessageId, error = "auth_error")
                    else -> SendResult(false, clientMessageId = clientMessageId, error = "HTTP ${resp.code}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "sendMessage failed: ${e.message}")
            SendResult(false, clientMessageId = clientMessageId, error = e.message)
        }
    }

    private fun parseMessage(obj: JSONObject, myUniqueId: String): MonitoringMessage? {
        val scope = obj.optString("scope", obj.optString("type", MonitoringMessage.SCOPE_DIRECT))
            .trim()
            .lowercase()
            .let {
                when (it) {
                    "group", "groups" -> MonitoringMessage.SCOPE_GROUP
                    else -> MonitoringMessage.SCOPE_DIRECT
                }
            }
        val serverId = obj.optString("messageId", obj.optString("id", ""))
        val clientId = obj.optString("clientMessageId", "").ifBlank { null }
        val senderUniqueId = obj.optString("senderUniqueId", obj.optString("fromUniqueId", "")).ifBlank { null }
        val senderName = obj.optString("senderName", obj.optString("fromName", "Участник")).ifBlank { "Участник" }
        val targetId = when (scope) {
            MonitoringMessage.SCOPE_GROUP -> obj.optString("groupId", "")
            else -> obj.optString("targetUniqueId", obj.optString("peerUniqueId", ""))
        }.ifBlank {
            if (scope == MonitoringMessage.SCOPE_GROUP) "group" else senderUniqueId ?: "direct"
        }
        val targetName = when (scope) {
            MonitoringMessage.SCOPE_GROUP -> obj.optString("groupName", "Группа")
            else -> obj.optString("targetName", obj.optString("peerName", senderName))
        }.ifBlank { if (scope == MonitoringMessage.SCOPE_GROUP) "Группа" else senderName }
        val attachment = obj.optJSONObject("attachment")?.let {
            runCatching { MonitoringAttachment.fromJson(it) }.getOrNull()
        }
        val text = obj.optString("text", "").trim()
            .ifBlank { attachment?.previewText.orEmpty() }
        val sentAt = obj.optLong("sentAt", 0L)
            .takeIf { it > 0L }
            ?: MonitoringTime.parseToMillis(obj.optString("timestamp", obj.optString("createdAt", "")))
        if (text.isBlank() && attachment == null) return null
        val incoming = when (obj.optString("direction", "").trim().lowercase()) {
            "outgoing" -> false
            "incoming" -> true
            else -> senderUniqueId?.equals(myUniqueId, ignoreCase = true) != true
        }
        return MonitoringMessage(
            messageId = serverId.ifBlank { clientId ?: createClientMessageId() },
            clientMessageId = clientId,
            scope = scope,
            targetId = targetId,
            targetName = targetName,
            senderUniqueId = senderUniqueId,
            senderName = senderName,
            senderStatusCode = obj.optString("senderStatusCode", obj.optString("statusCode", "")).ifBlank { null },
            text = text,
            kind = if (attachment != null) MonitoringMessageKind.ATTACHMENT.code else obj.optString("kind", MonitoringMessageKind.TEXT.code),
            attachment = attachment,
            sentAt = sentAt.takeIf { it > 0L } ?: System.currentTimeMillis(),
            incoming = incoming,
            read = obj.optBoolean("read", false),
            delivery = MonitoringMessage.DELIVERY_SENT
        )
    }

    private fun createClientMessageId(): String {
        return System.currentTimeMillis().toString(36) + "_" + (Math.random() * 1e6).toLong().toString(36)
    }
}
