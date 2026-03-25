package com.andreykoff.racenav

import android.content.Context
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.andreykoff.racenav.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        // Head units (Li Auto, Chinese tablets) have large screens with low density (160dpi),
        // making everything tiny. Scale up density only for this app.
        // User needs to press "fullscreen" button on Li Auto to avoid letterboxing.
        val metrics = resources.displayMetrics
        if (metrics.densityDpi <= 160 && maxOf(metrics.widthPixels, metrics.heightPixels) >= 1920) {
            val scale = 2.0f
            metrics.density = scale
            metrics.scaledDensity = scale
            metrics.densityDpi = (160 * scale).toInt()
        }

        super.onCreate(savedInstanceState)

        // Crash logger — saves stacktrace to /sdcard/Download/racenav_crash.txt
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val f = java.io.File(
                    android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_DOWNLOADS),
                    "racenav_crash.txt")
                f.writeText("${java.util.Date()}\nThread: ${t.name}\n${e.stackTraceToString()}")
            } catch (_: Exception) {}
            defaultHandler?.uncaughtException(t, e)
        }

        applyKeepScreen()
        applyOrientation()
        // Tell system this app handles volume keys — prevents MIUI/EMUI intercepting them
        volumeControlStream = android.media.AudioManager.STREAM_MUSIC
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // License system
        LicenseManager.ensureInstallTime(this)

        // Anonymous analytics — app always loads (free mode after trial)
        if (savedInstanceState == null) {
            Analytics.sendEvent(this, if (LicenseManager.hasFullAccess(this)) "launch" else "launch_free")
            DiagnosticsCollector.rotateLog(this)
            DiagnosticsCollector.sendToServer(this)
            DiagnosticsCollector.sendPendingIfNeeded(this)
            supportFragmentManager.beginTransaction()
                .replace(R.id.container, MapFragment())
                .commit()
            handleFileIntent(intent)

            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                checkForAppUpdate()
            }, 3000)

            // Trial warning (5 days or less left)
            if (LicenseManager.shouldShowTrialWarning(this)) {
                val days = LicenseManager.trialDaysLeft(this)
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Пробный период заканчивается")
                    .setMessage("Осталось $days дн.\n\nПосле окончания запись треков, экспорт, синхронизация и другие функции будут заблокированы.\n\nКарта и навигация продолжат работать.")
                    .setPositiveButton("Подробнее") { _, _ ->
                        try { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://trophynav.ru"))) }
                        catch (_: Exception) {}
                    }
                    .setNegativeButton("Позже", null)
                    .show()
            }
            // Free mode notification (trial expired)
            else if (LicenseManager.isFreeMode(this)) {
                showTrialExpiredDialog()
            }

            // Check license from server in background
            Thread { LicenseManager.checkLicenseFromServer(this) }.start()
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleFileIntent(intent)
    }

    private fun handleFileIntent(intent: android.content.Intent?) {
        val uri = intent?.data ?: return
        if (intent.action != android.content.Intent.ACTION_VIEW) return

        // Delay slightly to let MapFragment initialize
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (!LicenseManager.hasFullAccess(this)) {
                LicenseManager.showLicenseRequired(this)
                return@postDelayed
            }
            try {
                val fileName = uri.lastPathSegment?.lowercase() ?: ""
                val mapFrag = supportFragmentManager.fragments.filterIsInstance<MapFragment>().firstOrNull()
                    ?: return@postDelayed

                // Handle offline maps separately — they can be very large
                if (fileName.endsWith(".sqlitedb") || fileName.endsWith(".mbtiles") || fileName.endsWith(".db")) {
                    val ext = fileName.substringAfterLast('.', "sqlitedb")
                    val dest = java.io.File(filesDir, "offline_map_${System.currentTimeMillis()}.$ext")
                    contentResolver.openInputStream(uri)?.use { input ->
                        dest.outputStream().use { output -> input.copyTo(output) }
                    }
                    val displayName = uri.lastPathSegment?.substringAfterLast('/') ?: "map.$ext"
                    val key = mapFrag.addOfflineMap(dest.absolutePath, displayName)
                    if (key != null) {
                        android.widget.Toast.makeText(this, "🗺 Карта загружена: $displayName", android.widget.Toast.LENGTH_SHORT).show()
                    } else {
                        dest.delete()
                        android.widget.Toast.makeText(this, "Не удалось открыть карту", android.widget.Toast.LENGTH_LONG).show()
                    }
                    return@postDelayed
                }

                // Track/waypoint files — small enough to read into memory
                val inputStream = contentResolver.openInputStream(uri) ?: return@postDelayed
                val bytes = inputStream.readBytes()
                inputStream.close()

                when {
                    fileName.endsWith(".gpx") || intent.type == "application/gpx+xml" -> {
                        val result = GpxParser.parseGpxFull(bytes.inputStream())
                        if (result.waypoints.isNotEmpty()) {
                            mapFrag.loadWaypoints(result.waypoints)
                            android.widget.Toast.makeText(this, "📍 Загружено ${result.waypoints.size} точек из GPX", android.widget.Toast.LENGTH_SHORT).show()
                        }
                        if (result.trackPoints.isNotEmpty()) {
                            mapFrag.loadTrack(result.trackPoints)
                            android.widget.Toast.makeText(this, "🛤 Загружен трек: ${result.trackPoints.size} точек", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                    fileName.endsWith(".wpt") -> {
                        val wpts = GpxParser.parseWpt(bytes.inputStream())
                        if (wpts.isNotEmpty()) {
                            mapFrag.loadWaypoints(wpts)
                            android.widget.Toast.makeText(this, "📍 Загружено ${wpts.size} точек из WPT", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                    fileName.endsWith(".rte") -> {
                        val wpts = GpxParser.parseRteOzi(bytes.inputStream())
                        if (wpts.isNotEmpty()) {
                            mapFrag.loadWaypoints(wpts)
                            android.widget.Toast.makeText(this, "📍 Загружен маршрут: ${wpts.size} точек из RTE", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                    fileName.endsWith(".plt") -> {
                        val wpts = GpxParser.parsePlt(bytes.inputStream())
                        if (wpts.isNotEmpty()) {
                            mapFrag.loadWaypoints(wpts)
                            android.widget.Toast.makeText(this, "📍 Загружено ${wpts.size} точек из PLT", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                    else -> {
                        // Try GPX as fallback
                        try {
                            val result = GpxParser.parseGpxFull(bytes.inputStream())
                            if (result.waypoints.isNotEmpty()) mapFrag.loadWaypoints(result.waypoints)
                            if (result.trackPoints.isNotEmpty()) mapFrag.loadTrack(result.trackPoints)
                        } catch (_: Exception) {}
                    }
                }
            } catch (e: Exception) {
                android.widget.Toast.makeText(this, "Ошибка открытия файла: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
            }
        }, 1500)  // wait for MapFragment to fully load
    }

    private fun showTrialExpiredDialog() {
        val userEmail = getSharedPreferences(MapFragment.PREFS_NAME, Context.MODE_PRIVATE)
            .getString("sync_email", null) ?: ""
        val emailLine = if (userEmail.isNotEmpty()) "\nВаш email: $userEmail\n" else ""
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Пробный период завершён")
            .setMessage("Запись треков, экспорт, синхронизация отключены.\nКарта и навигация работают.$emailLine\nДля полного доступа — info@trophynav.ru")
            .setPositiveButton("trophynav.ru") { _, _ ->
                try { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://trophynav.ru"))) }
                catch (_: Exception) {}
            }
            .setNegativeButton("OK", null)
            .show()
    }

    @Deprecated("Use showTrialExpiredDialog() instead")
    private fun showTrialExpired() {
        val pad = 32
        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad * 2, pad * 3, pad * 2, pad * 2)
            setBackgroundColor(0xFF121212.toInt())
        }

        root.addView(android.widget.TextView(this).apply {
            text = "Trophy Navigator"
            setTextColor(0xFFFF6F00.toInt())
            textSize = 24f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 16)
        })

        root.addView(android.widget.TextView(this).apply {
            text = "Пробный период (${LicenseManager.TRIAL_DAYS} дней) завершён."
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 16f
            setPadding(0, 0, 0, 24)
        })

        // Show registered email if any
        val userEmail = getSharedPreferences(MapFragment.PREFS_NAME, Context.MODE_PRIVATE)
            .getString("sync_email", null) ?: ""
        if (userEmail.isNotEmpty()) {
            root.addView(android.widget.TextView(this).apply {
                text = "Ваш email: $userEmail"
                setTextColor(0xFF888888.toInt())
                textSize = 14f
                setPadding(0, 0, 0, 16)
            })
        }

        root.addView(android.widget.TextView(this).apply {
            text = "Для продолжения работы приобретите лицензию.\nСвяжитесь с нами:"
            setTextColor(0xFFCCCCCC.toInt())
            textSize = 14f
            setPadding(0, 0, 0, 32)
        })

        // Contact: Email
        root.addView(android.widget.TextView(this).apply {
            text = "info@trophynav.ru"
            setTextColor(0xFFFF6F00.toInt())
            textSize = 16f
            setPadding(0, 0, 0, 8)
            setOnClickListener {
                val deviceId = LicenseManager.getShortDeviceId(this@MainActivity)
                val emailIntent = android.content.Intent(android.content.Intent.ACTION_SENDTO).apply {
                    data = android.net.Uri.parse("mailto:info@trophynav.ru")
                    putExtra(android.content.Intent.EXTRA_SUBJECT, "Trophy Navigator — лицензия")
                    putExtra(android.content.Intent.EXTRA_TEXT, "ID устройства: $deviceId\n\nХочу приобрести лицензию.")
                }
                try { startActivity(emailIntent) } catch (_: Exception) {
                    android.widget.Toast.makeText(this@MainActivity, "Почтовый клиент не найден", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        })

        // Contact: Website
        root.addView(android.widget.TextView(this).apply {
            text = "trophynav.ru"
            setTextColor(0xFFFF6F00.toInt())
            textSize = 16f
            setPadding(0, 0, 0, 24)
            setOnClickListener {
                try { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://trophynav.ru"))) }
                catch (_: Exception) {}
            }
        })

        // Device ID (small, copyable)
        val deviceId = LicenseManager.getShortDeviceId(this@MainActivity)
        root.addView(android.widget.TextView(this).apply {
            text = "ID устройства: $deviceId (нажмите чтобы скопировать)"
            setTextColor(0xFF666666.toInt())
            textSize = 12f
            setPadding(0, 16, 0, 0)
            setOnClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Device ID", deviceId))
                android.widget.Toast.makeText(this@MainActivity, "ID скопирован: $deviceId", android.widget.Toast.LENGTH_SHORT).show()
            }
        })

        val scroll = android.widget.ScrollView(this).apply {
            addView(root)
            setBackgroundColor(0xFF121212.toInt())
        }
        setContentView(scroll)
    }

    fun applyKeepScreen() {
        val keep = getSharedPreferences(MapFragment.PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(MapFragment.PREF_KEEP_SCREEN, true)
        if (keep) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    fun applyOrientation() {
        val ori = getSharedPreferences(MapFragment.PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(MapFragment.PREF_ORIENTATION, 0)
        requestedOrientation = when (ori) {
            1 -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            2 -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    override fun onResume() {
        super.onResume()
        UpdateManager.retryPendingInstall(this)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        // If we're in a sub-fragment (Settings etc.) — go back normally
        if (supportFragmentManager.backStackEntryCount > 0) {
            super.onBackPressed()
            return
        }
        val mapFrag = supportFragmentManager.findFragmentById(R.id.container) as? MapFragment
        // Block Back when screen is locked — exit only after unlock
        if (mapFrag?.isScreenLocked == true) return
        if (TrackingService.isRunning) {
            // Recording active — offer to save before exit
            AlertDialog.Builder(this)
                .setTitle("Идёт запись трека")
                .setMessage("Трек записывается. Что сделать перед выходом?")
                .setPositiveButton("Сохранить и выйти") { _, _ ->
                    mapFrag?.saveTrackToFile()
                    stopAllServices()
                    finishAndRemoveTask()
                }
                .setNeutralButton("Выйти без сохранения") { _, _ ->
                    stopAllServices()
                    finishAndRemoveTask()
                }
                .setNegativeButton("Отмена", null)
                .show()
        } else {
            AlertDialog.Builder(this)
                .setMessage("Выйти из приложения?")
                .setPositiveButton("Выйти") { _, _ ->
                    stopAllServices()
                    finishAndRemoveTask()
                }
                .setNegativeButton("Отмена", null)
                .show()
        }
    }

    override fun onDestroy() {
        stopAllServices()
        super.onDestroy()
    }

    private fun stopAllServices() {
        // Stop TraccarService FIRST — so when TrackingService stops,
        // it won't restore notification (TraccarService already dead)
        if (TraccarService.isRunning) {
            stopService(android.content.Intent(this, TraccarService::class.java))
        }
        if (TrackingService.isRunning) {
            stopService(android.content.Intent(this, TrackingService::class.java))
        }
        // Force remove notification in case of race condition
        NotificationHelper.cancel(this)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val mapFrag = supportFragmentManager.fragments.filterIsInstance<MapFragment>().firstOrNull()
        val prefs = getSharedPreferences(MapFragment.PREFS_NAME, Context.MODE_PRIVATE)
        val volumeZoom = prefs.getBoolean(MapFragment.PREF_VOLUME_ZOOM, true)
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN -> {
                event.startTracking() // needed for long-press detection
                if (!volumeZoom || mapFrag == null) return super.onKeyDown(keyCode, event)
                if (event.repeatCount == 0) {
                    if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) mapFrag.zoomIn()
                    else mapFrag.zoomOut()
                }
                return true
            }
            252 -> { // Samsung XCover Key (keycode 0xFC)
                val action = prefs.getString(MapFragment.PREF_XCOVER_KEY_ACTION, "none") ?: "none"
                if (action == "none" || mapFrag == null) return super.onKeyDown(keyCode, event)
                mapFrag.handleXCoverAction(action)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun checkForAppUpdate() {
        val mapFrag = supportFragmentManager.fragments.filterIsInstance<MapFragment>().firstOrNull() ?: return
        mapFrag.checkForUpdates { latest, current, hasUpdate, apkUrl, changelog ->
            if (!hasUpdate || apkUrl == null) return@checkForUpdates
            try {
                showUpdateDialogWithProgress(latest ?: "", current, apkUrl, changelog)
            } catch (_: Exception) {}
        }
    }

    private fun showUpdateDialogWithProgress(latest: String, current: String, apkUrl: String, changelog: String?) {
        val dp = resources.displayMetrics.density
        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding((20 * dp).toInt(), (16 * dp).toInt(), (20 * dp).toInt(), (8 * dp).toInt())
        }
        root.addView(android.widget.TextView(this).apply {
            text = "Новая версия: $latest\nТекущая: $current\n\n${changelog ?: "Исправления и улучшения"}"
            setTextColor(0xFFCCCCCC.toInt()); textSize = 14f
        })
        val progressBar = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, (6 * dp).toInt()).apply { topMargin = (12 * dp).toInt() }
            max = 100; progress = 0; visibility = android.view.View.GONE
            progressDrawable.setColorFilter(0xFFFF6F00.toInt(), android.graphics.PorterDuff.Mode.SRC_IN)
        }
        root.addView(progressBar)
        val progressText = android.widget.TextView(this).apply {
            setTextColor(0xFF999999.toInt()); textSize = 12f; visibility = android.view.View.GONE
        }
        root.addView(progressText)

        val dlg = AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Dialog)
            .setTitle("Доступно обновление")
            .setView(root)
            .setPositiveButton("Скачать", null)
            .setNegativeButton("Позже", null)
            .setCancelable(true)
            .create()
        dlg.show()

        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
            dlg.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = false
            dlg.setCancelable(false)
            progressBar.visibility = android.view.View.VISIBLE
            progressText.visibility = android.view.View.VISIBLE
            progressText.text = "Скачивание..."

            UpdateManager.downloadAndInstall(this, apkUrl, latest,
                onProgress = { bytesRead, totalBytes ->
                    if (totalBytes > 0) {
                        val pct = (bytesRead * 100 / totalBytes).toInt()
                        progressBar.progress = pct
                        progressText.text = "Скачано ${"%.1f".format(bytesRead / 1048576.0)} / ${"%.1f".format(totalBytes / 1048576.0)} МБ ($pct%)"
                    } else {
                        progressText.text = "Скачано ${"%.1f".format(bytesRead / 1048576.0)} МБ..."
                        progressBar.isIndeterminate = true
                    }
                },
                onComplete = { success, error ->
                    if (success) {
                        progressText.text = "✓ Скачано! Установка..."
                        progressBar.progress = 100
                        progressText.postDelayed({ dlg.dismiss() }, 1500)
                    } else {
                        progressText.text = "Ошибка: $error"
                        progressText.setTextColor(0xFFEF4444.toInt())
                        dlg.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                        dlg.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = true
                        dlg.setCancelable(true)
                    }
                }
            )
        }
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        // Volume UP long press → toggle screen lock (if enabled in settings)
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            val prefs = getSharedPreferences(MapFragment.PREFS_NAME, Context.MODE_PRIVATE)
            if (!prefs.getBoolean(MapFragment.PREF_VOLUME_LOCK, true)) return super.onKeyLongPress(keyCode, event)
            val mapFrag = supportFragmentManager.fragments.filterIsInstance<MapFragment>().firstOrNull()
            if (mapFrag != null) {
                if (mapFrag.isScreenLocked) {
                    mapFrag.unlockScreen()
                } else {
                    mapFrag.lockScreen()
                }
                return true
            }
        }
        // Volume DOWN long press -> quick map switch (if enabled in settings)
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            val prefs = getSharedPreferences(MapFragment.PREFS_NAME, Context.MODE_PRIVATE)
            if (!prefs.getBoolean(MapFragment.PREF_VOLUME_MAP_SWITCH, true)) return super.onKeyLongPress(keyCode, event)
            val mapFrag = supportFragmentManager.fragments.filterIsInstance<MapFragment>().firstOrNull()
            if (mapFrag != null) {
                mapFrag.quickSwitchMap()
                return true
            }
        }
        return super.onKeyLongPress(keyCode, event)
    }
}
