package com.andreykoff.racenav

import android.database.sqlite.SQLiteDatabase
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream

class TileServer(port: Int) : NanoHTTPD(port) {

    enum class DbFormat { UNKNOWN, RMAPS, MBTILES }

    /** Cached working combination of z/y formulas per db index: Pair(invertZ, invertY) */
    private data class DbEntry(
        val db: SQLiteDatabase,
        val format: DbFormat,
        var workingFormula: Pair<Boolean, Boolean>? = null
    )

    private val databases = mutableMapOf<Int, DbEntry>()

    /** Open SQLite file at given index. Returns true on success. */
    fun openDatabase(index: Int, path: String): Boolean {
        return try {
            databases[index]?.db?.close()
            val opened = SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY)

            // Find all tables in db
            val tables = mutableSetOf<String>()
            opened.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null).use { c ->
                while (c.moveToNext()) tables.add(c.getString(0).lowercase())
            }

            if ("tiles" !in tables) {
                opened.close()
                return false
            }

            // Detect column names in tiles table
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

    override fun serve(session: IHTTPSession): Response {
        // Expect URI: /{mapIndex}/{z}/{x}/{y}.png
        val parts = session.uri.trim('/').split("/")
        if (parts.size < 4) return notFound()
        val idx = parts[0].toIntOrNull() ?: return notFound()
        val z   = parts[1].toIntOrNull() ?: return notFound()
        val x   = parts[2].toIntOrNull() ?: return notFound()
        val y   = parts[3].removeSuffix(".png").toIntOrNull() ?: return notFound()

        val data = queryTile(idx, z, x, y)
        if (data == null || data.isEmpty()) return notFound()
        // Detect image format by magic bytes
        val mime = when {
            data.size >= 2 && data[0] == 0xFF.toByte() && data[1] == 0xD8.toByte() -> "image/jpeg"
            data.size >= 4 && data[0] == 0x89.toByte() && data[1] == 0x50.toByte() -> "image/png"
            data.size >= 12 && data[0] == 0x52.toByte() && data[1] == 0x49.toByte()
                && data[2] == 0x46.toByte() && data[3] == 0x46.toByte()
                && data[8] == 0x57.toByte() && data[9] == 0x45.toByte()
                && data[10] == 0x42.toByte() && data[11] == 0x50.toByte() -> "image/webp"
            else -> "image/png"
        }
        return newFixedLengthResponse(
            Response.Status.OK, mime,
            ByteArrayInputStream(data), data.size.toLong()
        )
    }

    private fun queryTile(idx: Int, z: Int, x: Int, y: Int): ByteArray? {
        val entry = databases[idx] ?: return null
        val db = entry.db
        return when (entry.format) {
            DbFormat.RMAPS -> queryRMaps(entry, db, z, x, y)
            DbFormat.MBTILES -> queryMBTiles(entry, db, z, x, y)
            DbFormat.UNKNOWN -> null
        }
    }

    /**
     * RMaps/Locus SQLite: tries all 4 combinations of z/y formulas.
     * Caches the working combination after first successful hit.
     *
     * z formulas: invertZ=true → z=17-zoom (Locus classic), invertZ=false → z=zoom (direct)
     * y formulas: invertY=false → y as-is (slippy), invertY=true → y=TMS-flipped
     */
    private fun queryRMaps(entry: DbEntry, db: SQLiteDatabase, z: Int, x: Int, y: Int): ByteArray? {
        // If we already know the working formula — use it directly
        entry.workingFormula?.let { (invertZ, invertY) ->
            return queryRMapsWithFormula(db, z, x, y, invertZ, invertY)
        }

        // Try all 4 combinations; cache the first one that returns data
        val combinations = listOf(
            Pair(true, false),   // invertZ + slippy Y  (classic Locus)
            Pair(false, false),  // direct Z + slippy Y
            Pair(true, true),    // invertZ + TMS Y
            Pair(false, true),   // direct Z + TMS Y
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

    /**
     * MBTiles: standard TMS Y-flip.
     * Falls back to non-flipped Y if nothing found (some exports use slippy map Y).
     */
    private fun queryMBTiles(entry: DbEntry, db: SQLiteDatabase, z: Int, x: Int, y: Int): ByteArray? {
        entry.workingFormula?.let { (_, invertY) ->
            val ry = if (invertY) (1 shl z) - 1 - y else y
            return db.rawQuery(
                "SELECT tile_data FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=?",
                arrayOf(z.toString(), x.toString(), ry.toString())
            ).use { if (it.moveToFirst()) it.getBlob(0) else null }
        }

        // TMS Y-flip (standard MBTiles)
        val tmsY = (1 shl z) - 1 - y
        db.rawQuery(
            "SELECT tile_data FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=?",
            arrayOf(z.toString(), x.toString(), tmsY.toString())
        ).use { if (it.moveToFirst()) {
            entry.workingFormula = Pair(false, true)
            return it.getBlob(0)
        }}

        // Fallback: slippy map Y (no flip)
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
        databases.values.forEach { try { it.db.close() } catch (_: Exception) {} }
        databases.clear()
    }

    /** Return 1x1 transparent PNG so MapLibre shows the layer below instead of error */
    private fun notFound(): Response {
        val png = TRANSPARENT_PNG
        return newFixedLengthResponse(
            Response.Status.OK, "image/png",
            ByteArrayInputStream(png), png.size.toLong()
        )
    }

    companion object {
        /** 1x1 transparent PNG (67 bytes) */
        private val TRANSPARENT_PNG = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,  // PNG signature
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,            // IHDR chunk
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,            // 1x1
            0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15.toByte(), 0xC4.toByte(), 0x89.toByte(),
            0x00, 0x00, 0x00, 0x0A, 0x49, 0x44, 0x41, 0x54,            // IDAT chunk
            0x78, 0x9C.toByte(), 0x62, 0x00, 0x00, 0x00, 0x02, 0x00, 0x01,
            0xE5.toByte(), 0x27.toByte(), 0xDE.toByte(), 0xFC.toByte(),
            0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44,            // IEND chunk
            0xAE.toByte(), 0x42.toByte(), 0x60.toByte(), 0x82.toByte()
        )
    }
}
