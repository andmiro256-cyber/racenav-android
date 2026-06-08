package com.andreykoff.racenav

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import org.json.JSONArray
import java.io.File

object MapStorageManager {

    const val PREF_MAP_STORAGE_TARGET = "map_storage_target"
    private const val TARGET_PUBLIC_DOCUMENTS = "public_documents"
    private const val TARGET_APP_PRIMARY = "app_primary"
    private const val TARGET_APP_EXTERNAL_PREFIX = "app_external:"

    data class StorageOption(
        val id: String,
        val label: String,
        val dir: File
    )

    data class MigrationResult(
        val movedFiles: Int,
        val updatedEntries: Int,
        val newDir: File
    )

    fun getAvailableOptions(context: Context): List<StorageOption> {
        val options = mutableListOf<StorageOption>()
        options += StorageOption(
            id = TARGET_PUBLIC_DOCUMENTS,
            label = "Documents/RaceNav",
            dir = publicDocumentsMapsDir()
        )
        options += StorageOption(
            id = TARGET_APP_PRIMARY,
            label = "Память приложения",
            dir = primaryAppMapsDir(context)
        )

        var removableIndex = 1
        context.getExternalFilesDirs(null)
            .drop(1)
            .filterNotNull()
            .forEach { baseDir ->
                val removable = try {
                    Environment.isExternalStorageRemovable(baseDir)
                } catch (_: Exception) {
                    true
                }
                val label = if (removable) {
                    if (removableIndex == 1) "SD-карта" else "SD-карта $removableIndex"
                } else {
                    "Доп. хранилище"
                }
                options += StorageOption(
                    id = TARGET_APP_EXTERNAL_PREFIX + baseDir.absolutePath,
                    label = label,
                    dir = File(baseDir, "RaceNav/maps")
                )
                removableIndex++
            }
        return options.distinctBy { safePath(it.dir) }
    }

    fun getCurrentOption(context: Context): StorageOption {
        val prefs = context.getSharedPreferences(MapFragment.PREFS_NAME, Context.MODE_PRIVATE)
        val savedTarget = prefs.getString(PREF_MAP_STORAGE_TARGET, null)
        val options = getAvailableOptions(context)
        val defaultId = savedTarget ?: TARGET_PUBLIC_DOCUMENTS
        return options.firstOrNull { it.id == defaultId }
            ?: options.firstOrNull { it.id == TARGET_APP_PRIMARY }
            ?: options.first()
    }

    fun getMapsDir(context: Context): File {
        return getCurrentOption(context).dir.also { it.mkdirs() }
    }

    fun listSupportedMapFiles(context: Context): List<File> {
        val dirs = linkedSetOf<File>()
        dirs += getMapsDir(context)
        dirs += publicDocumentsMapsDir()
        dirs += primaryAppMapsDir(context)
        getAvailableOptions(context).forEach { dirs += it.dir }

        return dirs.flatMap { dir ->
            runCatching {
                dir.listFiles()
                    ?.filter { it.isFile && isSupportedMapFile(it) }
                    .orEmpty()
            }.getOrDefault(emptyList())
        }
            .distinctBy { safePath(it) }
            .sortedBy { it.name.lowercase() }
    }

    fun createManagedMapFile(context: Context, originalName: String): File {
        val dir = getMapsDir(context)
        val sanitized = sanitizeFileName(originalName)
        return uniqueFile(dir, sanitized)
    }

