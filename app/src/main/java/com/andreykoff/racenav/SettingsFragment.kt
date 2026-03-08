package com.andreykoff.racenav

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.SwitchCompat
import androidx.fragment.app.Fragment
import com.andreykoff.racenav.MapFragment.Companion.PREFS_NAME
import com.andreykoff.racenav.MapFragment.Companion.PREF_VOLUME_ZOOM
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsFragment : Fragment() {

    private val filePicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) loadFile(uri)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        view.findViewById<View>(R.id.btnBack).setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        val switchVol = view.findViewById<SwitchCompat>(R.id.switchVolumeZoom)
        switchVol.isChecked = prefs.getBoolean(PREF_VOLUME_ZOOM, true)
        switchVol.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(PREF_VOLUME_ZOOM, checked).apply()
        }

        view.findViewById<Button>(R.id.btnLoadFile).setOnClickListener {
            filePicker.launch(arrayOf("*/*"))
        }

        try {
            val versionName = requireContext().packageManager
                .getPackageInfo(requireContext().packageName, 0).versionName
            view.findViewById<TextView>(R.id.txtVersion).text = "Trophy Navigator v$versionName"
        } catch (e: Exception) { }
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
                    if (waypoints.isEmpty()) {
                        txtLoaded.text = "Файл: $name — точек не найдено"
                        return@withContext
                    }
                    txtLoaded.text = "Файл: $name (${waypoints.size} точек)"
                    // Find MapFragment in back stack and load waypoints
                    val mapFrag = parentFragmentManager.fragments.filterIsInstance<MapFragment>().firstOrNull()
                    mapFrag?.loadWaypoints(waypoints)
                    parentFragmentManager.popBackStack()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Ошибка чтения: ${e.message}", Toast.LENGTH_LONG).show()
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
