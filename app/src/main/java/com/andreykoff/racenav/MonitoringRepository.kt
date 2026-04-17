package com.andreykoff.racenav

import android.content.Context
import org.json.JSONArray
import java.io.File

object MonitoringRepository {
    const val PREF_MONITORING_STATUS_CODE = "monitoring_status_code"
    private const val PREF_MONITORING_STATUS_UPDATED_AT = "monitoring_status_updated_at"
    private const val PREF_MONITORING_MESSAGES_JSON = "monitoring_messages_json"
    const val PREF_MONITORING_UNREAD_COUNT = "monitoring_messages_unread_count"
    const val PREF_MONITORING_LAST_CURSOR = "monitoring_messages_last_cursor"
    const val PREF_MONITORING_ATTACHMENT_CACHE_LIMIT_MB = "monitoring_attachment_cache_limit_mb"
    private const val DEFAULT_ATTACHMENT_CACHE_LIMIT_MB = 100
    private const val ATTACHMENTS_DIR = "monitoring_attachments"
    private const val MAX_MESSAGES = 120

    data class MergeResult(
        val newIncoming: List<MonitoringMessage>,
        val unreadCount: Int,
        val allMessages: List<MonitoringMessage>
    )

    private fun prefs(context: Context) =
        context.getSharedPreferences(MapFragment.PREFS_NAME, Context.MODE_PRIVATE)

    private fun attachmentsDir(context: Context): File =
        File(context.filesDir, ATTACHMENTS_DIR).apply { mkdirs() }

    fun getMyStatus(context: Context): MonitoringPresenceStatus {
        return MonitoringPresenceStatus.fromCode(
            prefs(context).getString(PREF_MONITORING_STATUS_CODE, MonitoringPresenceStatus.NONE.code)
        )
    }

    fun saveMyStatus(context: Context, status: MonitoringPresenceStatus) {
        prefs(context).edit()
            .putString(PREF_MONITORING_STATUS_CODE, status.code)
            .putLong(PREF_MONITORING_STATUS_UPDATED_AT, System.currentTimeMillis())
            .apply()
    }

