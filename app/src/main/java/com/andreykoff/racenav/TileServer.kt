package com.andreykoff.racenav

import android.graphics.Bitmap
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import android.util.LruCache
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

class TileServer(port: Int) : NanoHTTPD(port) {

    enum class DbFormat { UNKNOWN, RMAPS, MBTILES }

    private data class DbEntry(
        val db: SQLiteDatabase,
        val format: DbFormat,
        val path: String,
        val fileStamp: Long,
        @Volatile var workingFormula: Pair<Boolean, Boolean>? = null,
        @Volatile var rmapsInverted: Boolean? = null  // cached result of detectRMapsScheme
    )

    private val databases = mutableMapOf<Int, DbEntry>()

    // LRU tile cache: 24MB max (tiles are ~25KB avg, so ~960 tiles)
    private val tileCache = object : LruCache<String, ByteArray>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ByteArray): Int = value.size
    }

    private fun computeFileStamp(path: String): Long {
        val file = File(path)
        return (file.lastModified() shl 1) xor file.length()
    }

    private fun clearTileCache() {
        tileCache.evictAll()
    }

    /** Open SQLite file at given index. Returns true on success. */
    fun openDatabase(index: Int, path: String): Boolean {
        return try {
            val normalizedPath = File(path).absolutePath
            val fileStamp = computeFileStamp(normalizedPath)
            databases[index]?.let { existing ->
                if (existing.path == normalizedPath && existing.fileStamp == fileStamp) {
                    return true
                }
                try { existing.db.close() } catch (_: Exception) {}
                databases.remove(index)
                clearTileCache()
            }
            val opened = SQLiteDatabase.openDatabase(normalizedPath, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS)

            // Performance: WAL for concurrent reads + larger cache + mmap
            try {
                opened.rawQuery("PRAGMA journal_mode=WAL", null).close()
                opened.rawQuery("PRAGMA cache_size=-4096", null).close()  // 4MB page cache
                opened.rawQuery("PRAGMA mmap_size=268435456", null).close()  // 256MB mmap
            } catch (_: Exception) {}

            val tables = mutableSetOf<String>()
            opened.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null).use { c ->
                while (c.moveToNext()) tables.add(c.getString(0).lowercase())
            }

            if ("tiles" !in tables) {
                Log.w(TAG, "Rejecting map DB without tiles table: $normalizedPath tables=$tables")
                opened.close()
                return false
            }

            val cols = mutableSetOf<String>()
            opened.rawQuery("PRAGMA table_info(tiles)", null).use { c ->
                val nameIdx = c.getColumnIndex("name")
                while (c.moveToNext()) cols.add(c.getString(nameIdx).lowercase())
            }

            val format = when {
                "tile_data" in cols -> DbFormat.MBTILES
                "image" in cols     -> DbFormat.RMAPS
                else                -> DbFormat.UNKNOWN
            }

            if (format == DbFormat.UNKNOWN) {
                Log.w(TAG, "Rejecting map DB with unsupported tiles columns: $normalizedPath columns=$cols")
                opened.close()
                return false
            }

            val sampleTile = sampleTileData(opened, format)
            if (sampleTile == null || !isSupportedRasterTileData(sampleTile)) {
                Log.w(TAG, "Rejecting map DB without raster tile sample: $normalizedPath format=$format sampleBytes=${sampleTile?.size ?: 0}")
                opened.close()
                return false
            }

            databases[index] = DbEntry(opened, format, normalizedPath, fileStamp)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to open map DB: $path error=${e.message}")
            false
        }
    }

    fun closeDatabase(index: Int) {
        databases.remove(index)?.let { entry ->
            clearTileCache()
            try { entry.db.close() } catch (_: Exception) {}
        }
    }

    /** Detect if RMaps file uses inverted z convention (actual_z = 17 - stored_z).
     *  Uses trend analysis: in normal RMaps max(x) GROWS as stored_z grows; in inverted
     *  max(x) SHRINKS as stored_z grows (because bigger stored_z = smaller actual_z).
     *  Result is cached per DbEntry. */
    private fun detectRMapsInverted(entry: DbEntry): Boolean {
        entry.rmapsInverted?.let { return it }
        val result = try {
            // (stored_z, max(max_x, max_y)) per zoom level
            val stats = mutableListOf<Pair<Int, Int>>()
            entry.db.rawQuery("SELECT z, MAX(x), MAX(y) FROM tiles GROUP BY z ORDER BY z", null).use { c ->
                while (c.moveToNext()) {
                    val z = c.getInt(0)
                    val maxCoord = maxOf(c.getInt(1), c.getInt(2))
                    stats.add(Pair(z, maxCoord))
                }
            }
            when {
                stats.isEmpty() -> true  // no data, assume RMaps default (inverted)
                stats.size == 1 -> {
                    // single zoom level — check both schemes, max(x,y) must fit
                    val (z, maxCoord) = stats[0]
                    val fitsNormal = maxCoord < (1 shl z)
                    val invertedZ = (17 - z).coerceAtLeast(0)
                    val fitsInverted = maxCoord < (1 shl invertedZ)
                    when {
                        fitsNormal && !fitsInverted -> false  // unambiguously normal
                        !fitsNormal && fitsInverted -> true   // unambiguously inverted
                        !fitsNormal && !fitsInverted -> true  // both fail → default inverted
                        else -> true  // both fit → ambiguous, default inverted (RMaps convention)
                    }
                }
                else -> {
                    // Trend: does max(x,y) grow or shrink with stored_z?
                    stats.last().second < stats.first().second
                }
            }
        } catch (_: Exception) { true }
        entry.rmapsInverted = result
        return result
    }

    fun getMaxZoom(index: Int): Int {
        val entry = databases[index] ?: return 19
        val db = entry.db
        if (entry.format == DbFormat.MBTILES) {
            try {
                db.rawQuery("SELECT value FROM metadata WHERE name='maxzoom'", null).use { c ->
                    if (c.moveToFirst()) c.getString(0).toIntOrNull()?.let { return it }
                }
            } catch (_: Exception) {}
            try {
                db.rawQuery("SELECT MAX(zoom_level) FROM tiles", null).use { c ->
                    if (c.moveToFirst()) return c.getInt(0)
                }
            } catch (_: Exception) {}
        }
        if (entry.format == DbFormat.RMAPS) {
            try {
                val inverted = detectRMapsInverted(entry)
                db.rawQuery("SELECT MIN(z), MAX(z) FROM tiles", null).use { c ->
                    if (c.moveToFirst()) {
                        val minStored = c.getInt(0)
                        val maxStored = c.getInt(1)
                        // If inverted: max actual zoom = 17 - min stored zoom
                        return if (inverted) 17 - minStored else maxStored
                    }
                }
            } catch (_: Exception) {}
        }
        return 19
    }

    fun getMinZoom(index: Int): Int {
        val entry = databases[index] ?: return 0
        val db = entry.db
        if (entry.format == DbFormat.MBTILES) {
            try {
                db.rawQuery("SELECT value FROM metadata WHERE name='minzoom'", null).use { c ->
                    if (c.moveToFirst()) c.getString(0).toIntOrNull()?.let { return it }
                }
            } catch (_: Exception) {}
            try {
                db.rawQuery("SELECT MIN(zoom_level) FROM tiles", null).use { c ->
                    if (c.moveToFirst()) return c.getInt(0)
                }
            } catch (_: Exception) {}
        }
        if (entry.format == DbFormat.RMAPS) {
            try {
                val inverted = detectRMapsInverted(entry)
                db.rawQuery("SELECT MIN(z), MAX(z) FROM tiles", null).use { c ->
                    if (c.moveToFirst()) {
                        val minStored = c.getInt(0)
                        val maxStored = c.getInt(1)
                        // If inverted: min actual zoom = 17 - max stored zoom
                        return if (inverted) 17 - maxStored else minStored
                    }
                }
            } catch (_: Exception) {}
        }
        return 0
    }

    /** Returns geographic bounds (north, south, east, west) of all tiles in the database, or null. */
    fun getBounds(index: Int): DoubleArray? {
        val entry = databases[index] ?: return null
        val db = entry.db
        return try {
            when (entry.format) {
                DbFormat.MBTILES -> boundsMBTiles(db)
                DbFormat.RMAPS -> boundsRMaps(db, detectRMapsInverted(entry))
                DbFormat.UNKNOWN -> null
            }
        } catch (_: Exception) { null }
    }

    private fun boundsMBTiles(db: SQLiteDatabase): DoubleArray? {
        // Try metadata bounds first
        try {
            db.rawQuery("SELECT value FROM metadata WHERE name='bounds'", null).use { c ->
                if (c.moveToFirst()) {
                    val parts = c.getString(0).split(",").mapNotNull { it.trim().toDoubleOrNull() }
                    if (parts.size == 4) return doubleArrayOf(parts[3], parts[1], parts[2], parts[0]) // N,S,E,W
                }
            }
        } catch (_: Exception) {}
        // Fallback: compute from tiles at max zoom (TMS y-flipped)
        db.rawQuery("SELECT MAX(zoom_level) FROM tiles", null).use { c ->
            if (!c.moveToFirst()) return null
            val z = c.getInt(0)
            return boundsFromTileRange(db, z, xCol = "tile_column", yCol = "tile_row", tmsY = true, zCol = "zoom_level")
        }
    }

    private fun boundsRMaps(db: SQLiteDatabase, inverted: Boolean): DoubleArray? {
        val storedMin = db.rawQuery("SELECT MIN(z) FROM tiles", null).use {
            if (it.moveToFirst()) it.getInt(0) else return null
        }
        val actualZ = if (inverted) 17 - storedMin else storedMin
        return boundsFromTileRangeRaw(db, storedMin, actualZ, xCol = "x", yCol = "y", tmsY = false, zCol = "z")
    }

    private fun boundsFromTileRange(db: SQLiteDatabase, actualZ: Int, xCol: String, yCol: String, tmsY: Boolean, zCol: String): DoubleArray? {
        return boundsFromTileRangeRaw(db, actualZ, actualZ, xCol, yCol, tmsY, zCol)
    }

    private fun boundsFromTileRangeRaw(db: SQLiteDatabase, storedZ: Int, actualZ: Int, xCol: String, yCol: String, tmsY: Boolean, zCol: String): DoubleArray? {
        val sql = "SELECT MIN($xCol), MAX($xCol), MIN($yCol), MAX($yCol) FROM tiles WHERE $zCol=?"
        db.rawQuery(sql, arrayOf(storedZ.toString())).use { c ->
            if (!c.moveToFirst()) return null
            val minX = c.getInt(0); val maxX = c.getInt(1)
            val minY = c.getInt(2); val maxY = c.getInt(3)
            val n = 1.0 * (1 shl actualZ)
            val west  = minX / n * 360.0 - 180.0
            val east  = (maxX + 1) / n * 360.0 - 180.0
            val (north, south) = if (tmsY) {
                // TMS y-axis is flipped: tms y=0 is south, xyz y=0 is north
                val minYxyz = (1 shl actualZ) - 1 - maxY  // max TMS y → north edge
                val maxYxyz = (1 shl actualZ) - 1 - minY  // min TMS y → south edge
                Pair(tileToLat(minYxyz, actualZ), tileToLat(maxYxyz + 1, actualZ))
            } else {
                Pair(tileToLat(minY, actualZ), tileToLat(maxY + 1, actualZ))
            }
            return doubleArrayOf(north, south, east, west)
        }
    }

    private fun tileToLat(y: Int, z: Int): Double {
        val n = Math.PI - 2.0 * Math.PI * y / (1 shl z)
        return Math.toDegrees(Math.atan(Math.sinh(n)))
    }

    override fun serve(session: IHTTPSession): Response {
        val rawParts = session.uri.trim('/').split("/").filter { it.isNotBlank() }
        val parts = if (rawParts.firstOrNull() == "offline") rawParts.drop(1) else rawParts
        if (parts.size < 4) return notFound()
        val idx = parts[0].toIntOrNull() ?: return notFound()
        val z   = parts[1].toIntOrNull() ?: return notFound()
        val x   = parts[2].toIntOrNull() ?: return notFound()
        val y   = parts[3].removeSuffix(".png").toIntOrNull() ?: return notFound()
        val entry = databases[idx] ?: return notFound()

        // Check LRU cache first
        val cacheKey = "${entry.fileStamp}/$idx/$z/$x/$y"
        tileCache.get(cacheKey)?.let { cached ->
            return tileResponse(cached)
        }

        val data = queryTile(entry, z, x, y)
        if (data == null || data.isEmpty() || !isSupportedRasterTileData(data)) return notFound()

        // Store in cache
        tileCache.put(cacheKey, data)
        return tileResponse(data)
    }

    private fun tileResponse(data: ByteArray): Response {
        val mime = detectMime(data)
        val resp = newFixedLengthResponse(
            Response.Status.OK, mime,
            ByteArrayInputStream(data), data.size.toLong()
        )
        resp.addHeader("Cache-Control", "max-age=86400, immutable")
        return resp
    }

    private fun detectMime(data: ByteArray): String = when {
        data.size >= 2 && data[0] == 0xFF.toByte() && data[1] == 0xD8.toByte() -> "image/jpeg"
        data.size >= 4 && data[0] == 0x89.toByte() && data[1] == 0x50.toByte() -> "image/png"
        data.size >= 12 && data[0] == 0x52.toByte() && data[1] == 0x49.toByte()
            && data[2] == 0x46.toByte() && data[3] == 0x46.toByte()
            && data[8] == 0x57.toByte() && data[9] == 0x45.toByte()
            && data[10] == 0x42.toByte() && data[11] == 0x50.toByte() -> "image/webp"
        // Detect gzip-compressed tiles
        data.size >= 2 && data[0] == 0x1F.toByte() && data[1] == 0x8B.toByte() -> "application/x-protobuf"
        else -> "application/octet-stream"
    }

    private fun isSupportedRasterTileData(data: ByteArray): Boolean = when (detectMime(data)) {
        "image/jpeg", "image/png", "image/webp" -> true
        else -> false
    }

    private fun sampleTileData(db: SQLiteDatabase, format: DbFormat): ByteArray? {
        val query = when (format) {
            DbFormat.MBTILES -> "SELECT tile_data FROM tiles LIMIT 1"
            DbFormat.RMAPS -> "SELECT image FROM tiles LIMIT 1"
            DbFormat.UNKNOWN -> return null
        }
        return db.rawQuery(query, null).use { c ->
            if (c.moveToFirst()) c.getBlob(0) else null
        }
    }

    private fun queryTile(entry: DbEntry, z: Int, x: Int, y: Int): ByteArray? {
        val db = entry.db
        return try {
            when (entry.format) {
                DbFormat.RMAPS -> queryRMaps(entry, db, z, x, y)
                DbFormat.MBTILES -> queryMBTiles(entry, db, z, x, y)
                DbFormat.UNKNOWN -> null
            }
        } catch (e: Exception) {
            null // handle SQLiteDatabaseLockedException gracefully
        }
    }

    private fun queryRMaps(entry: DbEntry, db: SQLiteDatabase, z: Int, x: Int, y: Int): ByteArray? {
        entry.workingFormula?.let { (invertZ, invertY) ->
            return queryRMapsWithFormula(db, z, x, y, invertZ, invertY)
        }

        val combinations = listOf(
            Pair(true, false),
            Pair(false, false),
            Pair(true, true),
            Pair(false, true),
        )
        for ((invertZ, invertY) in combinations) {
            val data = queryRMapsWithFormula(db, z, x, y, invertZ, invertY)
            if (data != null) {
                entry.workingFormula = Pair(invertZ, invertY)
                return data
            }
        }
        return null
    }

    private fun queryRMapsWithFormula(
        db: SQLiteDatabase, z: Int, x: Int, y: Int, invertZ: Boolean, invertY: Boolean
    ): ByteArray? {
        val rz = if (invertZ) 17 - z else z
        val ry = if (invertY) (1 shl z) - 1 - y else y
        return db.rawQuery(
            "SELECT image FROM tiles WHERE x=? AND y=? AND z=?",
            arrayOf(x.toString(), ry.toString(), rz.toString())
        ).use { if (it.moveToFirst()) it.getBlob(0) else null }
    }

    private fun queryMBTiles(entry: DbEntry, db: SQLiteDatabase, z: Int, x: Int, y: Int): ByteArray? {
        entry.workingFormula?.let { (_, invertY) ->
            val ry = if (invertY) (1 shl z) - 1 - y else y
            return db.rawQuery(
                "SELECT tile_data FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=?",
                arrayOf(z.toString(), x.toString(), ry.toString())
            ).use { if (it.moveToFirst()) it.getBlob(0) else null }
        }

        val tmsY = (1 shl z) - 1 - y
        db.rawQuery(
            "SELECT tile_data FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=?",
            arrayOf(z.toString(), x.toString(), tmsY.toString())
        ).use { if (it.moveToFirst()) {
            entry.workingFormula = Pair(false, true)
            return it.getBlob(0)
        }}

        db.rawQuery(
            "SELECT tile_data FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=?",
            arrayOf(z.toString(), x.toString(), y.toString())
        ).use { if (it.moveToFirst()) {
            entry.workingFormula = Pair(false, false)
            return it.getBlob(0)
        }}

        return null
    }

    fun cleanup() {
        try { stop() } catch (_: Exception) {}
        clearTileCache()
        databases.values.forEach { try { it.db.close() } catch (_: Exception) {} }
        databases.clear()
    }

    private fun notFound(): Response {
        val png = EMPTY_TILE_PNG
        val resp = newFixedLengthResponse(
            Response.Status.OK, "image/png",
            ByteArrayInputStream(png), png.size.toLong()
        )
        resp.addHeader("Cache-Control", "max-age=3600")
        return resp
    }

    companion object {
        private const val TAG = "TileServer"

        private val EMPTY_TILE_PNG: ByteArray by lazy {
            val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
            ByteArrayOutputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                bitmap.recycle()
                output.toByteArray()
            }
        }
    }
}
