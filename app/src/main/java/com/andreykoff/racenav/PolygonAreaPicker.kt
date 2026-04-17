package com.andreykoff.racenav

import android.graphics.PointF
import com.mapbox.mapboxsdk.maps.MapboxMap
import com.mapbox.mapboxsdk.maps.Style
import com.mapbox.mapboxsdk.geometry.LatLng
import com.mapbox.mapboxsdk.style.layers.*
import com.mapbox.mapboxsdk.style.sources.GeoJsonSource
import org.json.JSONArray
import org.json.JSONObject

/**
 * Режим рисования полигона для выбора области скачивания карт.
 * 3-8 точек → пользовательский полигон → bounding box.
 */
class PolygonAreaPicker(
    private val map: MapboxMap
) {
    companion object {
        const val MAX_POINTS = 8
        const val MIN_POINTS = 3
        const val SOURCE_POLYGON = "download_polygon_source"
        const val SOURCE_POINTS = "download_points_source"
        const val LAYER_FILL = "download_polygon_fill"
        const val LAYER_LINE = "download_polygon_line"
        const val LAYER_POINTS = "download_polygon_points"
    }

    val points = mutableListOf<LatLng>()
    var isActive = false
        private set

    fun start(style: Style) {
        isActive = true
        points.clear()

        // Add sources
        if (style.getSource(SOURCE_POLYGON) == null) {
            style.addSource(GeoJsonSource(SOURCE_POLYGON))
        }
        if (style.getSource(SOURCE_POINTS) == null) {
            style.addSource(GeoJsonSource(SOURCE_POINTS))
        }

        // Fill layer — полупрозрачная заливка
        if (style.getLayer(LAYER_FILL) == null) {
            style.addLayer(FillLayer(LAYER_FILL, SOURCE_POLYGON).withProperties(
                PropertyFactory.fillColor("#FF9800"),
                PropertyFactory.fillOpacity(0.2f)
            ))
        }

        // Line layer — граница
        if (style.getLayer(LAYER_LINE) == null) {
            style.addLayer(LineLayer(LAYER_LINE, SOURCE_POLYGON).withProperties(
                PropertyFactory.lineColor("#FF9800"),
                PropertyFactory.lineWidth(2.5f)
            ))
        }

        // Circle layer — точки
        if (style.getLayer(LAYER_POINTS) == null) {
            style.addLayer(CircleLayer(LAYER_POINTS, SOURCE_POINTS).withProperties(
                PropertyFactory.circleRadius(7f),
                PropertyFactory.circleColor("#FFFFFF"),
                PropertyFactory.circleStrokeColor("#FF9800"),
                PropertyFactory.circleStrokeWidth(2.5f)
            ))
        }

        updateLayers()
    }

    fun addPoint(latLng: LatLng): Boolean {
        if (points.size >= MAX_POINTS) return false
        points.add(latLng)
        updateLayers()
        return true
    }

    fun removeLastPoint(): Boolean {
        if (points.isEmpty()) return false
        points.removeAt(points.size - 1)
        updateLayers()
        return true
    }

    fun canFinish(): Boolean = points.size >= MIN_POINTS

    fun movePoint(index: Int, latLng: LatLng): Boolean {
        if (index !in points.indices) return false
        points[index] = latLng
        updateLayers()
        return true
    }

    fun findNearestPointIndex(screenPoint: PointF, thresholdPx: Float): Int {
        if (points.isEmpty()) return -1
        val thresholdSq = thresholdPx * thresholdPx
        var bestIndex = -1
        var bestDistSq = Float.MAX_VALUE
        points.forEachIndexed { index, point ->
            val pt = map.projection.toScreenLocation(point)
            val dx = pt.x - screenPoint.x
            val dy = pt.y - screenPoint.y
            val distSq = dx * dx + dy * dy
            if (distSq <= thresholdSq && distSq < bestDistSq) {
                bestDistSq = distSq
                bestIndex = index
            }
        }
        return bestIndex
    }

    fun previewAreaKm2(): Double {
        if (points.size < MIN_POINTS) return 0.0
        return points.calculateAreaKm2()
    }

    fun finish(): PolygonArea? {
        if (!canFinish()) return null
        val polygon = points.toList()
        val areaKm2 = polygon.calculateAreaKm2()
        if (areaKm2 <= 0.000001) return null
        if (hasSelfIntersection(polygon)) return null
        val bbox = polygon.toBoundingBox()
        return PolygonArea(polygon, bbox, areaKm2)
    }

    fun stop() {
        isActive = false
        points.clear()
        val style = map.style ?: return
        // Remove layers and sources
        style.removeLayer(LAYER_FILL)
        style.removeLayer(LAYER_LINE)
        style.removeLayer(LAYER_POINTS)
        style.removeSource(SOURCE_POLYGON)
        style.removeSource(SOURCE_POINTS)
    }

    private fun updateLayers() {
        val style = map.style ?: return

        // Update points source
        val pointFeatures = JSONArray()
        for (pt in points) {
            pointFeatures.put(JSONObject()
                .put("type", "Feature")
                .put("geometry", JSONObject()
                    .put("type", "Point")
                    .put("coordinates", JSONArray().put(pt.longitude).put(pt.latitude)))
                .put("properties", JSONObject()))
        }
        style.getSourceAs<GeoJsonSource>(SOURCE_POINTS)?.setGeoJson(
            JSONObject().put("type", "FeatureCollection").put("features", pointFeatures).toString()
        )

        // Update polygon source
        if (points.size >= 3) {
            val coords = JSONArray()
            for (pt in points) {
                coords.put(JSONArray().put(pt.longitude).put(pt.latitude))
            }
            // Close polygon
            coords.put(JSONArray().put(points[0].longitude).put(points[0].latitude))

            val polygon = JSONObject()
                .put("type", "Feature")
                .put("geometry", JSONObject()
                    .put("type", "Polygon")
                    .put("coordinates", JSONArray().put(coords)))
                .put("properties", JSONObject())

            style.getSourceAs<GeoJsonSource>(SOURCE_POLYGON)?.setGeoJson(polygon.toString())
        } else if (points.size == 2) {
            // Draw line between 2 points
            val coords = JSONArray()
            coords.put(JSONArray().put(points[0].longitude).put(points[0].latitude))
            coords.put(JSONArray().put(points[1].longitude).put(points[1].latitude))
            val line = JSONObject()
                .put("type", "Feature")
                .put("geometry", JSONObject()
                    .put("type", "LineString")
                    .put("coordinates", coords))
                .put("properties", JSONObject())
            style.getSourceAs<GeoJsonSource>(SOURCE_POLYGON)?.setGeoJson(line.toString())
        } else {
            style.getSourceAs<GeoJsonSource>(SOURCE_POLYGON)?.setGeoJson(
                JSONObject().put("type", "FeatureCollection").put("features", JSONArray()).toString()
            )
        }
    }

    private fun hasSelfIntersection(vertices: List<LatLng>): Boolean {
        if (vertices.size < 4) return false
        for (i in vertices.indices) {
            val a1 = vertices[i]
            val a2 = vertices[(i + 1) % vertices.size]
            for (j in i + 1 until vertices.size) {
                if (kotlin.math.abs(i - j) <= 1) continue
                if (i == 0 && j == vertices.lastIndex) continue
                val b1 = vertices[j]
                val b2 = vertices[(j + 1) % vertices.size]
                if (segmentsIntersect(a1, a2, b1, b2)) return true
            }
        }
        return false
    }

    private fun segmentsIntersect(a1: LatLng, a2: LatLng, b1: LatLng, b2: LatLng): Boolean {
        val d1 = direction(a1, a2, b1)
        val d2 = direction(a1, a2, b2)
        val d3 = direction(b1, b2, a1)
        val d4 = direction(b1, b2, a2)
        if ((d1 > 0 && d2 < 0 || d1 < 0 && d2 > 0) &&
            (d3 > 0 && d4 < 0 || d3 < 0 && d4 > 0)) {
            return true
        }
        return d1 == 0.0 && onSegment(a1, a2, b1) ||
            d2 == 0.0 && onSegment(a1, a2, b2) ||
            d3 == 0.0 && onSegment(b1, b2, a1) ||
            d4 == 0.0 && onSegment(b1, b2, a2)
    }

    private fun direction(a: LatLng, b: LatLng, c: LatLng): Double {
        return (b.longitude - a.longitude) * (c.latitude - a.latitude) -
            (b.latitude - a.latitude) * (c.longitude - a.longitude)
    }

    private fun onSegment(a: LatLng, b: LatLng, c: LatLng): Boolean {
        return c.longitude in minOf(a.longitude, b.longitude)..maxOf(a.longitude, b.longitude) &&
            c.latitude in minOf(a.latitude, b.latitude)..maxOf(a.latitude, b.latitude)
    }
}

data class PolygonArea(
    val polygon: List<LatLng>,
    val boundingBox: BoundsRect,
    val areaKm2: Double
)
