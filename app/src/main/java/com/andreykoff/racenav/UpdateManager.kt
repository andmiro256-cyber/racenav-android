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

    // Pending APK path for retry after permission grant
    var pendingApkFile: File? = null

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
                val totalBytes = conn.contentLengthLong
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
                    val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(apkUrl))
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                }
            }
        }
    }

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
        context.startActivity(intent)
    }

    /** Call from Activity.onResume to retry install after permission grant */
    fun retryPendingInstall(context: Context) {
        val apk = pendingApkFile ?: return
        if (apk.exists() && context.packageManager.canRequestPackageInstalls()) {
            installApk(context, apk)
        }
    }
}