    fun getDisplayName(context: Context, uri: Uri, fallback: String = "map"): String {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) {
                    cursor.getString(idx)?.let { name ->
                        sanitizeFileName(name).takeIf { it.isNotBlank() }?.let { return it }
                    }
                }
            }
        }
        val rawPath = Uri.decode(uri.lastPathSegment ?: "")
        val rawName = rawPath.substringAfterLast('/').substringAfterLast(':')
        return sanitizeFileName(rawName.ifBlank { fallback })
    }

    fun resolveExistingMapFile(context: Context, storedPath: String): File? {
        if (storedPath.isBlank()) return null
        val direct = File(storedPath)
        if (direct.exists()) return direct.takeIf { isSupportedMapFile(it) }
        val fileName = direct.name.takeIf { it.isNotBlank() } ?: return null
        val candidates = linkedSetOf<File>()
        candidates += File(getMapsDir(context), fileName)
        candidates += File(publicDocumentsMapsDir(), fileName)
        candidates += File(primaryAppMapsDir(context), fileName)
        getAvailableOptions(context).forEach { option ->
            candidates += File(option.dir, fileName)
        }
        candidates += File(context.filesDir, fileName)
        return candidates.firstOrNull { it.exists() && isSupportedMapFile(it) }
    }

    @Synchronized
    fun migrateToTarget(
        context: Context,
        targetId: String,
        shouldCancel: () -> Boolean = { false }
    ): MigrationResult {
        fun checkCancelled() {
            if (shouldCancel()) throw java.util.concurrent.CancellationException("Перенос отменён")
        }
        val prefs = context.getSharedPreferences(MapFragment.PREFS_NAME, Context.MODE_PRIVATE)
        val current = getCurrentOption(context)
        val target = getAvailableOptions(context).firstOrNull { it.id == targetId }
            ?: throw IllegalArgumentException("Unknown storage target: $targetId")
        if (safePath(current.dir) == safePath(target.dir)) {
            prefs.edit().putString(PREF_MAP_STORAGE_TARGET, target.id).apply()
            return MigrationResult(0, 0, target.dir.also { it.mkdirs() })
        }

        val oldDir = current.dir.also { it.mkdirs() }
        val newDir = target.dir.also { it.mkdirs() }
        var movedFiles = 0
        var updatedEntries = 0
        checkCancelled()

        val offlineMapsJson = prefs.getString(MapFragment.PREF_OFFLINE_MAPS_JSON, null)
        var migratedOfflineMapsJson: String? = null
        if (!offlineMapsJson.isNullOrBlank()) {
            val arr = JSONArray(offlineMapsJson)
            for (i in 0 until arr.length()) {
                checkCancelled()
                val obj = arr.optJSONObject(i) ?: continue
                val oldPath = obj.optString("path")
                val src = resolveExistingMapFile(context, oldPath) ?: continue
                val dest = if (sameDir(src.parentFile, oldDir)) {
                    moveToExactLocation(src, File(newDir, src.name), shouldCancel)
                } else {
                    moveToUniqueLocation(src, newDir, shouldCancel)
                }
                if (safePath(src) != safePath(dest)) movedFiles++
                if (obj.optString("path") != dest.absolutePath) {
                    obj.put("path", dest.absolutePath)
                    updatedEntries++
                }
            }
            migratedOfflineMapsJson = arr.toString()
        }

        oldDir.listFiles()
            ?.filter { it.isFile && isSupportedMapFile(it) }
            ?.forEach { src ->
                checkCancelled()
                if (!src.exists()) return@forEach
                val dest = moveToExactLocation(src, File(newDir, src.name), shouldCancel)
                if (safePath(src) != safePath(dest)) movedFiles++
            }

        var oldAreasJsonToDelete: File? = null
        val oldAreasJson = File(oldDir, "areas.json")
        if (oldAreasJson.exists() && oldAreasJson.isFile) {
            checkCancelled()
            val dest = copyToExactLocation(oldAreasJson, File(newDir, oldAreasJson.name), shouldCancel)
            if (safePath(oldAreasJson) != safePath(dest)) {
                movedFiles++
                oldAreasJsonToDelete = oldAreasJson
            }
        }

        checkCancelled()
        prefs.edit().apply {
            if (migratedOfflineMapsJson != null) {
                putString(MapFragment.PREF_OFFLINE_MAPS_JSON, migratedOfflineMapsJson)
            }
            putString(PREF_MAP_STORAGE_TARGET, target.id)
        }.apply()
        oldAreasJsonToDelete?.delete()
        return MigrationResult(movedFiles, updatedEntries, newDir)
    }

    private fun publicDocumentsMapsDir(): File {
        val docsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        return File(docsDir, "RaceNav/maps")
    }

    private fun primaryAppMapsDir(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, "RaceNav/maps")
    }

    private fun sanitizeFileName(name: String): String {
        val cleaned = name.trim().replace(Regex("[/\\\\:*?\"<>|]"), "_")
        return if (cleaned.isNotBlank()) cleaned else "offline_map.mbtiles"
    }

    private fun uniqueFile(dir: File, name: String): File {
        dir.mkdirs()
        val baseName = name.substringBeforeLast('.', name)
        val extension = name.substringAfterLast('.', "")
        var candidate = File(dir, name)
        var index = 2
        while (candidate.exists()) {
            val fileName = if (extension.isNotBlank()) "${baseName}_($index).$extension" else "${baseName}_($index)"
            candidate = File(dir, fileName)
            index++
        }
        return candidate
    }

    private fun moveToExactLocation(src: File, dest: File, shouldCancel: () -> Boolean = { false }): File {
        if (shouldCancel()) throw java.util.concurrent.CancellationException("Перенос отменён")
        if (safePath(src) == safePath(dest)) return dest
        dest.parentFile?.mkdirs()
        if (dest.exists()) {
            if (dest.length() == src.length()) {
                src.delete()
                return dest
            }
            throw IllegalStateException("Файл уже существует: ${dest.name}")
        }
        return moveFile(src, dest, shouldCancel)
    }

    private fun moveToUniqueLocation(src: File, dir: File, shouldCancel: () -> Boolean = { false }): File {
        if (shouldCancel()) throw java.util.concurrent.CancellationException("Перенос отменён")
        if (sameDir(src.parentFile, dir)) return src
        return moveFile(src, uniqueFile(dir, src.name), shouldCancel)
    }

    private fun copyToExactLocation(src: File, dest: File, shouldCancel: () -> Boolean = { false }): File {
        if (shouldCancel()) throw java.util.concurrent.CancellationException("Перенос отменён")
        if (safePath(src) == safePath(dest)) return dest
        dest.parentFile?.mkdirs()
        if (dest.exists()) {
            if (dest.length() == src.length()) {
                return dest
            }
            throw IllegalStateException("Файл уже существует: ${dest.name}")
        }
        return copyFile(src, dest, shouldCancel)
    }

    private fun moveFile(src: File, dest: File, shouldCancel: () -> Boolean = { false }): File {
        if (shouldCancel()) throw java.util.concurrent.CancellationException("Перенос отменён")
        if (safePath(src) == safePath(dest)) return dest
        dest.parentFile?.mkdirs()
        if (src.renameTo(dest)) return dest
        try {
            src.inputStream().use { input ->
                dest.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        if (shouldCancel()) throw java.util.concurrent.CancellationException("Перенос отменён")
                        val bytes = input.read(buffer)
                        if (bytes < 0) break
                        output.write(buffer, 0, bytes)
                    }
                }
            }
        } catch (e: java.util.concurrent.CancellationException) {
            runCatching { dest.delete() }
            throw e
        }
        if (!src.delete()) src.deleteOnExit()
        return dest
    }

    private fun copyFile(src: File, dest: File, shouldCancel: () -> Boolean = { false }): File {
        if (shouldCancel()) throw java.util.concurrent.CancellationException("Перенос отменён")
        if (safePath(src) == safePath(dest)) return dest
        dest.parentFile?.mkdirs()
        try {
            src.inputStream().use { input ->
                dest.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        if (shouldCancel()) throw java.util.concurrent.CancellationException("Перенос отменён")
                        val bytes = input.read(buffer)
                        if (bytes < 0) break
                        output.write(buffer, 0, bytes)
                    }
                }
            }
        } catch (e: java.util.concurrent.CancellationException) {
            runCatching { dest.delete() }
            throw e
        }
        return dest
    }

    fun isSupportedMapFile(file: File): Boolean {
        return isSupportedMapFileName(file.name)
    }

    fun isSupportedMapFileName(name: String): Boolean {
        val lower = name.lowercase()
        if (lower.endsWith("-journal") || lower.endsWith("-wal") || lower.endsWith("-shm")) {
            return false
        }
        val ext = lower.substringAfterLast('.', "")
        return ext == "mbtiles" || ext == "sqlitedb" || ext == "db"
    }

    private fun sameDir(a: File?, b: File): Boolean {
        if (a == null) return false
        return safePath(a) == safePath(b)
    }

    private fun safePath(file: File): String {
        return try {
            file.canonicalPath
        } catch (_: Exception) {
            file.absolutePath
        }
    }
}
