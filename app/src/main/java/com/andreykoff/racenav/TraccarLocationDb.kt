package com.andreykoff.racenav

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class TraccarPoint(
    val id: Long,
    val lat: Double,
    val lon: Double,
    val speed: Float,      // m/s
    val bearing: Float,
    val altitude: Double,
    val timestamp: Long,   // epoch ms
    val battery: Int       // 0-100 or -1 if unknown
)

class TraccarLocationDb(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    companion object {
        private const val DB_NAME = "traccar_buffer.db"
        private const val DB_VERSION = 2
        private const val TABLE = "locations"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE $TABLE (
                _id INTEGER PRIMARY KEY AUTOINCREMENT,
                lat REAL NOT NULL,
                lon REAL NOT NULL,
                speed REAL NOT NULL DEFAULT 0,
                bearing REAL NOT NULL DEFAULT 0,
                altitude REAL NOT NULL DEFAULT 0,
                timestamp INTEGER NOT NULL,
                battery INTEGER NOT NULL DEFAULT -1,
                sent INTEGER NOT NULL DEFAULT 0
            )
        """)
        db.execSQL("CREATE INDEX idx_sent ON $TABLE(sent)")
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
        if (old < 2) {
            // Add battery column
            db.execSQL("ALTER TABLE $TABLE ADD COLUMN battery INTEGER NOT NULL DEFAULT -1")
        }
    }

    fun insertPoint(lat: Double, lon: Double, speed: Float, bearing: Float,
                    altitude: Double, timestamp: Long, battery: Int = -1) {
        writableDatabase.insert(TABLE, null, ContentValues().apply {
            put("lat", lat)
            put("lon", lon)
            put("speed", speed)
            put("bearing", bearing)
            put("altitude", altitude)
            put("timestamp", timestamp)
            put("battery", battery)
            put("sent", 0)
        })
    }

    /** Returns up to [limit] unsent points, oldest first */
    fun getUnsent(limit: Int = 50): List<TraccarPoint> {
        val list = mutableListOf<TraccarPoint>()
        val cursor = readableDatabase.query(
            TABLE, null, "sent = 0", null, null, null,
            "timestamp ASC", limit.toString()
        )
        cursor.use {
            while (it.moveToNext()) {
                list.add(TraccarPoint(
                    id = it.getLong(it.getColumnIndexOrThrow("_id")),
                    lat = it.getDouble(it.getColumnIndexOrThrow("lat")),
                    lon = it.getDouble(it.getColumnIndexOrThrow("lon")),
                    speed = it.getFloat(it.getColumnIndexOrThrow("speed")),
                    bearing = it.getFloat(it.getColumnIndexOrThrow("bearing")),
                    altitude = it.getDouble(it.getColumnIndexOrThrow("altitude")),
                    timestamp = it.getLong(it.getColumnIndexOrThrow("timestamp")),
                    battery = it.getInt(it.getColumnIndexOrThrow("battery"))
                ))
            }
        }
        return list
    }

    /** Mark points as sent by their IDs */
    fun markSent(ids: List<Long>) {
        if (ids.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            val idList = ids.joinToString(",")
            db.execSQL("UPDATE $TABLE SET sent = 1 WHERE _id IN ($idList)")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Delete old sent points (keep DB small) */
    fun purgeOldSent(keepLastN: Int = 1000) {
        writableDatabase.execSQL("""
            DELETE FROM $TABLE WHERE sent = 1 AND _id NOT IN (
                SELECT _id FROM $TABLE WHERE sent = 1 ORDER BY _id DESC LIMIT $keepLastN
            )
        """)
    }

    /** Count of unsent points */
    fun unsentCount(): Int {
        val cursor = readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE WHERE sent = 0", null)
        cursor.use {
            return if (it.moveToFirst()) it.getInt(0) else 0
        }
    }
}
