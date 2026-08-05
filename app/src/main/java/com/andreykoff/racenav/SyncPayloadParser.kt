package com.andreykoff.racenav

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal

data class SyncCoordinate(
    val lat: Double,
    val lon: Double,
)

data class SyncWaypoint(
    val name: String,
    val coordinate: SyncCoordinate,
    val color: String,
    val symbol: String,
    val proximity: Double,
)

data class SyncTrack(
    val name: String,
    val points: List<SyncCoordinate>,
)

data class SyncRoute(
    val name: String,
    val color: String,
    val points: List<SyncWaypoint>,
)

data class SyncPayload(
    val waypoints: List<SyncWaypoint>,
    val tracks: List<SyncTrack>,
    val routes: List<SyncRoute>,
    val skippedCoordinates: Int,
) {
    val trackPointCount: Int
        get() = tracks.sumOf { it.points.size }
}

class SyncPayloadException(message: String) : IllegalArgumentException(message)

/** Converts both the flat legacy view and the modern data.<type>.items view. */
object SyncPayloadParser {

    private enum class Layout { FLAT, NESTED }

    fun parse(root: JSONObject): SyncPayload {
        val layout = validateLayout(root)
        val waypointItems = items(root, "waypoints", layout)
        val trackItems = items(root, "tracks", layout)
        val routeItems = items(root, "routes", layout)
        var skippedCoordinates = 0

        val waypoints = buildList {
            for (index in 0 until waypointItems.length()) {
                val item = waypointItems.optJSONObject(index)
                val coordinate = item?.let(::coordinate)
                if (item == null || coordinate == null) {
                    skippedCoordinates++
                    continue
                }
                add(
                    SyncWaypoint(
                        name = item.optString("name", "Точка ${index + 1}").ifBlank { "Точка ${index + 1}" },
                        coordinate = coordinate,
                        color = item.optString("color", "#1565C0").ifBlank { "#1565C0" },
                        symbol = item.optString("icon", item.optString("symbol", "")),
                        proximity = item.optDouble("radius", item.optDouble("proximity", 0.0))
                            .takeIf { it.isFinite() && it >= 0.0 } ?: 0.0,
                    )
                )
            }
        }

        val tracks = buildList {
            for (trackIndex in 0 until trackItems.length()) {
                val item = trackItems.optJSONObject(trackIndex) ?: continue
                val pointItems = item.optJSONArray("points") ?: JSONArray()
                val points = buildList {
                    for (pointIndex in 0 until pointItems.length()) {
                        val point = pointItems.optJSONObject(pointIndex)
                        val parsed = point?.let(::coordinate)
                        if (point == null || parsed == null) {
                            skippedCoordinates++
                        } else {
                            add(parsed)
                        }
                    }
                }
                if (points.isNotEmpty()) {
                    add(
                        SyncTrack(
                            name = item.optString("name", "Синхр. трек ${trackIndex + 1}")
                                .ifBlank { "Синхр. трек ${trackIndex + 1}" },
                            points = points,
                        )
                    )
                }
            }
        }

        val routes = buildList {
            for (routeIndex in 0 until routeItems.length()) {
                val item = routeItems.optJSONObject(routeIndex) ?: continue
                val routeName = item.optString("name", "Маршрут синхронизации")
                    .ifBlank { "Маршрут синхронизации" }
                val routeColor = item.optString("color", "")
                val pointItems = item.optJSONArray("points") ?: JSONArray()
                val labels = item.optJSONArray("labels")
                val pointRadii = item.optJSONArray("pointRadii")
                val points = buildList {
                    for (pointIndex in 0 until pointItems.length()) {
                        val point = pointItems.optJSONObject(pointIndex)
                        val parsed = point?.let(::coordinate)
                        if (point == null || parsed == null) {
                            skippedCoordinates++
                            continue
                        }
                        add(
                            SyncWaypoint(
                                name = labels?.optString(pointIndex)?.takeIf { it.isNotBlank() }
                                    ?: point.optString("name", "WP${pointIndex + 1}").ifBlank { "WP${pointIndex + 1}" },
                                coordinate = parsed,
                                color = point.optString("color", routeColor),
                                symbol = point.optString("icon", point.optString("symbol", "")),
                                proximity = pointRadii?.optDouble(pointIndex, Double.NaN)
                                    ?.takeIf { it.isFinite() && it >= 0.0 }
                                    ?: point.optDouble("radius", point.optDouble("proximity", 0.0))
                                        .takeIf { it.isFinite() && it >= 0.0 }
                                    ?: 0.0,
                            )
                        )
                    }
                }
                if (points.isNotEmpty()) add(SyncRoute(routeName, routeColor, points))
            }
        }

        rejectFullyInvalidType("waypoints", waypointItems, waypoints.size)
        rejectFullyInvalidType("tracks", trackItems, tracks.size)
        rejectFullyInvalidType("routes", routeItems, routes.size)
        return SyncPayload(waypoints, tracks, routes, skippedCoordinates)
    }

