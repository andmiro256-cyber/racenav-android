package com.andreykoff.racenav

import com.mapbox.mapboxsdk.geometry.LatLng

/**
 * Convex Hull — алгоритм Грэхема (Graham Scan), O(n log n).
 * Строит выпуклую оболочку по набору точек.
 */
object ConvexHull {

    fun compute(points: List<LatLng>): List<LatLng> {
        if (points.size < 3) return points

        // Pivot: точка с минимальной широтой (при равенстве — минимальная долгота)
        val pivot = points.minWith(compareBy({ it.latitude }, { it.longitude }))

        // Сортировка по полярному углу относительно pivot
        val sorted = points.filter { it != pivot }.sortedWith(Comparator { a, b ->
            val cross = cross(pivot, a, b)
            if (cross == 0.0) {
                // Коллинеарные — ближайшая первая
                distSq(pivot, a).compareTo(distSq(pivot, b))
            } else {
                -cross.compareTo(0.0) // Против часовой стрелки
            }
        })

        val hull = mutableListOf(pivot)
        for (p in sorted) {
            while (hull.size >= 2 && cross(hull[hull.size - 2], hull[hull.size - 1], p) <= 0) {
                hull.removeAt(hull.size - 1)
            }
            hull.add(p)
        }
        return hull
    }

    /** Cross product (b-a) × (c-a). Positive = counter-clockwise. */
    private fun cross(a: LatLng, b: LatLng, c: LatLng): Double {
        return (b.longitude - a.longitude) * (c.latitude - a.latitude) -
               (b.latitude - a.latitude) * (c.longitude - a.longitude)
    }

    private fun distSq(a: LatLng, b: LatLng): Double {
        val dx = a.longitude - b.longitude
        val dy = a.latitude - b.latitude
        return dx * dx + dy * dy
    }
}

// BoundsRect defined in TileDownloadManager.kt

fun List<LatLng>.toBoundingBox() = BoundsRect(
    north = maxOf { it.latitude },
    south = minOf { it.latitude },
    east = maxOf { it.longitude },
    west = minOf { it.longitude }
)

/** Area of polygon in km² (Shoelace formula with lat/lon → meters) */
fun List<LatLng>.calculateAreaKm2(): Double {
    if (size < 3) return 0.0
    val metersPerDegLat = 111_320.0
    val centerLat = Math.toRadians(sumOf { it.latitude } / size)
    val metersPerDegLon = 111_320.0 * Math.cos(centerLat)

    var area = 0.0
    for (i in indices) {
        val j = (i + 1) % size
        val xi = this[i].longitude * metersPerDegLon
        val yi = this[i].latitude * metersPerDegLat
        val xj = this[j].longitude * metersPerDegLon
        val yj = this[j].latitude * metersPerDegLat
        area += xi * yj - xj * yi
    }
    return Math.abs(area) / 2.0 / 1_000_000.0
}
