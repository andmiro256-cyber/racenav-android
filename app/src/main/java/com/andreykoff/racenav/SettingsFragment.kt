package com.andreykoff.racenav

import android.content.Context
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
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
import com.andreykoff.racenav.MapFragment.Companion.PREF_WIDGET_TRIPMASTER
import com.andreykoff.racenav.MapFragment.Companion.PREF_WIDGET_ORDER
import com.andreykoff.racenav.MapFragment.Companion.ALL_WIDGET_KEYS
import com.andreykoff.racenav.MapFragment.Companion.PREF_AUTO_RECENTER
import com.andreykoff.racenav.MapFragment.Companion.PREF_RECENTER_DELAY
import com.andreykoff.racenav.MapFragment.Companion.PREF_FOLLOW_MODE
import com.andreykoff.racenav.MapFragment.Companion.PREF_KEEP_SCREEN
import com.andreykoff.racenav.MapFragment.Companion.PREF_AUTO_RECORD
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
import com.andreykoff.racenav.MapFragment.Companion.PREF_WP_APPROACH_RADIUS
import com.andreykoff.racenav.MapFragment.Companion.DEFAULT_WP_APPROACH_RADIUS
import com.andreykoff.racenav.MapFragment.Companion.PREF_NAV_LINE_COLOR
import com.andreykoff.racenav.MapFragment.Companion.PREF_NAV_LINE_WIDTH
import com.andreykoff.racenav.MapFragment.Companion.PREF_ROUTE_LINE_COLOR
import com.andreykoff.racenav.MapFragment.Companion.PREF_ROUTE_LINE_WIDTH
import com.andreykoff.racenav.MapFragment.Companion.PREF_WP_LABEL_SIZE
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.ImageButton
import android.widget.ProgressBar
import com.andreykoff.racenav.MapFragment.Companion.PREF_ORIENTATION
import androidx.appcompat.app.AlertDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class LoadedDataset(
    val id: String,
    val name: String,
    val waypointCount: Int,
    val trackPointCount: Int,
    val loadedAt: String,
    val filePath: String? = null,
    val mapKey: String? = null
)

class SettingsFragment : Fragment() {

    private companion object {
        const val PREF_DATASETS_JSON = "loaded_datasets_json"
        const val MAX_DATASETS = 10
    }

