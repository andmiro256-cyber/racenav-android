package com.andreykoff.racenav

import android.app.Activity
import android.content.Context
import android.content.Intent
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

class SettingsFragment : Fragment() {

    private val filePicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            val name = getFileName(uri)
            view?.findViewById<TextView>(R.id.txtLoadedFile)?.text = "Файл: $name"
            Toast.makeText(context, "Загружен: $name\n(Парсинг треков будет добавлен в следующей версии)", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Back button
        view.findViewById<View>(R.id.btnBack).setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        // Volume zoom switch
        val switchVol = view.findViewById<SwitchCompat>(R.id.switchVolumeZoom)
        switchVol.isChecked = prefs.getBoolean(PREF_VOLUME_ZOOM, true)
        switchVol.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(PREF_VOLUME_ZOOM, checked).apply()
        }

        // File loader
        view.findViewById<Button>(R.id.btnLoadFile).setOnClickListener {
            filePicker.launch(arrayOf(
                "*/*"  // Accept all, filter by extension in handler
            ))
        }

        // Version info
        try {
            val versionName = requireContext().packageManager
                .getPackageInfo(requireContext().packageName, 0).versionName
            view.findViewById<TextView>(R.id.txtVersion).text = "Trophy Navigator v$versionName"
        } catch (e: Exception) { /* ignore */ }
    }

    private fun getFileName(uri: Uri): String {
        val cursor = requireContext().contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) return it.getString(nameIndex)
            }
        }
        return uri.lastPathSegment ?: "unknown"
    }
}
