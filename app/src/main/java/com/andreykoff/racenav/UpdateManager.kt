package com.andreykoff.racenav

import android.content.Context
import android.content.Intent
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

    const val UPDATE_URL = "http://87.120.84.254/updates/latest.json"

    /**
     * Download APK with progress callback.
     * @param onProgress (bytesRead, totalBytes) — called on Main thread; totalBytes = -1 if unknown
     * @param onComplete called on Main thread when download finishes (success or error)
     */
    fun downloadAndInstall(
        context: Context,
        apkUrl: String,
        version: String,
        onProgress: ((Long, Long) -> Unit)? = null,
        onComplete: ((success: Boolean, error: String?) -> Unit)? = null
    ) {
        val apkFile = File(context.externalCacheDir, "racenav-update.apk")

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val conn = URL(apkUrl).openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                conn.connect()
                val totalBytes = conn.contentLengthLong  // -1 if unknown
                var bytesRead = 0L

                conn.inputStream.use { input ->
                    apkFile.outputStream().use { output ->
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
                withContext(Dispatchers.Main) {
                    onComplete?.invoke(true, null)
                    installApk(context, apkFile)
                }
            } catch (e: Exception) {
                Log.e("UpdateManager", "Download failed: ${e.message}")
                withContext(Dispatchers.Main) {
                    onComplete?.invoke(false, e.message)
                    Toast.makeText(context, "Ошибка скачивания: ${e.message}", Toast.LENGTH_LONG).show()
                    // Fallback to browser
                    val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(apkUrl))
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                }
            }
        }
    }

    /** Compare version strings like "2.0.39" > "2.0.8" */
    fun isNewer(remote: String, local: String): Boolean {
        val r = remote.removePrefix("v").split(".").map { it.toIntOrNull() ?: 0 }
        val l = local.removePrefix("v").split(".").map { it.toIntOrNull() ?: 0 }
        val maxLen = maxOf(r.size, l.size)
        for (i in 0 until maxLen) {
            val rv = r.getOrElse(i) { 0 }
            val lv = l.getOrElse(i) { 0 }
            if (rv > lv) return true
            if (rv < lv) return false
        }
        return false
    }

    private fun installApk(context: Context, apkFile: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.provider",
            apkFile
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
