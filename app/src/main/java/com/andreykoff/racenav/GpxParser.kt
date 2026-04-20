package com.andreykoff.racenav

import android.util.Xml
import java.io.InputStream

data class Waypoint(
    val name: String,
    val lat: Double,
    val lon: Double,
    val index: Int,
    val description: String = "",
    val proximity: Double = 0.0,  // radius in meters, 0 = use global setting
    val color: String = "",       // hex color e.g. "#FF0000", empty = default
    val symbol: String = ""       // symbol name: circle, triangle, flag, star, cross, square, diamond, pin
)

data class GpxResult(
    val pointWaypoints: List<Waypoint> = emptyList(),
    val routeWaypoints: List<Waypoint> = emptyList(),
    val trackPoints: List<Pair<Double, Double>> = emptyList(),
    val routeName: String? = null,
    val trackName: String? = null
) {
    val waypoints: List<Waypoint>
        get() = if (routeWaypoints.isNotEmpty()) routeWaypoints else pointWaypoints
}

object GpxParser {

    /** Parse GPX file — returns both waypoints (wpt/rtept) and track points (trkpt) */
    fun parseGpxFull(inputStream: InputStream): GpxResult {
        val wptList = mutableListOf<Waypoint>()   // standalone <wpt>
        val rteptList = mutableListOf<Waypoint>()  // route <rtept>
        val trackPoints = mutableListOf<Pair<Double, Double>>()
        val parser = Xml.newPullParser()
        parser.setInput(inputStream, null)

        var eventType = parser.eventType
        var inWpt = false; var inRtept = false; var inExtensions = false
        var inRoute = false; var inTrack = false
        var lat = 0.0; var lon = 0.0
        var name = ""; var desc = ""; var proximity = 0.0; var color = ""; var symbol = ""
        var routeName = ""
        var trackName = ""

        while (eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                org.xmlpull.v1.XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "wpt" -> {
                            inWpt = true
                            lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull() ?: 0.0
                            lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull() ?: 0.0
                            name = ""; desc = ""; proximity = 0.0; color = ""; symbol = ""
                        }
                        "rte" -> inRoute = true
                        "rtept" -> {
                            inRtept = true
                            lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull() ?: 0.0
                            lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull() ?: 0.0
                            name = ""; desc = ""; proximity = 0.0; color = ""; symbol = ""
                        }
                        "trk" -> {
                            inTrack = true
                            if (trackPoints.isNotEmpty() &&
                                !(trackPoints.last().first.isNaN() && trackPoints.last().second.isNaN())) {
                                trackPoints.add(Pair(Double.NaN, Double.NaN))
                            }
                        }
                        "trkseg" -> {
                            // Insert NaN marker between tracks/segments (avoid double NaN)
                            if (trackPoints.isNotEmpty() &&
                                !(trackPoints.last().first.isNaN() && trackPoints.last().second.isNaN())) {
                                trackPoints.add(Pair(Double.NaN, Double.NaN))
                            }
                        }
                        "trkpt" -> {
                            lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull() ?: 0.0
                            lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull() ?: 0.0
                        }
                        "extensions" -> if (inWpt || inRtept) inExtensions = true
                        "name" -> when {
                            inWpt || inRtept -> name = parser.nextText()
                            inRoute && routeName.isBlank() -> routeName = parser.nextText()
                            inTrack && trackName.isBlank() -> trackName = parser.nextText()
                        }
                        "desc", "cmt" -> if ((inWpt || inRtept) && desc.isEmpty()) desc = parser.nextText()
                        "proximity" -> if (inWpt || inRtept) proximity = parser.nextText().toDoubleOrNull() ?: 0.0
                        "sym" -> if (inWpt || inRtept) symbol = parser.nextText()
                        "color", "tn:color" -> if ((inWpt || inRtept) && inExtensions) color = parser.nextText()
                    }
                }
                org.xmlpull.v1.XmlPullParser.END_TAG -> {
                    when (parser.name) {
                        "extensions" -> inExtensions = false
                        "rte" -> inRoute = false
                        "wpt" -> {
                            if (lat != 0.0 || lon != 0.0) {
                                wptList.add(Waypoint(
                                    name = name.ifBlank { "WP%02d".format(wptList.size + 1) },
                                    lat = lat, lon = lon,
                                    index = wptList.size + 1, description = desc,
                                    proximity = proximity, color = color, symbol = symbol
                                ))
                            }
                            inWpt = false
                        }
                        "rtept" -> {
                            if (lat != 0.0 || lon != 0.0) {
                                rteptList.add(Waypoint(
                                    name = name.ifBlank { "WP%02d".format(rteptList.size + 1) },
                                    lat = lat, lon = lon,
                                    index = rteptList.size + 1, description = desc,
                                    proximity = proximity, color = color, symbol = symbol
                                ))
                            }
                            inRtept = false
                        }
                        "trkpt" -> {
                            if (lat != 0.0 || lon != 0.0) trackPoints.add(Pair(lat, lon))
                        }
                        "trk" -> inTrack = false
                    }
                }
            }
            eventType = parser.next()
        }
        return GpxResult(
            pointWaypoints = wptList,
            routeWaypoints = rteptList,
            trackPoints = trackPoints,
            routeName = routeName.ifBlank { null },
            trackName = trackName.ifBlank { null }
        )
    }

    /** Parse GPX file — returns list of waypoints (wpt elements) */
    fun parseGpx(inputStream: InputStream): List<Waypoint> {
        val waypoints = mutableListOf<Waypoint>()
        val parser = Xml.newPullParser()
        parser.setInput(inputStream, null)

        var eventType = parser.eventType
        var inWpt = false; var inExtensions = false
        var lat = 0.0; var lon = 0.0
        var name = ""; var desc = ""; var proximity = 0.0; var color = ""; var symbol = ""

        while (eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                org.xmlpull.v1.XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "wpt", "rtept", "trkpt" -> {
                            inWpt = true
                            lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull() ?: 0.0
                            lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull() ?: 0.0
                            name = ""; desc = ""; proximity = 0.0; color = ""; symbol = ""
                        }
                        "extensions" -> if (inWpt) inExtensions = true
                        "name" -> if (inWpt) name = parser.nextText()
                        "desc", "cmt" -> if (inWpt && desc.isEmpty()) desc = parser.nextText()
                        "proximity" -> if (inWpt) proximity = parser.nextText().toDoubleOrNull() ?: 0.0
                        "sym" -> if (inWpt) symbol = parser.nextText()
                        "color" -> if (inWpt && inExtensions) color = parser.nextText()
                    }
                }
                org.xmlpull.v1.XmlPullParser.END_TAG -> {
                    if (parser.name == "extensions") inExtensions = false
                    if (parser.name == "trkpt") { inWpt = false }
                    if (parser.name == "wpt" || parser.name == "rtept") {
                        if (lat != 0.0 || lon != 0.0) {
                            waypoints.add(Waypoint(
                                name = name.ifBlank { "WP%02d".format(waypoints.size + 1) },
                                lat = lat, lon = lon,
                                index = waypoints.size + 1,
                                description = desc,
                                proximity = proximity, color = color, symbol = symbol
                            ))
                        }
                        inWpt = false
                    }
                }
            }
            eventType = parser.next()
        }
        return waypoints
    }

    /** Parse OziExplorer WPT file */
    fun parseWpt(inputStream: InputStream): List<Waypoint> {
        val waypoints = mutableListOf<Waypoint>()
        val lines = inputStream.bufferedReader().readLines()
        // Skip header lines (first 4 lines in OziExplorer format)
        var dataStarted = false
        for (line in lines) {
            if (line.startsWith("Waypoint File") || line.startsWith("WGS 84") ||
                line.startsWith("Reserved") || line.trim().isEmpty()) {
                dataStarted = true; continue
            }
            if (!dataStarted) continue
            val parts = line.split(",")
            if (parts.size < 7) continue
            try {
                val name = parts[1].trim()
                val lat = parts[2].trim().toDoubleOrNull() ?: continue
                val lon = parts[3].trim().toDoubleOrNull() ?: continue
                if (lat == 0.0 && lon == 0.0) continue
                // Ozi WPT field 14 (1-based) = proximity distance, so zero-based index is 13.
                val proximity = parts.getOrNull(13)?.trim()?.toDoubleOrNull() ?: 0.0
                waypoints.add(Waypoint(
                    name = name.ifBlank { "WP%02d".format(waypoints.size + 1) },
                    lat = lat, lon = lon,
                    index = waypoints.size + 1,
                    proximity = proximity
                ))
            } catch (e: Exception) { continue }
        }
        return waypoints
    }

    /** Parse OziExplorer PLT track file — returns track points */
    fun parsePltTrack(inputStream: InputStream): List<Pair<Double, Double>> {
        val points = mutableListOf<Pair<Double, Double>>()
        val lines = inputStream.bufferedReader().readLines()
        var lineNum = 0
        for (line in lines) {
            lineNum++
            if (lineNum <= 6) continue  // skip OziExplorer PLT header
            val parts = line.split(",")
            if (parts.size < 2) continue
            try {
                val lat = parts[0].trim().toDoubleOrNull() ?: continue
                val lon = parts[1].trim().toDoubleOrNull() ?: continue
                if (lat == 0.0 && lon == 0.0) continue
                points.add(Pair(lat, lon))
            } catch (e: Exception) { continue }
        }
        return points
    }

    /** Write track points as GPX string, splitting by NaN markers into separate trkseg */
    fun writeGpx(points: List<Pair<Double, Double>>, name: String = "Трек"): String {
        val safeName = name.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        return buildString {
            appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
            appendLine("<gpx version=\"1.1\" creator=\"TrophyNavigator\" xmlns=\"http://www.topografix.com/GPX/1/1\" xmlns:tn=\"http://trophynav.ru/gpx/1\">")
            appendLine("  <trk><name>$safeName</name>")
            var inSeg = false
            for ((lat, lon) in points) {
                if (lat.isNaN() || lon.isNaN()) {
                    if (inSeg) { appendLine("  </trkseg>"); inSeg = false }
                    continue
                }
                if (!inSeg) { appendLine("  <trkseg>"); inSeg = true }
                appendLine("    <trkpt lat=\"$lat\" lon=\"$lon\"/>")
            }
            if (inSeg) appendLine("  </trkseg>")
            appendLine("  </trk>")
            append("</gpx>")
        }
    }

    fun writeWaypointsGpx(waypoints: List<Waypoint>, name: String = "Маршрут"): String {
        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        return buildString {
            appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
            appendLine("<gpx version=\"1.1\" creator=\"TrophyNavigator\" xmlns=\"http://www.topografix.com/GPX/1/1\" xmlns:tn=\"http://trophynav.ru/gpx/1\">")
            appendLine("  <metadata><name>${esc(name)}</name></metadata>")
            for (wp in waypoints) {
                append("  <wpt lat=\"${wp.lat}\" lon=\"${wp.lon}\"><name>${esc(wp.name)}</name>")
                if (wp.description.isNotBlank()) append("<desc>${esc(wp.description)}</desc>")
                if (wp.symbol.isNotBlank()) append("<sym>${esc(wp.symbol)}</sym>")
                if (wp.proximity > 0) append("<proximity>${wp.proximity}</proximity>")
                if (wp.color.isNotBlank()) append("<extensions><tn:color>${esc(wp.color)}</tn:color></extensions>")
                appendLine("</wpt>")
            }
            append("</gpx>")
        }
    }

    /**
     * Write combined GPX with optional sections: route waypoints, track, user points, recorded track.
     * Each section is included only if list is non-empty.
     */
    fun writeFullGpx(
        routeWaypoints: List<Waypoint>? = null,
        trackPoints: List<Pair<Double, Double>>? = null,
        userPoints: List<Waypoint>? = null,
        recordedTrack: List<Pair<Double, Double>>? = null,
        name: String = "Trophy Navigator Export"
    ): String {
        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        return buildString {
            appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
            appendLine("<gpx version=\"1.1\" creator=\"TrophyNavigator\" xmlns=\"http://www.topografix.com/GPX/1/1\" xmlns:tn=\"http://trophynav.ru/gpx/1\">")
            appendLine("  <metadata><name>${esc(name)}</name><time>${java.time.Instant.now()}</time></metadata>")

            // User waypoints as <wpt>
            userPoints?.forEach { wp ->
                append("  <wpt lat=\"${wp.lat}\" lon=\"${wp.lon}\"><name>${esc(wp.name)}</name>")
                if (wp.description.isNotBlank()) append("<desc>${esc(wp.description)}</desc>")
                if (wp.symbol.isNotBlank()) append("<sym>${esc(wp.symbol)}</sym>")
                if (wp.proximity > 0) append("<proximity>${wp.proximity}</proximity>")
                if (wp.color.isNotBlank()) append("<extensions><tn:color>${esc(wp.color)}</tn:color></extensions>")
                appendLine("</wpt>")
            }

            // Route waypoints as <rte>
            if (!routeWaypoints.isNullOrEmpty()) {
                val routeName = name
                appendLine("  <rte><name>${esc(routeName)}</name>")
                routeWaypoints.forEach { wp ->
                    append("    <rtept lat=\"${wp.lat}\" lon=\"${wp.lon}\"><name>${esc(wp.name)}</name>")
                    if (wp.description.isNotBlank()) append("<desc>${esc(wp.description)}</desc>")
                    if (wp.symbol.isNotBlank()) append("<sym>${esc(wp.symbol)}</sym>")
                    if (wp.proximity > 0) append("<proximity>${wp.proximity}</proximity>")
                    if (wp.color.isNotBlank()) append("<extensions><tn:color>${esc(wp.color)}</tn:color></extensions>")
                    appendLine("</rtept>")
                }
                appendLine("  </rte>")
            }

            // Loaded track as <trk>
            fun writeTrack(pts: List<Pair<Double, Double>>, trkName: String) {
                appendLine("  <trk><name>${esc(trkName)}</name>")
                var inSeg = false
                for ((lat, lon) in pts) {
                    if (lat.isNaN() || lon.isNaN()) {
                        if (inSeg) { appendLine("  </trkseg>"); inSeg = false }
                        continue
                    }
                    if (!inSeg) { appendLine("  <trkseg>"); inSeg = true }
                    appendLine("    <trkpt lat=\"$lat\" lon=\"$lon\"/>")
                }
                if (inSeg) appendLine("  </trkseg>")
                appendLine("  </trk>")
            }

            if (!trackPoints.isNullOrEmpty()) writeTrack(trackPoints, "Загруженный трек")
            if (!recordedTrack.isNullOrEmpty()) writeTrack(recordedTrack, "Записанный трек")

            append("</gpx>")
        }
    }

    /** Parse OziExplorer PLT track file (legacy — returns as waypoints) */
    fun parsePlt(inputStream: InputStream): List<Waypoint> {
        val points = mutableListOf<Waypoint>()
        val lines = inputStream.bufferedReader().readLines()
        var lineNum = 0
        for (line in lines) {
            lineNum++
            if (lineNum <= 6) continue
            val parts = line.split(",")
            if (parts.size < 2) continue
            try {
                val lat = parts[0].trim().toDoubleOrNull() ?: continue
                val lon = parts[1].trim().toDoubleOrNull() ?: continue
                if (lat == 0.0 && lon == 0.0) continue
                points.add(Waypoint("", lat, lon, points.size + 1))
            } catch (e: Exception) { continue }
        }
        return points
    }

    /** Parse OziExplorer RTE file — returns list of waypoints */
    fun parseRteOzi(inputStream: InputStream): List<Waypoint> {
        val waypoints = mutableListOf<Waypoint>()
        val lines = inputStream.bufferedReader().readLines()
        for (line in lines) {
            if (!line.startsWith("W,") && !line.startsWith("W ,")) continue
            val parts = line.split(",")
            if (parts.size < 7) continue
            try {
                // Ozi RTE waypoint record:
                // 1=W, 2=route number, 3=record index, 4=wp number, 5=name, 6=lat, 7=lon
                val lat = parts[5].trim().toDoubleOrNull() ?: continue
                val lon = parts[6].trim().toDoubleOrNull() ?: continue
                if (lat == 0.0 && lon == 0.0) continue
                val name = parts.getOrNull(4)?.trim().orEmpty()
                    .ifBlank { "WP%02d".format(waypoints.size + 1) }
                waypoints.add(Waypoint(
                    name = name, lat = lat, lon = lon,
                    index = waypoints.size + 1
                ))
            } catch (e: Exception) { continue }
        }
        return waypoints
    }
}
