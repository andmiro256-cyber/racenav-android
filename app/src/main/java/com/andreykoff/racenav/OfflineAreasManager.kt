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
        var status: String = "downloading"  // "downloading" | "complete"
    ) {
        /** Display string: "Google Sat + Топо + Wiki" */
        fun layersDescription(): String {
            val parts = mutableListOf(baseLabel)
            overlays.forEach { parts.add(it.label) }
            return parts.joinToString(" + ")
        }

        /** Enabled overlays only */
        fun enabledOverlays(): List<OfflineOverlay> = overlays.filter { it.enabled }
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
        val dir = MapFragment.getRaceNavDir(context, "maps")
        if (!File(dir, fileName).exists()) return fileName
        val name = fileName.substringBeforeLast(".")
        val ext = fileName.substringAfterLast(".")
        var i = 2
        while (File(dir, "${name}_($i).$ext").exists()) i++
        return "${name}_($i).$ext"
    }

    // ── Registry ──

    private fun getAreasFile(context: Context): File {
        val dir = MapFragment.getRaceNavDir(context, "maps")
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
    fun deleteArea(context: Context, areaId: String, onRemoveOfflineMap: ((String) -> Unit)? = null) {
        val areas = loadAreas(context).toMutableList()
        val area = areas.find { it.id == areaId } ?: return
        val mapsDir = MapFragment.getRaceNavDir(context, "maps")
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
        val areas = loadAreas(context).toMutableList()
        areas.find { it.id == areaId }?.status = "complete"
        saveAll(context, areas)
    }

    /** Remove areas stuck in "downloading" (cleanup on startup) */
    @Synchronized
    fun cleanupIncomplete(context: Context) {
        val areas = loadAreas(context).toMutableList()
        val incomplete = areas.filter { it.status == "downloading" }
        if (incomplete.isEmpty()) return
        val mapsDir = MapFragment.getRaceNavDir(context, "maps")
        incomplete.forEach { a ->
            File(mapsDir, a.baseFile).delete()
            a.overlays.forEach { File(mapsDir, it.file).delete() }
        }
        areas.removeAll { it.status == "downloading" }
        saveAll(context, areas)
        Log.i(TAG, "Cleaned ${incomplete.size} incomplete downloads")
    }

    fun getAreaById(context: Context, id: String): OfflineArea? =
        loadAreas(context).find { it.id == id }

    /** Find area by base file name (for tile server lookup) */
    fun findAreaByBaseFile(context: Context, fileName: String): OfflineArea? =
        loadAreas(context).find { it.baseFile == fileName }

    // ── Serialization ──

    private fun saveAll(context: Context, areas: List<OfflineArea>) {
        val file = getAreasFile(context)
        val arr = JSONArray()
        areas.forEach { arr.put(areaToJson(it)) }
        file.writeText(JSONObject().put("areas", arr).toString(2))
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
            status = j.optString("status", "complete")
        )
    }
}
