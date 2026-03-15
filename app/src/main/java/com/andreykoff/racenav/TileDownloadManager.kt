package com.andreykoff.racenav

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import okhttp3.*
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.*

data class DownloadTask(
    val name: String,
    val layers: List<LayerDownload>,
    val bounds: BoundsRect,
    val minZoom: Int,
    val maxZoom: Int
)

data class LayerDownload(
    val layerKey: String,
    val layerLabel: String,
    val outputPath: String
)

data class BoundsRect(
    val north: Double, val south: Double,
    val east: Double, val west: Double
)

data class DownloadProgress(
    val totalTiles: Int,
    val downloadedTiles: Int,
    val currentLayer: String,
    val bytesDownloaded: Long,
    val isRunning: Boolean,
    val error: String? = null
) {
    val percent: Int get() = if (totalTiles > 0) (downloadedTiles * 100 / totalTiles) else 0
}

object TileDownloadManager {

    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    var lastTask: DownloadTask? = null
    val isDownloading = AtomicBoolean(false)
    val downloaded = AtomicInteger(0)
    val totalTiles = AtomicInteger(0)
    val bytesTotal = AtomicLong(0)
    var currentLayerName = ""
    var error: String? = null

    // Callbacks
    var onProgressUpdate: ((DownloadProgress) -> Unit)? = null
    var onComplete: (() -> Unit)? = null

    // Tile sources reference — will be set from MapFragment
    var tileSourcesRef: Map<String, MapFragment.Companion.TileSourceInfo>? = null

    private val client = OkHttpClient.Builder()
        .dispatcher(Dispatcher().apply {
            maxRequests = 16
            maxRequestsPerHost = 4
        })
        .connectionPool(ConnectionPool(8, 30, java.util.concurrent.TimeUnit.SECONDS))
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    fun estimateTiles(bounds: BoundsRect, minZoom: Int, maxZoom: Int): Int {
        var total = 0
        for (z in minZoom..maxZoom) {
            val n = 1 shl z
            val xMin = lonToTileX(bounds.west, n)
            val xMax = lonToTileX(bounds.east, n)
            val yMin = latToTileY(bounds.north, n)
            val yMax = latToTileY(bounds.south, n)
            total += (xMax - xMin + 1) * (yMax - yMin + 1)
        }
        return total
    }

