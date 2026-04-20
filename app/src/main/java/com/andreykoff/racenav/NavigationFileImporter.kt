package com.andreykoff.racenav

data class ParsedNavigationFile(
    val pointWaypoints: List<Waypoint> = emptyList(),
    val routeWaypoints: List<Waypoint> = emptyList(),
    val trackPoints: List<Pair<Double, Double>> = emptyList(),
    val routeName: String? = null,
    val trackName: String? = null
) {
    val hasPointSet: Boolean get() = pointWaypoints.isNotEmpty()
    val hasRoute: Boolean get() = routeWaypoints.isNotEmpty()
    val hasTrack: Boolean get() = trackPoints.any { !it.first.isNaN() && !it.second.isNaN() }
    val hasAnything: Boolean get() = hasPointSet || hasRoute || hasTrack
}

object NavigationFileImporter {

    fun parse(fileName: String, bytes: ByteArray): ParsedNavigationFile {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "gpx" -> parseGpx(bytes)
            "wpt" -> ParsedNavigationFile(pointWaypoints = GpxParser.parseWpt(bytes.inputStream()))
            "rte" -> parseRte(bytes)
            "plt" -> ParsedNavigationFile(trackPoints = GpxParser.parsePltTrack(bytes.inputStream()))
            else -> runCatching { parseGpx(bytes) }.getOrDefault(ParsedNavigationFile())
        }
    }

    private fun parseGpx(bytes: ByteArray): ParsedNavigationFile {
        val result = GpxParser.parseGpxFull(bytes.inputStream())
        return ParsedNavigationFile(
            pointWaypoints = result.pointWaypoints,
            routeWaypoints = result.routeWaypoints,
            trackPoints = result.trackPoints,
            routeName = result.routeName,
            trackName = result.trackName
        )
    }

    private fun parseRte(bytes: ByteArray): ParsedNavigationFile {
        val gpxResult = runCatching { GpxParser.parseGpxFull(bytes.inputStream()) }.getOrNull()
        if (gpxResult != null && (gpxResult.routeWaypoints.isNotEmpty() || gpxResult.pointWaypoints.isNotEmpty() || gpxResult.trackPoints.isNotEmpty())) {
            return ParsedNavigationFile(
                pointWaypoints = gpxResult.pointWaypoints,
                routeWaypoints = gpxResult.routeWaypoints,
                trackPoints = gpxResult.trackPoints,
                routeName = gpxResult.routeName,
                trackName = gpxResult.trackName
            )
        }
        return ParsedNavigationFile(routeWaypoints = GpxParser.parseRteOzi(bytes.inputStream()))
    }
}
