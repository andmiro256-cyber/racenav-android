package com.andreykoff.racenav

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.andreykoff.racenav.databinding.FragmentMapBinding
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.mapbox.mapboxsdk.camera.CameraUpdateFactory
import com.mapbox.mapboxsdk.geometry.LatLng
import com.mapbox.mapboxsdk.location.LocationComponentActivationOptions
import com.mapbox.mapboxsdk.location.modes.CameraMode
import com.mapbox.mapboxsdk.location.modes.RenderMode
import com.mapbox.mapboxsdk.maps.MapboxMap
import com.mapbox.mapboxsdk.maps.Style

class MapFragment : Fragment() {

    private var _binding: FragmentMapBinding? = null
    private val binding get() = _binding!!
    private var mapboxMap: MapboxMap? = null

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
    }

    private fun buildStyleJson(key: String): String {
        val source = tileSources[key] ?: return ""
        val tilesArray = source.urls.joinToString(",") { "\"$it\"" }
        val scheme = if (source.tms) "\"scheme\": \"tms\"," else ""
        return """
            {
              "version": 8,
              "sources": {
                "raster-tiles": {
                  "type": "raster",
                  "tiles": [$tilesArray],
                  "tileSize": 256,
                  $scheme
                  "attribution": ""
                }
              },
              "layers": [{
                "id": "raster-layer",
                "type": "raster",
                "source": "raster-tiles",
                "minzoom": 0,
                "maxzoom": 22
              }]
            }
        """.trimIndent()
    }

    private fun loadTileStyle(key: String) {
        currentTileKey = key
        val json = buildStyleJson(key)
        mapboxMap?.setStyle(Style.Builder().fromJson(json)) { style ->
            enableLocation(style)
            updateCompass()
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableLocation(style: Style) {
        val ctx = context ?: return
        val granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            val lc = mapboxMap?.locationComponent ?: return
            lc.activateLocationComponent(LocationComponentActivationOptions.builder(ctx, style).build())
            lc.isLocationComponentEnabled = true
            lc.cameraMode = CameraMode.NONE
            lc.renderMode = RenderMode.COMPASS
        } else {
            @Suppress("DEPRECATION")
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 100)
        }
    }

    private fun setupButtons(map: MapboxMap) {
        binding.btnZoomIn.setOnClickListener { map.animateCamera(CameraUpdateFactory.zoomIn()) }
        binding.btnZoomOut.setOnClickListener { map.animateCamera(CameraUpdateFactory.zoomOut()) }
        binding.btnGps.setOnClickListener { centerOnGps() }
        binding.btnLayers.setOnClickListener { showLayerPicker() }
        binding.compassView.setOnClickListener { map.animateCamera(CameraUpdateFactory.bearingTo(0.0)) }

        map.addOnCameraIdleListener { updateCompass() }
        map.addOnCameraMoveListener { updateCompass() }
    }

    private fun updateCompass() {
        val bearing = mapboxMap?.cameraPosition?.bearing ?: 0.0
        binding.compassView.rotation = (-bearing).toFloat()
    }

    @SuppressLint("MissingPermission")
    private fun centerOnGps() {
        val loc = mapboxMap?.locationComponent?.lastKnownLocation ?: return
        mapboxMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(loc.latitude, loc.longitude), 15.0))
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
