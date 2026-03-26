package com.andreykoff.racenav

import kotlin.math.*

/**
 * Оценка количества тайлов и размера загрузки для заданной области и слоёв.
 */
object SizeEstimator {

    const val BASE_TILE_BYTES = 25_000L    // спутник ~25 КБ
    const val OVERLAY_TILE_BYTES = 8_000L  // векторный оверлей ~8 КБ
    const val WARNING_THRESHOLD_MB = 500.0

    fun estimate(
        bounds: BoundsRect,
        baseLayers: List<String>,
        overlayLayers: List<String>,
        minZoom: Int,
        maxZoom: Int,
        polygon: List<Pair<Double, Double>>? = null
    ): SizeEstimate {
        var totalTiles = 0L
        var totalBytes = 0L
        val perLayer = mutableMapOf<String, LayerEstimate>()

        for (zoom in minZoom..maxZoom) {
            val n = if (polygon != null && polygon.size >= 3)
                countTilesInPolygon(bounds, zoom, polygon)
            else countTilesAtZoom(bounds, zoom)

            for (key in baseLayers) {
                val bytes = n * BASE_TILE_BYTES
                val est = perLayer.getOrPut(key) { LayerEstimate() }
                est.tiles += n; est.bytes += bytes
                totalTiles += n; totalBytes += bytes
            }

            for (key in overlayLayers) {
                val bytes = n * OVERLAY_TILE_BYTES
                val est = perLayer.getOrPut(key) { LayerEstimate() }
                est.tiles += n; est.bytes += bytes
                totalTiles += n; totalBytes += bytes
            }
        }
        return SizeEstimate(totalTiles, totalBytes, perLayer)
    }

    fun countTilesAtZoom(bounds: BoundsRect, zoom: Int): Long {
        val x1 = lonToTileX(bounds.west, zoom)
        val x2 = lonToTileX(bounds.east, zoom)
        val y1 = latToTileY(bounds.north, zoom)
        val y2 = latToTileY(bounds.south, zoom)
        val maxTile = 1 shl zoom
        // Handle antimeridian crossing (west > east in tile coords)
        val xCount = if (x2 >= x1) (x2 - x1 + 1).toLong() else (maxTile - x1 + x2 + 1).toLong()
        val yCount = (y2 - y1 + 1).toLong().coerceAtLeast(0)
        return xCount * yCount
    }

    fun countTilesInPolygon(bounds: BoundsRect, zoom: Int, polygon: List<Pair<Double, Double>>): Long {
        val x1 = lonToTileX(bounds.west, zoom)
        val x2 = lonToTileX(bounds.east, zoom)
        val y1 = latToTileY(bounds.north, zoom)
        val y2 = latToTileY(bounds.south, zoom)
        var count = 0L
        for (x in x1..x2) {
            for (y in y1..y2) {
                val centerLat = tileCenterLat(y, zoom)
                val centerLon = tileCenterLon(x, zoom)
                if (pointInPolygon(centerLat, centerLon, polygon)) count++
            }
        }
        return count
    }

    private fun pointInPolygon(lat: Double, lon: Double, polygon: List<Pair<Double, Double>>): Boolean {
        var inside = false
        var j = polygon.size - 1
        for (i in polygon.indices) {
            val yi = polygon[i].first; val xi = polygon[i].second
            val yj = polygon[j].first; val xj = polygon[j].second
            if ((yi > lat) != (yj > lat) && lon < (xj - xi) * (lat - yi) / (yj - yi) + xi) inside = !inside
            j = i
        }
        return inside
    }

    private fun tileCenterLat(y: Int, z: Int): Double {
        val n = Math.PI - 2.0 * Math.PI * (y.toDouble() + 0.5) / (1 shl z)
        return Math.toDegrees(Math.atan(Math.sinh(n)))
    }

    private fun tileCenterLon(x: Int, z: Int): Double {
        return (x.toDouble() + 0.5) / (1 shl z) * 360.0 - 180.0
    }

    fun lonToTileX(lon: Double, zoom: Int): Int {
        return ((lon + 180.0) / 360.0 * (1 shl zoom)).toInt()
    }

    fun latToTileY(lat: Double, zoom: Int): Int {
        val latRad = Math.toRadians(lat.coerceIn(-85.051129, 85.051129))
        return ((1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * (1 shl zoom)).toInt()
    }
}

data class SizeEstimate(
    val totalTiles: Long,
    val totalBytes: Long,
    val perLayer: Map<String, LayerEstimate>
) {
    val totalMB: Double get() = totalBytes / 1_048_576.0
    val isLarge: Boolean get() = totalMB > SizeEstimator.WARNING_THRESHOLD_MB

    fun formatSize(): String = when {
        totalMB >= 1024 -> String.format("%.1f ГБ", totalMB / 1024)
        totalMB >= 1 -> String.format("%.0f МБ", totalMB)
        else -> String.format("%.0f КБ", totalBytes / 1024.0)
    }
}

data class LayerEstimate(var tiles: Long = 0, var bytes: Long = 0)
