package com.andreykoff.racenav

import android.util.Xml
import java.io.InputStream

data class Waypoint(
    val name: String,
    val lat: Double,
    val lon: Double,
    val index: Int,
    val description: String = ""
)

data class GpxResult(
    val waypoints: List<Waypoint> = emptyList(),
    val trackPoints: List<Pair<Double, Double>> = emptyList()
)

object GpxParser {

    /** Parse GPX file — returns both waypoints (wpt/rtept) and track points (trkpt) */
    fun parseGpxFull(inputStream: InputStream): GpxResult {
        val waypoints = mutableListOf<Waypoint>()
        val trackPoints = mutableListOf<Pair<Double, Double>>()
        val parser = Xml.newPullParser()
        parser.setInput(inputStream, null)

        var eventType = parser.eventType
        var inWpt = false; var inTrkpt = false
        var lat = 0.0; var lon = 0.0
        var name = ""; var desc = ""

        while (eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                org.xmlpull.v1.XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "wpt", "rtept" -> {
                            inWpt = true
                            lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull() ?: 0.0
                            lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull() ?: 0.0
                            name = ""; desc = ""
                        }
                        "trkpt" -> {
                            inTrkpt = true
                            lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull() ?: 0.0
                            lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull() ?: 0.0
                        }
                        "name" -> if (inWpt) name = parser.nextText()
                        "desc", "cmt" -> if (inWpt && desc.isEmpty()) desc = parser.nextText()
                    }
                }
                org.xmlpull.v1.XmlPullParser.END_TAG -> {
                    when (parser.name) {
                        "wpt", "rtept" -> {
                            if (lat != 0.0 || lon != 0.0) {
                                waypoints.add(Waypoint(
                                    name = name.ifBlank { "КП ${waypoints.size + 1}" },
                                    lat = lat, lon = lon,
                                    index = waypoints.size + 1, description = desc
                                ))
                            }
                            inWpt = false
                        }
                        "trkpt" -> {
                            if (lat != 0.0 || lon != 0.0) trackPoints.add(Pair(lat, lon))
                            inTrkpt = false
                        }
                    }
                }
            }
            eventType = parser.next()
        }
        return GpxResult(waypoints, trackPoints)
    }

    /** Parse GPX file — returns list of waypoints (wpt elements) */
    fun parseGpx(inputStream: InputStream): List<Waypoint> {
        val waypoints = mutableListOf<Waypoint>()
        val parser = Xml.newPullParser()
        parser.setInput(inputStream, null)

        var eventType = parser.eventType
        var inWpt = false
        var lat = 0.0; var lon = 0.0
        var name = ""; var desc = ""

        while (eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                org.xmlpull.v1.XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "wpt", "rtept", "trkpt" -> {
                            inWpt = true
                            lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull() ?: 0.0
                            lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull() ?: 0.0
                            name = ""; desc = ""
                        }
                        "name" -> if (inWpt) name = parser.nextText()
                        "desc", "cmt" -> if (inWpt && desc.isEmpty()) desc = parser.nextText()
                    }
                }
                org.xmlpull.v1.XmlPullParser.END_TAG -> {
                    if (parser.name == "wpt" || parser.name == "rtept") {
                        if (lat != 0.0 || lon != 0.0) {
                            waypoints.add(Waypoint(
                                name = name.ifBlank { "КП ${waypoints.size + 1}" },
                                lat = lat, lon = lon,
                                index = waypoints.size + 1,
                                description = desc
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
                waypoints.add(Waypoint(
                    name = name.ifBlank { "КП ${waypoints.size + 1}" },
                    lat = lat, lon = lon,
                    index = waypoints.size + 1
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

    /** Write track points as GPX string */
    fun writeGpx(points: List<Pair<Double, Double>>, name: String = "Трек"): String {
        val safeName = name.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        return buildString {
            appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
            appendLine("<gpx version=\"1.1\" creator=\"RaceNav\" xmlns=\"http://www.topografix.com/GPX/1/1\">")
            appendLine("  <trk><name>$safeName</name><trkseg>")
            for ((lat, lon) in points) appendLine("    <trkpt lat=\"$lat\" lon=\"$lon\"/>")
            appendLine("  </trkseg></trk>")
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
            if (parts.size < 5) continue
            try {
                val lat = parts[3].trim().toDoubleOrNull() ?: continue
                val lon = parts[4].trim().toDoubleOrNull() ?: continue
                if (lat == 0.0 && lon == 0.0) continue
                // Name is usually at index 10 or 8, try both
                val name = (parts.getOrNull(10) ?: parts.getOrNull(8) ?: "").trim()
                    .ifBlank { "КП ${waypoints.size + 1}" }
                waypoints.add(Waypoint(
                    name = name, lat = lat, lon = lon,
                    index = waypoints.size + 1
                ))
            } catch (e: Exception) { continue }
        }
        return waypoints
    }
}
