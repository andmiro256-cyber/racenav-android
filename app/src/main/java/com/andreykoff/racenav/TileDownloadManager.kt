package com.andreykoff.racenav

import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.os.StatFs
import android.util.Log
import okhttp3.*
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.*

data class DownloadTask(
    val name: String,
    val layers: List<LayerDownload>,
    val bounds: BoundsRect,
    val polygon: List<Pair<Double, Double>>? = null,
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
    val successfulTiles: Int,
    val failedTiles: Int,
    val skippedTiles: Int,
    val currentLayer: String,
    val bytesDownloaded: Long,
    val isRunning: Boolean,
    val isStopping: Boolean,
    val isPaused: Boolean,
    val wasCancelled: Boolean,
    val error: String? = null
) {
    val percent: Int
        get() = if (totalTiles > 0) {
            ((downloadedTiles.toLong() * 100L) / totalTiles.toLong()).toInt().coerceIn(0, 100)
        } else {
            0
        }
    val isPartial: Boolean get() = failedTiles > 0 || skippedTiles > 0
}

data class DownloadStateSnapshot(
    val sessionId: Long?,
    val task: DownloadTask?,
    val areaId: String?,
    val progress: DownloadProgress
)

object TileDownloadManager {

    private data class DownloadSessionInfo(
        val sessionId: Long,
        val task: DownloadTask,
        val areaId: String?,
        val sourceInfoMap: Map<String, MapFragment.Companion.TileSourceInfo>?
    )

    private val stateLock = Any()
    private val sessionCounter = AtomicLong(0L)
    private val MAX_IN_FLIGHT = 256
    private val BYTES_PER_TILE_ESTIMATE = 50_000L
    @Volatile
    private var workerThread: Thread? = null
    @Volatile
    private var sessionInfo: DownloadSessionInfo? = null

    val lastTask: DownloadTask?
        get() = sessionInfo?.task
    val isDownloading = AtomicBoolean(false)
    val downloaded = AtomicInteger(0)
    val successful = AtomicInteger(0)
    val failed = AtomicInteger(0)
    val skipped = AtomicInteger(0)
    val totalTiles = AtomicInteger(0)
    val bytesTotal = AtomicLong(0)
    val paused = AtomicBoolean(false)
    val cancelled = AtomicBoolean(false)
    var currentLayerName = ""
    val currentAreaId: String?
        get() = sessionInfo?.areaId
    var error: String? = null

    // Callbacks
    var onProgressUpdate: ((DownloadStateSnapshot) -> Unit)? = null
    var onComplete: ((DownloadStateSnapshot) -> Unit)? = null

