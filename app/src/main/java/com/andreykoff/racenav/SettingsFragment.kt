package com.andreykoff.racenav

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import com.andreykoff.racenav.MapFragment.Companion.DEFAULT_MARKER_COLOR
import com.andreykoff.racenav.MapFragment.Companion.DEFAULT_MARKER_SIZE
import com.andreykoff.racenav.MapFragment.Companion.PREF_FULLSCREEN
import com.andreykoff.racenav.MapFragment.Companion.PREF_MARKER_COLOR
import com.andreykoff.racenav.MapFragment.Companion.PREF_MARKER_SIZE
import com.andreykoff.racenav.MapFragment.Companion.PREFS_NAME
import com.andreykoff.racenav.MapFragment.Companion.PREF_VOLUME_ZOOM
import com.andreykoff.racenav.MapFragment.Companion.PREF_WIDGET_SPEED
import com.andreykoff.racenav.MapFragment.Companion.PREF_WIDGET_BEARING
import com.andreykoff.racenav.MapFragment.Companion.PREF_WIDGET_TRACKLEN
import com.andreykoff.racenav.MapFragment.Companion.PREF_WIDGET_NEXTCP
import com.andreykoff.racenav.MapFragment.Companion.PREF_WIDGET_ALTITUDE
import com.andreykoff.racenav.MapFragment.Companion.PREF_WIDGET_CHRONO
import com.andreykoff.racenav.MapFragment.Companion.PREF_WIDGET_TIME
import com.andreykoff.racenav.MapFragment.Companion.PREF_WIDGET_REMAIN_KM
import com.andreykoff.racenav.MapFragment.Companion.PREF_WIDGET_NEXTCP_NAME
import com.andreykoff.racenav.MapFragment.Companion.PREF_WIDGET_ORDER
import com.andreykoff.racenav.MapFragment.Companion.ALL_WIDGET_KEYS
import com.andreykoff.racenav.MapFragment.Companion.PREF_AUTO_RECENTER
import com.andreykoff.racenav.MapFragment.Companion.PREF_RECENTER_DELAY
import com.andreykoff.racenav.MapFragment.Companion.PREF_FOLLOW_MODE
import com.andreykoff.racenav.MapFragment.Companion.PREF_KEEP_SCREEN
import com.andreykoff.racenav.MapFragment.Companion.PREF_TRACK_INTERVAL
import com.andreykoff.racenav.MapFragment.Companion.PREF_TRACK_COLOR
import com.andreykoff.racenav.MapFragment.Companion.PREF_TRACK_WIDTH
import com.andreykoff.racenav.MapFragment.Companion.PREF_LOADED_TRACK_COLOR
import com.andreykoff.racenav.MapFragment.Companion.PREF_LOADED_TRACK_WIDTH
import com.andreykoff.racenav.MapFragment.Companion.DEFAULT_TRACK_COLOR
import com.andreykoff.racenav.MapFragment.Companion.DEFAULT_TRACK_WIDTH
import com.andreykoff.racenav.MapFragment.Companion.DEFAULT_LOADED_TRACK_COLOR
import com.andreykoff.racenav.MapFragment.Companion.DEFAULT_LOADED_TRACK_WIDTH
import com.andreykoff.racenav.MapFragment.Companion.PREF_3D_TILT
import com.andreykoff.racenav.MapFragment.Companion.PREF_AUTO_ZOOM
import com.andreykoff.racenav.MapFragment.Companion.PREF_SYNC_API_KEY
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.ImageButton
import android.widget.ProgressBar
import androidx.appcompat.app.AlertDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsFragment : Fragment() {

    private val filePicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> if (uri != null) loadFile(uri) }

    private val offlineMapPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> if (uri != null) loadOfflineMap(uri) }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Fix status bar overlap for settings toolbar
        ViewCompat.setOnApplyWindowInsetsListener(view.findViewById(R.id.statusBarSpacer)) { v, insets ->
            val h = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            v.layoutParams.height = h
            v.requestLayout()
            insets
        }

        val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        view.findViewById<View>(R.id.btnBack).setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        // Fullscreen
        val switchFs = view.findViewById<SwitchCompat>(R.id.switchFullscreen)
        switchFs.isChecked = prefs.getBoolean(PREF_FULLSCREEN, false)
        switchFs.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(PREF_FULLSCREEN, checked).apply()
            // Apply immediately to MapFragment
            parentFragmentManager.fragments.filterIsInstance<MapFragment>().firstOrNull()?.applyFullscreenPref()
        }

        // Volume zoom
        val switchVol = view.findViewById<SwitchCompat>(R.id.switchVolumeZoom)
        switchVol.isChecked = prefs.getBoolean(PREF_VOLUME_ZOOM, true)
        switchVol.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(PREF_VOLUME_ZOOM, checked).apply()
        }

        // Auto-recenter
        val switchRecenter = view.findViewById<SwitchCompat>(R.id.switchAutoRecenter)
        val rowDelay = view.findViewById<View>(R.id.rowRecenterDelay)
        switchRecenter.isChecked = prefs.getBoolean(PREF_AUTO_RECENTER, false)
        rowDelay.visibility = if (switchRecenter.isChecked) View.VISIBLE else View.GONE
        switchRecenter.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(PREF_AUTO_RECENTER, checked).apply()
            rowDelay.visibility = if (checked) View.VISIBLE else View.GONE
            parentFragmentManager.fragments.filterIsInstance<MapFragment>().firstOrNull()?.autoRecenterEnabled = checked
        }

        val txtDelay = view.findViewById<TextView>(R.id.txtRecenterDelay)
        var recenterDelay = prefs.getInt(PREF_RECENTER_DELAY, 3).coerceIn(1, 30)
        txtDelay.text = recenterDelay.toString()
        view.findViewById<ImageButton>(R.id.btnRecenterDelayMinus).setOnClickListener {
            if (recenterDelay > 1) {
                recenterDelay--
                txtDelay.text = recenterDelay.toString()
                prefs.edit().putInt(PREF_RECENTER_DELAY, recenterDelay).apply()
            }
        }
        view.findViewById<ImageButton>(R.id.btnRecenterDelayPlus).setOnClickListener {
            if (recenterDelay < 30) {
                recenterDelay++
                txtDelay.text = recenterDelay.toString()
                prefs.edit().putInt(PREF_RECENTER_DELAY, recenterDelay).apply()
            }
        }

        // Keep screen on
        val switchKeep = view.findViewById<SwitchCompat>(R.id.switchKeepScreen)
        switchKeep.isChecked = prefs.getBoolean(PREF_KEEP_SCREEN, true)
        switchKeep.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(PREF_KEEP_SCREEN, checked).apply()
            (activity as? MainActivity)?.applyKeepScreen()
        }

        // Track recording interval
        val txtInterval = view.findViewById<TextView>(R.id.txtTrackInterval)
        var currentInterval = prefs.getInt(PREF_TRACK_INTERVAL, 1).coerceIn(1, 60)
        txtInterval.text = currentInterval.toString()
        view.findViewById<ImageButton>(R.id.btnIntervalMinus).setOnClickListener {
            if (currentInterval > 1) {
                currentInterval--
                txtInterval.text = currentInterval.toString()
                prefs.edit().putInt(PREF_TRACK_INTERVAL, currentInterval).apply()
            }
        }
        view.findViewById<ImageButton>(R.id.btnIntervalPlus).setOnClickListener {
            if (currentInterval < 60) {
                currentInterval++
                txtInterval.text = currentInterval.toString()
                prefs.edit().putInt(PREF_TRACK_INTERVAL, currentInterval).apply()
            }
        }

        // Recording track color swatches
        val mapFragRef = { parentFragmentManager.fragments.filterIsInstance<MapFragment>().firstOrNull() }
        val trackRecColors = mapOf(
            R.id.trackRecColorRed    to "#FF2200",
            R.id.trackRecColorOrange to "#FF6F00",
            R.id.trackRecColorYellow to "#FFD600",
            R.id.trackRecColorGreen  to "#2E7D32",
            R.id.trackRecColorBlue   to "#1565C0",
            R.id.trackRecColorWhite  to "#FFFFFF"
        )
        var currentTrackColor = prefs.getString(PREF_TRACK_COLOR, DEFAULT_TRACK_COLOR) ?: DEFAULT_TRACK_COLOR
        trackRecColors.forEach { (id, hex) ->
            val sw = view.findViewById<View>(id)
            sw.alpha = if (hex == currentTrackColor) 1f else 0.35f
            sw.setOnClickListener {
                currentTrackColor = hex
                prefs.edit().putString(PREF_TRACK_COLOR, hex).apply()
                trackRecColors.forEach { (i, _) -> view.findViewById<View>(i).alpha = 0.35f }
                sw.alpha = 1f
                mapFragRef()?.applyTrackStyle()
            }
        }

        // Recording track width
        val txtTrackWidth = view.findViewById<TextView>(R.id.txtTrackWidth)
        var currentTrackWidth = prefs.getFloat(PREF_TRACK_WIDTH, DEFAULT_TRACK_WIDTH).toInt().coerceIn(1, 20)
        txtTrackWidth.text = currentTrackWidth.toString()
        view.findViewById<ImageButton>(R.id.btnTrackWidthMinus).setOnClickListener {
            if (currentTrackWidth > 1) {
                currentTrackWidth--
                txtTrackWidth.text = currentTrackWidth.toString()
                prefs.edit().putFloat(PREF_TRACK_WIDTH, currentTrackWidth.toFloat()).apply()
                mapFragRef()?.applyTrackStyle()
            }
        }
        view.findViewById<ImageButton>(R.id.btnTrackWidthPlus).setOnClickListener {
            if (currentTrackWidth < 20) {
                currentTrackWidth++
                txtTrackWidth.text = currentTrackWidth.toString()
                prefs.edit().putFloat(PREF_TRACK_WIDTH, currentTrackWidth.toFloat()).apply()
                mapFragRef()?.applyTrackStyle()
            }
        }

        // Cursor vertical offset
        val txtCursorOffset = view.findViewById<TextView>(R.id.txtCursorOffset)
        var cursorOffset = prefs.getInt(MapFragment.PREF_CURSOR_OFFSET, 1).coerceIn(1, 10)
        txtCursorOffset.text = cursorOffset.toString()
        view.findViewById<ImageButton>(R.id.btnCursorOffsetMinus).setOnClickListener {
            if (cursorOffset > 1) {
                cursorOffset--
                txtCursorOffset.text = cursorOffset.toString()
                prefs.edit().putInt(MapFragment.PREF_CURSOR_OFFSET, cursorOffset).apply()
                parentFragmentManager.fragments.filterIsInstance<MapFragment>().firstOrNull()?.applyCursorOffset()
            }
        }
        view.findViewById<ImageButton>(R.id.btnCursorOffsetPlus).setOnClickListener {
            if (cursorOffset < 10) {
                cursorOffset++
                txtCursorOffset.text = cursorOffset.toString()
                prefs.edit().putInt(MapFragment.PREF_CURSOR_OFFSET, cursorOffset).apply()
                parentFragmentManager.fragments.filterIsInstance<MapFragment>().firstOrNull()?.applyCursorOffset()
            }
        }

        // Follow mode
        val rgFollow = view.findViewById<RadioGroup>(R.id.rgFollowMode)
        val savedMode = prefs.getString(PREF_FOLLOW_MODE, "free") ?: "free"
        rgFollow.check(when (savedMode) {
            "north" -> R.id.rbFollowNorth
            "course" -> R.id.rbFollowCourse
            else -> R.id.rbFollowFree
        })
        rgFollow.setOnCheckedChangeListener { _, id ->
            val mode = when (id) {
                R.id.rbFollowNorth -> "north"
                R.id.rbFollowCourse -> "course"
                else -> "free"
            }
            prefs.edit().putString(PREF_FOLLOW_MODE, mode).apply()
            // Apply immediately to MapFragment if it exists in backstack
            val mapFrag = parentFragmentManager.fragments.filterIsInstance<MapFragment>().firstOrNull()
            if (mapFrag != null) {
                mapFrag.followMode = when (mode) {
                    "north" -> MapFragment.FollowMode.FOLLOW_NORTH
                    "course" -> MapFragment.FollowMode.FOLLOW_COURSE
                    else -> MapFragment.FollowMode.FREE
                }
                mapFrag.applyFollowMode()
            }
        }

        // 3D tilt on speed
        val switch3d = view.findViewById<SwitchCompat>(R.id.switch3dTilt)
        switch3d.isChecked = prefs.getBoolean(PREF_3D_TILT, false)
        switch3d.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(PREF_3D_TILT, checked).apply()
            parentFragmentManager.fragments.filterIsInstance<MapFragment>().firstOrNull()
                ?.let { it.tilt3dEnabled = checked }
        }

        // Auto-zoom by speed (0=off, 1-10)
        var autoZoom = prefs.getInt(PREF_AUTO_ZOOM, 0).coerceIn(0, 10)
        val txtAutoZoom = view.findViewById<TextView>(R.id.txtAutoZoom)
        fun updateAutoZoomText() {
            txtAutoZoom.text = if (autoZoom == 0) "Выкл" else autoZoom.toString()
        }
        updateAutoZoomText()
        view.findViewById<ImageButton>(R.id.btnAutoZoomMinus).setOnClickListener {
            if (autoZoom > 0) {
                autoZoom--
                updateAutoZoomText()
                prefs.edit().putInt(PREF_AUTO_ZOOM, autoZoom).apply()
                parentFragmentManager.fragments.filterIsInstance<MapFragment>().firstOrNull()
                    ?.let { it.autoZoomLevel = autoZoom }
            }
        }
        view.findViewById<ImageButton>(R.id.btnAutoZoomPlus).setOnClickListener {
            if (autoZoom < 10) {
                autoZoom++
                updateAutoZoomText()
                prefs.edit().putInt(PREF_AUTO_ZOOM, autoZoom).apply()
                parentFragmentManager.fragments.filterIsInstance<MapFragment>().firstOrNull()
                    ?.let { it.autoZoomLevel = autoZoom }
            }
        }

        // Save current track to GPX file
        view.findViewById<android.widget.Button>(R.id.btnSaveTrack).setOnClickListener {
            val mapFrag = parentFragmentManager.fragments.filterIsInstance<MapFragment>().firstOrNull()
            if (mapFrag != null) {
                mapFrag.saveTrackToFile()
            } else {
                Toast.makeText(requireContext(), "Нет активного трека", Toast.LENGTH_SHORT).show()
            }
        }

        // Marker color swatches
        val colorMap = mapOf(
            R.id.colorGold   to "#FFD600",
            R.id.colorRed    to "#FF2200",
            R.id.colorOrange to "#FF6F00",
            R.id.colorBlue   to "#1565C0",
            R.id.colorGreen  to "#2E7D32",
            R.id.colorWhite  to "#FFFFFF"
        )
        val currentColor = prefs.getString(PREF_MARKER_COLOR, DEFAULT_MARKER_COLOR)
        colorMap.forEach { (viewId, hex) ->
            val swatch = view.findViewById<View>(viewId)
            // Highlight selected
            swatch.alpha = if (hex == currentColor) 1f else 0.4f
            swatch.setOnClickListener {
                prefs.edit().putString(PREF_MARKER_COLOR, hex).apply()
                colorMap.forEach { (id, _) -> view.findViewById<View>(id).alpha = 0.4f }
                swatch.alpha = 1f
                parentFragmentManager.fragments.filterIsInstance<MapFragment>().firstOrNull()?.refreshGpsArrow()
                Toast.makeText(context, "Цвет сохранён", Toast.LENGTH_SHORT).show()
            }
        }

        // Marker size
        val seekSize = view.findViewById<SeekBar>(R.id.seekMarkerSize)
        val txtSize = view.findViewById<TextView>(R.id.txtMarkerSize)
        val savedSize = prefs.getInt(PREF_MARKER_SIZE, DEFAULT_MARKER_SIZE)
        seekSize.progress = savedSize
        txtSize.text = savedSize.toString()
        seekSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                txtSize.text = p.toString()
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {
                prefs.edit().putInt(PREF_MARKER_SIZE, sb.progress).apply()
                parentFragmentManager.fragments.filterIsInstance<MapFragment>().firstOrNull()?.refreshGpsArrow()
            }
        })

        // Widgets — dynamic ordered list with enable toggles and up/down reorder buttons
        buildWidgetOrderUI(view, prefs)

        // File loader
        view.findViewById<View>(R.id.btnLoadFile).setOnClickListener {
            filePicker.launch(arrayOf("*/*", "application/gpx+xml", "application/octet-stream"))
        }

        // Eye toggles for loaded track/waypoints
        val rowTrack = view.findViewById<View>(R.id.rowLoadedTrack)
        val rowWp = view.findViewById<View>(R.id.rowLoadedWp)
        val btnToggleTrack = view.findViewById<ImageButton>(R.id.btnToggleTrack)
        val btnToggleWp = view.findViewById<ImageButton>(R.id.btnToggleWp)
        val txtTrackRow = view.findViewById<TextView>(R.id.txtLoadedTrack)
        val txtWpRow = view.findViewById<TextView>(R.id.txtLoadedWp)
        val mapFrag = parentFragmentManager.fragments.filterIsInstance<MapFragment>().firstOrNull()

        // Restore track row from prefs (only if track is actually in memory)
        var trackVisible = prefs.getBoolean(MapFragment.PREF_LOADED_TRACK_VISIBLE, true)
        var wpVisible = prefs.getBoolean(MapFragment.PREF_LOADED_WP_VISIBLE, true)
        val rowLoadedTrackStyle = view.findViewById<View>(R.id.rowLoadedTrackStyle)
        val trackActuallyLoaded = mapFrag?.hasLoadedTrack() == true
        if (trackActuallyLoaded) {
            prefs.getString(MapFragment.PREF_LOADED_TRACK_NAME, null)?.let { name ->
                txtTrackRow?.text = name; rowTrack?.visibility = View.VISIBLE
                rowLoadedTrackStyle?.visibility = View.VISIBLE
                btnToggleTrack.setImageResource(if (trackVisible) R.drawable.ic_eye else R.drawable.ic_eye_off)
            }
        }
        prefs.getString(MapFragment.PREF_LOADED_WP_NAME, null)?.let { name ->
            txtWpRow?.text = name; rowWp?.visibility = View.VISIBLE
            btnToggleWp.setImageResource(if (wpVisible) R.drawable.ic_eye else R.drawable.ic_eye_off)
        }

        btnToggleTrack.setOnClickListener {
            trackVisible = !trackVisible
            btnToggleTrack.setImageResource(if (trackVisible) R.drawable.ic_eye else R.drawable.ic_eye_off)
            mapFrag?.setLoadedTrackVisible(trackVisible)
        }
        btnToggleWp.setOnClickListener {
            wpVisible = !wpVisible
            btnToggleWp.setImageResource(if (wpVisible) R.drawable.ic_eye else R.drawable.ic_eye_off)
            mapFrag?.setLoadedWpVisible(wpVisible)
        }

        // Navigation start/stop
        view.findViewById<android.widget.Button>(R.id.btnNavStart).setOnClickListener {
            mapFrag?.startNavigation()
            Toast.makeText(requireContext(), "Навигация запущена", Toast.LENGTH_SHORT).show()
        }
        view.findViewById<android.widget.Button>(R.id.btnNavStop).setOnClickListener {
            mapFrag?.stopNavigation()
            Toast.makeText(requireContext(), "Навигация остановлена", Toast.LENGTH_SHORT).show()
        }

        // Loaded track color swatches
        val loadedTrackColors = mapOf(
            R.id.loadedTrackColorBlue   to "#2196F3",
            R.id.loadedTrackColorRed    to "#FF2200",
            R.id.loadedTrackColorOrange to "#FF6F00",
            R.id.loadedTrackColorGreen  to "#2E7D32",
            R.id.loadedTrackColorYellow to "#FFD600",
            R.id.loadedTrackColorWhite  to "#FFFFFF"
        )
        var currentLoadedColor = prefs.getString(PREF_LOADED_TRACK_COLOR, DEFAULT_LOADED_TRACK_COLOR) ?: DEFAULT_LOADED_TRACK_COLOR
        loadedTrackColors.forEach { (id, hex) ->
            val sw = view.findViewById<View>(id)
            sw.alpha = if (hex == currentLoadedColor) 1f else 0.35f
            sw.setOnClickListener {
                currentLoadedColor = hex
                prefs.edit().putString(PREF_LOADED_TRACK_COLOR, hex).apply()
                loadedTrackColors.forEach { (i, _) -> view.findViewById<View>(i).alpha = 0.35f }
                sw.alpha = 1f
                mapFrag?.applyLoadedTrackStyle()
            }
        }

        // Loaded track width
        val txtLoadedWidth = view.findViewById<TextView>(R.id.txtLoadedTrackWidth)
        var currentLoadedWidth = prefs.getFloat(PREF_LOADED_TRACK_WIDTH, DEFAULT_LOADED_TRACK_WIDTH).toInt().coerceIn(1, 20)
        txtLoadedWidth.text = currentLoadedWidth.toString()
        view.findViewById<ImageButton>(R.id.btnLoadedTrackWidthMinus).setOnClickListener {
            if (currentLoadedWidth > 1) {
                currentLoadedWidth--
                txtLoadedWidth.text = currentLoadedWidth.toString()
                prefs.edit().putFloat(PREF_LOADED_TRACK_WIDTH, currentLoadedWidth.toFloat()).apply()
                mapFrag?.applyLoadedTrackStyle()
            }
        }
        view.findViewById<ImageButton>(R.id.btnLoadedTrackWidthPlus).setOnClickListener {
            if (currentLoadedWidth < 20) {
                currentLoadedWidth++
                txtLoadedWidth.text = currentLoadedWidth.toString()
                prefs.edit().putFloat(PREF_LOADED_TRACK_WIDTH, currentLoadedWidth.toFloat()).apply()
                mapFrag?.applyLoadedTrackStyle()
            }
        }

        // Offline maps — dynamic list
        refreshOfflineMapsUI(view)
        view.findViewById<View>(R.id.btnLoadOfflineMap).setOnClickListener {
            offlineMapPicker.launch(arrayOf("*/*", "application/octet-stream"))
        }

        // Tile cache size
        val txtCache = view.findViewById<TextView>(R.id.txtCacheMb)
        var cacheMb = prefs.getInt(MapFragment.PREF_TILE_CACHE_MB, 200).coerceIn(100, 4000)
        txtCache.text = cacheMb.toString()
        view.findViewById<ImageButton>(R.id.btnCacheMinus).setOnClickListener {
            if (cacheMb > 100) {
                cacheMb = (cacheMb - 100).coerceAtLeast(100)
                txtCache.text = cacheMb.toString()
                prefs.edit().putInt(MapFragment.PREF_TILE_CACHE_MB, cacheMb).apply()
                parentFragmentManager.fragments.filterIsInstance<MapFragment>().firstOrNull()?.applyCacheSize()
            }
        }
        view.findViewById<ImageButton>(R.id.btnCachePlus).setOnClickListener {
            if (cacheMb < 4000) {
                cacheMb = (cacheMb + 100).coerceAtMost(4000)
                txtCache.text = cacheMb.toString()
                prefs.edit().putInt(MapFragment.PREF_TILE_CACHE_MB, cacheMb).apply()
                parentFragmentManager.fragments.filterIsInstance<MapFragment>().firstOrNull()?.applyCacheSize()
            }
        }

        // Sync
        val editApiKey = view.findViewById<EditText>(R.id.editSyncApiKey)
        val btnSyncPull = view.findViewById<android.widget.Button>(R.id.btnSyncPull)
        val btnSyncPush = view.findViewById<android.widget.Button>(R.id.btnSyncPush)
        val txtSyncStatus = view.findViewById<TextView>(R.id.txtSyncStatus)
        editApiKey.setText(prefs.getString(PREF_SYNC_API_KEY, ""))

        fun setSyncStatus(ok: Boolean, msg: String) {
            txtSyncStatus.text = msg
            txtSyncStatus.setTextColor(if (ok) 0xFF22C55E.toInt() else 0xFFFF6B6B.toInt())
            txtSyncStatus.visibility = View.VISIBLE
            btnSyncPull.isEnabled = true
            btnSyncPush.isEnabled = true
        }

        btnSyncPull.setOnClickListener {
            val key = editApiKey.text.toString().trim()
            if (key.isEmpty()) { Toast.makeText(requireContext(), "Введите ключ активации", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            prefs.edit().putString(PREF_SYNC_API_KEY, key).apply()
            btnSyncPull.isEnabled = false; btnSyncPush.isEnabled = false
            txtSyncStatus.text = "Получаю данные..."; txtSyncStatus.setTextColor(0xFF888888.toInt()); txtSyncStatus.visibility = View.VISIBLE
            val mf = parentFragmentManager.fragments.filterIsInstance<MapFragment>().firstOrNull()
            if (mf != null) {
                mf.syncPull(key) { ok, msg -> setSyncStatus(ok, msg) }
            } else {
                setSyncStatus(false, "Откройте карту и попробуйте снова")
            }
        }

        btnSyncPush.setOnClickListener {
            val key = editApiKey.text.toString().trim()
            if (key.isEmpty()) { Toast.makeText(requireContext(), "Введите ключ активации", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            prefs.edit().putString(PREF_SYNC_API_KEY, key).apply()
            btnSyncPull.isEnabled = false; btnSyncPush.isEnabled = false
            txtSyncStatus.text = "Отправляю трек..."; txtSyncStatus.setTextColor(0xFF888888.toInt()); txtSyncStatus.visibility = View.VISIBLE
            val mf = parentFragmentManager.fragments.filterIsInstance<MapFragment>().firstOrNull()
            if (mf != null) {
                mf.syncPush(key) { ok, msg -> setSyncStatus(ok, msg) }
            } else {
                setSyncStatus(false, "Откройте карту и попробуйте снова")
            }
        }

        // Version
        try {
            val v = requireContext().packageManager.getPackageInfo(requireContext().packageName, 0).versionName
            view.findViewById<TextView>(R.id.txtVersion).text = "Trophy Navigator v$v"
        } catch (e: Exception) { }

        // Check for updates button
        val btnCheck = view.findViewById<android.widget.Button>(R.id.btnCheckUpdates)
        val txtStatus = view.findViewById<TextView>(R.id.txtUpdateStatus)
        btnCheck.setOnClickListener {
            txtStatus.text = "Проверяю..."
            txtStatus.setTextColor(0xFF888888.toInt())
            btnCheck.isEnabled = false
            val mapFrag = parentFragmentManager.fragments.filterIsInstance<MapFragment>().firstOrNull()
            if (mapFrag != null) {
                mapFrag.checkForUpdates { latest, current, hasUpdate ->
                    btnCheck.isEnabled = true
                    if (latest == null) {
                        txtStatus.text = "Нет связи"
                        txtStatus.setTextColor(0xFFFF6F00.toInt())
                    } else if (hasUpdate) {
                        txtStatus.text = "Доступна $latest"
                        txtStatus.setTextColor(0xFF22C55E.toInt())
                        UpdateManager.downloadAndInstall(requireContext(), latest)
                    } else {
                        txtStatus.text = "✓ Актуальная версия"
                        txtStatus.setTextColor(0xFF22C55E.toInt())
                    }
                }
            } else {
                btnCheck.isEnabled = true
                txtStatus.text = "Откройте карту и попробуйте снова"
            }
        }
    }

    private fun buildWidgetOrderUI(
        view: View,
        prefs: android.content.SharedPreferences
    ) {
        data class WInfo(val key: String, val label: String, val prefKey: String, val defaultOn: Boolean)
        val allWidgets = listOf(
            WInfo("speed",       "Скорость",       PREF_WIDGET_SPEED,       true),
            WInfo("bearing",     "Курс / стрелка", PREF_WIDGET_BEARING,     true),
            WInfo("tracklen",    "Длина трека",     PREF_WIDGET_TRACKLEN,    true),
            WInfo("nextcp",      "До след. КП",     PREF_WIDGET_NEXTCP,      true),
            WInfo("altitude",    "Высота",          PREF_WIDGET_ALTITUDE,    true),
            WInfo("chrono",      "Хронометр",       PREF_WIDGET_CHRONO,      false),
            WInfo("time",        "Текущее время",   PREF_WIDGET_TIME,        false),
            WInfo("remain_km",   "Остаток км",      PREF_WIDGET_REMAIN_KM,   false),
            WInfo("nextcp_name", "Имя след. КП",    PREF_WIDGET_NEXTCP_NAME, false),
        )

        val savedOrder = prefs.getString(PREF_WIDGET_ORDER, ALL_WIDGET_KEYS.joinToString(",")) ?: ALL_WIDGET_KEYS.joinToString(",")
        val orderedKeys = savedOrder.split(",").toMutableList()
        // Append any new keys not yet in saved order
        ALL_WIDGET_KEYS.forEach { k -> if (k !in orderedKeys) orderedKeys.add(k) }

        val container = view.findViewById<LinearLayout>(R.id.widgetOrderContainer)

        fun rebuildRows() {
            container.removeAllViews()
            orderedKeys.forEachIndexed { idx, key ->
                val info = allWidgets.find { it.key == key } ?: return@forEachIndexed
                val row = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setBackgroundColor(0xFF1E1E1E.toInt())
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        (52 * resources.displayMetrics.density + 0.5f).toInt()
                    ).apply { topMargin = (1 * resources.displayMetrics.density + 0.5f).toInt() }
                    setPadding(
                        (16 * resources.displayMetrics.density + 0.5f).toInt(), 0,
                        (8 * resources.displayMetrics.density + 0.5f).toInt(), 0
                    )
                }

                val sw = SwitchCompat(requireContext()).apply {
                    isChecked = prefs.getBoolean(info.prefKey, info.defaultOn)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    setOnCheckedChangeListener { _, checked ->
                        prefs.edit().putBoolean(info.prefKey, checked).apply()
                        parentFragmentManager.fragments.filterIsInstance<MapFragment>().firstOrNull()?.applyWidgetPrefs()
                    }
                }

                val label = TextView(requireContext()).apply {
                    text = info.label
                    setTextColor(0xFFFFFFFF.toInt())
                    textSize = 15f
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }

                val dp = resources.displayMetrics.density
                val btnSize = (32 * dp + 0.5f).toInt()

                val btnUp = ImageButton(requireContext()).apply {
                    setImageResource(R.drawable.ic_remove) // reuse minus icon as "up"
                    setImageResource(android.R.drawable.arrow_up_float)
                    background = null
                    layoutParams = LinearLayout.LayoutParams(btnSize, btnSize)
                    isEnabled = idx > 0
                    alpha = if (idx > 0) 1f else 0.3f
                    setOnClickListener {
                        if (idx > 0) {
                            orderedKeys.removeAt(idx); orderedKeys.add(idx - 1, key)
                            prefs.edit().putString(PREF_WIDGET_ORDER, orderedKeys.joinToString(",")).apply()
                            parentFragmentManager.fragments.filterIsInstance<MapFragment>().firstOrNull()?.applyWidgetPrefs()
                            rebuildRows()
                        }
                    }
                }

                val btnDown = ImageButton(requireContext()).apply {
                    setImageResource(android.R.drawable.arrow_down_float)
                    background = null
                    layoutParams = LinearLayout.LayoutParams(btnSize, btnSize)
                    isEnabled = idx < orderedKeys.size - 1
                    alpha = if (idx < orderedKeys.size - 1) 1f else 0.3f
                    setOnClickListener {
                        if (idx < orderedKeys.size - 1) {
                            orderedKeys.removeAt(idx); orderedKeys.add(idx + 1, key)
                            prefs.edit().putString(PREF_WIDGET_ORDER, orderedKeys.joinToString(",")).apply()
                            parentFragmentManager.fragments.filterIsInstance<MapFragment>().firstOrNull()?.applyWidgetPrefs()
                            rebuildRows()
                        }
                    }
                }

                row.addView(sw)
                row.addView(label)
                row.addView(btnUp)
                row.addView(btnDown)
                container.addView(row)
            }
        }
        rebuildRows()
    }

    private fun loadFile(uri: Uri) {
        val name = getFileName(uri)
        val ext = name.substringAfterLast('.', "").lowercase()
        val mapFrag = parentFragmentManager.fragments.filterIsInstance<MapFragment>().firstOrNull()
        val rowTrack = view?.findViewById<View>(R.id.rowLoadedTrack)
        val rowWp    = view?.findViewById<View>(R.id.rowLoadedWp)
        val txtTrack = view?.findViewById<TextView>(R.id.txtLoadedTrack)
        val txtWp    = view?.findViewById<TextView>(R.id.txtLoadedWp)
        val txtErr   = view?.findViewById<TextView>(R.id.txtLoadedFile)
        val filePrefs = requireContext().getSharedPreferences(MapFragment.PREFS_NAME, Context.MODE_PRIVATE)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val bytes = requireContext().contentResolver.openInputStream(uri)?.readBytes()
                    ?: throw Exception("Не удалось открыть файл")

                when (ext) {
                    "gpx", "rte" -> {
                        val result = GpxParser.parseGpxFull(bytes.inputStream())
                        withContext(Dispatchers.Main) {
                            if (result.trackPoints.isNotEmpty()) {
                                mapFrag?.loadTrack(result.trackPoints)
                                val label = "Трек: $name (${result.trackPoints.size} точек)"
                                txtTrack?.text = label; rowTrack?.visibility = View.VISIBLE
                                view?.findViewById<View>(R.id.rowLoadedTrackStyle)?.visibility = View.VISIBLE
                                filePrefs.edit().putString(MapFragment.PREF_LOADED_TRACK_NAME, label).apply()
                            }
                            if (result.waypoints.isNotEmpty()) {
                                mapFrag?.loadWaypoints(result.waypoints)
                                val label = "КП: $name (${result.waypoints.size} точек)"
                                txtWp?.text = label; rowWp?.visibility = View.VISIBLE
                                filePrefs.edit().putString(MapFragment.PREF_LOADED_WP_NAME, label).apply()
                            }
                            if (result.trackPoints.isEmpty() && result.waypoints.isEmpty()) {
                                txtErr?.text = "Файл пустой: $name"
                                txtErr?.visibility = View.VISIBLE
                            }
                        }
                    }
                    "wpt" -> {
                        val wpts = GpxParser.parseWpt(bytes.inputStream())
                        withContext(Dispatchers.Main) {
                            if (wpts.isNotEmpty()) {
                                mapFrag?.loadWaypoints(wpts)
                                val label = "КП: $name (${wpts.size} точек)"
                                txtWp?.text = label; rowWp?.visibility = View.VISIBLE
                                filePrefs.edit().putString(MapFragment.PREF_LOADED_WP_NAME, label).apply()
                            } else {
                                txtErr?.text = "Файл пустой: $name"; txtErr?.visibility = View.VISIBLE
                            }
                        }
                    }
                    "plt" -> {
                        val pts = GpxParser.parsePltTrack(bytes.inputStream())
                        withContext(Dispatchers.Main) {
                            if (pts.isNotEmpty()) {
                                mapFrag?.loadTrack(pts)
                                val label = "Трек: $name (${pts.size} точек)"
                                txtTrack?.text = label; rowTrack?.visibility = View.VISIBLE
                                view?.findViewById<View>(R.id.rowLoadedTrackStyle)?.visibility = View.VISIBLE
                                filePrefs.edit().putString(MapFragment.PREF_LOADED_TRACK_NAME, label).apply()
                            } else {
                                txtErr?.text = "Файл пустой: $name"; txtErr?.visibility = View.VISIBLE
                            }
                        }
                    }
                    else -> withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Формат не поддерживается: .$ext", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun loadOfflineMap(uri: Uri) {
        val name = getFileName(uri)
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext !in listOf("sqlitedb", "mbtiles", "db")) {
            Toast.makeText(context, "Формат не поддерживается: .$ext\nОжидается .sqlitedb или .mbtiles", Toast.LENGTH_LONG).show()
            return
        }
        // Show progress dialog while copying
        val progressBar = ProgressBar(requireContext(), null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false; max = 100; progress = 0
        }
        val progressDialog = AlertDialog.Builder(requireContext())
            .setTitle("Загрузка карты")
            .setMessage(name)
            .setView(progressBar)
            .setCancelable(false)
            .create()
        progressDialog.show()

        // Copy to app private storage with progress tracking
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dest = java.io.File(requireContext().filesDir, "offline_map_${System.currentTimeMillis()}.$ext")
                val total = requireContext().contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
                var copied = 0L
                val buffer = ByteArray(65536)
                requireContext().contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { output ->
                        var n: Int
                        while (input.read(buffer).also { n = it } != -1) {
                            output.write(buffer, 0, n)
                            copied += n
                            if (total > 0) {
                                val pct = (copied * 100 / total).toInt()
                                withContext(Dispatchers.Main) { progressBar.progress = pct }
                            }
                        }
                    }
                }
                val path = dest.absolutePath
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    val mapFrag = parentFragmentManager.fragments.filterIsInstance<MapFragment>().firstOrNull()
                    val key = mapFrag?.addOfflineMap(path, name)
                    if (key != null) {
                        view?.let { refreshOfflineMapsUI(it) }
                        Toast.makeText(context, "Карта загружена: $name", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "Не удалось открыть карту: неизвестный формат", Toast.LENGTH_LONG).show()
                        dest.delete()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    Toast.makeText(context, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun refreshOfflineMapsUI(view: View) {
        val container = view.findViewById<LinearLayout>(R.id.containerOfflineMaps)
        container.removeAllViews()
        val mapFrag = parentFragmentManager.fragments.filterIsInstance<MapFragment>().firstOrNull()
        val maps = mapFrag?.getOfflineMaps() ?: emptyList()
        if (maps.isEmpty()) {
            val tv = android.widget.TextView(requireContext()).apply {
                text = "Нет загруженных карт"; setTextColor(0xFF888888.toInt())
                textSize = 13f; setPadding(4, 8, 4, 8)
            }
            container.addView(tv)
        } else {
            maps.forEach { info ->
                val row = layoutInflater.inflate(R.layout.item_offline_map, container, false)
                row.findViewById<android.widget.TextView>(R.id.txtOfflineMapName).text = info.name
                row.findViewById<View>(R.id.btnRemoveOfflineMap).setOnClickListener {
                    mapFrag?.removeOfflineMap(info.key)
                    refreshOfflineMapsUI(view)
                }
                container.addView(row)
            }
        }
    }

    private fun getFileName(uri: Uri): String {
        requireContext().contentResolver.query(uri, null, null, null, null)?.use {
            if (it.moveToFirst()) {
                val idx = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) return it.getString(idx)
            }
        }
        return uri.lastPathSegment ?: "unknown"
    }
}