    private val filePickerLauncher = registerForActivityResult(
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

        // Block touch events from passing through to map behind settings
        view.isClickable = true
        view.isFocusable = true

        // Tab switching — show/hide settings sections
        setupTabs(view)

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

        // Volume+ screen lock toggle
        val switchVolLock = view.findViewById<SwitchCompat>(R.id.switchVolumeLock)
        switchVolLock.isChecked = prefs.getBoolean(MapFragment.PREF_VOLUME_LOCK, true)
        switchVolLock.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(MapFragment.PREF_VOLUME_LOCK, checked).apply()
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

        // Screen orientation spinner
        val rgOrientation = view.findViewById<RadioGroup>(R.id.rgOrientation)
        val savedOrientation = prefs.getInt(PREF_ORIENTATION, 0)
        rgOrientation.check(when (savedOrientation) {
            1 -> R.id.rbOrientPortrait
            2 -> R.id.rbOrientLandscape
            else -> R.id.rbOrientAuto
        })
        rgOrientation.setOnCheckedChangeListener { _, checkedId ->
            val pos = when (checkedId) {
                R.id.rbOrientPortrait -> 1
                R.id.rbOrientLandscape -> 2
                else -> 0
            }
            prefs.edit().putInt(PREF_ORIENTATION, pos).apply()
            (activity as? MainActivity)?.applyOrientation()
        }

        // UI Scale slider (programmatic — after keep screen toggle)
        val keepScreenRow = switchKeep.parent as? ViewGroup
        if (keepScreenRow?.parent is ViewGroup) {
            val keepParent = keepScreenRow.parent as ViewGroup
            val keepIdx = keepParent.indexOfChild(keepScreenRow) + 1
            val dp = resources.displayMetrics.density
            val scaleRow = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(0xFF1E1E1E.toInt())
                setPadding((16 * dp).toInt(), (8 * dp).toInt(), (16 * dp).toInt(), (12 * dp).toInt())
            }
            val labelRow = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
            }
            labelRow.addView(TextView(requireContext()).apply {
                text = "Масштаб интерфейса"; setTextColor(0xFFFFFFFF.toInt()); textSize = 15f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            val scaleValue = TextView(requireContext()).apply {
                val cur = prefs.getInt(MapFragment.PREF_UI_SCALE, 5)
                text = "${cur * 20}%"; setTextColor(0xFF888888.toInt()); textSize = 13f
            }
            labelRow.addView(scaleValue)
            scaleRow.addView(labelRow)
            scaleRow.addView(SeekBar(requireContext()).apply {
                max = 9; progress = prefs.getInt(MapFragment.PREF_UI_SCALE, 5) - 1
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar, p: Int, u: Boolean) {
                        val v = p + 1
                        prefs.edit().putInt(MapFragment.PREF_UI_SCALE, v).apply()
                        scaleValue.text = "${v * 20}%"
                    }
                    override fun onStartTrackingTouch(sb: SeekBar) {}
                    override fun onStopTrackingTouch(sb: SeekBar) {
                        Toast.makeText(context, "Масштаб применится после перезапуска", Toast.LENGTH_SHORT).show()
                    }
                })
            })
            keepParent.addView(scaleRow, keepIdx)
        }

        // Auto-start track recording
        val switchAutoRecord = view.findViewById<SwitchCompat>(R.id.switchAutoRecord)
        switchAutoRecord.isChecked = prefs.getBoolean(PREF_AUTO_RECORD, true)
        switchAutoRecord.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(PREF_AUTO_RECORD, checked).apply()
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

        // Recording track color — single swatch with picker
        val mapFragRef = { parentFragmentManager.fragments.filterIsInstance<MapFragment>().firstOrNull() }
        val viewTrackRecColor = view.findViewById<View>(R.id.viewTrackRecColor)
        val savedTrackColor = prefs.getString(PREF_TRACK_COLOR, DEFAULT_TRACK_COLOR) ?: DEFAULT_TRACK_COLOR
        viewTrackRecColor.setBackgroundColor(android.graphics.Color.parseColor(savedTrackColor))
        viewTrackRecColor.setOnClickListener {
            showColorPicker(prefs.getString(PREF_TRACK_COLOR, DEFAULT_TRACK_COLOR) ?: DEFAULT_TRACK_COLOR) { color ->
                viewTrackRecColor.setBackgroundColor(android.graphics.Color.parseColor(color))
                prefs.edit().putString(PREF_TRACK_COLOR, color).apply()
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

        // Clear recorded track
        view.findViewById<android.widget.Button>(R.id.btnClearTrack).setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("Очистить трек")
                .setMessage("Удалить записанный трек с карты?")
                .setPositiveButton("Очистить") { _, _ ->
                    parentFragmentManager.fragments.filterIsInstance<MapFragment>().firstOrNull()?.clearRecordedTrack()
                }
                .setNegativeButton("Отмена", null)
                .show()
        }

        // Share recorded track
        view.findViewById<android.widget.Button>(R.id.btnShareTrack).setOnClickListener {
            parentFragmentManager.fragments.filterIsInstance<MapFragment>().firstOrNull()?.shareRecordedTrack()
        }

        // Marker color — single swatch with picker
        val viewMarkerColor = view.findViewById<View>(R.id.viewMarkerColor)
        val savedMarkerColor = prefs.getString(PREF_MARKER_COLOR, DEFAULT_MARKER_COLOR) ?: DEFAULT_MARKER_COLOR
        viewMarkerColor.setBackgroundColor(android.graphics.Color.parseColor(savedMarkerColor))
        viewMarkerColor.setOnClickListener {
            showColorPicker(prefs.getString(PREF_MARKER_COLOR, DEFAULT_MARKER_COLOR) ?: DEFAULT_MARKER_COLOR) { color ->
                viewMarkerColor.setBackgroundColor(android.graphics.Color.parseColor(color))
                prefs.edit().putString(PREF_MARKER_COLOR, color).apply()
                parentFragmentManager.fragments.filterIsInstance<MapFragment>().firstOrNull()?.refreshGpsArrow()
            }
        }

        // Marker size (+/- buttons, scale 1-10)
        val txtSize = view.findViewById<TextView>(R.id.txtMarkerSize)
        var markerSizeScale = prefs.getInt(PREF_MARKER_SIZE, DEFAULT_MARKER_SIZE).coerceIn(1, 10)
        txtSize.text = markerSizeScale.toString()
        view.findViewById<android.widget.ImageButton>(R.id.btnMarkerSizeMinus)?.setOnClickListener {
            if (markerSizeScale > 1) {
                markerSizeScale -= 1
                txtSize.text = markerSizeScale.toString()
                prefs.edit().putInt(PREF_MARKER_SIZE, markerSizeScale).apply()
                parentFragmentManager.fragments.filterIsInstance<MapFragment>().firstOrNull()?.refreshGpsArrow()
            }
        }
        view.findViewById<android.widget.ImageButton>(R.id.btnMarkerSizePlus)?.setOnClickListener {
            if (markerSizeScale < 10) {
                markerSizeScale += 1
                txtSize.text = markerSizeScale.toString()
                prefs.edit().putInt(PREF_MARKER_SIZE, markerSizeScale).apply()
                parentFragmentManager.fragments.filterIsInstance<MapFragment>().firstOrNull()?.refreshGpsArrow()
            }
        }

        // Widgets — dynamic ordered list with enable toggles and up/down reorder buttons
        buildWidgetOrderUI(view, prefs)

        // Build current map selector
        val mapContainer = view.findViewById<LinearLayout>(R.id.currentMapContainer)
        if (mapContainer != null) {
            val dp = resources.displayMetrics.density
            val mapFrag = (activity as? MainActivity)?.supportFragmentManager
                ?.fragments?.filterIsInstance<MapFragment>()?.firstOrNull()

            val currentBase = prefs.getString(MapFragment.PREF_TILE_KEY, "osm") ?: "osm"

            // --- Online base maps ---
            mapContainer.addView(TextView(requireContext()).apply {
                text = "Онлайн карты"
                setTextColor(0xFFCCCCCC.toInt())
                textSize = 13f
                setPadding(0, 0, 0, (4 * dp).toInt())
            })

            var offlineRg: RadioGroup? = null
            val baseRg = RadioGroup(requireContext()).apply { orientation = RadioGroup.VERTICAL }
            // Get tile sources dynamically from MapFragment
            val allTileSources = mapFrag?.getTileSources() ?: emptyMap()
            allTileSources.filter { !it.key.startsWith("offline_") }.forEach { (key, source) ->
                baseRg.addView(RadioButton(requireContext()).apply {
                    text = source.label; tag = key
                    setTextColor(0xFFCCCCCC.toInt()); textSize = 14f
                    id = View.generateViewId()
                    isChecked = (key == currentBase)
                })
            }
            baseRg.setOnCheckedChangeListener { group, checkedId ->
                val rb = group.findViewById<RadioButton>(checkedId)
                val key = rb?.tag as? String ?: return@setOnCheckedChangeListener
                // Uncheck offline radio if exists
                offlineRg?.clearCheck()
                prefs.edit().putString(MapFragment.PREF_TILE_KEY, key).apply()
                mapFrag?.switchMap(key)
            }
            mapContainer.addView(baseRg)

            // --- Offline maps ---
            val offlineMaps = mapFrag?.getOfflineMaps() ?: emptyList()
            mapContainer.addView(TextView(requireContext()).apply {
                text = "Офлайн карты"
                setTextColor(0xFFCCCCCC.toInt())
                textSize = 13f
                setPadding(0, (16 * dp).toInt(), 0, (4 * dp).toInt())
            })

            if (offlineMaps.isEmpty()) {
                mapContainer.addView(TextView(requireContext()).apply {
                    text = "Нет загруженных карт"
                    setTextColor(0xFF555555.toInt()); textSize = 13f
                    setPadding(0, (4 * dp).toInt(), 0, (4 * dp).toInt())
                })
            } else {
                offlineRg = RadioGroup(requireContext()).apply { orientation = RadioGroup.VERTICAL }
                offlineMaps.forEach { info ->
                    offlineRg!!.addView(RadioButton(requireContext()).apply {
                        text = info.name; tag = info.key
                        setTextColor(0xFFCCCCCC.toInt()); textSize = 14f
                        id = View.generateViewId()
                        isChecked = (info.key == currentBase)
                    })
                }
                offlineRg!!.setOnCheckedChangeListener { group, checkedId ->
                    val rb = group.findViewById<RadioButton>(checkedId)
                    val key = rb?.tag as? String ?: return@setOnCheckedChangeListener
                    baseRg.clearCheck()
                    prefs.edit().putString(MapFragment.PREF_TILE_KEY, key).apply()
                    mapFrag?.switchMap(key)
                }
                mapContainer.addView(offlineRg!!)
            }

            // --- Overlays ---
            mapContainer.addView(TextView(requireContext()).apply {
                text = "Оверлеи"
                setTextColor(0xFFCCCCCC.toInt())
                textSize = 13f
                setPadding(0, (16 * dp).toInt(), 0, (4 * dp).toInt())
            })

            val allOverlays = mapFrag?.getOverlaySources() ?: emptyMap()
            val activeOverlayStr = prefs.getString(MapFragment.PREF_OVERLAY_KEY, "") ?: ""
            val activeOverlays = activeOverlayStr.split(",").filter { it.isNotBlank() && it != "none" }.toSet()
            allOverlays.filter { it.key != "none" }.forEach { (key, source) ->
                mapContainer.addView(android.widget.CheckBox(requireContext()).apply {
                    text = source.label; tag = key
                    setTextColor(0xFFCCCCCC.toInt()); textSize = 14f
                    isChecked = key in activeOverlays
                    setOnCheckedChangeListener { _, checked ->
                        val currentStr = prefs.getString(MapFragment.PREF_OVERLAY_KEY, "") ?: ""
                        val current = currentStr.split(",").filter { it.isNotBlank() && it != "none" }.toMutableSet()
                        if (checked) current.add(key) else current.remove(key)
                        val newVal = if (current.isEmpty()) "none" else current.joinToString(",")
                        prefs.edit().putString(MapFragment.PREF_OVERLAY_KEY, newVal).apply()
                        val baseKey = prefs.getString(MapFragment.PREF_TILE_KEY, "osm") ?: "osm"
                        mapFrag?.switchMap(baseKey)
                    }
                })
            }
        }

        // File loader — show dataset library first
        view.findViewById<View>(R.id.btnLoadFile).setOnClickListener {
            showDatasetLibrary()
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

        // Nav buttons: show if waypoints are loaded (from memory or from prefs)
        val rowNavButtons = view.findViewById<View>(R.id.rowNavButtons)
        val hasWaypoints = mapFrag?.hasLoadedWaypoints() == true ||
            prefs.getString(MapFragment.PREF_SAVED_WAYPOINTS_JSON, null)?.isNotEmpty() == true
        if (hasWaypoints) rowNavButtons.visibility = View.VISIBLE
        if (hasWaypoints) view.findViewById<View>(R.id.rowShareWp)?.visibility = View.VISIBLE

        // Share waypoints
        view.findViewById<android.widget.Button>(R.id.btnShareWp)?.setOnClickListener {
            parentFragmentManager.fragments.filterIsInstance<MapFragment>().firstOrNull()?.shareLoadedWaypoints()
        }

        // Approach radius
        val txtApproachRadius = view.findViewById<TextView>(R.id.txtApproachRadius)
        var approachRadius = prefs.getInt(PREF_WP_APPROACH_RADIUS, DEFAULT_WP_APPROACH_RADIUS).coerceIn(5, 100)
        txtApproachRadius.text = approachRadius.toString()
        view.findViewById<ImageButton>(R.id.btnApproachRadiusMinus).setOnClickListener {
            if (approachRadius > 5) {
                approachRadius -= 5
                txtApproachRadius.text = approachRadius.toString()
                prefs.edit().putInt(PREF_WP_APPROACH_RADIUS, approachRadius).apply()
            }
        }
        view.findViewById<ImageButton>(R.id.btnApproachRadiusPlus).setOnClickListener {
            if (approachRadius < 100) {
                approachRadius += 5
                txtApproachRadius.text = approachRadius.toString()
                prefs.edit().putInt(PREF_WP_APPROACH_RADIUS, approachRadius).apply()
            }
        }

        // Nav line color — color swatch
        val viewNavLineColor = view.findViewById<View>(R.id.viewNavLineColor)
        val savedNavColor = prefs.getString(PREF_NAV_LINE_COLOR, "#FF6F00") ?: "#FF6F00"
        viewNavLineColor.setBackgroundColor(android.graphics.Color.parseColor(savedNavColor))
        viewNavLineColor.setOnClickListener {
            showColorPicker(prefs.getString(PREF_NAV_LINE_COLOR, "#FF6F00") ?: "#FF6F00") { color ->
                viewNavLineColor.setBackgroundColor(android.graphics.Color.parseColor(color))
                prefs.edit().putString(PREF_NAV_LINE_COLOR, color).apply()
                mapFragRef()?.applyNavLineStyle()
            }
        }

        // Nav line width
        val txtNavLineWidth = view.findViewById<TextView>(R.id.txtNavLineWidth)
        var navLineWidth = prefs.getInt(PREF_NAV_LINE_WIDTH, 3).coerceIn(1, 8)
        txtNavLineWidth.text = navLineWidth.toString()
        view.findViewById<ImageButton>(R.id.btnNavLineWidthMinus).setOnClickListener {
            if (navLineWidth > 1) {
                navLineWidth--
                txtNavLineWidth.text = navLineWidth.toString()
                prefs.edit().putInt(PREF_NAV_LINE_WIDTH, navLineWidth).apply()
                mapFragRef()?.applyNavLineStyle()
            }
        }
        view.findViewById<ImageButton>(R.id.btnNavLineWidthPlus).setOnClickListener {
            if (navLineWidth < 8) {
                navLineWidth++
                txtNavLineWidth.text = navLineWidth.toString()
                prefs.edit().putInt(PREF_NAV_LINE_WIDTH, navLineWidth).apply()
                mapFragRef()?.applyNavLineStyle()
            }
        }

        // Route line color — color swatch
        val viewRouteLineColor = view.findViewById<View>(R.id.viewRouteLineColor)
        val savedRouteColor = prefs.getString(PREF_ROUTE_LINE_COLOR, "#FF6F00") ?: "#FF6F00"
        viewRouteLineColor.setBackgroundColor(android.graphics.Color.parseColor(savedRouteColor))
        viewRouteLineColor.setOnClickListener {
            showColorPicker(prefs.getString(PREF_ROUTE_LINE_COLOR, "#FF6F00") ?: "#FF6F00") { color ->
                viewRouteLineColor.setBackgroundColor(android.graphics.Color.parseColor(color))
                prefs.edit().putString(PREF_ROUTE_LINE_COLOR, color).apply()
                mapFragRef()?.applyRouteLineStyle()
            }
        }

        // Route line width
        val txtRouteLineWidth = view.findViewById<TextView>(R.id.txtRouteLineWidth)
        var routeLineWidth = prefs.getInt(PREF_ROUTE_LINE_WIDTH, 2).coerceIn(1, 8)
        txtRouteLineWidth.text = routeLineWidth.toString()
        view.findViewById<ImageButton>(R.id.btnRouteLineWidthMinus).setOnClickListener {
            if (routeLineWidth > 1) {
                routeLineWidth--
                txtRouteLineWidth.text = routeLineWidth.toString()
                prefs.edit().putInt(PREF_ROUTE_LINE_WIDTH, routeLineWidth).apply()
                mapFragRef()?.applyRouteLineStyle()
            }
        }
        view.findViewById<ImageButton>(R.id.btnRouteLineWidthPlus).setOnClickListener {
            if (routeLineWidth < 8) {
                routeLineWidth++
                txtRouteLineWidth.text = routeLineWidth.toString()
                prefs.edit().putInt(PREF_ROUTE_LINE_WIDTH, routeLineWidth).apply()
                mapFragRef()?.applyRouteLineStyle()
            }
        }

        // KP label size
        val txtWpLabelSize = view.findViewById<TextView>(R.id.txtWpLabelSize)
        var wpLabelSize = prefs.getInt(PREF_WP_LABEL_SIZE, 3).coerceIn(1, 10)
        txtWpLabelSize.text = wpLabelSize.toString()
        view.findViewById<ImageButton>(R.id.btnWpLabelSizeMinus).setOnClickListener {
            if (wpLabelSize > 1) {
                wpLabelSize--
                txtWpLabelSize.text = wpLabelSize.toString()
                prefs.edit().putInt(PREF_WP_LABEL_SIZE, wpLabelSize).apply()
                mapFragRef()?.applyWpLabelSize()
            }
        }
        view.findViewById<ImageButton>(R.id.btnWpLabelSizePlus).setOnClickListener {
            if (wpLabelSize < 10) {
                wpLabelSize++
                txtWpLabelSize.text = wpLabelSize.toString()
                prefs.edit().putInt(PREF_WP_LABEL_SIZE, wpLabelSize).apply()
                mapFragRef()?.applyWpLabelSize()
            }
        }

        // Sound settings
        val switchApproach = view.findViewById<android.widget.Switch>(R.id.switchSoundApproach)
        val switchTaken = view.findViewById<android.widget.Switch>(R.id.switchSoundTaken)
        switchApproach.isChecked = prefs.getBoolean(MapFragment.PREF_SOUND_APPROACH, true)
        switchTaken.isChecked = prefs.getBoolean(MapFragment.PREF_SOUND_TAKEN, true)
        switchApproach.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(MapFragment.PREF_SOUND_APPROACH, checked).apply()
        }
        switchTaken.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(MapFragment.PREF_SOUND_TAKEN, checked).apply()
        }

        // Hints toggle
        val switchHints = view.findViewById<android.widget.Switch>(R.id.switchHints)
        switchHints.isChecked = prefs.getBoolean(MapFragment.PREF_HINTS_ENABLED, true)
        switchHints.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(MapFragment.PREF_HINTS_ENABLED, checked).apply()
        }

        // Custom map sources
        setupCustomSources(view, prefs)

        // Navigation start/stop
        view.findViewById<android.widget.Button>(R.id.btnNavStart).setOnClickListener {
            mapFrag?.startNavigation()
            Toast.makeText(requireContext(), "Навигация запущена", Toast.LENGTH_SHORT).show()
        }
        view.findViewById<android.widget.Button>(R.id.btnNavStop).setOnClickListener {
            mapFrag?.stopNavigation()
            Toast.makeText(requireContext(), "Навигация остановлена", Toast.LENGTH_SHORT).show()
        }

        // Loaded track color — single swatch with picker
        val viewLoadedTrackColor = view.findViewById<View>(R.id.viewLoadedTrackColor)
        val savedLoadedColor = prefs.getString(PREF_LOADED_TRACK_COLOR, DEFAULT_LOADED_TRACK_COLOR) ?: DEFAULT_LOADED_TRACK_COLOR
        viewLoadedTrackColor.setBackgroundColor(android.graphics.Color.parseColor(savedLoadedColor))
        viewLoadedTrackColor.setOnClickListener {
            showColorPicker(prefs.getString(PREF_LOADED_TRACK_COLOR, DEFAULT_LOADED_TRACK_COLOR) ?: DEFAULT_LOADED_TRACK_COLOR) { color ->
                viewLoadedTrackColor.setBackgroundColor(android.graphics.Color.parseColor(color))
                prefs.edit().putString(PREF_LOADED_TRACK_COLOR, color).apply()
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
// Download maps button
        view.findViewById<android.widget.Button>(R.id.btnStartDownload)?.setOnClickListener {
            val mapFrag = parentFragmentManager.fragments.filterIsInstance<MapFragment>().firstOrNull()
            if (mapFrag != null) {
                parentFragmentManager.popBackStack()
                mapFrag.startDownloadMode()
            } else {
                Toast.makeText(context, "Карта не найдена", Toast.LENGTH_SHORT).show()
            }
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

        // ── Backup ──
        val editBackupEmail = view.findViewById<EditText>(R.id.editBackupEmail)
        val btnBackupCreate = view.findViewById<android.widget.Button>(R.id.btnBackupCreate)
        val btnBackupRestore = view.findViewById<android.widget.Button>(R.id.btnBackupRestore)
        val txtBackupStatus = view.findViewById<TextView>(R.id.txtBackupStatus)

        editBackupEmail.setText(prefs.getString("backup_email", ""))

        fun setBackupStatus(ok: Boolean, msg: String) {
            txtBackupStatus.text = msg
            txtBackupStatus.setTextColor(if (ok) 0xFF4CAF50.toInt() else 0xFFE53935.toInt())
            txtBackupStatus.visibility = View.VISIBLE
            btnBackupCreate.isEnabled = true
            btnBackupRestore.isEnabled = true
        }

        btnBackupCreate.setOnClickListener {
            val email = editBackupEmail.text.toString().trim()
            if (email.isNotEmpty()) prefs.edit().putString("backup_email", email).apply()
            btnBackupCreate.isEnabled = false; btnBackupRestore.isEnabled = false
            txtBackupStatus.text = "Создаю бэкап..."; txtBackupStatus.setTextColor(0xFF888888.toInt()); txtBackupStatus.visibility = View.VISIBLE
            kotlinx.coroutines.MainScope().launch {
                val result = BackupManager.createBackup(requireContext(), email.ifEmpty { null })
                setBackupStatus(result.ok, result.message)
            }
        }

        btnBackupRestore.setOnClickListener {
            val email = editBackupEmail.text.toString().trim()
            if (email.isEmpty()) { Toast.makeText(requireContext(), "Введите email для восстановления", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            android.app.AlertDialog.Builder(requireContext())
                .setTitle("Восстановить бэкап?")
                .setMessage("Текущие настройки будут заменены. Продолжить?")
                .setPositiveButton("Да") { _, _ ->
                    btnBackupCreate.isEnabled = false; btnBackupRestore.isEnabled = false
                    txtBackupStatus.text = "Восстанавливаю..."; txtBackupStatus.setTextColor(0xFF888888.toInt()); txtBackupStatus.visibility = View.VISIBLE
                    kotlinx.coroutines.MainScope().launch {
                        val result = BackupManager.restoreBackup(requireContext(), email)
                        setBackupStatus(result.ok, result.message)
                    }
                }
                .setNegativeButton("Отмена", null)
                .show()
        }

        // ── Server Live Monitoring ──
        val switchTraccar = view.findViewById<SwitchCompat>(R.id.switchTraccar)
        val rowTraccarDeviceName = view.findViewById<View>(R.id.rowTraccarDeviceName)
        val rowTraccarStatus = view.findViewById<View>(R.id.rowTraccarStatus)
        val txtTraccarDeviceName = view.findViewById<TextView>(R.id.txtTraccarDeviceName)
        val txtTraccarStatus = view.findViewById<TextView>(R.id.txtTraccarStatus)
        val viewTraccarStatusDot = view.findViewById<View>(R.id.viewTraccarStatusDot)

        // Auto-assign server URL and Device ID if not set
        if (prefs.getString(MapFragment.PREF_TRACCAR_URL, "").isNullOrBlank()) {
            prefs.edit().putString(MapFragment.PREF_TRACCAR_URL, "http://217.60.1.225:5055").apply()
        }
        run {
            val stableId = LicenseManager.getRawDeviceId(requireContext())
            val stableTraccarId = stableId.replace("-", "").take(8).uppercase()
            val currentId = prefs.getString(MapFragment.PREF_TRACCAR_DEVICE_ID, "") ?: ""
            
            if (currentId.isBlank()) {
                // New install — use stable ID
                prefs.edit().putString(MapFragment.PREF_TRACCAR_DEVICE_ID, stableTraccarId).apply()
            } else if (currentId != stableTraccarId && !prefs.getBoolean("traccar_id_migrated", false)) {
                // Existing user with old random ID — migrate to stable
                val oldId = currentId
                prefs.edit()
                    .putString(MapFragment.PREF_TRACCAR_DEVICE_ID, stableTraccarId)
                    .putString("traccar_old_device_id", oldId)
                    .putBoolean("traccar_id_migrated", true)
                    .apply()
                // Update device on Traccar server (change uniqueId)
                val url = prefs.getString(MapFragment.PREF_TRACCAR_URL, "") ?: ""
                val name = prefs.getString(MapFragment.PREF_TRACCAR_DEVICE_NAME, "") ?: ""
                if (url.isNotBlank()) {
                    migrateTraccarDeviceId(url, oldId, stableTraccarId, name)
                }
                android.util.Log.d("Settings", "Traccar ID migrated: $oldId -> $stableTraccarId")
            }
        }

        val traccarEnabled = prefs.getBoolean(MapFragment.PREF_TRACCAR_ENABLED, false)
        switchTraccar.isChecked = traccarEnabled
        fun showTraccarRows(show: Boolean) {
            val vis = if (show) View.VISIBLE else View.GONE
            rowTraccarDeviceName.visibility = vis
            rowTraccarStatus.visibility = vis
        }
        showTraccarRows(traccarEnabled)

        // Display saved values
        fun refreshTraccarDisplay() {
            val devName = prefs.getString(MapFragment.PREF_TRACCAR_DEVICE_NAME, "") ?: ""
            txtTraccarDeviceName.text = if (devName.isNotBlank()) devName else "Не задано"
        }
        refreshTraccarDisplay()

        // Update status indicator
        fun updateTraccarStatus() {
            if (TraccarService.isRunning) {
                viewTraccarStatusDot.setBackgroundColor(0xFF4CAF50.toInt())
                txtTraccarStatus.text = "Статус: активен"
                txtTraccarStatus.setTextColor(0xFF4CAF50.toInt())
            } else {
                viewTraccarStatusDot.setBackgroundColor(0xFF888888.toInt())
                txtTraccarStatus.text = "Статус: выключен"
                txtTraccarStatus.setTextColor(0xFF888888.toInt())
            }
        }
        updateTraccarStatus()

        // Toggle starts/stops TraccarService
        switchTraccar.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(MapFragment.PREF_TRACCAR_ENABLED, checked).apply()
            showTraccarRows(checked)
            val ctx = requireContext()
            if (checked) {
                val devName = prefs.getString(MapFragment.PREF_TRACCAR_DEVICE_NAME, "") ?: ""
                if (devName.isBlank()) {
                    Toast.makeText(ctx, "Сначала укажите имя устройства", Toast.LENGTH_LONG).show()
                    switchTraccar.isChecked = false
                    prefs.edit().putBoolean(MapFragment.PREF_TRACCAR_ENABLED, false).apply()
                    return@setOnCheckedChangeListener
                }
                // First-time consent dialog
                val consentGiven = prefs.getBoolean("traccar_consent_given", false)
                if (!consentGiven) {
                    switchTraccar.isChecked = false
                    prefs.edit().putBoolean(MapFragment.PREF_TRACCAR_ENABLED, false).apply()
                    android.app.AlertDialog.Builder(ctx)
                        .setTitle("Отправка местоположения")
                        .setMessage("При включении этой функции ваше местоположение будет передаваться на сервер и может быть видно другим участникам системы.\n\nВы согласны?")
                        .setPositiveButton("Согласен") { _, _ ->
                            prefs.edit().putBoolean("traccar_consent_given", true).apply()
                            switchTraccar.isChecked = true
                        }
                        .setNegativeButton("Отмена", null)
                        .show()
                    return@setOnCheckedChangeListener
                }
                // Request background location permission for reliable background GPS
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q &&
                    androidx.core.content.ContextCompat.checkSelfPermission(ctx,
                        android.Manifest.permission.ACCESS_BACKGROUND_LOCATION) !=
                        android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(
                        arrayOf(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION), 200)
                }
                // Register device on Traccar server, then start service
                val url = prefs.getString(MapFragment.PREF_TRACCAR_URL, "") ?: ""
                val devId = prefs.getString(MapFragment.PREF_TRACCAR_DEVICE_ID, "") ?: ""
                registerTraccarDevice(url, devId, devName) {
                    ctx.startForegroundService(
                        android.content.Intent(ctx, TraccarService::class.java).apply {
                            action = TraccarService.ACTION_START
                        }
                    )
                    // Включаем тумблер мониторинга и запускаем если он был включён
                    view.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchLiveUsers)?.let {
                        it.isEnabled = true
                        it.alpha = 1.0f
                    }
                    if (prefs.getBoolean(MapFragment.PREF_LIVE_USERS_ENABLED, false)) {
                        parentFragmentManager.fragments.filterIsInstance<MapFragment>().firstOrNull()?.startLiveUsersPoller()
                    }
                    view.postDelayed({ updateTraccarStatus() }, 500)
                }
            } else {
                ctx.startService(
                    android.content.Intent(ctx, TraccarService::class.java).apply {
                        action = TraccarService.ACTION_STOP
                    }
                )
                // Блокируем тумблер и останавливаем мониторинг — взаимная функция
                view.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchLiveUsers)?.let {
                    it.isEnabled = false
                    it.alpha = 0.4f
                }
                parentFragmentManager.fragments.filterIsInstance<MapFragment>().firstOrNull()?.stopLiveUsersPoller()
                view.postDelayed({ updateTraccarStatus() }, 500)
            }
        }

        // Edit Device Name on click
        rowTraccarDeviceName.setOnClickListener {
            val container = android.widget.FrameLayout(requireContext()).apply {
                setBackgroundColor(0xFF2A2A2A.toInt())
                setPadding(48, 24, 48, 24)
            }
            val input = EditText(requireContext()).apply {
                setText(prefs.getString(MapFragment.PREF_TRACCAR_DEVICE_NAME, ""))
                hint = "Андрей М планшет"
                setTextColor(0xFFFFFFFF.toInt())
                setHintTextColor(0xFF888888.toInt())
                setBackgroundColor(0xFF333333.toInt())
                setPadding(24, 16, 24, 16)
            }
            container.addView(input)
            AlertDialog.Builder(requireContext(), androidx.appcompat.R.style.Theme_AppCompat_Dialog)
                .setTitle("Имя устройства")
                .setMessage("Ваше имя для отображения на мониторинге")
                .setView(container)
                .setPositiveButton("OK") { _, _ ->
                    val name = input.text.toString().trim()
                    prefs.edit().putString(MapFragment.PREF_TRACCAR_DEVICE_NAME, name).apply()
                    refreshTraccarDisplay()
                }
                .setNegativeButton("Отмена", null)
                .show()
        }

        // Live Users on map toggle
        val switchLiveUsers = view.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchLiveUsers)
        val traccarOn = prefs.getBoolean(MapFragment.PREF_TRACCAR_ENABLED, false)
        switchLiveUsers.isChecked = prefs.getBoolean(MapFragment.PREF_LIVE_USERS_ENABLED, false)
        switchLiveUsers.isEnabled = traccarOn
        switchLiveUsers.alpha = if (traccarOn) 1.0f else 0.4f
        switchLiveUsers.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(MapFragment.PREF_LIVE_USERS_ENABLED, checked).apply()
            val mapFrag = parentFragmentManager.fragments.filterIsInstance<MapFragment>().firstOrNull()
            Toast.makeText(context, "LiveUsers: ${if (checked) "ON" else "OFF"}, mapFrag=${mapFrag != null}", Toast.LENGTH_SHORT).show()
            if (checked) {
                mapFrag?.startLiveUsersPoller()
            } else {
                mapFrag?.stopLiveUsersPoller()
            }
        }

        // "Only online" checkbox for Live Users
        val onlineOnlyRow = view.findViewById<View>(R.id.switchLiveUsers)?.parent as? ViewGroup
        if (onlineOnlyRow?.parent is ViewGroup) {
            val parentVg = onlineOnlyRow.parent as ViewGroup
            val idx = parentVg.indexOfChild(onlineOnlyRow) + 1
            val cbRow = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding((32 * resources.displayMetrics.density).toInt(), 0, (16 * resources.displayMetrics.density).toInt(), (4 * resources.displayMetrics.density).toInt())
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            cbRow.addView(android.widget.CheckBox(requireContext()).apply {
                text = "Только онлайн"
                setTextColor(0xFFCCCCCC.toInt())
                textSize = 13f
                isChecked = prefs.getBoolean(MapFragment.PREF_LIVE_USERS_ONLINE_ONLY, false)
                buttonTintList = android.content.res.ColorStateList.valueOf(0xFFFF6F00.toInt())
                setOnCheckedChangeListener { _, c -> prefs.edit().putBoolean(MapFragment.PREF_LIVE_USERS_ONLINE_ONLY, c).apply() }
            })
            parentVg.addView(cbRow, idx)

        // ── Live user marker size ──
        val txtLiveUserSize = view.findViewById<TextView>(R.id.txtLiveUserSize)
        var liveUserSize = prefs.getInt(MapFragment.PREF_LIVE_USER_SIZE, MapFragment.DEFAULT_LIVE_USER_SIZE).coerceIn(1, 10)
        txtLiveUserSize?.text = liveUserSize.toString()
        view.findViewById<ImageButton>(R.id.btnLiveUserSizeMinus)?.setOnClickListener {
            if (liveUserSize > 1) {
                liveUserSize -= 1
                txtLiveUserSize?.text = liveUserSize.toString()
                prefs.edit().putInt(MapFragment.PREF_LIVE_USER_SIZE, liveUserSize).apply()
            }
        }
        view.findViewById<ImageButton>(R.id.btnLiveUserSizePlus)?.setOnClickListener {
            if (liveUserSize < 10) {
                liveUserSize += 1
                txtLiveUserSize?.text = liveUserSize.toString()
                prefs.edit().putInt(MapFragment.PREF_LIVE_USER_SIZE, liveUserSize).apply()
            }
        }

        // ── Live user label size ──
        val txtLiveUserLabelSize = view.findViewById<TextView>(R.id.txtLiveUserLabelSize)
        var liveUserLabelSize = prefs.getInt(MapFragment.PREF_LIVE_USER_LABEL_SIZE, MapFragment.DEFAULT_LIVE_USER_LABEL_SIZE).coerceIn(1, 10)
        txtLiveUserLabelSize?.text = liveUserLabelSize.toString()
        view.findViewById<ImageButton>(R.id.btnLiveUserLabelMinus)?.setOnClickListener {
            if (liveUserLabelSize > 1) {
                liveUserLabelSize -= 1
                txtLiveUserLabelSize?.text = liveUserLabelSize.toString()
                prefs.edit().putInt(MapFragment.PREF_LIVE_USER_LABEL_SIZE, liveUserLabelSize).apply()
            }
        }
        view.findViewById<ImageButton>(R.id.btnLiveUserLabelPlus)?.setOnClickListener {
            if (liveUserLabelSize < 10) {
                liveUserLabelSize += 1
                txtLiveUserLabelSize?.text = liveUserLabelSize.toString()
                prefs.edit().putInt(MapFragment.PREF_LIVE_USER_LABEL_SIZE, liveUserLabelSize).apply()
            }
        }

        // ── Live users list (sorted by distance) ──
        val liveUsersListContainer = view.findViewById<LinearLayout>(R.id.liveUsersListContainer)
        val liveHandler = android.os.Handler(android.os.Looper.getMainLooper())
        liveUsersHandler = liveHandler
        val liveUsersRefreshRunnable = object : Runnable {
            override fun run() {
                if (!isAdded) return
                val mapFrag = parentFragmentManager.fragments.filterIsInstance<MapFragment>().firstOrNull()
                val container = liveUsersListContainer ?: return
                container.removeAllViews()
                val devices = mapFrag?.lastLiveDevices ?: emptyList()
                val gps = mapFrag?.getLastGpsPoint()
                val density = resources.displayMetrics.density
                val onlineDevices = devices.filter { it.status == "online" }
                val sorted = if (gps != null) {
                    onlineDevices.sortedBy { d ->
                        val R = 6371000.0
                        val dLat = Math.toRadians(d.lat - gps.latitude)
                        val dLon = Math.toRadians(d.lon - gps.longitude)
                        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                                Math.cos(Math.toRadians(gps.latitude)) * Math.cos(Math.toRadians(d.lat)) *
                                Math.sin(dLon / 2) * Math.sin(dLon / 2)
                        R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
                    }
                } else onlineDevices
                sorted.forEach { d ->
                    val dist = if (gps != null) {
                        val R = 6371000.0
                        val dLat = Math.toRadians(d.lat - gps.latitude)
                        val dLon = Math.toRadians(d.lon - gps.longitude)
                        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                                Math.cos(Math.toRadians(gps.latitude)) * Math.cos(Math.toRadians(d.lat)) *
                                Math.sin(dLon / 2) * Math.sin(dLon / 2)
                        R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
                    } else 0.0
                    val distStr = if (dist > 0) String.format("%.1f км", dist / 1000.0) else "—"
                    val row = LinearLayout(requireContext()).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding((16 * density).toInt(), (6 * density).toInt(), (16 * density).toInt(), (6 * density).toInt())
                        gravity = android.view.Gravity.CENTER_VERTICAL
                        setBackgroundColor(0xFF1E1E1E.toInt())
                    }
                    // Green dot for online
                    val dot = android.view.View(requireContext()).apply {
                        val lp = LinearLayout.LayoutParams((8 * density).toInt(), (8 * density).toInt())
                        lp.marginEnd = (8 * density).toInt()
                        layoutParams = lp
                        setBackgroundColor(android.graphics.Color.parseColor("#4CAF50"))
                    }
                    row.addView(dot)
                    row.addView(TextView(requireContext()).apply {
                        text = "${d.name} — $distStr"
                        setTextColor(0xFFCCCCCC.toInt())
                        textSize = 13f
                    })
                    container.addView(row)
                }
                if (sorted.isEmpty()) {
                    container.addView(TextView(requireContext()).apply {
                        text = "Нет участников онлайн"
                        setTextColor(0xFF666666.toInt())
                        textSize = 12f
                        setPadding((16 * density).toInt(), (8 * density).toInt(), 0, (4 * density).toInt())
                    })
                }
                liveHandler.postDelayed(this, 4000)
            }
        }
        liveUsersRunnable = liveUsersRefreshRunnable
        liveHandler.post(liveUsersRefreshRunnable)
        }

        // ── Crosshair, Coords, Distance Line settings (programmatic) ──
        try {
            val markerSizeView = view.findViewById<View>(R.id.markerSizeRow)
            val parentContainer = markerSizeView?.parent as? ViewGroup
            if (parentContainer != null) {
                val insertIdx = parentContainer.indexOfChild(markerSizeView) + 1
                val mapFrag = parentFragmentManager.fragments.filterIsInstance<MapFragment>().firstOrNull()
                val density = resources.displayMetrics.density
                val section = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundColor(0xFF1E1E1E.toInt())
                    setPadding(0, (8 * density).toInt(), 0, (8 * density).toInt())
                }
                // Section title
                section.addView(TextView(requireContext()).apply {
                    text = "Карта — дополнительно"
                    setTextColor(0xFFFF6F00.toInt()); textSize = 14f
                    setPadding((16 * density).toInt(), (8 * density).toInt(), 0, (12 * density).toInt())
                    setTypeface(null, android.graphics.Typeface.BOLD)
                })
                fun makeToggle(label: String, prefKey: String, default: Boolean, onChange: ((Boolean) -> Unit)? = null) {
                    val row = LinearLayout(requireContext()).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding((16 * density).toInt(), (6 * density).toInt(), (16 * density).toInt(), (6 * density).toInt())
                        gravity = android.view.Gravity.CENTER_VERTICAL
                        minimumHeight = (44 * density).toInt()
                    }
                    row.addView(TextView(requireContext()).apply {
                        text = label; setTextColor(0xFFFFFFFF.toInt()); textSize = 14f
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    })
                    row.addView(androidx.appcompat.widget.SwitchCompat(requireContext()).apply {
                        isChecked = prefs.getBoolean(prefKey, default)
                        setOnCheckedChangeListener { _, c -> prefs.edit().putBoolean(prefKey, c).apply(); onChange?.invoke(c) }
                    })
                    section.addView(row)
                }
                fun makeSlider(label: String, min: Int, max: Int, current: Int, onChanged: (Int) -> Unit) {
                    val row = LinearLayout(requireContext()).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding((16 * density).toInt(), 0, (16 * density).toInt(), (6 * density).toInt())
                        gravity = android.view.Gravity.CENTER_VERTICAL
                    }
                    row.addView(TextView(requireContext()).apply {
                        text = label; setTextColor(0xFFAAAAAA.toInt()); textSize = 13f
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.4f)
                    })
                    row.addView(SeekBar(requireContext()).apply {
                        this.max = max - min; progress = current - min
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.6f)
                        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                            override fun onProgressChanged(sb: SeekBar, p: Int, u: Boolean) { onChanged(p + min) }
                            override fun onStartTrackingTouch(sb: SeekBar) {}
                            override fun onStopTrackingTouch(sb: SeekBar) {}
                        })
                    })
                    section.addView(row)
                }
                // Crosshair
                makeToggle("Перекрестие", MapFragment.PREF_CROSSHAIR_ENABLED, true) { mapFrag?.applyCrosshairPrefs() }
                makeSlider("Размер", 30, 100, prefs.getInt(MapFragment.PREF_CROSSHAIR_SIZE, 60)) { v ->
                    prefs.edit().putInt(MapFragment.PREF_CROSSHAIR_SIZE, v).apply(); mapFrag?.applyCrosshairPrefs()
                }
                // Coords
                makeToggle("Координаты центра", MapFragment.PREF_COORDS_ENABLED, true)
                // Distance line
                makeToggle("Линия расстояния", MapFragment.PREF_DISTANCE_LINE_ENABLED, true) {
                    mapFrag?.applyCrosshairAndDistancePrefs()
                }
                // Color picker (same style as track/marker color pickers)
                val colorRow = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding((16 * density).toInt(), (6 * density).toInt(), (16 * density).toInt(), (6 * density).toInt())
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    minimumHeight = (44 * density).toInt()
                }
                colorRow.addView(TextView(requireContext()).apply {
                    text = "Цвет линии"; setTextColor(0xFFFFFFFF.toInt()); textSize = 14f
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                val savedColor = prefs.getString(MapFragment.PREF_DISTANCE_LINE_COLOR, "#FFFF00") ?: "#FFFF00"
                val colorSwatch = View(requireContext()).apply {
                    layoutParams = LinearLayout.LayoutParams((40 * density).toInt(), (40 * density).toInt())
                    setBackgroundColor(android.graphics.Color.parseColor(savedColor))
                }
                colorSwatch.setOnClickListener {
                    showColorPicker(prefs.getString(MapFragment.PREF_DISTANCE_LINE_COLOR, "#FFFF00") ?: "#FFFF00") { color ->
                        colorSwatch.setBackgroundColor(android.graphics.Color.parseColor(color))
                        prefs.edit().putString(MapFragment.PREF_DISTANCE_LINE_COLOR, color).apply()
                    }
                }
                colorRow.addView(colorSwatch)
                section.addView(colorRow)
                // Distance line width
                makeSlider("Толщина линии", 1, 8, prefs.getFloat(MapFragment.PREF_DISTANCE_LINE_WIDTH, 2f).toInt()) { v ->
                    prefs.edit().putFloat(MapFragment.PREF_DISTANCE_LINE_WIDTH, v.toFloat()).apply()
                    mapFrag?.applyLineStyles()
                }

                // Divider
                section.addView(View(requireContext()).apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (1 * density).toInt())
                    setBackgroundColor(0xFF444444.toInt())
                })

                // === Heading line (predicted direction) ===
                section.addView(TextView(requireContext()).apply {
                    text = "Линия направления"
                    setTextColor(0xFFFF6F00.toInt()); textSize = 14f
                    setPadding((16 * density).toInt(), (12 * density).toInt(), 0, (8 * density).toInt())
                    setTypeface(null, android.graphics.Typeface.BOLD)
                })
                makeToggle("Показывать", MapFragment.PREF_HEADING_LINE_ENABLED, false)
                // Heading line color
                val hColorRow = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding((16 * density).toInt(), (6 * density).toInt(), (16 * density).toInt(), (6 * density).toInt())
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    minimumHeight = (44 * density).toInt()
                }
                hColorRow.addView(TextView(requireContext()).apply {
                    text = "Цвет"; setTextColor(0xFFFFFFFF.toInt()); textSize = 14f
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                val hSavedColor = prefs.getString(MapFragment.PREF_HEADING_LINE_COLOR, "#00BFFF") ?: "#00BFFF"
                val hColorSwatch = View(requireContext()).apply {
                    layoutParams = LinearLayout.LayoutParams((40 * density).toInt(), (40 * density).toInt())
                    setBackgroundColor(android.graphics.Color.parseColor(hSavedColor))
                }
                hColorSwatch.setOnClickListener {
                    showColorPicker(prefs.getString(MapFragment.PREF_HEADING_LINE_COLOR, "#00BFFF") ?: "#00BFFF") { color ->
                        hColorSwatch.setBackgroundColor(android.graphics.Color.parseColor(color))
                        prefs.edit().putString(MapFragment.PREF_HEADING_LINE_COLOR, color).apply()
                        mapFrag?.applyLineStyles()
                    }
                }
                hColorRow.addView(hColorSwatch)
                section.addView(hColorRow)
                // Heading line width
                makeSlider("Толщина", 1, 8, prefs.getFloat(MapFragment.PREF_HEADING_LINE_WIDTH, 2f).toInt()) { v ->
                    prefs.edit().putFloat(MapFragment.PREF_HEADING_LINE_WIDTH, v.toFloat()).apply()
                    mapFrag?.applyLineStyles()
                }

                parentContainer.addView(section, insertIdx)
            }
        } catch (e: Exception) {
            Log.w("Settings", "Map settings section error: ${e.message}")
        }

        // Version & Author + License status
        try {
            val ctx = requireContext()
            val v = ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
            val licStatus = if (LicenseManager.isActivated(ctx)) {
                "Лицензия: активирована ✓"
            } else {
                val days = LicenseManager.trialDaysLeft(ctx)
                "Пробный период: $days дн. осталось"
            }
            view.findViewById<TextView>(R.id.txtVersion).text = "Trophy Navigator v$v\n© Andrey Mironchik\n$licStatus"
            view.findViewById<TextView>(R.id.txtAboutFeatures).text = listOf(
                "• Оффлайн и онлайн карты с кешированием",
                "• Запись и экспорт треков (GPX/PLT/KML)",
                "• Загрузка маршрутов и точек (GPX/PLT/KML/RTE)",
                "• Навигация по маршруту и к отдельным точкам",
                "• Установка точек на карте с редактированием",
                "• Настраиваемые виджеты скорости, высоты, курса",
                "• Перекрестие, линия расстояния и направления",
                "• Координаты центра карты (WGS84)",
                "• Отображение участников в реальном времени",
                "• Передача местоположения на сервер",
                "• Блокировка экрана (кнопка или зажать Громкость+)",
                "• Автозапись трека при старте приложения"
            ).joinToString("\n")
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
                mapFrag.checkForUpdates { latest, current, hasUpdate, apkUrl, changelog ->
                    btnCheck.isEnabled = true
                    if (latest == null) {
                        txtStatus.text = "Нет связи"
                        txtStatus.setTextColor(0xFFFF6F00.toInt())
                    } else if (hasUpdate && apkUrl != null) {
                        txtStatus.text = ""
                        showUpdateDialog(latest, current, apkUrl, changelog)
                    } else {
                        txtStatus.text = "✓ $current — актуальная"
                        txtStatus.setTextColor(0xFF22C55E.toInt())
                    }
                }
            } else {
                btnCheck.isEnabled = true
                txtStatus.text = "Откройте карту и попробуйте снова"
            }
        }

        // Readme / About
        view.findViewById<android.widget.Button>(R.id.btnReadme).setOnClickListener {
            showReadme()
        }

        // Make sections collapsible
        setupCollapsibleSections(view)
    }

    private fun showUpdateDialog(latest: String, current: String, apkUrl: String, changelog: String?) {
        val ctx = context ?: return
        val dp = resources.displayMetrics.density
        val pad = (20 * dp).toInt()

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        // Version info
        root.addView(TextView(ctx).apply {
            text = "Доступно обновление $latest"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 17f
            setTypeface(null, android.graphics.Typeface.BOLD)
        })

        root.addView(TextView(ctx).apply {
            text = "Текущая версия: $current"
            setTextColor(0xFF999999.toInt())
            textSize = 13f
            setPadding(0, (4 * dp).toInt(), 0, (12 * dp).toInt())
        })

        // Changelog
        if (!changelog.isNullOrBlank()) {
            root.addView(TextView(ctx).apply {
                text = "Что нового:"
                setTextColor(0xFFFF6F00.toInt())
                textSize = 14f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(0, 0, 0, (6 * dp).toInt())
            })
            root.addView(TextView(ctx).apply {
                text = changelog
                setTextColor(0xFFCCCCCC.toInt())
                textSize = 13f
                setPadding(0, 0, 0, (8 * dp).toInt())
            })
        }

        // Progress bar (hidden initially)
        val progressBar = android.widget.ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (6 * dp).toInt()).apply {
                topMargin = (12 * dp).toInt()
            }
            max = 100
            progress = 0
            visibility = android.view.View.GONE
            progressDrawable.setColorFilter(0xFFFF6F00.toInt(), android.graphics.PorterDuff.Mode.SRC_IN)
        }
        root.addView(progressBar)

        // Progress text (hidden initially)
        val progressText = TextView(ctx).apply {
            setTextColor(0xFF999999.toInt())
            textSize = 12f
            setPadding(0, (4 * dp).toInt(), 0, 0)
            visibility = android.view.View.GONE
        }
        root.addView(progressText)

        val dlg = AlertDialog.Builder(ctx, androidx.appcompat.R.style.Theme_AppCompat_Dialog)
            .setView(root)
            .setPositiveButton("Скачать", null)  // set listener below to prevent auto-dismiss
            .setNegativeButton("Позже", null)
            .setCancelable(true)
            .create()

        dlg.show()

        // Override positive button to prevent dialog from closing during download
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            // Disable buttons during download
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
            dlg.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = false
            dlg.setCancelable(false)
            progressBar.visibility = android.view.View.VISIBLE
            progressText.visibility = android.view.View.VISIBLE
            progressText.text = "Скачивание..."

            UpdateManager.downloadAndInstall(
                requireContext(), apkUrl, latest,
                onProgress = { bytesRead, totalBytes ->
                    if (totalBytes > 0) {
                        val pct = (bytesRead * 100 / totalBytes).toInt()
                        progressBar.progress = pct
                        val readMb = "%.1f".format(bytesRead / 1048576.0)
                        val totalMb = "%.1f".format(totalBytes / 1048576.0)
                        progressText.text = "Скачано $readMb / $totalMb МБ ($pct%)"
                    } else {
                        val readMb = "%.1f".format(bytesRead / 1048576.0)
                        progressText.text = "Скачано $readMb МБ..."
                        progressBar.isIndeterminate = true
                    }
                },
                onComplete = { success, error ->
                    if (success) {
                        progressText.text = "✓ Скачано! Установка..."
                        progressBar.progress = 100
                        // Dialog will be covered by installer, dismiss after short delay
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

    /**
     * Register or update device on Traccar server via REST API.
     * If device with this uniqueId exists — check name, offer rename if different.
     * If device doesn't exist — create it.
     */

    /** Migrate Traccar device: update uniqueId on server from old random to stable */
    private fun migrateTraccarDeviceId(serverUrl: String, oldId: String, newId: String, deviceName: String) {
        val apiBase = serverUrl.replace(":5055", ":8082").replace(":5056", ":8082").trimEnd('/')
        val auth = okhttp3.Credentials.basic("snowwolf888@gmail.com", "7513")
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val client = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                // Find old device
                val checkReq = okhttp3.Request.Builder()
                    .url("$apiBase/api/devices?uniqueId=$oldId")
                    .header("Authorization", auth)
                    .get().build()
                val resp = client.newCall(checkReq).execute()
                val body = resp.body?.string() ?: "[]"
                resp.close()
                val devices = org.json.JSONArray(body)
                if (devices.length() > 0) {
                    val device = devices.getJSONObject(0)
                    val deviceServerId = device.getInt("id")
                    device.put("uniqueId", newId)
                    if (deviceName.isNotBlank()) device.put("name", deviceName)
                    val updateReq = okhttp3.Request.Builder()
                        .url("$apiBase/api/devices/$deviceServerId")
                        .header("Authorization", auth)
                        .put(device.toString().toRequestBody("application/json".toMediaType()))
                        .build()
                    val updateResp = client.newCall(updateReq).execute()
                    updateResp.close()
                    android.util.Log.d("Settings", "Traccar device migrated on server: $oldId -> $newId")
                }
            } catch (e: Exception) {
                android.util.Log.w("Settings", "Traccar migration failed: ${e.message}")
            }
        }
    }

    private fun registerTraccarDevice(serverUrl: String, uniqueId: String, deviceName: String, onDone: () -> Unit) {
        val ctx = context ?: return
        // Traccar API is on port 8082, OsmAnd is on 5055. Derive API base from OsmAnd URL.
        val apiBase = serverUrl.replace(":5055", ":8082").replace(":5056", ":8082").trimEnd('/')
        val auth = okhttp3.Credentials.basic("snowwolf888@gmail.com", "7513")

        CoroutineScope(Dispatchers.Main).launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val client = okhttp3.OkHttpClient.Builder()
                        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                        .build()

                    // Check if device exists
                    val checkReq = okhttp3.Request.Builder()
                        .url("$apiBase/api/devices?uniqueId=$uniqueId")
                        .header("Authorization", auth)
                        .get().build()
                    val checkResp = client.newCall(checkReq).execute()
                    val body = checkResp.body?.string() ?: "[]"
                    checkResp.close()

                    val devices = org.json.JSONArray(body)
                    if (devices.length() > 0) {
                        // Device exists — check name
                        val existing = devices.getJSONObject(0)
                        val existingName = existing.getString("name")
                        val deviceId = existing.getInt("id")
                        if (deviceName.isNotBlank() && deviceName != existingName) {
                            // Update name
                            existing.put("name", deviceName)
                            val updateReq = okhttp3.Request.Builder()
                                .url("$apiBase/api/devices/$deviceId")
                                .header("Authorization", auth)
                                .put(existing.toString()
                                    .toRequestBody("application/json".toMediaType()))
                                .build()
                            val updateResp = client.newCall(updateReq).execute()
                            updateResp.close()
                            "updated"
                        } else {
                            "exists"
                        }
                    } else if (deviceName.isNotBlank()) {
                        // Create new device
                        val json = org.json.JSONObject().apply {
                            put("name", deviceName)
                            put("uniqueId", uniqueId)
                            put("groupId", 66)  // Users group
                        }
                        val createReq = okhttp3.Request.Builder()
                            .url("$apiBase/api/devices")
                            .header("Authorization", auth)
                            .post(json.toString()
                                .toRequestBody("application/json".toMediaType()))
                            .build()
                        val createResp = client.newCall(createReq).execute()
                        createResp.close()
                        "created"
                    } else {
                        "skip" // No name, device will auto-create on first data
                    }
                }
                when (result) {
                    "created" -> Toast.makeText(ctx, "Устройство создано в Traccar", Toast.LENGTH_SHORT).show()
                    "updated" -> Toast.makeText(ctx, "Имя устройства обновлено", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                // Non-critical — device will be auto-created by Traccar on first data point
                android.util.Log.w("SettingsFragment", "Traccar API: ${e.message}")
            }
            onDone()
        }
    }

    // Section header texts → tab groups
    private val tabSections = mapOf(
        "main" to listOf("ЭКРАН", "МАРКЕР ПОЗИЦИИ", "УПРАВЛЕНИЕ", "ВИДЖЕТЫ НА КАРТЕ", "Карта — дополнительно"),
        "files" to listOf("ОБЪЕКТЫ КАРТЫ", "ЗАПИСЬ ТРЕКА", "НАВИГАЦИЯ"),
        "maps" to listOf("ТЕКУЩАЯ КАРТА", "ИСТОЧНИКИ КАРТ", "ОФФЛАЙН КАРТЫ", "ЗАГРУЗКА КАРТ", "КЭШ КАРТ"),
        "network" to listOf("МОНИТОРИНГ", "СИНХРОНИЗАЦИЯ", "О ПРИЛОЖЕНИИ"),
        "about" to listOf("О ПРИЛОЖЕНИИ")
    )

    private var liveUsersHandler: android.os.Handler? = null
    private var liveUsersRunnable: Runnable? = null
    private var currentSettingsTab = "main"
    private var scrollContentRef: android.view.ViewGroup? = null

    private fun setupTabs(view: View) {
        val tabs = mapOf(
            "main" to view.findViewById<TextView>(R.id.tabMain),
            "files" to view.findViewById<TextView>(R.id.tabFiles),
            "maps" to view.findViewById<TextView>(R.id.tabMaps),
            "network" to view.findViewById<TextView>(R.id.tabNetwork),
            "about" to view.findViewById<TextView>(R.id.tabAbout)
        )
        scrollContentRef = view.findViewById<android.widget.ScrollView>(R.id.settingsScroll)
            ?.getChildAt(0) as? android.view.ViewGroup ?: return

        tabs.forEach { (key, tv) ->
            tv?.setOnClickListener {
                currentSettingsTab = key
                applyTabVisibility()
                tabs.forEach { (k, t) ->
                    t?.setTextColor(if (k == key) 0xFFFF6F00.toInt() else 0xFF888888.toInt())
                    t?.setTypeface(null, if (k == key) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                }
            }
        }
        // Defer first apply to after all programmatic views are added
        view.post { applyTabVisibility() }
    }

    /** Scan all children, assign to tab by section headers, show/hide */
    private fun applyTabVisibility() {
        val sc = scrollContentRef ?: return
        var curTab = "main"
        for (i in 0 until sc.childCount) {
            val child = sc.getChildAt(i)
            // Check tag first (reliable), fallback to text matching
            val tag = child.tag
            if (tag is String && tag.startsWith("tab:")) {
                curTab = tag.removePrefix("tab:")
            } else if (tag is Map<*, *>) {
                // collapsible header — use origTag
                val origTag = (tag as? Map<String, Any>)?.get("origTag") as? String ?: ""
                if (origTag.startsWith("tab:")) {
                    curTab = origTag.removePrefix("tab:")
                }
            }
            child.visibility = if (curTab == currentSettingsTab) View.VISIBLE else View.GONE
        }
        // Re-apply collapsed state for visible sections
        reapplyCollapsedState(sc)
        (sc.parent as? android.widget.ScrollView)?.scrollTo(0, 0)
    }

    private fun reapplyCollapsedState(sc: android.view.ViewGroup) {
        for (i in 0 until sc.childCount) {
            val child = sc.getChildAt(i)
            if (child is TextView && child.tag is Map<*, *>) {
                @Suppress("UNCHECKED_CAST")
                val info = child.tag as? Map<String, Any> ?: continue
                val collapsed = info["collapsed"] as? Boolean ?: continue
                val views = info["contentViews"] as? List<View> ?: continue
                if (collapsed) views.forEach { it.visibility = View.GONE }
            }
        }
    }

    private fun showColorPicker(currentColor: String, onColorSelected: (String) -> Unit) {
        val ctx = context ?: return
        val colors = listOf(
            "#FF6F00" to "Оранжевый",
            "#FFFF00" to "Жёлтый",
            "#FFFFFF" to "Белый",
            "#00FF00" to "Зелёный",
            "#FF4444" to "Красный",
            "#00BFFF" to "Голубой",
            "#FF00FF" to "Пурпурный",
            "#1565C0" to "Синий",
            "#00E676" to "Салатовый",
            "#FF8A80" to "Розовый",
            "#B388FF" to "Фиолетовый",
            "#CCCCCC" to "Серый"
        )
        val dp = resources.displayMetrics.density
        val pad = (16 * dp).toInt()
        val swatchSize = (48 * dp).toInt()
        val gap = (8 * dp).toInt()

        val grid = android.widget.GridLayout(ctx).apply {
            columnCount = 4
            setPadding(pad, pad, pad, pad)
        }

        val dialog = AlertDialog.Builder(ctx, androidx.appcompat.R.style.Theme_AppCompat_Dialog)
            .setTitle("Выберите цвет")
            .setView(grid)
            .setNegativeButton("Отмена", null)
            .create()

        colors.forEach { (hex, _) ->
            val swatch = View(ctx).apply {
                layoutParams = android.widget.GridLayout.LayoutParams().apply {
                    width = swatchSize
                    height = swatchSize
                    setMargins(gap, gap, gap, gap)
                }
                setBackgroundColor(android.graphics.Color.parseColor(hex))
                alpha = if (hex == currentColor) 1f else 0.6f
                setOnClickListener {
                    onColorSelected(hex)
                    dialog.dismiss()
                }
            }
            // Add border for current selection
            if (hex == currentColor) {
                swatch.foreground = android.graphics.drawable.GradientDrawable().apply {
                    setStroke((2 * dp).toInt(), android.graphics.Color.WHITE)
                }
            }
            grid.addView(swatch)
        }

        dialog.show()
    }

    private fun showReadme() {
        val ctx = context ?: return
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(ctx)
        val pad = (24 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        val sections = listOf(
            "Trophy Navigator" to "Навигационное приложение для трофи-рейдов, ралли-рейдов и внедорожных соревнований.\n\n© Andrey Mironchik",

            "🗺 Карта" to """
• Переключение слоёв: кнопка слоёв (◇) слева
• Множественные оверлеи: включайте несколько одновременно
• Свои источники карт: добавляйте в Настройках → Источники карт
• Оффлайн карты: загружайте .sqlitedb / .mbtiles файлы
• Зум: кнопками +/−, жестами или кнопками громкости
• Долгое нажатие на карту — скрыть/показать панели
""".trimIndent(),

            "🧭 Компас и режимы следования" to """
• Тап по компасу переключает режим по кругу:
  Белый — свободный (карта не вращается)
  Зелёный — север вверху (карта следует за GPS)
  Оранжевый — по курсу (карта вращается по ходу движения)
• Долгое нажатие на компас — диагностика GPS
""".trimIndent(),

            "📍 Навигация по маршруту" to """
• Загрузите GPX файл с точками через Настройки → Загрузить файл
• Или создайте маршрут вручную: кнопка ⋮ → Редактор маршрута
• Запуск навигации: ⋮ → ▶ Старт
• Переключение КП: кнопки ◀ ▶ в навбаре
• Тап по инфо в навбаре — полный список КП с расстояниями
• Автосмена КП при входе в радиус сближения
""".trimIndent(),

            "📌 Маркеры" to """
• Кнопка + ставит маркер в текущей GPS позиции
• Маркеры не участвуют в навигации — просто метки на карте
• Экспорт маркеров как GPX: ⋮ → Маркеры → Экспорт
""".trimIndent(),

            "⏺ Запись трека" to """
• Кнопка REC начинает запись в фоне (работает при выключенном экране)
• При остановке — сохранение как GPX с возможностью отправки
• Виджеты: скорость, длина трека, хронометр
""".trimIndent(),

            "✏️ Редактор маршрута" to """
• Кнопка ⋮ → Редактор маршрута
• Добавляйте точки: по GPS или по центру карты
• Редактируйте имя и координаты каждой точки
• Меняйте порядок (↑) или удаляйте (✕)
• Устанавливайте радиус сближения для всех КП
• Применяйте на карту или сохраняйте как GPX файл
""".trimIndent(),

            "📊 Виджеты" to """
• Нижняя панель — настраиваемые виджеты
• Включение/выключение и порядок — в Настройках → Виджеты
• Доступно: скорость, курс, трек, до КП, высота, хронометр, время, остаток км, имя КП, триппмастер
• Триппмастер: тап сбрасывает счётчик
""".trimIndent(),

            "🔒 Блокировка экрана" to """
• Кнопка 🔒 блокирует экран от случайных нажатий
• Для разблокировки — удерживайте кнопку Громкость+
""".trimIndent(),

            "💡 Подсказки" to """
• Долгое нажатие на любую кнопку покажет подсказку
• Отключаются в Настройках → Подсказки на кнопках
""".trimIndent(),

            "📁 Файлы и папки" to """
• Приложение создаёт папку Documents/RaceNav/ с подпапками:
  📂 maps/ — офлайн карты (.sqlitedb, .mbtiles)
  📂 tracks/ — записанные и сохранённые треки (.gpx)
  📂 points/ — экспортированные точки (.gpx, .wpt)
  📂 routes/ — маршруты (.gpx, .rte)
• Вы можете вручную копировать файлы в эти папки
• Загрузка файлов: Настройки → Файлы → 📂 Загрузить файл
• Офлайн карты: Настройки → Карты → 📥 Загрузить карту
""".trimIndent(),

            "☁️ Бэкап и восстановление" to """
• Настройки → Сеть → раздел «Бэкап»
• Укажите email — он служит ключом для восстановления на новом устройстве
• «Создать бэкап» — сохраняет в облако: все настройки, маршруты, треки
• «Восстановить» — введите email, данные загрузятся автоматически
• Бэкап привязан к устройству (Android ID) и email одновременно
• Файлы оффлайн-карт не включаются в бэкап (слишком большие)
""".trimIndent(),

            "🆕 Что нового (v2.2.3)" to """
• Бэкап настроек, треков и маршрутов в облако по email
• Восстановление на новом устройстве по email
• Загрузка оффлайн-карт прямо из приложения (выбор области на карте)
• Мониторинг участников и GPS-сервер — взаимная функция
• Тумблер мониторинга недоступен пока сервер выключен
• Стабильный ID устройства (не меняется при переустановке)
• 20+ источников карт: Google, Яндекс, ESRI, 2GIS, Thunderforest и др.
""".trimIndent()
        )

        sections.forEach { (title, body) ->
            root.addView(TextView(ctx).apply {
                text = title
                setTextColor(0xFFFF6F00.toInt())
                textSize = 16f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(0, 16, 0, 4)
            })
            root.addView(TextView(ctx).apply {
                text = body
                setTextColor(0xFFCCCCCC.toInt())
                textSize = 14f
                setPadding(0, 0, 0, 8)
            })
        }

        val scroll = android.widget.ScrollView(ctx).apply {
            addView(root)
            setBackgroundColor(0xFF121212.toInt())
        }
        dialog.setContentView(scroll)
        dialog.window?.navigationBarColor = 0xFF121212.toInt()
        (scroll.parent as? android.view.View)?.setBackgroundColor(0xFF121212.toInt())
        // Expand to full height
        dialog.behavior.peekHeight = resources.displayMetrics.heightPixels
        dialog.show()
    }

    private val SECTION_HEADERS = setOf(
        "ЭКРАН", "МАРКЕР ПОЗИЦИИ", "УПРАВЛЕНИЕ", "ВИДЖЕТЫ НА КАРТЕ",
        "ОБЪЕКТЫ КАРТЫ", "ЗАПИСЬ ТРЕКА", "НАВИГАЦИЯ",
        "ТЕКУЩАЯ КАРТА", "ИСТОЧНИКИ КАРТ", "ОФФЛАЙН КАРТЫ", "ЗАГРУЗКА КАРТ", "КЭШ КАРТ",
        "МОНИТОРИНГ", "СИНХРОНИЗАЦИЯ", "О ПРИЛОЖЕНИИ"
    )

    /** Default collapsed sections — less frequently used */
    private val DEFAULT_COLLAPSED = setOf(
        "ВИДЖЕТЫ НА КАРТЕ", "КЭШ КАРТ", "ЗАГРУЗКА КАРТ"
    )

    private fun setupCollapsibleSections(view: View) {
        val root = view as? ViewGroup ?: return
        var scrollView: android.widget.ScrollView? = null
        for (i in 0 until root.childCount) {
            if (root.getChildAt(i) is android.widget.ScrollView) {
                scrollView = root.getChildAt(i) as android.widget.ScrollView; break
            }
        }
        val contentLayout = scrollView?.getChildAt(0) as? LinearLayout ?: return

        // Collect section header indices
        val headerIndices = mutableListOf<Int>()
        for (i in 0 until contentLayout.childCount) {
            val child = contentLayout.getChildAt(i)
            if (child is TextView) {
                val text = child.text?.toString()?.trim() ?: ""
                if (SECTION_HEADERS.any { text.contains(it, ignoreCase = true) }) {
                    headerIndices.add(i)
                }
            }
        }

        // For each header, set up collapsible behavior
        val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        for (h in headerIndices.indices) {
            val headerIdx = headerIndices[h]
            val nextHeaderIdx = if (h + 1 < headerIndices.size) headerIndices[h + 1] else contentLayout.childCount
            val header = contentLayout.getChildAt(headerIdx) as TextView
            val sectionName = header.text.toString().trim()

            // Collect content views between this header and next
            val contentViews = mutableListOf<View>()
            for (i in (headerIdx + 1) until nextHeaderIdx) {
                contentViews.add(contentLayout.getChildAt(i))
            }
            if (contentViews.isEmpty() || sectionName.contains("О ПРИЛОЖЕНИИ")) continue

            // Always start collapsed when settings open
            val prefKey = "section_collapsed_$sectionName"
            var collapsed = true

            // Set initial state — always collapsed
            header.text = "▶  $sectionName"
            header.setPadding(0, 16, 0, 8)
            header.textSize = 12f
            val origTag = header.tag as? String ?: ""; header.tag = mapOf("collapsed" to true, "contentViews" to contentViews, "name" to sectionName, "origTag" to origTag)
            contentViews.forEach { it.visibility = View.GONE }

            // Click handler
            header.setOnClickListener {
                collapsed = !collapsed
                prefs.edit().putBoolean(prefKey, collapsed).apply()
                header.text = "${if (collapsed) "▶" else "▼"}  $sectionName"
                header.tag = mapOf("collapsed" to collapsed, "contentViews" to contentViews, "name" to sectionName, "origTag" to origTag)
                contentViews.forEach { it.visibility = if (collapsed) View.GONE else View.VISIBLE }
            }
        }
    }

    private fun buildWidgetOrderUI(
        view: View,
        prefs: android.content.SharedPreferences
    ) {
        data class WInfo(val key: String, val label: String, val prefKey: String, val defaultOn: Boolean)
        val allWidgets = listOf(
            WInfo("speed",         "Скорость",         PREF_WIDGET_SPEED,         true),
            WInfo("bearing",       "Курс / стрелка",   PREF_WIDGET_BEARING,       true),
            WInfo("tracklen",      "Длина трека",       PREF_WIDGET_TRACKLEN,      true),
            WInfo("nextcp",        "До след. КП",       PREF_WIDGET_NEXTCP,        true),
            WInfo("altitude",      "Высота",            PREF_WIDGET_ALTITUDE,      true),
            WInfo("chrono",        "Хронометр",         PREF_WIDGET_CHRONO,        false),
            WInfo("time",          "Текущее время",     PREF_WIDGET_TIME,          false),
            WInfo("remain_km",     "Остаток км",        PREF_WIDGET_REMAIN_KM,     false),
            WInfo("nextcp_name",   "Имя след. КП",      PREF_WIDGET_NEXTCP_NAME,   false),
            WInfo("tripmaster",    "Триппмастер",       PREF_WIDGET_TRIPMASTER,    false),
            WInfo("server_status", "Статус сервера",    MapFragment.PREF_WIDGET_SERVER_STATUS, false),
        )

        val savedOrder = prefs.getString(PREF_WIDGET_ORDER, ALL_WIDGET_KEYS.joinToString(",")) ?: ALL_WIDGET_KEYS.joinToString(",")
        val orderedKeys = savedOrder.split(",").toMutableList()
        ALL_WIDGET_KEYS.forEach { k -> if (k !in orderedKeys) orderedKeys.add(k) }

        val container = view.findViewById<LinearLayout>(R.id.widgetOrderContainer)

        fun rebuildRows() {
            container.removeAllViews()

            // ── Top bar buttons subsection ──
            val topLabel = TextView(requireContext()).apply {
                text = "ВЕРХНЯЯ ПАНЕЛЬ"
                setTextColor(0xFF888888.toInt())
                textSize = 11f
                letterSpacing = 0.1f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = (8 * resources.displayMetrics.density).toInt()
                    bottomMargin = (8 * resources.displayMetrics.density).toInt()
                }
            }
            container.addView(topLabel)

            data class BtnInfo(val label: String, val prefKey: String, val defaultOn: Boolean)
            val topButtons = listOf(
                BtnInfo("Компас",          MapFragment.PREF_BTN_COMPASS,  true),
                BtnInfo("Зум +/−",         MapFragment.PREF_BTN_ZOOM,     true),
                BtnInfo("Добавить точку",  MapFragment.PREF_BTN_WAYPOINT, true),
                BtnInfo("Быстрые действия", MapFragment.PREF_BTN_QUICK,   true),
                BtnInfo("Слои карты",      MapFragment.PREF_BTN_LAYERS,   true),
                BtnInfo("Запись трека",    MapFragment.PREF_BTN_REC,      true),
                BtnInfo("Блокировка",      MapFragment.PREF_BTN_LOCK,     true),
                BtnInfo("Смена карты ⇄",   MapFragment.PREF_BTN_MAP_SWITCH, false),
                BtnInfo("Статус сервера",  MapFragment.PREF_BTN_SERVER_DOT, false),
            )
            val dp = resources.displayMetrics.density
            for (btn in topButtons) {
                val row = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setBackgroundColor(0xFF1E1E1E.toInt())
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        (48 * dp + 0.5f).toInt()
                    ).apply { topMargin = (1 * dp + 0.5f).toInt() }
                    setPadding((16 * dp + 0.5f).toInt(), 0, (16 * dp + 0.5f).toInt(), 0)
                }
                val label = TextView(requireContext()).apply {
                    text = btn.label
                    setTextColor(0xFFFFFFFF.toInt())
                    textSize = 15f
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                val sw = SwitchCompat(requireContext()).apply {
                    isChecked = prefs.getBoolean(btn.prefKey, btn.defaultOn)
                    setOnCheckedChangeListener { _, checked ->
                        prefs.edit().putBoolean(btn.prefKey, checked).apply()
                        parentFragmentManager.fragments.filterIsInstance<MapFragment>()
                            .firstOrNull()?.applyTopBarPrefs()
                    }
                }
                row.addView(label)
                row.addView(sw)
                container.addView(row)
            }

            // ── Map switch settings (only shown when map switch button is enabled) ──
            if (prefs.getBoolean(MapFragment.PREF_BTN_MAP_SWITCH, false)) {
                val mapNames = linkedMapOf(
                    "osm" to "OpenStreetMap",
                    "satellite" to "Спутник ESRI",
                    "topo" to "OpenTopoMap",
                    "google" to "Google Спутник",
                    "genshtab250" to "Генштаб 250м",
                    "genshtab500" to "Генштаб 500м",
                    "ggc500" to "ГГЦ 500м",
                    "ggc1000" to "ГГЦ 1км",
                    "topo250" to "Топо 250м",
                    "topo500" to "Топо 500м",
                )
                val mapKeys = mapNames.keys.toList()
                val mapLabels = mapNames.values.toTypedArray()

                fun addMapRow(label: String, prefKey: String, defaultKey: String) {
                    val row = LinearLayout(requireContext()).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = android.view.Gravity.CENTER_VERTICAL
                        setBackgroundColor(0xFF1E1E1E.toInt())
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            (48 * dp + 0.5f).toInt()
                        ).apply { topMargin = (1 * dp + 0.5f).toInt() }
                        setPadding((16 * dp + 0.5f).toInt(), 0, (16 * dp + 0.5f).toInt(), 0)
                    }
                    val currentKey = prefs.getString(prefKey, defaultKey) ?: defaultKey
                    val txt = TextView(requireContext()).apply {
                        text = label
                        setTextColor(0xFFFFFFFF.toInt())
                        textSize = 15f
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    val value = TextView(requireContext()).apply {
                        text = mapNames[currentKey] ?: currentKey
                        setTextColor(0xFF888888.toInt())
                        textSize = 13f
                    }
                    row.addView(txt)
                    row.addView(value)
                    row.setOnClickListener {
                        val idx = mapKeys.indexOf(currentKey).coerceAtLeast(0)
                        AlertDialog.Builder(requireContext(), androidx.appcompat.R.style.Theme_AppCompat_Dialog)
                            .setTitle(label)
                            .setSingleChoiceItems(mapLabels, idx) { dlg, which ->
                                prefs.edit().putString(prefKey, mapKeys[which]).apply()
                                value.text = mapLabels[which]
                                dlg.dismiss()
                                rebuildRows()
                            }
                            .setNegativeButton("Отмена", null)
                            .show()
                    }
                    container.addView(row)
                }
                addMapRow("  Карта 1", MapFragment.PREF_MAP_SWITCH_A, "ggc500")
                addMapRow("  Карта 2", MapFragment.PREF_MAP_SWITCH_B, "google")
            }

            // ── Bottom widgets subsection ──
            val bottomLabel = TextView(requireContext()).apply {
                text = "НИЖНЯЯ ПАНЕЛЬ"
                setTextColor(0xFF888888.toInt())
                textSize = 11f
                letterSpacing = 0.1f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = (16 * dp).toInt()
                    bottomMargin = (8 * dp).toInt()
                }
            }
            container.addView(bottomLabel)

            orderedKeys.forEachIndexed { idx, key ->
                val info = allWidgets.find { it.key == key } ?: return@forEachIndexed
                val row = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setBackgroundColor(0xFF1E1E1E.toInt())
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        (52 * dp + 0.5f).toInt()
                    ).apply { topMargin = (1 * dp + 0.5f).toInt() }
                    setPadding((16 * dp + 0.5f).toInt(), 0, (8 * dp + 0.5f).toInt(), 0)
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

                val btnSize = (32 * dp + 0.5f).toInt()

                val btnUp = ImageButton(requireContext()).apply {
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

    private fun openFilePicker() {
        filePickerLauncher.launch(arrayOf("*/*", "application/gpx+xml", "application/octet-stream"))
    }

    private fun loadDatasetList(): List<LoadedDataset> {
        val json = requireContext().getSharedPreferences(MapFragment.PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREF_DATASETS_JSON, null) ?: return emptyList()
        return try {
            val arr = org.json.JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                LoadedDataset(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    waypointCount = o.optInt("wpCount", 0),
                    trackPointCount = o.optInt("trackCount", 0),
                    loadedAt = o.optString("loadedAt", ""),
                    filePath = o.optString("filePath", null).takeIf { it?.isNotBlank() == true },
                    mapKey = o.optString("mapKey", null).takeIf { it?.isNotBlank() == true }
                )
            }
        } catch (e: Exception) { emptyList() }
    }

    private fun saveDatasetToList(name: String, wpCount: Int, trackCount: Int,
                                   waypoints: List<Waypoint> = emptyList()) {
        val ctx = requireContext()
        val prefs = ctx.getSharedPreferences(MapFragment.PREFS_NAME, Context.MODE_PRIVATE)
        val existing = try {
            org.json.JSONArray(prefs.getString(PREF_DATASETS_JSON, "[]"))
        } catch (e: Exception) { org.json.JSONArray() }

        // Remove duplicate by name if exists
        val filtered = org.json.JSONArray()
        for (i in 0 until existing.length()) {
            if (existing.getJSONObject(i).getString("name") != name) {
                filtered.put(existing.getJSONObject(i))
            }
        }

        val id = System.currentTimeMillis().toString()
        val mapKey = prefs.getString(MapFragment.PREF_TILE_KEY, "osm") ?: "osm"

        // Save waypoints to GPX file for later reloading
        var filePath: String? = null
        if (waypoints.isNotEmpty()) {
            try {
                val dir = ctx.getExternalFilesDir("datasets")
                dir?.mkdirs()
                val file = java.io.File(dir, "${id}_${name.take(40)}.gpx")
                file.writeText(GpxParser.writeWaypointsGpx(waypoints, name))
                filePath = file.absolutePath
            } catch (_: Exception) {}
        }

        val newEntry = org.json.JSONObject()
            .put("id", id)
            .put("name", name)
            .put("wpCount", wpCount)
            .put("trackCount", trackCount)
            .put("mapKey", mapKey)
            .put("loadedAt", java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale.getDefault())
                .format(java.util.Date()))
        if (filePath != null) newEntry.put("filePath", filePath)

        // Insert at beginning, keep MAX_DATASETS
        val result = org.json.JSONArray()
        result.put(newEntry)
        for (i in 0 until minOf(filtered.length(), MAX_DATASETS - 1)) result.put(filtered.getJSONObject(i))

        prefs.edit().putString(PREF_DATASETS_JSON, result.toString()).apply()
    }

    private fun deleteDataset(dsId: String) {
        val prefs = requireContext().getSharedPreferences(MapFragment.PREFS_NAME, Context.MODE_PRIVATE)
        val existing = try {
            org.json.JSONArray(prefs.getString(PREF_DATASETS_JSON, "[]"))
        } catch (e: Exception) { return }

        val filtered = org.json.JSONArray()
        for (i in 0 until existing.length()) {
            val o = existing.getJSONObject(i)
            if (o.getString("id") != dsId) {
                filtered.put(o)
            } else {
                // Delete saved GPX file if exists
                val path = o.optString("filePath", null)
                if (!path.isNullOrBlank()) {
                    try { java.io.File(path).delete() } catch (_: Exception) {}
                }
            }
        }
        prefs.edit().putString(PREF_DATASETS_JSON, filtered.toString()).apply()
    }

    private fun showDatasetLibrary() {
        val datasets = loadDatasetList()
        val ctx = requireContext()
        var dialog: com.google.android.material.bottomsheet.BottomSheetDialog? = null

        val layout = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(0, 16, 0, 16)
        }

        val title = android.widget.TextView(ctx).apply {
            text = "📋 Загруженные наборы (${datasets.size})"
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(32, 16, 32, 8)
        }
        layout.addView(title)

        if (datasets.isEmpty()) {
            layout.addView(android.widget.TextView(ctx).apply {
                text = "Нет сохранённых наборов.\nЗагрузите файл GPX, WPT, RTE или PLT."
                textSize = 14f
                setTextColor(0xFF888888.toInt())
                setPadding(32, 8, 32, 8)
            })
        } else {
            datasets.forEach { ds ->
                // Card-like container
                val card = android.widget.LinearLayout(ctx).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    setPadding(32, 16, 32, 16)
                    setBackgroundResource(android.R.drawable.list_selector_background)
                }

                // Row 1: name + file type
                val ext = ds.name.substringAfterLast('.', "").uppercase()
                val typeLabel = if (ext.isNotBlank()) " [$ext]" else ""
                card.addView(android.widget.TextView(ctx).apply {
                    text = "${ds.name}$typeLabel"
                    textSize = 14f
                    setTextColor(0xFFFFFFFF.toInt())
                    setTypeface(null, android.graphics.Typeface.BOLD)
                })

                // Row 2: properties
                val props = buildString {
                    if (ds.waypointCount > 0) append("🎯 КП: ${ds.waypointCount}  ")
                    if (ds.trackPointCount > 0) append("📍 Трек: ${ds.trackPointCount} точек  ")
                    if (ds.loadedAt.isNotBlank()) append("📅 ${ds.loadedAt}")
                }
                card.addView(android.widget.TextView(ctx).apply {
                    text = props
                    textSize = 12f
                    setTextColor(0xFFAAAAAA.toInt())
                    setPadding(0, 4, 0, 0)
                })

                // Row 3: map key + file status
                val meta = buildString {
                    if (!ds.mapKey.isNullOrBlank()) append("🗺 Карта: ${ds.mapKey}  ")
                    if (ds.filePath != null) {
                        if (java.io.File(ds.filePath).exists()) append("✅ Файл сохранён")
                        else append("❌ Файл не найден")
                    }
                }
                if (meta.isNotBlank()) {
                    card.addView(android.widget.TextView(ctx).apply {
                        text = meta
                        textSize = 11f
                        setTextColor(0xFF777777.toInt())
                        setPadding(0, 2, 0, 0)
                    })
                }

                // Row 4: action buttons
                val btnRow = android.widget.LinearLayout(ctx).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    setPadding(0, 8, 0, 0)
                }
                val btnLp = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = 16
                }

                // Load button
                if (ds.filePath != null && java.io.File(ds.filePath).exists()) {
                    btnRow.addView(android.widget.Button(ctx).apply {
                        text = "▶ Загрузить"
                        textSize = 12f; isAllCaps = false
                        setTextColor(0xFFFF6F00.toInt())
                        setBackgroundColor(android.graphics.Color.TRANSPARENT)
                        layoutParams = btnLp
                        setOnClickListener {
                            dialog?.dismiss()
                            loadDatasetFromFile(ds)
                        }
                    })
                }

                // Delete button
                btnRow.addView(android.widget.Button(ctx).apply {
                    text = "🗑 Удалить"
                    textSize = 12f; isAllCaps = false
                    setTextColor(0xFFFF4444.toInt())
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    layoutParams = btnLp
                    setOnClickListener {
                        android.app.AlertDialog.Builder(ctx)
                            .setTitle("Удалить набор?")
                            .setMessage("${ds.name}")
                            .setPositiveButton("Удалить") { _, _ ->
                                deleteDataset(ds.id)
                                dialog?.dismiss()
                                showDatasetLibrary() // refresh
                            }
                            .setNegativeButton("Отмена", null)
                            .show()
                    }
                })

                card.addView(btnRow)
                layout.addView(card)

                // Divider between datasets
                layout.addView(android.view.View(ctx).apply {
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1)
                    setBackgroundColor(0xFF333333.toInt())
                })
            }
        }

        // Load new file button
        layout.addView(android.widget.Button(ctx).apply {
            text = "📂 Загрузить новый файл..."
            setTextColor(0xFFFF6F00.toInt())
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            textSize = 15f; isAllCaps = false
            setPadding(32, 16, 32, 8)
            setOnClickListener {
                dialog?.dismiss()
                openFilePicker()
            }
        })

        val scrollView = android.widget.ScrollView(ctx).apply { addView(layout) }
        dialog = com.google.android.material.bottomsheet.BottomSheetDialog(ctx,
            R.style.BottomSheetTheme)
        dialog!!.setContentView(scrollView)
        dialog!!.show()
    }

    private fun loadDatasetFromFile(ds: LoadedDataset) {
        val ctx = requireContext()
        val file = java.io.File(ds.filePath ?: return)
        if (!file.exists()) {
            android.widget.Toast.makeText(ctx, "Файл не найден", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val mapFrag = parentFragmentManager.fragments.filterIsInstance<MapFragment>().firstOrNull()
        val rowWp = view?.findViewById<android.view.View>(R.id.rowLoadedWp)
        val txtWp = view?.findViewById<android.widget.TextView>(R.id.txtLoadedWp)
        val filePrefs = ctx.getSharedPreferences(MapFragment.PREFS_NAME, android.content.Context.MODE_PRIVATE)

        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                val result = GpxParser.parseGpxFull(file.inputStream())
                val wpts = result.waypoints
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    if (wpts.isNotEmpty()) {
                        mapFrag?.loadWaypoints(wpts)
                        val label = "КП: ${ds.name} (${wpts.size} точек)"
                        txtWp?.text = label; rowWp?.visibility = android.view.View.VISIBLE
                        filePrefs.edit().putString(MapFragment.PREF_LOADED_WP_NAME, label).apply()
                        filePrefs.edit().putString(MapFragment.PREF_ROUTE_NAME, ds.name).apply()
                        view?.findViewById<android.view.View>(R.id.rowNavButtons)?.visibility = android.view.View.VISIBLE
                        view?.findViewById<android.view.View>(R.id.rowShareWp)?.visibility = android.view.View.VISIBLE
                        if (!ds.mapKey.isNullOrBlank()) mapFrag?.switchMap(ds.mapKey)
                        android.widget.Toast.makeText(ctx,
                            "✅ ${ds.name}: ${wpts.size} КП загружено", android.widget.Toast.LENGTH_SHORT).show()
                    } else {
                        android.widget.Toast.makeText(ctx, "Файл пустой", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    android.widget.Toast.makeText(ctx, "Ошибка: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
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
                    "gpx" -> {
                        val result = GpxParser.parseGpxFull(bytes.inputStream())
                        val hasTrack = result.trackPoints.isNotEmpty()
                        val hasWaypoints = result.waypoints.isNotEmpty()

                        if (!hasTrack && !hasWaypoints) {
                            withContext(Dispatchers.Main) {
                                txtErr?.text = "Файл пустой: $name"
                                txtErr?.visibility = View.VISIBLE
                            }
                            return@launch
                        }

                        withContext(Dispatchers.Main) {
                            showGpxImportDialog(
                                fileName = name,
                                trackPointCount = result.trackPoints.size,
                                waypoints = result.waypoints,
                                onConfirm = { loadTrack, loadWps ->
                                    if (loadTrack && result.trackPoints.isNotEmpty()) {
                                        mapFrag?.loadTrack(result.trackPoints)
                                        val label = "Трек: $name (${result.trackPoints.size} точек)"
                                        txtTrack?.text = label; rowTrack?.visibility = View.VISIBLE
                                        view?.findViewById<View>(R.id.rowLoadedTrackStyle)?.visibility = View.VISIBLE
                                        filePrefs.edit().putString(MapFragment.PREF_LOADED_TRACK_NAME, label).apply()
                                    }
                                    if (loadWps && result.waypoints.isNotEmpty()) {
                                        mapFrag?.loadWaypoints(result.waypoints)
                                        val label = "КП: $name (${result.waypoints.size} точек)"
                                        txtWp?.text = label; rowWp?.visibility = View.VISIBLE
                                        filePrefs.edit().putString(MapFragment.PREF_LOADED_WP_NAME, label).apply()
                                        filePrefs.edit().putString(MapFragment.PREF_ROUTE_NAME, name.substringBeforeLast('.')).apply()
                                        view?.findViewById<View>(R.id.rowNavButtons)?.visibility = View.VISIBLE
                                        view?.findViewById<View>(R.id.rowShareWp)?.visibility = View.VISIBLE
                                    }
                                    saveDatasetToList(
                                        name,
                                        if (loadWps) result.waypoints.size else 0,
                                        if (loadTrack) result.trackPoints.size else 0,
                                        if (loadWps) result.waypoints else emptyList()
                                    )
                                }
                            )
                        }
                    }
                    "rte" -> {
                        // Try GPX XML first, fallback to OziExplorer RTE format
                        val result = try { GpxParser.parseGpxFull(bytes.inputStream()) } catch (e: Exception) { null }
                        val wpts = if (result != null && (result.waypoints.isNotEmpty() || result.trackPoints.isNotEmpty())) {
                            // GPX-format RTE
                            withContext(Dispatchers.Main) {
                                showGpxImportDialog(
                                    fileName = name,
                                    trackPointCount = result.trackPoints.size,
                                    waypoints = result.waypoints,
                                    onConfirm = { loadTrack, loadWps ->
                                        if (loadTrack && result.trackPoints.isNotEmpty()) {
                                            mapFrag?.loadTrack(result.trackPoints)
                                            val label = "Трек: $name (${result.trackPoints.size} точек)"
                                            txtTrack?.text = label; rowTrack?.visibility = View.VISIBLE
                                            view?.findViewById<View>(R.id.rowLoadedTrackStyle)?.visibility = View.VISIBLE
                                            filePrefs.edit().putString(MapFragment.PREF_LOADED_TRACK_NAME, label).apply()
                                        }
                                        if (loadWps && result.waypoints.isNotEmpty()) {
                                            mapFrag?.loadWaypoints(result.waypoints)
                                            val label = "КП: $name (${result.waypoints.size} точек)"
                                            txtWp?.text = label; rowWp?.visibility = View.VISIBLE
                                            filePrefs.edit().putString(MapFragment.PREF_LOADED_WP_NAME, label).apply()
                                            filePrefs.edit().putString(MapFragment.PREF_ROUTE_NAME, name.substringBeforeLast('.')).apply()
                                            view?.findViewById<View>(R.id.rowNavButtons)?.visibility = View.VISIBLE
                                            view?.findViewById<View>(R.id.rowShareWp)?.visibility = View.VISIBLE
                                        }
                                        saveDatasetToList(
                                            name,
                                            if (loadWps) result.waypoints.size else 0,
                                            if (loadTrack) result.trackPoints.size else 0,
                                            if (loadWps) result.waypoints else emptyList()
                                        )
                                    }
                                )
                            }
                            null
                        } else {
                            // Try OziExplorer format
                            GpxParser.parseRteOzi(bytes.inputStream())
                        }
                        if (wpts != null) {
                            withContext(Dispatchers.Main) {
                                if (wpts.isNotEmpty()) {
                                    mapFrag?.loadWaypoints(wpts)
                                    val label = "КП: $name (${wpts.size} точек)"
                                    txtWp?.text = label; rowWp?.visibility = View.VISIBLE
                                    filePrefs.edit().putString(MapFragment.PREF_LOADED_WP_NAME, label).apply()
                                    filePrefs.edit().putString(MapFragment.PREF_ROUTE_NAME, name.substringBeforeLast('.')).apply()
                                    view?.findViewById<View>(R.id.rowNavButtons)?.visibility = View.VISIBLE
                                    view?.findViewById<View>(R.id.rowShareWp)?.visibility = View.VISIBLE
                                    saveDatasetToList(name, wpts.size, 0, wpts)
                                    // Show summary of loaded waypoints
                                    if (wpts.size <= 30) {
                                        val names = wpts.joinToString("\n") { "${it.index}. ${it.name}" }
                                        android.app.AlertDialog.Builder(requireContext())
                                            .setTitle("Загружено КП: ${wpts.size}")
                                            .setMessage(names)
                                            .setPositiveButton("OK", null)
                                            .show()
                                    }
                                } else {
                                    txtErr?.text = "Файл пустой: $name"; txtErr?.visibility = View.VISIBLE
                                }
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
                                filePrefs.edit().putString(MapFragment.PREF_ROUTE_NAME, name.substringBeforeLast('.')).apply()
                                view?.findViewById<View>(R.id.rowNavButtons)?.visibility = View.VISIBLE
                                view?.findViewById<View>(R.id.rowShareWp)?.visibility = View.VISIBLE
                                saveDatasetToList(name, wpts.size, 0, wpts)
                                // Show summary of loaded waypoints
                                if (wpts.size <= 30) {
                                    val names = wpts.joinToString("\n") { "${it.index}. ${it.name}" }
                                    android.app.AlertDialog.Builder(requireContext())
                                        .setTitle("Загружено КП: ${wpts.size}")
                                        .setMessage(names)
                                        .setPositiveButton("OK", null)
                                        .show()
                                }
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
                                saveDatasetToList(name, 0, pts.size)
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
                // Add share button before remove button
                val shareBtn = android.widget.ImageButton(requireContext()).apply {
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        (36 * resources.displayMetrics.density).toInt(),
                        (36 * resources.displayMetrics.density).toInt()
                    )
                    setImageResource(android.R.drawable.ic_menu_share)
                    setBackgroundResource(android.R.attr.selectableItemBackgroundBorderless.let { attr ->
                        val ta = requireContext().obtainStyledAttributes(intArrayOf(attr))
                        val resId = ta.getResourceId(0, 0)
                        ta.recycle()
                        resId
                    })
                    imageTintList = android.content.res.ColorStateList.valueOf(0xFF1565C0.toInt())
                    contentDescription = "Share"
                    setOnClickListener {
                        mapFrag?.shareOfflineMap(info.key)
                    }
                }
                (row as android.widget.LinearLayout).addView(shareBtn, row.childCount - 1)

                row.findViewById<View>(R.id.btnRemoveOfflineMap).apply {
                    // Change minus icon to trash
                    if (this is android.widget.ImageButton) {
                        setImageResource(R.drawable.ic_delete)
                        imageTintList = android.content.res.ColorStateList.valueOf(0xFFEF4444.toInt())
                    }
                    setOnClickListener {
                        androidx.appcompat.app.AlertDialog.Builder(requireContext())
                            .setTitle("Удалить карту")
                            .setMessage("Удалить \"${info.name}\"?")
                            .setPositiveButton("Удалить") { _, _ ->
                                mapFrag?.removeOfflineMap(info.key)
                                refreshOfflineMapsUI(view)
                            }
                            .setNegativeButton("Отмена", null)
                            .show()
                    }
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

    private fun showGpxImportDialog(
        fileName: String,
        trackPointCount: Int,
        waypoints: List<Waypoint>,
        onConfirm: (loadTrack: Boolean, loadWaypoints: Boolean) -> Unit
    ) {
        val hasTrack = trackPointCount > 0
        val hasWaypoints = waypoints.isNotEmpty()

        // Build message showing what's in the file
        val sb = StringBuilder()
        if (hasTrack) sb.appendLine("📍 Трек: $trackPointCount точек")
        if (hasWaypoints) {
            sb.appendLine("🚩 КП: ${waypoints.size} точек")
            waypoints.take(10).forEach { sb.appendLine("  ${it.index}. ${it.name}") }
            if (waypoints.size > 10) sb.appendLine("  ... и ещё ${waypoints.size - 10}")
        }

        val dialogView = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
        }

        val cbTrack = android.widget.CheckBox(requireContext()).apply {
            text = "Загрузить трек ($trackPointCount точек)"
            isChecked = hasTrack
            isEnabled = hasTrack
        }
        val cbWaypoints = android.widget.CheckBox(requireContext()).apply {
            text = "Загрузить КП (${waypoints.size} точек)"
            isChecked = hasWaypoints
            isEnabled = hasWaypoints
        }

        // Info text with КП list
        val infoText = android.widget.TextView(requireContext()).apply {
            text = sb.toString().trim()
            textSize = 12f
            setPadding(0, 8, 0, 0)
            setTextColor(0xFF888888.toInt())
        }

        if (hasTrack) dialogView.addView(cbTrack)
        if (hasWaypoints) dialogView.addView(cbWaypoints)
        if (hasWaypoints && waypoints.isNotEmpty()) dialogView.addView(infoText)

        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Что загрузить из $fileName?")
            .setView(dialogView)
            .setPositiveButton("Загрузить") { _, _ ->
                onConfirm(cbTrack.isChecked && hasTrack, cbWaypoints.isChecked && hasWaypoints)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private val PREF_CUSTOM_SOURCES = "custom_sources_json"

    private fun setupCustomSources(view: View, prefs: android.content.SharedPreferences) {
        val container = view.findViewById<LinearLayout>(R.id.customSourcesContainer)
        val mapFrag = parentFragmentManager.fragments.filterIsInstance<MapFragment>().firstOrNull()

        fun loadSources(): org.json.JSONArray {
            val json = prefs.getString(PREF_CUSTOM_SOURCES, "[]") ?: "[]"
            return try { org.json.JSONArray(json) } catch (_: Exception) { org.json.JSONArray() }
        }

        fun rebuildList() {
            container.removeAllViews()
            val arr = loadSources()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val name = obj.optString("name", "Без имени")
                val url = obj.optString("url", "")
                val type = obj.optString("type", "base")

                val row = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(16, 12, 16, 12)
                    setBackgroundColor(0xFF1E1E1E.toInt())
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = 2 }
                }

                val typeIcon = if (type == "overlay") "🔲" else "🗺"
                row.addView(TextView(requireContext()).apply {
                    text = "$typeIcon $name"
                    setTextColor(0xFFFFFFFF.toInt())
                    textSize = 14f
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    setSingleLine(true)
                })

                row.addView(android.widget.ImageButton(requireContext()).apply {
                    setImageResource(android.R.drawable.ic_menu_delete)
                    setBackgroundColor(0x00000000)
                    setOnClickListener {
                        val sources = loadSources()
                        val newArr = org.json.JSONArray()
                        for (j in 0 until sources.length()) {
                            if (j != i) newArr.put(sources.getJSONObject(j))
                        }
                        prefs.edit().putString(PREF_CUSTOM_SOURCES, newArr.toString()).apply()
                        mapFrag?.reloadCustomSources()
                        rebuildList()
                        Toast.makeText(requireContext(), "Удалено: $name", Toast.LENGTH_SHORT).show()
                    }
                })
                container.addView(row)
            }

            if (arr.length() == 0) {
                container.addView(TextView(requireContext()).apply {
                    text = "Нет пользовательских источников"
                    setTextColor(0xFF666666.toInt())
                    textSize = 13f
                    setPadding(16, 8, 16, 8)
                })
            }
        }

        rebuildList()

        view.findViewById<android.widget.Button>(R.id.btnAddCustomSource).setOnClickListener {
            showAddSourceDialog(prefs) {
                mapFrag?.reloadCustomSources()
                rebuildList()
            }
        }
    }

    private fun showAddSourceDialog(prefs: android.content.SharedPreferences, onDone: () -> Unit) {
        val ctx = requireContext()
        val pad = 24
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        root.addView(TextView(ctx).apply {
            text = "Формат URL: https://.../{z}/{x}/{y}.png"
            setTextColor(0xFF888888.toInt())
            textSize = 12f
            setPadding(0, 0, 0, 12)
        })

        val inputName = android.widget.EditText(ctx).apply {
            hint = "Название"
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF666666.toInt())
        }
        root.addView(inputName)

        val inputUrl = android.widget.EditText(ctx).apply {
            hint = "URL шаблон (с {z}/{x}/{y})"
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF666666.toInt())
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        root.addView(inputUrl)

        // Type selector
        val typeGroup = RadioGroup(ctx).apply { orientation = RadioGroup.HORIZONTAL }
        val rbBase = android.widget.RadioButton(ctx).apply {
            setText("Базовый слой"); setTextColor(0xFFFFFFFF.toInt()); isChecked = true
            id = View.generateViewId()
        }
        val rbOverlay = android.widget.RadioButton(ctx).apply {
            setText("Оверлей"); setTextColor(0xFFFFFFFF.toInt())
            id = View.generateViewId()
        }
        typeGroup.addView(rbBase)
        typeGroup.addView(rbOverlay)
        root.addView(typeGroup)

        // TMS checkbox
        val cbTms = android.widget.CheckBox(ctx).apply {
            text = "TMS (перевёрнутая Y)"; setTextColor(0xFFCCCCCC.toInt()); textSize = 13f
        }
        root.addView(cbTms)

        val dialogBuilder = android.app.AlertDialog.Builder(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Добавить источник карты")
            .setView(root)
            .setPositiveButton("Добавить") { _, _ ->
                val name = inputName.text.toString().trim()
                val url = inputUrl.text.toString().trim()
                if (name.isEmpty() || url.isEmpty() || !url.contains("{z}")) {
                    Toast.makeText(ctx, "Заполните название и URL с {z}/{x}/{y}", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                val type = if (rbOverlay.isChecked) "overlay" else "base"
                val json = prefs.getString(PREF_CUSTOM_SOURCES, "[]") ?: "[]"
                val arr = try { org.json.JSONArray(json) } catch (_: Exception) { org.json.JSONArray() }
                arr.put(org.json.JSONObject().apply {
                    put("name", name)
                    put("url", url)
                    put("type", type)
                    put("tms", cbTms.isChecked)
                    put("opacity", 0.7)
                })
                prefs.edit().putString(PREF_CUSTOM_SOURCES, arr.toString()).apply()
                onDone()
                Toast.makeText(ctx, "Добавлено: $name", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Отмена", null)

        dialogBuilder.show()
    }




    override fun onDestroyView() {
        liveUsersHandler?.removeCallbacksAndMessages(null)
        liveUsersHandler = null
        super.onDestroyView()
    }
}
