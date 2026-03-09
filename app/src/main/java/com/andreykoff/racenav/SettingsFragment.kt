package com.andreykoff.racenav

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsFragment : Fragment() {

    private val filePicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> if (uri != null) loadFile(uri) }

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

        // Marker color swatches
        val colorMap = mapOf(
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

        // File loader
        view.findViewById<View>(R.id.btnLoadFile).setOnClickListener {
            filePicker.launch(arrayOf("*/*"))
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

    private fun loadFile(uri: Uri) {
        val name = getFileName(uri)
        val ext = name.substringAfterLast('.', "").lowercase()
        val txtLoaded = view?.findViewById<TextView>(R.id.txtLoadedFile) ?: return

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val waypoints = requireContext().contentResolver.openInputStream(uri)?.use { stream ->
                    when (ext) {
                        "gpx", "rte" -> GpxParser.parseGpx(stream)
                        "wpt"        -> GpxParser.parseWpt(stream)
                        "plt"        -> GpxParser.parsePlt(stream)
                        else         -> null
                    }
                }
                withContext(Dispatchers.Main) {
                    if (waypoints == null) {
                        Toast.makeText(context, "Формат не поддерживается: .$ext", Toast.LENGTH_SHORT).show()
                        return@withContext
                    }
                    txtLoaded.text = if (waypoints.isEmpty()) "Файл: $name — точек не найдено"
                                     else "Файл: $name (${waypoints.size} точек)"
                    if (waypoints.isNotEmpty()) {
                        parentFragmentManager.fragments.filterIsInstance<MapFragment>().firstOrNull()
                            ?.loadWaypoints(waypoints)
                        parentFragmentManager.popBackStack()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
                }
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