    fun startDownload(context: Context, task: DownloadTask) {
        if (isDownloading.get()) return

        lastTask = task
        isDownloading.set(true)
        downloaded.set(0)
        error = null

        val tilesPerLayer = estimateTiles(task.bounds, task.minZoom, task.maxZoom)
        totalTiles.set(tilesPerLayer * task.layers.size)
        bytesTotal.set(0)
        Log.d("TileDownload", "startDownload: ${task.layers.size} layers, $tilesPerLayer tiles/layer, zoom ${task.minZoom}-${task.maxZoom}")
        Log.d("TileDownload", "startDownload: layers=${task.layers.map { it.layerKey }}")
        Log.d("TileDownload", "startDownload: tileSourcesRef keys=${tileSourcesRef?.keys}")

        Thread {
            try {
                for (layer in task.layers) {
                    if (!isDownloading.get()) break
                    currentLayerName = layer.layerLabel
                    downloadLayerSync(context, layer, task.bounds, task.minZoom, task.maxZoom)
                }
            } catch (e: Exception) {
                error = e.message
                Log.e("TileDownload", "Download error", e)
            } finally {
                isDownloading.set(false)
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    notifyProgress()
                    onComplete?.invoke()
                }
            }
        }.start()
    }

    fun stopDownload() {
        isDownloading.set(false)
        job?.cancel()
    }

    fun getProgress(): DownloadProgress {
        return DownloadProgress(
            totalTiles = totalTiles.get(),
            downloadedTiles = downloaded.get(),
            currentLayer = currentLayerName,
            bytesDownloaded = bytesTotal.get(),
            isRunning = isDownloading.get(),
            error = error
        )
    }

    private fun downloadLayerSync(context: Context, layer: LayerDownload, bounds: BoundsRect, minZoom: Int, maxZoom: Int) {
        val dbFile = File(layer.outputPath)
        dbFile.parentFile?.mkdirs()
        
        val db = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        db.rawQuery("PRAGMA journal_mode=WAL", null).close()
        db.rawQuery("PRAGMA cache_size=-8192", null).close()
        db.execSQL("CREATE TABLE IF NOT EXISTS tiles (zoom_level INTEGER, tile_column INTEGER, tile_row INTEGER, tile_data BLOB, PRIMARY KEY (zoom_level, tile_column, tile_row))")
        db.execSQL("CREATE TABLE IF NOT EXISTS metadata (name TEXT PRIMARY KEY, value TEXT)")
        db.execSQL("INSERT OR REPLACE INTO metadata VALUES ('name', ?)", arrayOf(layer.layerLabel))
        db.execSQL("INSERT OR REPLACE INTO metadata VALUES ('format', 'png')")
        db.execSQL("INSERT OR REPLACE INTO metadata VALUES ('minzoom', ?)", arrayOf(minZoom.toString()))
        db.execSQL("INSERT OR REPLACE INTO metadata VALUES ('maxzoom', ?)", arrayOf(maxZoom.toString()))
        db.execSQL("INSERT OR REPLACE INTO metadata VALUES ('bounds', ?)",
            arrayOf("${bounds.west},${bounds.south},${bounds.east},${bounds.north}"))

        val sourceInfo = tileSourcesRef?.get(layer.layerKey)
        Log.d("TileDownload", "downloadLayerSync: key='${layer.layerKey}', has sourceInfo=${sourceInfo != null}")
        if (sourceInfo == null) {
            db.close()
            return
        }

        // Collect tiles
        val tiles = mutableListOf<Triple<Int, Int, Int>>()
        for (z in minZoom..maxZoom) {
            val n = 1 shl z
            for (x in lonToTileX(bounds.west, n)..lonToTileX(bounds.east, n)) {
                for (y in latToTileY(bounds.north, n)..latToTileY(bounds.south, n)) {
                    tiles.add(Triple(x, y, z))
                }
            }
        }
        Log.d("TileDownload", "downloadLayerSync: ${tiles.size} tiles for ${layer.layerKey}")

        // Download tiles using thread pool
        val executor = java.util.concurrent.Executors.newFixedThreadPool(4)
        val latch = java.util.concurrent.CountDownLatch(tiles.size)

        for ((x, y, z) in tiles) {
            if (!isDownloading.get()) { latch.countDown(); continue }
            executor.submit {
                try {
                    val url = buildTileUrl(sourceInfo, x, y, z)
                    if (url == null) { downloaded.incrementAndGet(); notifyProgressThrottled(); latch.countDown(); return@submit }
                    
                    val request = Request.Builder().url(url)
                        .header("User-Agent", "RaceNav/2.1 Android")
                        .build()
                    val response = client.newCall(request).execute()
                    if (response.isSuccessful) {
                        val bytes = response.body?.bytes()
                        if (bytes != null && bytes.isNotEmpty()) {
                            val tmsY = (1 shl z) - 1 - y
                            synchronized(db) {
                                val stmt = db.compileStatement("INSERT OR REPLACE INTO tiles VALUES (?, ?, ?, ?)")
                                stmt.bindLong(1, z.toLong())
                                stmt.bindLong(2, x.toLong())
                                stmt.bindLong(3, tmsY.toLong())
                                stmt.bindBlob(4, bytes)
                                stmt.executeInsert()
                                stmt.close()
                            }
                            bytesTotal.addAndGet(bytes.size.toLong())
                        }
                    }
                    response.close()
                } catch (e: Exception) {
                    Log.w("TileDownload", "Tile fail: $e")
                } finally {
                    downloaded.incrementAndGet()
                    notifyProgressThrottled()
                    latch.countDown()
                }
            }
        }

        // Wait for ALL tiles to complete
        latch.await()
        executor.shutdown()
        db.close()
        Log.d("TileDownload", "downloadLayerSync DONE: ${layer.layerKey}, file size=${dbFile.length()}")
    }

    private var lastNotifyTime = 0L

    private fun notifyProgressThrottled() {
        val now = System.currentTimeMillis()
        if (now - lastNotifyTime < 500) return
        lastNotifyTime = now
        notifyProgress()
    }

    private fun notifyProgress() {
        val progress = getProgress()
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            onProgressUpdate?.invoke(progress)
        }
    }

    /** Build a tile URL from the source info urls list, substituting {z}/{x}/{y} */
    private fun buildTileUrl(info: MapFragment.Companion.TileSourceInfo?, x: Int, y: Int, z: Int): String? {
        if (info == null) {
            Log.e("TileDownload", "buildTileUrl: info is null for tile z=$z x=$x y=$y")
            return null
        }
        val urls = info.urls
        if (urls.isEmpty()) return null
        val template = urls[(x + y + z) % urls.size]
        val actualY = if (info.tms) ((1 shl z) - 1 - y) else y
        return template
            .replace("{z}", z.toString())
            .replace("{x}", x.toString())
            .replace("{y}", actualY.toString())
    }

    private fun lonToTileX(lon: Double, n: Int): Int =
        ((lon + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)

    private fun latToTileY(lat: Double, n: Int): Int =
        ((1 - ln(tan(Math.toRadians(lat)) + 1 / cos(Math.toRadians(lat))) / PI) / 2 * n).toInt().coerceIn(0, n - 1)
}
