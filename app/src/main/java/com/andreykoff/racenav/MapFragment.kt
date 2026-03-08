package com.andreykoff.racenav

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.andreykoff.racenav.databinding.FragmentMapBinding
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.snackbar.Snackbar
import com.mapbox.mapboxsdk.camera.CameraUpdateFactory
import com.mapbox.mapboxsdk.geometry.LatLng
import com.mapbox.mapboxsdk.location.LocationComponentActivationOptions
import com.mapbox.mapboxsdk.location.LocationComponentOptions
import com.mapbox.mapboxsdk.location.modes.CameraMode
import com.mapbox.mapboxsdk.location.modes.RenderMode
import com.mapbox.mapboxsdk.maps.MapboxMap
import com.mapbox.mapboxsdk.maps.Style
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL

class MapFragment : Fragment() {

    private var _binding: FragmentMapBinding? = null
    private val binding get() = _binding!!
    private var mapboxMap: MapboxMap? = null

    enum class FollowMode { FREE, FOLLOW_NORTH, FOLLOW_COURSE }
    private var followMode = FollowMode.FREE

    data class TileSource(val label: String, val urls: List<String>, val tms: Boolean = false)

    private val tileSources = linkedMapOf(
        "osm" to TileSource(
            "OpenStreetMap",
            listOf(
                "https://a.tile.openstreetmap.org/{z}/{x}/{y}.png",
                "https://b.tile.openstreetmap.org/{z}/{x}/{y}.png",
                "https://c.tile.openstreetmap.org/{z}/{x}/{y}.png"
            )
        ),
        "satellite" to TileSource(
            "Спутник ESRI",
            listOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}")
        ),
        "topo" to TileSource(
            "OpenTopoMap",
            listOf("https://tile.opentopomap.org/{z}/{x}/{y}.png")
        ),
        "google" to TileSource(
            "Google Спутник",
            listOf(
                "https://mt0.google.com/vt/lyrs=s&x={x}&y={y}&z={z}",
                "https://mt1.google.com/vt/lyrs=s&x={x}&y={y}&z={z}",
                "https://mt2.google.com/vt/lyrs=s&x={x}&y={y}&z={z}",
                "https://mt3.google.com/vt/lyrs=s&x={x}&y={y}&z={z}"
            )
        ),
        "genshtab250" to TileSource(
            "Генштаб 250м",
            listOf(
                "https://a.tiles.nakarte.me/g250/{z}/{x}/{y}",
                "https://b.tiles.nakarte.me/g250/{z}/{x}/{y}",
                "https://c.tiles.nakarte.me/g250/{z}/{x}/{y}"
            ),
            tms = true
        ),
        "genshtab500" to TileSource(
            "Генштаб 500м",
            listOf(
                "https://a.tiles.nakarte.me/g500/{z}/{x}/{y}",
                "https://b.tiles.nakarte.me/g500/{z}/{x}/{y}",
                "https://c.tiles.nakarte.me/g500/{z}/{x}/{y}"
            ),
            tms = true
        ),
        "ggc500" to TileSource(
            "ГосГисЦентр 500м",
            listOf(
                "https://a.tiles.nakarte.me/ggc500/{z}/{x}/{y}",
                "https://b.tiles.nakarte.me/ggc500/{z}/{x}/{y}",
                "https://c.tiles.nakarte.me/ggc500/{z}/{x}/{y}"
            ),
            tms = true
        )
    )

    private var currentTileKey = "osm"

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentMapBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.mapView.onCreate(savedInstanceState)
        binding.mapView.getMapAsync { map ->
            mapboxMap = map
            map.uiSettings.isCompassEnabled = false
            map.uiSettings.isAttributionEnabled = false
            map.uiSettings.isLogoEnabled = false
            loadTileStyle("osm")
            setupButtons(map)
        }
        checkForUpdates()
    }

    private fun buildStyleJson(key: String): String {
        val source = tileSources[key] ?: return ""
        val tilesArray = source.urls.joinToString(",") { "\"$it\"" }
        val schemeField = if (source.tms) "\"scheme\": \"tms\"," else ""
        return """{"version":8,"sources":{"rt":{"type":"raster","tiles":[$tilesArray],"tileSize":256${ if (source.tms) ",\"scheme\":\"tms\"" else ""}}},"layers":[{"id":"rl","type":"raster","source":"rt","minzoom":0,"maxzoom":22}]}"""
    }

    private fun loadTileStyle(key: String) {
        currentTileKey = key
        val json = buildStyleJson(key)
        Log.d("RaceNav", "Loading style for $key: ${json.take(200)}")
        mapboxMap?.setStyle(Style.Builder().fromJson(json)) { style ->
            Log.d("RaceNav", "Style loaded OK for $key")
            enableLocation(style)
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableLocation(style: Style) {
        val ctx = context ?: return
        val granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            @Suppress("DEPRECATION")
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 100)
            return
        }

        val navArrow = ContextCompat.getDrawable(ctx, R.drawable.ic_nav_arrow)

        val options = LocationComponentOptions.builder(ctx)
            .foregroundDrawable(R.drawable.ic_nav_arrow)
            .accuracyAlpha(0.15f)
            .accuracyColor(Color.parseColor("#4DE8380A"))
            .elevation(0f)
            .build()

        val activationOptions = LocationComponentActivationOptions.builder(ctx, style)
            .locationComponentOptions(options)
            .build()

        val lc = mapboxMap?.locationComponent ?: return
        lc.activateLocationComponent(activationOptions)
        lc.isLocationComponentEnabled = true
        applyFollowMode()
    }

    private fun applyFollowMode() {
        val lc = mapboxMap?.locationComponent ?: return
        when (followMode) {
            FollowMode.FREE -> {
                lc.cameraMode = CameraMode.NONE
                lc.renderMode = RenderMode.GPS
                binding.btnGps.setImageResource(R.drawable.ic_my_location)
                binding.btnGps.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#1E1E1E"))
            }
            FollowMode.FOLLOW_NORTH -> {
                lc.cameraMode = CameraMode.TRACKING
                lc.renderMode = RenderMode.GPS
                binding.btnGps.setImageResource(R.drawable.ic_my_location)
                binding.btnGps.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#1565C0"))
            }
            FollowMode.FOLLOW_COURSE -> {
                lc.cameraMode = CameraMode.TRACKING_GPS
                lc.renderMode = RenderMode.GPS
                binding.btnGps.setImageResource(R.drawable.ic_nav_arrow)
                binding.btnGps.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#E8380A"))
            }
        }
    }

    private fun setupButtons(map: MapboxMap) {
        binding.btnZoomIn.setOnClickListener { map.animateCamera(CameraUpdateFactory.zoomIn()) }
        binding.btnZoomOut.setOnClickListener { map.animateCamera(CameraUpdateFactory.zoomOut()) }

        binding.btnGps.setOnClickListener {
            followMode = when (followMode) {
                FollowMode.FREE -> FollowMode.FOLLOW_NORTH
                FollowMode.FOLLOW_NORTH -> FollowMode.FOLLOW_COURSE
                FollowMode.FOLLOW_COURSE -> FollowMode.FREE
            }
            applyFollowMode()
            val msg = when (followMode) {
                FollowMode.FREE -> "Свободный режим"
                FollowMode.FOLLOW_NORTH -> "Следить — север вверху"
                FollowMode.FOLLOW_COURSE -> "Следить — курс вверху"
            }
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }

        binding.btnLayers.setOnClickListener { showLayerPicker() }

        binding.compassView.setOnClickListener {
            when (followMode) {
                FollowMode.FREE -> {
                    // Reset bearing to north
                    map.animateCamera(CameraUpdateFactory.bearingTo(0.0))
                }
                FollowMode.FOLLOW_NORTH -> {
                    followMode = FollowMode.FOLLOW_COURSE
                    applyFollowMode()
                    Toast.makeText(context, "Курс вверху", Toast.LENGTH_SHORT).show()
                }
                FollowMode.FOLLOW_COURSE -> {
                    followMode = FollowMode.FOLLOW_NORTH
                    applyFollowMode()
                    Toast.makeText(context, "Север вверху", Toast.LENGTH_SHORT).show()
                }
            }
        }

        map.addOnCameraMoveListener { updateCompass() }
        map.addOnCameraIdleListener {
            updateCompass()
            // If user manually moved map, switch to FREE mode
            if (followMode != FollowMode.FREE) {
                val lc = map.locationComponent
                if (lc.isLocationComponentEnabled) {
                    // Keep mode — don't reset on camera idle from tracking
                }
            }
        }
    }

    private fun updateCompass() {
        val bearing = mapboxMap?.cameraPosition?.bearing ?: 0.0
        binding.compassView.rotation = (-bearing).toFloat()
    }

    private fun showLayerPicker() {
        val dialog = BottomSheetDialog(requireContext(), R.style.BottomSheetTheme)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_layers, null)
        dialog.setContentView(view)

        val radioGroup = view.findViewById<RadioGroup>(R.id.layerRadioGroup)
        tileSources.forEach { (key, source) ->
            val rb = RadioButton(requireContext()).apply {
                text = source.label
                tag = key
                isChecked = key == currentTileKey
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 16f
                setPadding(32, 28, 32, 28)
                id = View.generateViewId()
            }
            radioGroup.addView(rb)
        }

        radioGroup.setOnCheckedChangeListener { group, id ->
            val key = group.findViewById<RadioButton>(id)?.tag as? String ?: return@setOnCheckedChangeListener
            loadTileStyle(key)
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun checkForUpdates() {
        lifecycleScope.launch {
            try {
                val latest = withContext(Dispatchers.IO) {
                    val json = URL("https://api.github.com/repos/andmiro256-cyber/racenav-android/releases/latest").readText()
                    JSONObject(json).getString("tag_name")
                }
                val current = "v${requireContext().packageManager.getPackageInfo(requireContext().packageName, 0).versionName}"
                if (latest != current) {
                    Snackbar.make(binding.root, "Доступна версия $latest", Snackbar.LENGTH_LONG)
                        .setAction("Скачать") {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(
                                "https://github.com/andmiro256-cyber/racenav-android/releases/download/$latest/racenav-$latest.apk"
                            )))
                        }
                        .show()
                }
            } catch (e: Exception) {
                Log.d("RaceNav", "Update check failed: ${e.message}")
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        if (requestCode == 100 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            mapboxMap?.style?.let { enableLocation(it) }
        }
    }

    override fun onStart() { super.onStart(); _binding?.mapView?.onStart() }
    override fun onResume() { super.onResume(); _binding?.mapView?.onResume() }
    override fun onPause() { super.onPause(); _binding?.mapView?.onPause() }
    override fun onStop() { super.onStop(); _binding?.mapView?.onStop() }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); _binding?.mapView?.onSaveInstanceState(outState) }
    override fun onLowMemory() { super.onLowMemory(); _binding?.mapView?.onLowMemory() }
    override fun onDestroyView() {
        super.onDestroyView()
        _binding?.mapView?.onDestroy()
        _binding = null
    }
}
