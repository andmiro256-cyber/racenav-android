package com.andreykoff.racenav

import org.json.JSONArray
import org.json.JSONObject

/**
 * User-defined favorites groups for live monitoring.
 * Server: GET/PUT /api/live2/favorites (per-email JSON document).
 * Members stored as uniqueId (uppercase).
 */

data class FavoritesDocument(
    val schemaVersion: Int = 1,
    val version: Int = 0,
    val activeGroupId: String = "all",
    val updatedAt: String? = null,
    val updatedByDeviceId: String? = null,
    val groups: List<FavoritesGroup> = emptyList()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("schemaVersion", schemaVersion)
        put("version", version)
        put("activeGroupId", activeGroupId)
        if (updatedAt != null) put("updatedAt", updatedAt)
        if (updatedByDeviceId != null) put("updatedByDeviceId", updatedByDeviceId)
        val arr = JSONArray()
        groups.forEach { arr.put(it.toJson()) }
        put("groups", arr)
    }

    companion object {
        fun empty(): FavoritesDocument = FavoritesDocument()

        fun fromJson(o: JSONObject): FavoritesDocument {
            val groupsArr = o.optJSONArray("groups") ?: JSONArray()
            val groups = mutableListOf<FavoritesGroup>()
            for (i in 0 until groupsArr.length()) {
                groups.add(FavoritesGroup.fromJson(groupsArr.getJSONObject(i)))
            }
            return FavoritesDocument(
                schemaVersion = o.optInt("schemaVersion", 1),
                version = o.optInt("version", 0),
                activeGroupId = o.optString("activeGroupId", "all"),
                updatedAt = if (o.isNull("updatedAt")) null else o.optString("updatedAt", null),
                updatedByDeviceId = if (o.isNull("updatedByDeviceId")) null else o.optString("updatedByDeviceId", null),
                groups = groups
            )
        }
    }
}

data class FavoritesGroup(
    val id: String,
    val name: String,
    val color: String,
    val members: List<String>,
    val updatedAt: String? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("color", color)
        val arr = JSONArray()
        members.forEach { arr.put(it) }
        put("members", arr)
        if (updatedAt != null) put("updatedAt", updatedAt)
    }

    companion object {
        fun fromJson(o: JSONObject): FavoritesGroup {
            val membersArr = o.optJSONArray("members") ?: JSONArray()
            val members = mutableListOf<String>()
            for (i in 0 until membersArr.length()) {
                val m = membersArr.optString(i, "").trim().uppercase()
                if (m.isNotEmpty()) members.add(m)
            }
            return FavoritesGroup(
                id = o.optString("id", ""),
                name = o.optString("name", ""),
                color = o.optString("color", "#FF6B00"),
                members = members.distinct(),
                updatedAt = if (o.isNull("updatedAt")) null else o.optString("updatedAt", null)
            )
        }
    }
}

data class FavoritesLimits(
    val maxGroups: Int = 10,
    val maxMembersPerGroup: Int = 25
)

/** Result of API or repository operations. */
sealed class FavoritesResult {
    data class Success(val doc: FavoritesDocument, val limits: FavoritesLimits) : FavoritesResult()
    data class VersionConflict(val serverVersion: Int) : FavoritesResult()
    object AuthError : FavoritesResult()
    object RateLimit : FavoritesResult()
    object DocumentTooLarge : FavoritesResult()
    data class NetworkError(val message: String) : FavoritesResult()
    object NoSync : FavoritesResult()
}

/** Predefined group colors (hex). */
object GroupColors {
    const val ORANGE = "#FF6B00"
    const val GREEN = "#4CAF50"
    const val RED = "#F44336"
    const val BLUE = "#2196F3"
    const val PURPLE = "#9C27B0"
    const val YELLOW = "#FFEB3B"
    const val WHITE = "#FFFFFF"

    val ALL = listOf(ORANGE, GREEN, RED, BLUE, PURPLE, YELLOW, WHITE)
}
