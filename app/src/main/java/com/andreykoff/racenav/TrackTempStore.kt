package com.andreykoff.racenav

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

object TrackTempStore {
    const val TMP_FILENAME = "current_track_tmp.gpx"
    private const val NEW_FILENAME = "current_track_tmp.gpx.new"
    private const val BAK_FILENAME = "current_track_tmp.gpx.bak"

    data class TempTrack(val file: File, val result: GpxResult)

    fun hasAny(context: Context): Boolean = candidateFiles(context).any { it.exists() && it.length() > 0L }

    fun readBest(context: Context, logTag: String = "TrackTempStore"): TempTrack? {
        for (file in candidateFiles(context)) {
            if (!file.exists() || file.length() == 0L) continue
            val result = try {
                file.inputStream().use { GpxParser.parseGpxFull(it) }
            } catch (e: Exception) {
                Log.w(logTag, "Failed to parse temp track ${file.name}: ${e.message}")
                null
            } ?: continue
            if (result.trackPoints.any { !it.first.isNaN() && !it.second.isNaN() }) {
                return TempTrack(file, result)
            }
            Log.w(logTag, "Temp track ${file.name} has no valid track points")
        }
        return null
    }

    fun writeAtomic(
        context: Context,
        points: List<Pair<Double, Double>>,
        pointTimes: List<Long?>,
        trackName: String = "Текущий трек"
    ) {
        if (points.isEmpty()) return
        val tmpFile = File(context.filesDir, TMP_FILENAME)
        val newFile = File(context.filesDir, NEW_FILENAME)
        val bakFile = File(context.filesDir, BAK_FILENAME)
        val gpx = GpxParser.writeGpx(points, trackName, pointTimes)

        if (newFile.exists() && !newFile.delete()) {
            Log.w("TrackTempStore", "Could not delete stale ${newFile.name}")
        }
        newFile.writeText(gpx)

        if (tmpFile.exists() && tmpFile.length() > 0L) {
            try {
                Files.copy(tmpFile.toPath(), bakFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } catch (e: Exception) {
                Log.w("TrackTempStore", "Failed to refresh ${bakFile.name}: ${e.message}")
            }
        }

        try {
            Files.move(
                newFile.toPath(),
                tmpFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: Exception) {
            Files.move(newFile.toPath(), tmpFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }

        if (!tmpFile.exists() || tmpFile.length() == 0L) {
            throw IOException("Temp track was not written")
        }
    }

    fun deleteAll(context: Context) {
        candidateFiles(context).forEach { file ->
            if (file.exists() && !file.delete()) {
                Log.w("TrackTempStore", "Could not delete ${file.name}")
            }
        }
    }

    private fun candidateFiles(context: Context): List<File> = listOf(
        File(context.filesDir, NEW_FILENAME),
        File(context.filesDir, TMP_FILENAME),
        File(context.filesDir, BAK_FILENAME)
    )
}
