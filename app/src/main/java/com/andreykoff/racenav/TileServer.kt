package com.andreykoff.racenav

import android.database.sqlite.SQLiteDatabase
import android.util.LruCache
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream

class TileServer(port: Int) : NanoHTTPD(port) {

    enum class DbFormat { UNKNOWN, RMAPS, MBTILES }

    private data class DbEntry(
        val db: SQLiteDatabase,
        val format: DbFormat,
        @Volatile var workingFormula: Pair<Boolean, Boolean>? = null
    )

    private val databases = mutableMapOf<Int, DbEntry>()

    // LRU tile cache: 24MB max (tiles are ~25KB avg, so ~960 tiles)
    private val tileCache = object : LruCache<String, ByteArray>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ByteArray): Int = value.size
    }

    /** Open SQLite file at given index. Returns true on success. */
    fun openDatabase(index: Int, path: String): Boolean {
        return try {
            databases[index]?.db?.close()
            val opened = SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS)

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
                opened.close()
                return false
            }
            databases[index] = DbEntry(opened, format)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun closeDatabase(index: Int) {
        databases.remove(index)?.db?.close()
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
                db.rawQuery("SELECT MAX(z) FROM tiles", null).use { c ->
                    if (c.moveToFirst()) return c.getInt(0)
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
                db.rawQuery("SELECT MIN(z) FROM tiles", null).use { c ->
                    if (c.moveToFirst()) return c.getInt(0)
                }
            } catch (_: Exception) {}
        }
        return 0
    }

    override fun serve(session: IHTTPSession): Response {
        val parts = session.uri.trim('/').split("/")
        if (parts.size < 4) return notFound()
        val idx = parts[0].toIntOrNull() ?: return notFound()
        val z   = parts[1].toIntOrNull() ?: return notFound()
        val x   = parts[2].toIntOrNull() ?: return notFound()
        val y   = parts[3].removeSuffix(".png").toIntOrNull() ?: return notFound()

        // Check LRU cache first
        val cacheKey = "$idx/$z/$x/$y"
        tileCache.get(cacheKey)?.let { cached ->
            return tileResponse(cached)
        }

        val data = queryTile(idx, z, x, y)
        if (data == null || data.isEmpty()) return notFound()

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
        else -> "image/png"
    }

    private fun queryTile(idx: Int, z: Int, x: Int, y: Int): ByteArray? {
        val entry = databases[idx] ?: return null
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
        tileCache.evictAll()
        databases.values.forEach { try { it.db.close() } catch (_: Exception) {} }
        databases.clear()
    }

    private fun notFound(): Response {
        val png = TRANSPARENT_PNG
        val resp = newFixedLengthResponse(
            Response.Status.OK, "image/png",
            ByteArrayInputStream(png), png.size.toLong()
        )
        resp.addHeader("Cache-Control", "max-age=3600")
        return resp
    }

    companion object {
        private val TRANSPARENT_PNG = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
            0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15.toByte(), 0xC4.toByte(), 0x89.toByte(),
            0x00, 0x00, 0x00, 0x0A, 0x49, 0x44, 0x41, 0x54,
            0x78, 0x9C.toByte(), 0x62, 0x00, 0x00, 0x00, 0x02, 0x00, 0x01,
            0xE5.toByte(), 0x27.toByte(), 0xDE.toByte(), 0xFC.toByte(),
            0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44,
            0xAE.toByte(), 0x42.toByte(), 0x60.toByte(), 0x82.toByte()
        )
    }
}
