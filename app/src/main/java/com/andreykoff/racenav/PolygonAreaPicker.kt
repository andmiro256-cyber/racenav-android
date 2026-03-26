package com.andreykoff.racenav

import com.mapbox.mapboxsdk.maps.MapboxMap
import com.mapbox.mapboxsdk.maps.Style
import com.mapbox.mapboxsdk.geometry.LatLng
import com.mapbox.mapboxsdk.style.layers.*
import com.mapbox.mapboxsdk.style.sources.GeoJsonSource
import org.json.JSONArray
import org.json.JSONObject

/**
 * Режим рисования полигона для выбора области скачивания карт.
 * 3-8 точек → convex hull → bounding box.
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

    fun finish(): PolygonArea? {
        if (!canFinish()) return null
        val hull = ConvexHull.compute(points)
        val bbox = hull.toBoundingBox()
        val areaKm2 = hull.calculateAreaKm2()
        return PolygonArea(hull, bbox, areaKm2)
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
            val hull = ConvexHull.compute(points)
            val coords = JSONArray()
            for (pt in hull) {
                coords.put(JSONArray().put(pt.longitude).put(pt.latitude))
            }
            // Close polygon
            coords.put(JSONArray().put(hull[0].longitude).put(hull[0].latitude))

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
}

data class PolygonArea(
    val polygon: List<LatLng>,
    val boundingBox: BoundsRect,
    val areaKm2: Double
)