    fun getMessages(context: Context): List<MonitoringMessage> {
        val raw = prefs(context).getString(PREF_MONITORING_MESSAGES_JSON, "[]") ?: "[]"
        val arr = try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
        val out = mutableListOf<MonitoringMessage>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val parsed = try { MonitoringMessage.fromJson(obj) } catch (_: Exception) { null } ?: continue
            if (parsed.messageId.isBlank()) continue
            if (parsed.text.isBlank() && parsed.attachment == null) continue
            out.add(parsed)
        }
        return out.sortedBy { it.sentAt }
    }

    fun putMessages(context: Context, messages: List<MonitoringMessage>) {
        val normalized = messages
            .sortedBy { it.sentAt }
            .takeLast(MAX_MESSAGES)
        val arr = JSONArray()
        normalized.forEach { arr.put(it.toJson()) }
        val unread = normalized.count { it.incoming && !it.read }
        cleanupAttachmentFiles(context, normalized)
        prefs(context).edit()
            .putString(PREF_MONITORING_MESSAGES_JSON, arr.toString())
            .putInt(PREF_MONITORING_UNREAD_COUNT, unread)
            .apply()
    }

    fun addLocalMessage(context: Context, message: MonitoringMessage) {
        val merged = getMessages(context).toMutableList().apply { add(message) }
        putMessages(context, merged)
    }

    fun mergeIncoming(context: Context, incoming: List<MonitoringMessage>): MergeResult {
        val current = getMessages(context)
        val byKey = linkedMapOf<String, MonitoringMessage>()
        current.forEach { msg ->
            byKey[dedupeKey(msg)] = msg
        }
        val newIncoming = mutableListOf<MonitoringMessage>()
        incoming.sortedBy { it.sentAt }.forEach { msg ->
            val key = dedupeKey(msg)
            val existing = byKey[key]
            val merged = when {
                existing == null -> msg
                existing.read && msg.incoming -> msg.copy(read = true, delivery = existing.delivery)
                existing.delivery == MonitoringMessage.DELIVERY_FAILED && !msg.incoming -> msg.copy(read = existing.read)
                else -> msg.copy(read = existing.read || msg.read, delivery = if (msg.incoming) msg.delivery else existing.delivery)
            }
            if (existing == null && merged.incoming && !merged.read) {
                newIncoming.add(merged)
            }
            byKey[key] = merged
        }
        val all = byKey.values.sortedBy { it.sentAt }.takeLast(MAX_MESSAGES)
        putMessages(context, all)
        return MergeResult(
            newIncoming = newIncoming,
            unreadCount = all.count { it.incoming && !it.read },
            allMessages = all
        )
    }

    fun markAllRead(context: Context) {
        val updated = getMessages(context).map { msg ->
            if (msg.incoming && !msg.read) msg.copy(read = true) else msg
        }
        putMessages(context, updated)
    }

    fun clearMessages(context: Context) {
        clearAttachmentCache(context)
        prefs(context).edit()
            .putString(PREF_MONITORING_MESSAGES_JSON, "[]")
            .putInt(PREF_MONITORING_UNREAD_COUNT, 0)
            .apply()
    }

    fun getUnreadCount(context: Context): Int {
        return prefs(context).getInt(PREF_MONITORING_UNREAD_COUNT, 0)
    }

    fun getLastCursor(context: Context): Long {
        return prefs(context).getLong(PREF_MONITORING_LAST_CURSOR, 0L)
    }

    fun saveLastCursor(context: Context, cursor: Long) {
        if (cursor <= 0L) return
        prefs(context).edit().putLong(PREF_MONITORING_LAST_CURSOR, cursor).apply()
    }

    fun getAttachmentCacheLimitMb(context: Context): Int {
        return prefs(context)
            .getInt(PREF_MONITORING_ATTACHMENT_CACHE_LIMIT_MB, DEFAULT_ATTACHMENT_CACHE_LIMIT_MB)
            .coerceIn(50, 1024)
    }

    fun setAttachmentCacheLimitMb(context: Context, mb: Int) {
        prefs(context).edit()
            .putInt(PREF_MONITORING_ATTACHMENT_CACHE_LIMIT_MB, mb.coerceIn(50, 1024))
            .apply()
        trimAttachmentCache(context)
    }

    fun getAttachmentCacheUsageBytes(context: Context): Long {
        return attachmentsDir(context)
            .listFiles()
            ?.filter { it.isFile }
            ?.sumOf { it.length() }
            ?: 0L
    }

    fun clearAttachmentCache(context: Context) {
        attachmentsDir(context).listFiles()?.forEach { file ->
            runCatching { file.delete() }
        }
    }

    fun touchAttachment(context: Context, attachment: MonitoringAttachment?) {
        val path = attachment?.localPath ?: return
        val file = File(path)
        if (file.exists()) {
            runCatching { file.setLastModified(System.currentTimeMillis()) }
            trimAttachmentCache(context, preservePaths = setOf(file.absolutePath))
        }
    }

    fun cacheAttachment(
        context: Context,
        type: MonitoringAttachmentType,
        name: String,
        payload: String,
        remoteId: String? = null
    ): MonitoringAttachment? {
        return try {
            val dir = attachmentsDir(context)
            val safeName = name
                .trim()
                .ifBlank { type.label.lowercase() }
                .replace(Regex("[^A-Za-z0-9._-]+"), "_")
                .trim('_')
                .take(48)
                .ifBlank { type.code }
            val file = File(dir, "${System.currentTimeMillis()}_${safeName}.gpx")
            file.writeText(payload)
            file.setLastModified(System.currentTimeMillis())
            trimAttachmentCache(context, preservePaths = setOf(file.absolutePath))
            MonitoringAttachment(
                attachmentId = remoteId ?: file.nameWithoutExtension,
                type = type.code,
                name = name.ifBlank { type.label },
                localPath = file.absolutePath,
                sizeBytes = file.length(),
                remoteId = remoteId,
                cachedAt = System.currentTimeMillis()
            )
        } catch (_: Exception) {
            null
        }
    }

    fun formatCacheSize(bytes: Long): String {
        if (bytes < 1024L) return "$bytes Б"
        if (bytes < 1024L * 1024L) return "${bytes / 1024L} КБ"
        val mb = bytes / 1_048_576.0
        return if (mb < 100) String.format("%.1f МБ", mb) else "${mb.toInt()} МБ"
    }

    private fun dedupeKey(message: MonitoringMessage): String {
        return when {
            !message.clientMessageId.isNullOrBlank() -> "client:${message.clientMessageId}"
            else -> "server:${message.messageId}"
        }
    }

    private fun cleanupAttachmentFiles(context: Context, messages: List<MonitoringMessage>) {
        val keepPaths = messages.mapNotNull { it.attachment?.localPath }.toSet()
        attachmentsDir(context).listFiles()?.forEach { file ->
            if (!file.isFile) return@forEach
            if (file.absolutePath !in keepPaths) {
                runCatching { file.delete() }
            }
        }
        trimAttachmentCache(context)
    }

    private fun trimAttachmentCache(context: Context, preservePaths: Set<String> = emptySet()) {
        val limitBytes = getAttachmentCacheLimitMb(context).toLong() * 1024L * 1024L
        val files = attachmentsDir(context)
            .listFiles()
            ?.filter { it.isFile }
            ?.sortedBy { it.lastModified() }
            ?: return
        var total = files.sumOf { it.length() }
        for (file in files) {
            if (total <= limitBytes) break
            if (file.absolutePath in preservePaths) continue
            total -= file.length()
            runCatching { file.delete() }
        }
    }
}
