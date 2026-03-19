package com.andreykoff.racenav

import kotlin.math.*

object TrackEditor {

    data class TrackPoint(val lat: Double, val lon: Double)

    val editPoints: MutableList<TrackPoint> = mutableListOf()
    var selectedIndex: Int = -1
    private val undoStack: ArrayDeque<List<TrackPoint>> = ArrayDeque()

    fun load(points: List<TrackPoint>) {
        editPoints.clear()
        editPoints.addAll(points)
        selectedIndex = -1
        undoStack.clear()
    }

    fun isEmpty() = editPoints.isEmpty()

    fun canUndo() = undoStack.isNotEmpty()

    private fun pushUndo() {
        undoStack.addLast(editPoints.toList())
        if (undoStack.size > 20) undoStack.removeFirst()
    }

    fun undo(): Boolean {
        val prev = undoStack.removeLastOrNull() ?: return false
        editPoints.clear()
        editPoints.addAll(prev)
        if (selectedIndex >= editPoints.size) selectedIndex = -1
        return true
    }

    fun deletePoint(index: Int) {
        if (index !in editPoints.indices) return
        pushUndo()
        editPoints.removeAt(index)
        when {
            selectedIndex == index -> selectedIndex = -1
            selectedIndex > index -> selectedIndex--
        }
    }

    fun movePoint(index: Int, lat: Double, lon: Double) {
        if (index !in editPoints.indices) return
        pushUndo()
        editPoints[index] = editPoints[index].copy(lat = lat, lon = lon)
    }

    fun trimFromStart(index: Int) {
        if (index <= 0 || index >= editPoints.size) return
        pushUndo()
        editPoints.subList(0, index).clear()
        selectedIndex = -1
    }

    fun trimFromEnd(index: Int) {
        if (index < 0 || index >= editPoints.size - 1) return
        pushUndo()
        while (editPoints.size > index + 1) editPoints.removeAt(editPoints.size - 1)
        selectedIndex = -1
    }

    fun reverse() {
        pushUndo()
        editPoints.reverse()
        selectedIndex = -1
    }

    /** Douglas-Peucker simplification. Returns number of removed points. */
    fun simplify(toleranceMeters: Double): Int {
        val before = editPoints.size
        if (before < 3) return 0
        pushUndo()
        val simplified = douglasPeucker(editPoints.toList(), toleranceMeters)
        editPoints.clear()
        editPoints.addAll(simplified)
        selectedIndex = -1
        return before - editPoints.size
    }

    /** Preview: how many points would remain after simplify (without modifying state) */
    fun simplifyPreview(toleranceMeters: Double): Int {
        if (editPoints.size < 3) return editPoints.size
        return douglasPeucker(editPoints.toList(), toleranceMeters).size
    }

    fun totalDistanceM(): Double {
        var dist = 0.0
        for (i in 1 until editPoints.size) {
            dist += haversineM(editPoints[i - 1].lat, editPoints[i - 1].lon,
                editPoints[i].lat, editPoints[i].lon)
        }
        return dist
    }

    private fun douglasPeucker(points: List<TrackPoint>, epsilon: Double): List<TrackPoint> {
        if (points.size < 3) return points
        var maxDist = 0.0
        var maxIdx = 0
        for (i in 1 until points.size - 1) {
            val d = perpendicularDistanceM(points[i], points.first(), points.last())
            if (d > maxDist) { maxDist = d; maxIdx = i }
        }
        return if (maxDist > epsilon) {
            val left = douglasPeucker(points.subList(0, maxIdx + 1), epsilon)
            val right = douglasPeucker(points.subList(maxIdx, points.size), epsilon)
            left.dropLast(1) + right
        } else {
            listOf(points.first(), points.last())
        }
    }

    private fun perpendicularDistanceM(p: TrackPoint, a: TrackPoint, b: TrackPoint): Double {
        val cosLat = cos(Math.toRadians((a.lat + b.lat) / 2))
        val ax = a.lon * cosLat; val ay = a.lat
        val bx = b.lon * cosLat; val by = b.lat
        val px = p.lon * cosLat; val py = p.lat
        val dx = bx - ax; val dy = by - ay
        val lenSq = dx * dx + dy * dy
        if (lenSq < 1e-20) return haversineM(p.lat, p.lon, a.lat, a.lon)
        val t = ((px - ax) * dx + (py - ay) * dy) / lenSq
        val tc = t.coerceIn(0.0, 1.0)
        val closeLat = ay + tc * dy
        val closeLon = (ax + tc * dx) / cosLat
        return haversineM(p.lat, p.lon, closeLat, closeLon)
    }

    private fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return R * 2 * asin(sqrt(a))
    }
}
