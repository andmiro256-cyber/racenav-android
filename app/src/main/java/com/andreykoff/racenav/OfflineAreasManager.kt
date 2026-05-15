package com.andreykoff.racenav

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Manages offline map areas: areas.json registry + file naming.
 *
 * File naming convention:
 *   Base:    {name}.mbtiles
 *   Overlay: {name}_слой_{overlayLabel}.mbtiles
 *
 * areas.json structure:
 * {
 *   "areas": [{
 *     "id": "a1b2c3d4",
 *     "name": "Карелия",
 *     "baseKey": "google_sat",
 *     "baseLabel": "Google Спутник",
 *     "baseFile": "Карелия.mbtiles",
 *     "overlays": [
 *       {"key": "topo_250", "label": "Топо 250м", "file": "Карелия_слой_Топо 250м.mbtiles", "enabled": true}
 *     ],
 *     "minZoom": 10, "maxZoom": 15,
 *     "areaKm2": 4500,
 *     "createdAt": "2026-03-26T..."
 *   }]
 * }
 */
object OfflineAreasManager {

    private const val TAG = "OfflineAreas"
    private const val AREAS_FILE = "areas.json"

    data class OfflineOverlay(
        val key: String,
        val label: String,
        val file: String,
        var enabled: Boolean = true
    )

    data class OfflineArea(
        val id: String,
        val name: String,
        val baseKey: String,
        val baseLabel: String,
        val baseFile: String,
        val overlays: MutableList<OfflineOverlay>,
        val minZoom: Int,
        val maxZoom: Int,
        val areaKm2: Double,
        val createdAt: String,
        val bounds: BoundsRect? = null,
        val polygon: List<Pair<Double, Double>>? = null,
        var status: String = "downloading"  // "downloading" | "paused" | "complete" | "partial"
    ) {
        /** Display string: "Google Sat + Топо + Wiki" */
        fun layersDescription(): String {
            val parts = mutableListOf(baseLabel)
            overlays.forEach { parts.add(it.label) }
            return parts.joinToString(" + ")
        }

        /** Enabled overlays only */
        fun enabledOverlays(): List<OfflineOverlay> = overlays.filter { it.enabled }

        fun hasStoredGeometry(): Boolean = bounds != null
    }

    // ── File naming ──

    fun sanitizeName(name: String): String =
        name.replace(Regex("[/\\\\:*?\"<>|]"), "_").trim()

    fun baseFileName(name: String, context: Context? = null): String {
        val base = "${sanitizeName(name)}.mbtiles"
        return if (context != null) ensureUnique(context, base) else base
    }

    fun overlayFileName(name: String, overlayLabel: String, context: Context? = null): String {
        val base = "${sanitizeName(name)}_слой_${sanitizeName(overlayLabel)}.mbtiles"
        return if (context != null) ensureUnique(context, base) else base
    }

    private fun ensureUnique(context: Context, fileName: String): String {
        val dir = MapStorageManager.getMapsDir(context)
        if (!File(dir, fileName).exists()) return fileName
        val name = fileName.substringBeforeLast(".")
        val ext = fileName.substringAfterLast(".")
        var i = 2
        while (File(dir, "${name}_($i).$ext").exists()) i++
        return "${name}_($i).$ext"
    }

    // ── Registry ──

    private fun getAreasFile(context: Context): File {
        val dir = MapStorageManager.getMapsDir(context)
        return File(dir, AREAS_FILE)
    }

