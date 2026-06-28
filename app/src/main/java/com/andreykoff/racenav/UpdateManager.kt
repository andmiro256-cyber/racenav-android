package com.andreykoff.racenav

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object UpdateManager {

    const val UPDATE_BASE_URL = "https://trophynav.ru"
    const val LEGACY_UPDATE_BASE_URL = "http://87.120.84.254"
    const val UPDATE_URL = "$UPDATE_BASE_URL/updates/latest.json"
    const val BETA_UPDATE_URL = "$UPDATE_BASE_URL/api/update/beta"
    const val UPDATE_CHANNEL_URL = "$UPDATE_BASE_URL/api/update-channel"
    const val LEGACY_BETA_UPDATE_URL = "$LEGACY_UPDATE_BASE_URL/api/update/beta"
    const val LEGACY_UPDATE_CHANNEL_URL = "$LEGACY_UPDATE_BASE_URL/api/update-channel"

    // Pending APK path for retry after permission grant
    var pendingApkFile: File? = null

    fun downloadAndInstall(
        context: Context,
        apkUrl: String,
        version: String,
        onProgress: ((Long, Long) -> Unit)? = null,
        onComplete: ((success: Boolean, error: String?) -> Unit)? = null
    ) {
        val apkFile = File(context.externalCacheDir ?: context.filesDir, "racenav-update.apk")
        val tmpFile = File(apkFile.absolutePath + ".tmp")

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val conn = URL(apkUrl).openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                conn.connect()
                val totalBytes = conn.contentLengthLong
                var bytesRead = 0L

                conn.inputStream.use { input ->
                    tmpFile.outputStream().use { output ->
                        val buf = ByteArray(8192)
                        var n: Int
                        while (input.read(buf).also { n = it } != -1) {
                            output.write(buf, 0, n)
                            bytesRead += n
                            onProgress?.let { cb ->
                                val br = bytesRead
                                val tb = totalBytes
                                withContext(Dispatchers.Main) { cb(br, tb) }
                            }
                        }
                    }
                }
                // Validate: check file size matches Content-Length (if known)
                if (totalBytes > 0 && tmpFile.length() != totalBytes) {
                    tmpFile.delete()
                    throw Exception("Incomplete download: ${tmpFile.length()} / $totalBytes bytes")
                }
                // Atomic rename: only replace target if download was complete
                tmpFile.renameTo(apkFile)
                withContext(Dispatchers.Main) {
                    onComplete?.invoke(true, null)
                    installApk(context, apkFile)
                }
            } catch (e: Exception) {
                Log.e("UpdateManager", "Download failed: ${e.message}")
                withContext(Dispatchers.Main) {
                    onComplete?.invoke(false, e.message)
                    Toast.makeText(context, "Ошибка скачивания: ${e.message}", Toast.LENGTH_LONG).show()
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(apkUrl))
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                    } catch (_: Exception) { /* no browser available */ }
                }
            }
        }
    }

    fun isNewer(remote: String, local: String): Boolean {
        if (remote.isBlank() || local.isBlank()) return false
        val remoteVersion = parseVersion(remote)
        val localVersion = parseVersion(local)

        val maxLen = maxOf(remoteVersion.base.size, localVersion.base.size)
        for (i in 0 until maxLen) {
            val rv = remoteVersion.base.getOrElse(i) { 0 }
            val lv = localVersion.base.getOrElse(i) { 0 }
            if (rv > lv) return true
            if (rv < lv) return false
        }

        val remotePre = remoteVersion.prerelease
        val localPre = localVersion.prerelease
        if (remotePre == null && localPre == null) return false
        if (remotePre == null) return true
        if (localPre == null) return false

        if (remotePre.rank != localPre.rank) return remotePre.rank > localPre.rank
        if (remotePre.number != localPre.number) return remotePre.number > localPre.number
        return remotePre.raw > localPre.raw
    }

    fun installedVersionCode(context: Context): Long {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
    }

    fun isUpdateAvailable(
        remoteVersion: String,
        remoteVersionCode: Long,
        localVersion: String,
        localVersionCode: Long
    ): Boolean {
        if (remoteVersionCode > 0L && localVersionCode > 0L) {
            return remoteVersionCode > localVersionCode
        }
        return isNewer(remoteVersion, localVersion)
    }

    private data class ParsedVersion(
        val base: List<Int>,
        val prerelease: ParsedPrerelease?
    )

    private data class ParsedPrerelease(
        val raw: String,
        val rank: Int,
        val number: Int
    )

    private fun parseVersion(raw: String): ParsedVersion {
        val normalized = raw.removePrefix("v").trim().lowercase()
        val basePart = normalized.substringBefore('-')
        val suffixPart = normalized.substringAfter('-', "").trim()
        val base = basePart.split('.').map { it.toIntOrNull() ?: 0 }
        return ParsedVersion(
            base = base,
            prerelease = suffixPart.takeIf { it.isNotBlank() }?.let(::parsePrerelease)
        )
    }

    private fun parsePrerelease(raw: String): ParsedPrerelease {
        val compact = raw.replace(".", "").trim()
        val match = Regex("([a-z]+)(\\d+)?").matchEntire(compact)
        val label = match?.groupValues?.getOrNull(1).orEmpty()
        val number = match?.groupValues?.getOrNull(2)?.toIntOrNull() ?: 0
        val rank = when (label) {
            "alpha" -> 0
            "beta" -> 1
            "rc" -> 2
            else -> 1
        }
        return ParsedPrerelease(
            raw = compact,
            rank = rank,
            number = number
        )
    }

    private fun installApk(context: Context, apkFile: File) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            // Save pending APK for retry in onResume
            pendingApkFile = apkFile
            Toast.makeText(context, "Разрешите установку, затем вернитесь в приложение", Toast.LENGTH_LONG).show()
            val settingsIntent = Intent(
                android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                android.net.Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(settingsIntent)
            return
        }

        pendingApkFile = null
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", apkFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e("UpdateManager", "Install failed: ${e.message}")
            Toast.makeText(context, "Ошибка установки: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /** Call from Activity.onResume to retry install after permission grant */
    fun retryPendingInstall(context: Context) {
        val apk = pendingApkFile ?: return
        if (apk.exists() && context.packageManager.canRequestPackageInstalls()) {
            installApk(context, apk)
        }
    }
}