    private fun validateLayout(root: JSONObject): Layout {
        if (root.has("ok") && !root.optBoolean("ok", false)) {
            throw SyncPayloadException("Сервер отклонил запрос синхронизации")
        }
        val types = listOf("waypoints", "tracks", "routes")
        val data = root.optJSONObject("data")
        val flatPresent = types.any { root.has(it) }
        val nestedPresent = data != null && types.any { data.has(it) }
        val flatComplete = flatPresent && types.all { root.optJSONArray(it) != null }
        val nestedComplete = nestedPresent && data != null &&
            types.all { data.optJSONObject(it)?.optJSONArray("items") != null }

        if (flatPresent && !flatComplete) {
            throw SyncPayloadException("Flat snapshot содержит не все разделы")
        }
        if (nestedPresent && !nestedComplete) {
            throw SyncPayloadException("Nested snapshot содержит не все разделы")
        }

        if (flatComplete && nestedComplete) {
            val nestedData = requireNotNull(data)
            types.forEach { type ->
                val flatItems = root.getJSONArray(type)
                val nestedItems = nestedData.getJSONObject(type).getJSONArray("items")
                if (canonicalJson(flatItems) != canonicalJson(nestedItems)) {
                    throw SyncPayloadException("Flat и nested snapshot расходятся в разделе $type")
                }
            }
            return Layout.FLAT
        }
        if (flatComplete) return Layout.FLAT
        if (nestedComplete) return Layout.NESTED
        throw SyncPayloadException("Ответ синхронизации не содержит полный snapshot")
    }

    private fun items(root: JSONObject, type: String, layout: Layout): JSONArray {
        return when (layout) {
            Layout.FLAT -> root.getJSONArray(type)
            Layout.NESTED -> root.getJSONObject("data").getJSONObject(type).getJSONArray("items")
        }
    }

    private fun rejectFullyInvalidType(type: String, rawItems: JSONArray, validItems: Int) {
        if (rawItems.length() > 0 && validItems == 0) {
            throw SyncPayloadException("Раздел $type не содержит корректных объектов")
        }
    }

    private fun canonicalJson(value: Any?): String {
        return when (value) {
            null, JSONObject.NULL -> "null"
            is JSONArray -> (0 until value.length()).joinToString(prefix = "[", postfix = "]") { index ->
                canonicalJson(value.opt(index))
            }
            is JSONObject -> {
                val keys = buildList {
                    val iterator = value.keys()
                    while (iterator.hasNext()) add(iterator.next())
                }.sorted()
                keys.joinToString(prefix = "{", postfix = "}") { key ->
                    JSONObject.quote(key) + ":" + canonicalJson(value.opt(key))
                }
            }
            is Number -> runCatching {
                BigDecimal(value.toString()).stripTrailingZeros().toPlainString()
            }.getOrElse { value.toString() }
            is Boolean -> value.toString()
            else -> JSONObject.quote(value.toString())
        }
    }

    private fun coordinate(item: JSONObject): SyncCoordinate? {
        val lat = item.optDouble("lat", Double.NaN)
        val lon = when {
            item.has("lng") -> item.optDouble("lng", Double.NaN)
            else -> item.optDouble("lon", Double.NaN)
        }
        if (!lat.isFinite() || !lon.isFinite()) return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        if (lat == 0.0 && lon == 0.0) return null
        return SyncCoordinate(lat, lon)
    }
}