    @Synchronized
    fun loadAreas(context: Context): List<OfflineArea> {
        val file = getAreasFile(context)
        if (!file.exists()) return emptyList()
        return try {
            val json = JSONObject(file.readText())
            val arr = json.optJSONArray("areas") ?: return emptyList()
            (0 until arr.length()).map { parseArea(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load areas: ${e.message}")
            emptyList()
        }
    }

    @Synchronized
    fun saveArea(context: Context, area: OfflineArea) {
        val areas = loadAreas(context).toMutableList()
        // Replace if exists, else add
        val idx = areas.indexOfFirst { it.id == area.id }
        if (idx >= 0) areas[idx] = area else areas.add(area)
        saveAll(context, areas)
    }

    @Synchronized
    fun renameArea(context: Context, areaId: String, newName: String): OfflineArea? {
        val cleanName = sanitizeName(newName).replace(Regex("\\s+"), " ").trim()
        if (cleanName.isBlank() || cleanName.contains("_слой_")) return null

        val areas = loadAreas(context).toMutableList()
        if (areas.any { it.id != areaId && it.name.equals(cleanName, ignoreCase = true) }) return null
        val idx = areas.indexOfFirst { it.id == areaId }
        if (idx < 0) return null

        val renamed = areas[idx].copy(name = cleanName)
        areas[idx] = renamed
        saveAll(context, areas)
        return renamed
    }

    @Synchronized
    fun deleteArea(context: Context, areaId: String, onRemoveOfflineMap: ((String) -> Unit)? = null) {
        val areas = loadAreas(context).toMutableList()
        val area = areas.find { it.id == areaId } ?: return
        val mapsDir = MapStorageManager.getMapsDir(context)
        // Remove from runtime — pass display names matching offlineMaps registration
        val namesToRemove = mutableListOf<String>()
        // Base map was registered with display name containing area name
        namesToRemove.add(area.name)
        // Overlays registered as "{name}_слой_{label}"
        area.overlays.forEach { namesToRemove.add("${area.name}_слой_${it.label}") }
        namesToRemove.forEach { onRemoveOfflineMap?.invoke(it) }
        // Delete files
        File(mapsDir, area.baseFile).delete()
        area.overlays.forEach { File(mapsDir, it.file).delete() }
        areas.removeAll { it.id == areaId }
        saveAll(context, areas)
    }

    @Synchronized
    fun updateOverlayEnabled(context: Context, areaId: String, overlayKey: String, enabled: Boolean) {
        val areas = loadAreas(context).toMutableList()
        val area = areas.find { it.id == areaId } ?: return
        area.overlays.find { it.key == overlayKey }?.enabled = enabled
        saveAll(context, areas)
    }

    @Synchronized
    fun markComplete(context: Context, areaId: String) {
        updateStatus(context, areaId, "complete")
    }

    @Synchronized
    fun markPartial(context: Context, areaId: String) {
        updateStatus(context, areaId, "partial")
    }

    @Synchronized
    fun markPaused(context: Context, areaId: String) {
        updateStatus(context, areaId, "paused")
    }

    @Synchronized
    fun markDownloading(context: Context, areaId: String) {
        updateStatus(context, areaId, "downloading")
    }

    @Synchronized
    fun cleanupIncomplete(context: Context) {
        val areas = loadAreas(context).toMutableList()
        val incomplete = areas.filter { it.status == "downloading" }
        if (incomplete.isEmpty()) return
        var salvaged = 0
        var removed = 0
        incomplete.forEach { area ->
            val files = getAreaFiles(context, area)
            if (files.any(::hasAnyTiles)) {
                area.status = "partial"
                salvaged++
            } else {
                files.forEach { it.delete() }
                areas.removeAll { it.id == area.id }
                removed++
            }
        }
        saveAll(context, areas)
        Log.i(TAG, "Recovered incomplete downloads: partial=$salvaged removed=$removed")
    }

    fun getAreaById(context: Context, id: String): OfflineArea? =
        loadAreas(context).find { it.id == id }

    /** Find area by base file name (for tile server lookup) */
    fun findAreaByBaseFile(context: Context, fileName: String): OfflineArea? =
        loadAreas(context).find { it.baseFile == fileName }

    fun buildDownloadTask(context: Context, area: OfflineArea): DownloadTask? {
        val bounds = area.bounds ?: return null
        val mapsDir = MapStorageManager.getMapsDir(context)
        val layers = mutableListOf(
            LayerDownload(area.baseKey, area.baseLabel, File(mapsDir, area.baseFile).absolutePath)
        )
        area.overlays.forEach { overlay ->
            layers += LayerDownload(overlay.key, overlay.label, File(mapsDir, overlay.file).absolutePath)
        }
        return DownloadTask(area.name, layers, bounds, area.polygon, area.minZoom, area.maxZoom)
    }

    // ── Serialization ──

    private fun saveAll(context: Context, areas: List<OfflineArea>) {
        val arr = JSONArray()
        areas.forEach { arr.put(areaToJson(it)) }
        try {
            val file = getAreasFile(context)
            file.writeText(JSONObject().put("areas", arr).toString(2))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save areas.json: " + e.message)
            try {
                val fallback = java.io.File(context.filesDir, "RaceNav/maps").also { it.mkdirs() }
                java.io.File(fallback, "areas.json").writeText(JSONObject().put("areas", arr).toString(2))
                Log.i(TAG, "Saved areas.json to internal storage fallback")
            } catch (e2: Exception) {
                Log.e(TAG, "Fallback save also failed: " + e2.message)
            }
        }
    }

    private fun areaToJson(a: OfflineArea) = JSONObject().apply {
        put("id", a.id)
        put("name", a.name)
        put("baseKey", a.baseKey)
        put("baseLabel", a.baseLabel)
        put("baseFile", a.baseFile)
        put("minZoom", a.minZoom)
        put("maxZoom", a.maxZoom)
        put("areaKm2", a.areaKm2)
        put("createdAt", a.createdAt)
        put("status", a.status)
        a.bounds?.let { bounds ->
            put("bounds", JSONObject().apply {
                put("north", bounds.north)
                put("south", bounds.south)
                put("east", bounds.east)
                put("west", bounds.west)
            })
        }
        a.polygon?.takeIf { it.isNotEmpty() }?.let { polygon ->
            put("polygon", JSONArray().apply {
                polygon.forEach { (lat, lon) ->
                    put(JSONArray().put(lat).put(lon))
                }
            })
        }
        put("overlays", JSONArray().apply {
            a.overlays.forEach { ov ->
                put(JSONObject().apply {
                    put("key", ov.key)
                    put("label", ov.label)
                    put("file", ov.file)
                    put("enabled", ov.enabled)
                })
            }
        })
    }

    private fun parseArea(j: JSONObject): OfflineArea {
        val overlays = mutableListOf<OfflineOverlay>()
        val ovArr = j.optJSONArray("overlays")
        if (ovArr != null) {
            for (i in 0 until ovArr.length()) {
                val o = ovArr.getJSONObject(i)
                overlays.add(OfflineOverlay(
                    key = o.getString("key"),
                    label = o.getString("label"),
                    file = o.getString("file"),
                    enabled = o.optBoolean("enabled", true)
                ))
            }
        }
        val bounds = j.optJSONObject("bounds")?.let { b ->
            BoundsRect(
                north = b.optDouble("north"),
                south = b.optDouble("south"),
                east = b.optDouble("east"),
                west = b.optDouble("west")
            )
        }
        val polygon = j.optJSONArray("polygon")?.let { arr ->
            buildList {
                for (i in 0 until arr.length()) {
                    val point = arr.optJSONArray(i) ?: continue
                    add(point.optDouble(0) to point.optDouble(1))
                }
            }.takeIf { it.isNotEmpty() }
        }
        return OfflineArea(
            id = j.getString("id"),
            name = j.getString("name"),
            baseKey = j.getString("baseKey"),
            baseLabel = j.getString("baseLabel"),
            baseFile = j.getString("baseFile"),
            overlays = overlays,
            minZoom = j.optInt("minZoom", 10),
            maxZoom = j.optInt("maxZoom", 15),
            areaKm2 = j.optDouble("areaKm2", 0.0),
            createdAt = j.optString("createdAt", ""),
            bounds = bounds,
            polygon = polygon,
            status = j.optString("status", "complete")
        )
    }

    private fun updateStatus(context: Context, areaId: String, status: String) {
        val areas = loadAreas(context).toMutableList()
        areas.find { it.id == areaId }?.status = status
        saveAll(context, areas)
    }

    private fun getAreaFiles(context: Context, area: OfflineArea): List<File> {
        val mapsDir = MapStorageManager.getMapsDir(context)
        return buildList {
            add(File(mapsDir, area.baseFile))
            area.overlays.forEach { add(File(mapsDir, it.file)) }
        }
    }

    private fun hasAnyTiles(file: File): Boolean {
        if (!file.exists() || file.length() <= 0L) return false
        return try {
            val db = android.database.sqlite.SQLiteDatabase.openDatabase(
                file.absolutePath, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY
            )
            try {
                db.rawQuery("SELECT 1 FROM tiles LIMIT 1", null).use { it.moveToFirst() }
            } finally {
                db.close()
            }
        } catch (_: Exception) {
            false
        }
    }
}