    private val client = OkHttpClient.Builder()
        .dispatcher(Dispatcher().apply {
            maxRequests = 24
            maxRequestsPerHost = 6
        })
        .connectionPool(ConnectionPool(8, 30, java.util.concurrent.TimeUnit.SECONDS))
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private fun acquireLocks(context: Context) {
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RaceNav:TileDownload").apply {
                setReferenceCounted(false)
                acquire(4 * 60 * 60 * 1000L) // 4 hours max
            }
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "RaceNav:TileDownload").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            Log.w("TileDownload", "Failed to acquire locks: ${e.message}")
        }
    }

    private fun releaseLocks() {
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        try { wifiLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        wakeLock = null
        wifiLock = null
    }

    fun isStopping(): Boolean = !isDownloading.get() && (workerThread?.isAlive == true)

    fun getStateSnapshot(): DownloadStateSnapshot = buildStateSnapshot()

    fun awaitIdle(timeoutMs: Long = 20_000L): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val activeWorker = synchronized(stateLock) { workerThread }
            if (activeWorker == null || !activeWorker.isAlive) {
                return true
            }
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0L) {
                return false
            }
            try {
                activeWorker.join(minOf(remaining, 250L))
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
    }

    fun clearSession() {
        synchronized(stateLock) {
            sessionInfo = null
        }
    }

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

    /** Check if enough disk space is available. Returns null if OK, error message if not. */
    fun checkDiskSpace(context: Context, task: DownloadTask): String? {
        val tilesPerLayer = if (task.polygon != null && task.polygon.size >= 3) {
            countPolygonTiles(task)
        } else estimateTiles(task.bounds, task.minZoom, task.maxZoom)
        val estimatedBytes = tilesPerLayer.toLong() * task.layers.size * BYTES_PER_TILE_ESTIMATE
        val mapsDir = task.layers.firstOrNull()?.let { File(it.outputPath).parentFile }?.also { it.mkdirs() }
            ?: MapStorageManager.getMapsDir(context)
        val stat = StatFs(mapsDir.absolutePath)
        val available = stat.availableBytes
        if (estimatedBytes > available * 0.9) {
            val needMB = estimatedBytes / 1_048_576
            val freeMB = available / 1_048_576
            return "Недостаточно места: нужно ~${needMB} МБ, доступно ${freeMB} МБ"
        }
        return null
    }

    /** Returns total tile count across all layers — used for size warnings. */
    fun estimateTotalTiles(task: DownloadTask): Int {
        val tilesPerLayer = if (task.polygon != null && task.polygon.size >= 3) {
            countPolygonTiles(task)
        } else estimateTiles(task.bounds, task.minZoom, task.maxZoom)
        return tilesPerLayer * task.layers.size
    }

    /** Returns estimated download size in MB. */
    fun estimateMegabytes(task: DownloadTask): Long {
        return estimateTotalTiles(task).toLong() * BYTES_PER_TILE_ESTIMATE / 1_048_576L
    }

    fun startDownload(
        context: Context,
        task: DownloadTask,
        areaId: String? = null,
        sourceInfoMap: Map<String, MapFragment.Companion.TileSourceInfo>? = null
    ): Boolean {
        synchronized(stateLock) {
            if (isDownloading.get() || workerThread?.isAlive == true) return false
        }

        val session = DownloadSessionInfo(
            sessionId = sessionCounter.incrementAndGet(),
            task = task,
            areaId = areaId,
            sourceInfoMap = sourceInfoMap?.toMap()
        )
        synchronized(stateLock) {
            sessionInfo = session
        }
        skippedLayers.clear()
        isDownloading.set(true)
        downloaded.set(0)
        successful.set(0)
        failed.set(0)
        skipped.set(0)
        paused.set(false)
        cancelled.set(false)
        error = null

        val tilesPerLayer = if (task.polygon != null && task.polygon.size >= 3) {
            countPolygonTiles(task)
        } else estimateTiles(task.bounds, task.minZoom, task.maxZoom)
        totalTiles.set(tilesPerLayer * task.layers.size)
        bytesTotal.set(0)
        lastNotifyTime.set(0L)
        Log.d("TileDownload", "startDownload: ${task.layers.size} layers, $tilesPerLayer tiles/layer, zoom ${task.minZoom}-${task.maxZoom}")

        // Acquire WakeLock + WiFi lock
        acquireLocks(context)

        // Start foreground service to survive background
        try {
            val intent = Intent(context, TileDownloadService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (e: Exception) {
            Log.w("TileDownload", "Failed to start ForegroundService: ${e.message}")
        }

        val worker = Thread {
            try {
                for (layer in task.layers) {
                    if (!isDownloading.get()) break
                    currentLayerName = layer.layerLabel
                    downloadLayerSync(
                        layer = layer,
                        bounds = task.bounds,
                        polygon = task.polygon,
                        minZoom = task.minZoom,
                        maxZoom = task.maxZoom,
                        sourceInfoMap = session.sourceInfoMap
                    )
                }
            } catch (e: Exception) {
                error = e.message
                Log.e("TileDownload", "Download error", e)
                try { DiagnosticsCollector.logEvent(context, "DL error: ${currentLayerName} - ${e.message}") } catch (_: Exception) {}
            } finally {
                isDownloading.set(false)
                releaseLocks()
                // Stop foreground service
                try { context.stopService(Intent(context, TileDownloadService::class.java)) } catch (_: Exception) {}
                synchronized(stateLock) {
                    if (workerThread === Thread.currentThread()) {
                        workerThread = null
                    }
                    if (cancelled.get() && sessionInfo?.sessionId == session.sessionId) {
                        sessionInfo = null
                    }
                }
                val completionSnapshot = buildStateSnapshot(session)
                notifyProgress(completionSnapshot)
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    onComplete?.invoke(completionSnapshot)
                }
            }
        }
        synchronized(stateLock) {
            workerThread = worker
        }
        worker.start()
        return true
    }

    private fun countPolygonTiles(task: DownloadTask): Int {
        var count = 0
        val polygon = task.polygon ?: return 0
        for (z in task.minZoom..task.maxZoom) {
            val n = 1 shl z
            for (x in lonToTileX(task.bounds.west, n)..lonToTileX(task.bounds.east, n)) {
                for (y in latToTileY(task.bounds.north, n)..latToTileY(task.bounds.south, n)) {
                    if (pointInPolygon(tileCenterLat(y, z), tileCenterLon(x, z), polygon)) count++
                }
            }
        }
        return count
    }

    val skippedLayers = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    fun skipLayer(layerKey: String) {
        skippedLayers.add(layerKey)
    }

    fun stopDownload() {
        paused.set(false)
        cancelled.set(true)
        isDownloading.set(false)
        client.dispatcher.cancelAll()
        notifyProgress()
    }

    fun pauseDownload() {
        if (!isDownloading.get()) return
        paused.set(true)
        cancelled.set(false)
        isDownloading.set(false)
        client.dispatcher.cancelAll()
        notifyProgress()
    }

    fun getProgress(): DownloadProgress {
        val stopping = isStopping()
        return DownloadProgress(
            totalTiles = totalTiles.get(),
            downloadedTiles = downloaded.get(),
            successfulTiles = successful.get(),
            failedTiles = failed.get(),
            skippedTiles = skipped.get(),
            currentLayer = currentLayerName,
            bytesDownloaded = bytesTotal.get(),
            isRunning = isDownloading.get(),
            isStopping = stopping,
            isPaused = paused.get(),
            wasCancelled = cancelled.get(),
            error = error
        )
    }

    private fun buildStateSnapshot(session: DownloadSessionInfo? = sessionInfo): DownloadStateSnapshot {
        return DownloadStateSnapshot(
            sessionId = session?.sessionId,
            task = session?.task,
            areaId = session?.areaId,
            progress = getProgress()
        )
    }

    /** Ray-casting point-in-polygon test */
    private fun pointInPolygon(lat: Double, lon: Double, polygon: List<Pair<Double, Double>>): Boolean {
        var inside = false
        var j = polygon.size - 1
        for (i in polygon.indices) {
            val yi = polygon[i].first; val xi = polygon[i].second
            val yj = polygon[j].first; val xj = polygon[j].second
            if ((yi > lat) != (yj > lat) && lon < (xj - xi) * (lat - yi) / (yj - yi) + xi) {
                inside = !inside
            }
            j = i
        }
        return inside
    }

    private fun tileCenterLat(y: Int, z: Int): Double {
        val n = Math.PI - 2.0 * Math.PI * (y.toDouble() + 0.5) / (1 shl z)
        return Math.toDegrees(Math.atan(Math.sinh(n)))
    }
    private fun tileCenterLon(x: Int, z: Int): Double {
        return (x.toDouble() + 0.5) / (1 shl z) * 360.0 - 180.0
    }

    private fun downloadLayerSync(
        layer: LayerDownload,
        bounds: BoundsRect,
        polygon: List<Pair<Double, Double>>?,
        minZoom: Int,
        maxZoom: Int,
        sourceInfoMap: Map<String, MapFragment.Companion.TileSourceInfo>?
    ) {
        val dbFile = File(layer.outputPath)
        dbFile.parentFile?.mkdirs()

        val db = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        db.rawQuery("PRAGMA journal_mode=WAL", null).close()
        db.rawQuery("PRAGMA cache_size=-8192", null).close()
        db.rawQuery("PRAGMA synchronous=NORMAL", null).close()
        db.execSQL("CREATE TABLE IF NOT EXISTS tiles (zoom_level INTEGER, tile_column INTEGER, tile_row INTEGER, tile_data BLOB, PRIMARY KEY (zoom_level, tile_column, tile_row))")
        // Per-stripe resume scans by (zoom_level, tile_row); the primary key starts with zoom_level/tile_column,
        // so we need a dedicated index to avoid full scans during downloads of large regions.
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_tiles_zoom_row ON tiles(zoom_level, tile_row)")
        db.execSQL("CREATE TABLE IF NOT EXISTS metadata (name TEXT PRIMARY KEY, value TEXT)")
        db.execSQL("INSERT OR REPLACE INTO metadata VALUES ('name', ?)", arrayOf(layer.layerLabel))
        db.execSQL("INSERT OR REPLACE INTO metadata VALUES ('format', 'png')")
        db.execSQL("INSERT OR REPLACE INTO metadata VALUES ('minzoom', ?)", arrayOf(minZoom.toString()))
        db.execSQL("INSERT OR REPLACE INTO metadata VALUES ('maxzoom', ?)", arrayOf(maxZoom.toString()))
        db.execSQL("INSERT OR REPLACE INTO metadata VALUES ('bounds', ?)",
            arrayOf("${bounds.west},${bounds.south},${bounds.east},${bounds.north}"))

        if (layer.layerKey in skippedLayers) {
            Log.d("TileDownload", "Skipping layer: ${layer.layerKey}")
            val skipCount = countTilesIn(bounds, polygon, minZoom, maxZoom)
            skipped.addAndGet(skipCount)
            downloaded.addAndGet(skipCount)
            db.close()
            dbFile.delete()
            notifyProgressThrottled()
            return
        }

        val sourceInfo = sourceInfoMap?.get(layer.layerKey)
        Log.d("TileDownload", "downloadLayerSync: key='${layer.layerKey}', has sourceInfo=${sourceInfo != null}")
        if (sourceInfo == null) {
            db.close()
            dbFile.delete()
            throw IllegalStateException("Не найден источник слоя ${layer.layerLabel}")
        }

        // Bounded concurrency: at most MAX_IN_FLIGHT pending HTTP requests + queued executor tasks.
        // Prevents OOM on large regions where collected tile lists used to exceed millions of entries.
        val executor = java.util.concurrent.Executors.newFixedThreadPool(8)
        val inFlight = java.util.concurrent.Semaphore(MAX_IN_FLIGHT)

        val insertBuffer = java.util.Collections.synchronizedList(mutableListOf<Triple<Triple<Int, Int, Int>, ByteArray, Int>>())
        val BATCH_SIZE = 200

        fun flushBatch() {
            val batch: List<Triple<Triple<Int, Int, Int>, ByteArray, Int>>
            synchronized(insertBuffer) {
                if (insertBuffer.isEmpty()) return
                batch = ArrayList(insertBuffer)
                insertBuffer.clear()
            }
            synchronized(db) {
                db.beginTransaction()
                try {
                    val stmt = db.compileStatement("INSERT OR IGNORE INTO tiles VALUES (?, ?, ?, ?)")
                    for ((tile, bytes, tmsY) in batch) {
                        stmt.bindLong(1, tile.third.toLong())
                        stmt.bindLong(2, tile.first.toLong())
                        stmt.bindLong(3, tmsY.toLong())
                        stmt.bindBlob(4, bytes)
                        stmt.executeInsert()
                        stmt.clearBindings()
                    }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }
        }

        // Stream tiles by zoom level and y-stripe. Each stripe owns a small in-memory list,
        // so total RAM is O(stripe width) instead of O(total tiles).
        zoomLoop@ for (z in minZoom..maxZoom) {
            if (!isDownloading.get()) break
            val n = 1 shl z
            val xMin = lonToTileX(bounds.west, n)
            val xMax = lonToTileX(bounds.east, n)
            val yMin = latToTileY(bounds.north, n)
            val yMax = latToTileY(bounds.south, n)

            for (y in yMin..yMax) {
                if (!isDownloading.get()) break@zoomLoop

                val tmsY = (1 shl z) - 1 - y
                // Existing tile_columns for this stripe: small, bounded by (xMax-xMin+1).
                val existingX = HashSet<Int>()
                try {
                    db.rawQuery(
                        "SELECT tile_column FROM tiles WHERE zoom_level=? AND tile_row=?",
                        arrayOf(z.toString(), tmsY.toString())
                    ).use { c -> while (c.moveToNext()) existingX.add(c.getInt(0)) }
                } catch (_: Exception) {}

                val stripeTiles = ArrayList<Triple<Int, Int, Int>>()
                var stripeAlreadyDone = 0
                for (x in xMin..xMax) {
                    if (polygon != null && polygon.size >= 3) {
                        val centerLat = tileCenterLat(y, z)
                        val centerLon = tileCenterLon(x, z)
                        if (!pointInPolygon(centerLat, centerLon, polygon)) continue
                    }
                    if (x in existingX) {
                        stripeAlreadyDone++
                        continue
                    }
                    stripeTiles.add(Triple(x, y, z))
                }
                if (stripeAlreadyDone > 0) {
                    successful.addAndGet(stripeAlreadyDone)
                    downloaded.addAndGet(stripeAlreadyDone)
                    notifyProgressThrottled()
                }
                if (stripeTiles.isEmpty()) continue

                val stripeLatch = java.util.concurrent.CountDownLatch(stripeTiles.size)
                for ((tx, ty, tz) in stripeTiles) {
                    if (!isDownloading.get()) {
                        // Drain remaining counts so the latch can release without leaking tasks.
                        stripeLatch.countDown()
                        continue
                    }
                    try {
                        inFlight.acquire()
                    } catch (_: InterruptedException) {
                        stripeLatch.countDown()
                        continue
                    }
                    executor.submit {
                        var tileOk = false
                        try {
                            val url = buildTileUrl(sourceInfo, tx, ty, tz)
                            if (url == null) {
                                Log.w("TileDownload", "Tile URL missing: z=$tz x=$tx y=$ty layer=${layer.layerKey}")
                                return@submit
                            }

                            val retryDelaysMs = longArrayOf(1_000L, 5_000L, 30_000L)
                            var lastError: Exception? = null
                            for (attempt in 0..retryDelaysMs.size) {
                                if (!isDownloading.get()) break
                                try {
                                    val request = Request.Builder().url(url)
                                        .header("User-Agent", "RaceNav/2.1 Android")
                                        .build()
                                    val response = client.newCall(request).execute()
                                    response.use { resp ->
                                        if (resp.isSuccessful) {
                                            val contentType = resp.body?.contentType()?.toString()
                                                ?: resp.header("Content-Type").orEmpty()
                                            val bytes = resp.body?.bytes()
                                            if (bytes != null && bytes.isNotEmpty() && isSupportedRasterTile(bytes)) {
                                                val rowTms = (1 shl tz) - 1 - ty
                                                insertBuffer.add(Triple(Triple(tx, ty, tz), bytes, rowTms))
                                                bytesTotal.addAndGet(bytes.size.toLong())
                                                if (insertBuffer.size >= BATCH_SIZE) flushBatch()
                                                tileOk = true
                                                lastError = null
                                            } else {
                                                lastError = IllegalStateException(
                                                    if (bytes == null || bytes.isEmpty()) {
                                                        "Empty tile body"
                                                    } else {
                                                        "Unsupported tile body (${contentType.ifBlank { "unknown" }})"
                                                    }
                                                )
                                            }
                                        } else {
                                            lastError = IllegalStateException("HTTP ${resp.code}")
                                        }
                                    }
                                    if (tileOk) break
                                } catch (e: Exception) {
                                    lastError = e
                                }
                                if (attempt < retryDelaysMs.size && !tileOk && isDownloading.get()) {
                                    Thread.sleep(retryDelaysMs[attempt])
                                }
                            }
                            if (lastError != null) {
                                Log.w("TileDownload", "Tile fail after retries: z=$tz x=$tx y=$ty: $lastError")
                            }
                        } catch (e: Exception) {
                            Log.w("TileDownload", "Tile fail: $e")
                        } finally {
                            val interrupted = !tileOk && (paused.get() || cancelled.get())
                            if (tileOk) {
                                successful.incrementAndGet()
                                downloaded.incrementAndGet()
                            } else if (!interrupted) {
                                failed.incrementAndGet()
                                downloaded.incrementAndGet()
                            }
                            notifyProgressThrottled()
                            inFlight.release()
                            stripeLatch.countDown()
                        }
                    }
                }
                stripeLatch.await()
                // Periodic flush keeps the insert buffer (and SQLite WAL) bounded.
                flushBatch()
            }
        }

        // After pause/cancel HTTP is already aborted via client.dispatcher.cancelAll(); drop queued tasks
        // immediately so the worker exits quickly enough for awaitIdle()'s 20s window in MapFragment.
        // On normal completion we let in-flight finishes drain within 15s — also under awaitIdle's budget.
        if (paused.get() || cancelled.get()) {
            executor.shutdownNow()
        } else {
            executor.shutdown()
            try { executor.awaitTermination(15, java.util.concurrent.TimeUnit.SECONDS) } catch (_: InterruptedException) {}
        }
        flushBatch()

        // Final integrity check: if download was not paused/cancelled but DB has fewer tiles
        // than expected, surface an explicit error instead of silently reporting success.
        if (!paused.get() && !cancelled.get()) {
            val expected = countTilesIn(bounds, polygon, minZoom, maxZoom)
            val actual = try {
                db.rawQuery("SELECT COUNT(*) FROM tiles", null).use { c ->
                    if (c.moveToFirst()) c.getInt(0) else 0
                }
            } catch (_: Exception) { -1 }
            if (actual in 0 until expected) {
                val missing = expected - actual
                Log.w("TileDownload", "Layer ${layer.layerKey} incomplete: $actual / $expected (missing $missing)")
                if (error == null) {
                    error = "Скачивание неполное: $actual из $expected тайлов. Нажмите Resume."
                }
            }
        }

        db.close()
        Log.d("TileDownload", "downloadLayerSync DONE: ${layer.layerKey}, file size=${dbFile.length()}")
    }

    /** Tile count for a rectangular region (optionally clipped by polygon) across a zoom range. */
    private fun countTilesIn(
        bounds: BoundsRect,
        polygon: List<Pair<Double, Double>>?,
        minZoom: Int,
        maxZoom: Int
    ): Int {
        if (polygon == null || polygon.size < 3) return estimateTiles(bounds, minZoom, maxZoom)
        var count = 0
        for (z in minZoom..maxZoom) {
            val n = 1 shl z
            val xMin = lonToTileX(bounds.west, n)
            val xMax = lonToTileX(bounds.east, n)
            val yMin = latToTileY(bounds.north, n)
            val yMax = latToTileY(bounds.south, n)
            for (x in xMin..xMax) {
                for (y in yMin..yMax) {
                    if (pointInPolygon(tileCenterLat(y, z), tileCenterLon(x, z), polygon)) count++
                }
            }
        }
        return count
    }

    private val lastNotifyTime = AtomicLong(0L)

    private fun notifyProgressThrottled() {
        val now = System.currentTimeMillis()
        if (now - lastNotifyTime.get() < 500) return
        lastNotifyTime.set(now)
        notifyProgress()
    }

    private fun notifyProgress(snapshot: DownloadStateSnapshot = buildStateSnapshot()) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            onProgressUpdate?.invoke(snapshot)
        }
    }

    private fun isSupportedRasterTile(bytes: ByteArray): Boolean = when {
        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> true
        bytes.size >= 4 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() -> true
        bytes.size >= 12 && bytes[0] == 0x52.toByte() && bytes[1] == 0x49.toByte()
            && bytes[2] == 0x46.toByte() && bytes[3] == 0x46.toByte()
            && bytes[8] == 0x57.toByte() && bytes[9] == 0x45.toByte()
            && bytes[10] == 0x42.toByte() && bytes[11] == 0x50.toByte() -> true
        else -> false
    }

    /** Build a tile URL from the source info urls list, substituting {z}/{x}/{y} */
    private fun buildTileUrl(info: MapFragment.Companion.TileSourceInfo?, x: Int, y: Int, z: Int): String? {
        if (info == null) return null
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
