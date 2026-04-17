package com.andreykoff.racenav

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

enum class MonitoringPresenceStatus(
    val code: String,
    val emoji: String,
    val label: String,
    val markerGlyph: String,
    val accentColor: Int
) {
    NONE("none", "🙂", "Без статуса", "✴", 0xFF888888.toInt()),
    LOOKING_COMPANY("looking_company", "🤝", "Ищу компанию", "🤝", 0xFF42A5F5.toInt()),
    TAKE_WITH_ME("take_with_me", "🚙", "Возьму в компанию", "🚙", 0xFF66BB6A.toInt()),
    TOURISM("tourism", "🏕️", "Туризм", "🏕", 0xFFFFB300.toInt()),
    RACE("race", "🏁", "Гонка", "🏁", 0xFFAB47BC.toInt()),
    SOS("sos", "🆘", "SOS", "SOS", 0xFFE53935.toInt());

    val displayLabel: String get() = "$emoji $label"

    companion object {
        fun fromCode(raw: String?): MonitoringPresenceStatus {
            val normalized = raw?.trim()?.lowercase(Locale.US).orEmpty()
            return values().firstOrNull { it.code == normalized } ?: NONE
        }

        fun selectable(): List<MonitoringPresenceStatus> = listOf(
            NONE, LOOKING_COMPANY, TAKE_WITH_ME, TOURISM, RACE, SOS
        )
    }
}

enum class MonitoringMessageKind(val code: String) {
    TEXT("text"),
    ATTACHMENT("attachment");

    companion object {
        fun fromCode(raw: String?): MonitoringMessageKind {
            val normalized = raw?.trim()?.lowercase(Locale.US).orEmpty()
            return values().firstOrNull { it.code == normalized } ?: TEXT
        }
    }
}

enum class MonitoringAttachmentType(
    val code: String,
    val emoji: String,
    val label: String
) {
    GPX("gpx", "📦", "GPX"),
    ROUTE("route", "🗺", "Маршрут"),
    TRACK("track", "📏", "Трек"),
    WAYPOINTS("waypoints", "📍", "Точки");

    fun previewText(name: String): String = "$emoji $label: $name"

    companion object {
        fun fromCode(raw: String?): MonitoringAttachmentType {
            val normalized = raw?.trim()?.lowercase(Locale.US).orEmpty()
            return values().firstOrNull { it.code == normalized } ?: GPX
        }
    }
}

data class MonitoringAttachment(
    val attachmentId: String,
    val type: String,
    val name: String,
    val localPath: String? = null,
    val sizeBytes: Long = 0L,
    val remoteId: String? = null,
    val cachedAt: Long = 0L
) {
    val typeInfo: MonitoringAttachmentType get() = MonitoringAttachmentType.fromCode(type)
    val previewText: String get() = typeInfo.previewText(name)

    fun toJson(): JSONObject = JSONObject().apply {
        put("attachmentId", attachmentId)
        put("type", type)
        put("name", name)
        if (!localPath.isNullOrBlank()) put("localPath", localPath)
        put("sizeBytes", sizeBytes)
        if (!remoteId.isNullOrBlank()) put("remoteId", remoteId)
        put("cachedAt", cachedAt)
    }

    companion object {
        fun fromJson(o: JSONObject): MonitoringAttachment {
            return MonitoringAttachment(
                attachmentId = o.optString("attachmentId", ""),
                type = o.optString("type", MonitoringAttachmentType.GPX.code),
                name = o.optString("name", "Вложение"),
                localPath = o.optString("localPath", "").ifBlank { null },
                sizeBytes = o.optLong("sizeBytes", 0L),
                remoteId = o.optString("remoteId", "").ifBlank { null },
                cachedAt = o.optLong("cachedAt", 0L)
            )
        }
    }
}

data class MonitoringMessage(
    val messageId: String,
    val clientMessageId: String? = null,
    val scope: String,
    val targetId: String,
    val targetName: String,
    val senderUniqueId: String? = null,
    val senderName: String,
    val senderStatusCode: String? = null,
    val text: String,
    val kind: String = MonitoringMessageKind.TEXT.code,
    val attachment: MonitoringAttachment? = null,
    val sentAt: Long,
    val incoming: Boolean,
    val read: Boolean,
    val delivery: String = DELIVERY_SENT
) {
    val kindInfo: MonitoringMessageKind get() = MonitoringMessageKind.fromCode(kind)
    val previewText: String get() = attachment?.previewText ?: text

    fun toJson(): JSONObject = JSONObject().apply {
        put("messageId", messageId)
        if (clientMessageId != null) put("clientMessageId", clientMessageId)
        put("scope", scope)
        put("targetId", targetId)
        put("targetName", targetName)
        if (senderUniqueId != null) put("senderUniqueId", senderUniqueId)
        put("senderName", senderName)
        if (senderStatusCode != null) put("senderStatusCode", senderStatusCode)
        put("text", text)
        put("kind", kind)
        if (attachment != null) put("attachment", attachment.toJson())
        put("sentAt", sentAt)
        put("incoming", incoming)
        put("read", read)
        put("delivery", delivery)
    }

    companion object {
        const val SCOPE_DIRECT = "direct"
        const val SCOPE_GROUP = "group"
        const val DELIVERY_SENT = "sent"
        const val DELIVERY_FAILED = "failed"

        fun fromJson(o: JSONObject): MonitoringMessage {
            return MonitoringMessage(
                messageId = o.optString("messageId", ""),
                clientMessageId = o.optString("clientMessageId", "").ifBlank { null },
                scope = o.optString("scope", SCOPE_DIRECT),
                targetId = o.optString("targetId", ""),
                targetName = o.optString("targetName", ""),
                senderUniqueId = o.optString("senderUniqueId", "").ifBlank { null },
                senderName = o.optString("senderName", ""),
                senderStatusCode = o.optString("senderStatusCode", "").ifBlank { null },
                text = o.optString("text", ""),
                kind = o.optString("kind", MonitoringMessageKind.TEXT.code),
                attachment = o.optJSONObject("attachment")?.let { from -> runCatching { MonitoringAttachment.fromJson(from) }.getOrNull() },
                sentAt = o.optLong("sentAt", 0L),
                incoming = o.optBoolean("incoming", true),
                read = o.optBoolean("read", false),
                delivery = o.optString("delivery", DELIVERY_SENT)
            )
        }
    }
}

object MonitoringTime {
    private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    fun nowIsoUtc(): String = isoFormat.format(Date())

    fun parseToMillis(raw: String?): Long {
        if (raw.isNullOrBlank()) return 0L
        raw.toLongOrNull()?.let { if (it > 0) return it }
        val normalized = raw.substringBefore('.').substringBefore('+').substringBefore('Z')
        val formats = listOf(
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            },
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
        )
        for (fmt in formats) {
            try {
                val parsed = fmt.parse(normalized) ?: continue
                return parsed.time
            } catch (_: Exception) {
            }
        }
        return 0L
    }

    fun formatShort(rawMs: Long): String {
        if (rawMs <= 0L) return "—"
        val fmt = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())
        return fmt.format(Date(rawMs))
    }

    fun formatRelative(rawMs: Long): String {
        if (rawMs <= 0L) return "—"
        val deltaSec = ((System.currentTimeMillis() - rawMs) / 1000L).coerceAtLeast(0L)
        return when {
            deltaSec < 60L -> "${deltaSec}s"
            deltaSec < 3600L -> "${deltaSec / 60L}m"
            deltaSec < 86_400L -> "${deltaSec / 3600L}h"
            else -> "${deltaSec / 86_400L}d"
        }
    }
}
