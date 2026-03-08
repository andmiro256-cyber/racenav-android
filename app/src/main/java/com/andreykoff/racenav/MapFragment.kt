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
import androidx.core.graphics.drawable.toBitmap
import androidx.core.widget.ImageViewCompat
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
import com.mapbox.mapboxsdk.style.layers.LineLayer
import com.mapbox.mapboxsdk.style.layers.PropertyFactory
import com.mapbox.mapboxsdk.style.layers.SymbolLayer
import com.mapbox.mapboxsdk.style.sources.GeoJsonSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class MapFragment : Fragment() {

    private var _binding: FragmentMapBinding? = null
    private val binding get() = _binding!!
    private var mapboxMap: MapboxMap? = null

    enum class FollowMode { FREE, FOLLOW_NORTH, FOLLOW_COURSE }
    private var followMode = FollowMode.FREE

    // Track recording
    private val trackPoints = mutableListOf<LatLng>()
    private var isRecording = false
    private var lastArrowLat = 0.0
    private var lastArrowLon = 0.0
    private val arrowPoints = mutableListOf<Pair<LatLng, Float>>()
    private var trackLengthM = 0.0

    companion object {
        const val TRACK_SOURCE_ID = "track-source"
        const val TRACK_ARROWS_SOURCE_ID = "track-arrows-source"
        const val TRACK_LAYER_ID = "track-layer"
        const val TRACK_ARROWS_LAYER_ID = "track-arrows-layer"
        const val TRACK_ARROW_ICON = "track-arrow-icon"
        const val ARROW_DISTANCE_M = 80.0
        const val PREFS_NAME = "racenav_prefs"
        const val PREF_VOLUME_ZOOM = "volume_zoom_enabled"
    }

    data class TileSource(val label: String, val urls: List<String>, val tms: Boolean = false)

    private val tileSources = linkedMapOf(
        "osm" to TileSource("OpenStreetMap", listOf(
            "https://a.tile.openstreetmap.org/{z}/{x}/{y}.png",
            "https://b.tile.openstreetmap.org/{z}/{x}/{y}.png",
            "https://c.tile.openstreetmap.org/{z}/{x}/{y}.png"
        )),
        "satellite" to TileSource("Спутник ESRI", listOf(
            "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"
        )),
        "topo" to TileSource("OpenTopoMap", listOf("https://tile.opentopomap.org/{z}/{x}/{y}.png")),
        "google" to TileSource("Google Спутник", listOf(
            "https://mt0.google.com/vt/lyrs=s&x={x}&y={y}&z={z}",
            "https://mt1.google.com/vt/lyrs=s&x={x}&y={y}&z={z}",
            "https://mt2.google.com/vt/lyrs=s&x={x}&y={y}&z={z}",
            "https://mt3.google.com/vt/lyrs=s&x={x}&y={y}&z={z}"
        )),
        "genshtab250" to TileSource("Генштаб 250м", listOf(
            "https://a.tiles.nakarte.me/g250/{z}/{x}/{y}",
            "https://b.tiles.nakarte.me/g250/{z}/{x}/{y}",
            "https://c.tiles.nakarte.me/g250/{z}/{x}/{y}"
        ), tms = true),
        "genshtab500" to TileSource("Генштаб 500м", listOf(
            "https://a.tiles.nakarte.me/g500/{z}/{x}/{y}",
            "https://b.tiles.nakarte.me/g500/{z}/{x}/{y}",
            "https://c.tiles.nakarte.me/g500/{z}/{x}/{y}"
        ), tms = true),
        "ggc500" to TileSource("ГосГисЦентр 500м", listOf(
            "https://a.tiles.nakarte.me/ggc500/{z}/{x}/{y}",
            "https://b.tiles.nakarte.me/ggc500/{z}/{x}/{y}",
            "https://c.tiles.nakarte.me/ggc500/{z}/{x}/{y}"
        ), tms = true)
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

    // Called by MainActivity for volume key zoom
    fun zoomIn() { mapboxMap?.animateCamera(CameraUpdateFactory.zoomIn()) }
    fun zoomOut() { mapboxMap?.animateCamera(CameraUpdateFactory.zoomOut()) }

    private fun buildStyleJson(key: String): String {
        val source = tileSources[key] ?: return ""
        val tilesArray = source.urls.joinToString(",") { "\"$it\"" }
        val scheme = if (source.tms) ",\"scheme\":\"tms\"" else ""
        return """{"version":8,"sources":{"rt":{"type":"raster","tiles":[$tilesArray],"tileSize":256$scheme}},"layers":[{"id":"rl","type":"raster","source":"rt","minzoom":0,"maxzoom":22}]}"""
    }

    private fun loadTileStyle(key: String) {
        currentTileKey = key
        val json = buildStyleJson(key)
        mapboxMap?.setStyle(Style.Builder().fromJson(json)) { style ->
            enableLocation(style)
            setupTrackLayers(style)
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

        val options = LocationComponentOptions.builder(ctx)
            .foregroundDrawable(R.drawable.ic_nav_arrow)
            .accuracyAlpha(0f)
            .accuracyAnimationEnabled(false)
            .elevation(0f)
            .build()

        val lc = mapboxMap?.locationComponent ?: return
        lc.activateLocationComponent(LocationComponentActivationOptions.builder(ctx, style)
            .locationComponentOptions(options).build())
        lc.isLocationComponentEnabled = true
        applyFollowMode()
    }

    private fun setupTrackLayers(style: Style) {
        val ctx = context ?: return
        val arrowDrawable = ContextCompat.getDrawable(ctx, R.drawable.ic_track_arrow)
        if (arrowDrawable != null) {
            val bitmap = arrowDrawable.toBitmap(32, 32)
            style.addImage(TRACK_ARROW_ICON, bitmap)
        }

        style.addSource(GeoJsonSource(TRACK_SOURCE_ID))
        style.addSource(GeoJsonSource(TRACK_ARROWS_SOURCE_ID))

        style.addLayer(LineLayer(TRACK_LAYER_ID, TRACK_SOURCE_ID).withProperties(
            PropertyFactory.lineColor("#FF2200"),
            PropertyFactory.lineWidth(4f),
            PropertyFactory.lineCap("round"),
            PropertyFactory.lineJoin("round")
        ))

        style.addLayer(SymbolLayer(TRACK_ARROWS_LAYER_ID, TRACK_ARROWS_SOURCE_ID).withProperties(
            PropertyFactory.iconImage(TRACK_ARROW_ICON),
            PropertyFactory.iconRotationAlignment("map"),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true),
            PropertyFactory.iconRotate(com.mapbox.mapboxsdk.style.expressions.Expression.get("bearing")),
            PropertyFactory.iconSize(0.8f)
        ))

        if (trackPoints.isNotEmpty()) updateTrackOnMap()
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
                FollowMode.FREE -> map.animateCamera(CameraUpdateFactory.bearingTo(0.0))
                FollowMode.FOLLOW_NORTH -> { followMode = FollowMode.FOLLOW_COURSE; applyFollowMode() }
                FollowMode.FOLLOW_COURSE -> { followMode = FollowMode.FOLLOW_NORTH; applyFollowMode() }
            }
        }

        binding.btnRec.setOnClickListener { toggleRecording() }

        binding.btnSettings.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.container, SettingsFragment())
                .addToBackStack(null)
                .commit()
        }

        map.addOnCameraMoveListener { updateCompass() }
        map.addOnCameraIdleListener { updateCompass() }

        setupLocationTracking(map)
    }

    @SuppressLint("MissingPermission")
    private fun setupLocationTracking(map: MapboxMap) {
        val engine = map.locationComponent.locationEngine ?: return
        engine.requestLocationUpdates(
            com.mapbox.mapboxsdk.location.engine.LocationEngineRequest.Builder(1000L)
                .setPriority(com.mapbox.mapboxsdk.location.engine.LocationEngineRequest.PRIORITY_HIGH_ACCURACY)
                .setMaxWaitTime(2000L)
                .build(),
            object : com.mapbox.mapboxsdk.location.engine.LocationEngineCallback<com.mapbox.mapboxsdk.location.engine.LocationEngineResult> {
                override fun onSuccess(result: com.mapbox.mapboxsdk.location.engine.LocationEngineResult) {
                    val loc = result.lastLocation ?: return
                    val newPoint = LatLng(loc.latitude, loc.longitude)

                    // Update bottom bar widgets
                    val speedKmh = (loc.speed * 3.6).toInt()
                    val bearingDeg = loc.bearing.toInt()
                    val b = _binding ?: return
                    b.widgetSpeed.text = if (loc.speed > 0.5f) speedKmh.toString() else "--"
                    b.widgetBearing.text = "${bearingDeg}°"
                    b.widgetDirectionArrow.rotation = loc.bearing
                    if (loc.hasAltitude()) {
                        b.widgetAltitude.text = loc.altitude.toInt().toString()
                    }

                    // Record track
                    if (isRecording) {
                        if (trackPoints.isEmpty() || distanceM(trackPoints.last(), newPoint) > 2.0) {
                            if (trackPoints.isNotEmpty()) {
                                trackLengthM += distanceM(trackPoints.last(), newPoint)
                            }
                            trackPoints.add(newPoint)

                            // Update track length widget
                            val lenKm = trackLengthM / 1000.0
                            b.widgetTrackLen.text = if (lenKm < 10) String.format("%.1f", lenKm) else lenKm.toInt().toString()

                            // Add direction arrow every ARROW_DISTANCE_M meters
                            if (lastArrowLat == 0.0 || distanceM(LatLng(lastArrowLat, lastArrowLon), newPoint) >= ARROW_DISTANCE_M) {
                                if (trackPoints.size >= 2) {
                                    val prev = trackPoints[trackPoints.size - 2]
                                    val bearing = bearingBetween(prev, newPoint).toFloat()
                                    arrowPoints.add(Pair(newPoint, bearing))
                                    lastArrowLat = loc.latitude
                                    lastArrowLon = loc.longitude
                                }
                            }

                            updateTrackOnMap()
                        }
                    }
                }
                override fun onFailure(exception: Exception) {
                    Log.e("RaceNav", "Location error: ${exception.message}")
                }
            },
            android.os.Looper.getMainLooper()
        )
    }

    private fun toggleRecording() {
        isRecording = !isRecording
        if (isRecording) {
            trackPoints.clear()
            arrowPoints.clear()
            trackLengthM = 0.0
            lastArrowLat = 0.0
            lastArrowLon = 0.0
            binding.btnRec.setImageResource(R.drawable.ic_rec)
            binding.widgetTrackLen.text = "0.0"
            Toast.makeText(context, "⏺ Запись трека начата", Toast.LENGTH_SHORT).show()
        } else {
            binding.btnRec.setImageResource(R.drawable.ic_rec_start)
            val lenKm = trackLengthM / 1000.0
            Toast.makeText(context, "⏹ Запись: ${trackPoints.size} точек, ${String.format("%.1f", lenKm)} км", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateTrackOnMap() {
        val style = mapboxMap?.style ?: return
        val source = style.getSourceAs<GeoJsonSource>(TRACK_SOURCE_ID) ?: return
        val arrowSource = style.getSourceAs<GeoJsonSource>(TRACK_ARROWS_SOURCE_ID) ?: return

        if (trackPoints.size >= 2) {
            val coords = JSONArray()
            trackPoints.forEach { pt ->
                coords.put(JSONArray().put(pt.longitude).put(pt.latitude))
            }
            val geojson = JSONObject()
                .put("type", "Feature")
                .put("geometry", JSONObject().put("type", "LineString").put("coordinates", coords))
                .put("properties", JSONObject())
            source.setGeoJson(geojson.toString())
        }

        val features = JSONArray()
        arrowPoints.forEach { (pt, bearing) ->
            val feature = JSONObject()
                .put("type", "Feature")
                .put("geometry", JSONObject().put("type", "Point")
                    .put("coordinates", JSONArray().put(pt.longitude).put(pt.latitude)))
                .put("properties", JSONObject().put("bearing", bearing))
            features.put(feature)
        }
        arrowSource.setGeoJson(JSONObject().put("type", "FeatureCollection").put("features", features).toString())
    }

    private fun applyFollowMode() {
        val lc = mapboxMap?.locationComponent ?: return
        val b = _binding ?: return
        when (followMode) {
            FollowMode.FREE -> {
                lc.cameraMode = CameraMode.NONE
                lc.renderMode = RenderMode.GPS
                b.btnGps.setImageResource(R.drawable.ic_my_location)
                ImageViewCompat.setImageTintList(b.btnGps, android.content.res.ColorStateList.valueOf(Color.WHITE))
            }
            FollowMode.FOLLOW_NORTH -> {
                lc.cameraMode = CameraMode.TRACKING
                lc.renderMode = RenderMode.GPS
                b.btnGps.setImageResource(R.drawable.ic_my_location)
                ImageViewCompat.setImageTintList(b.btnGps, android.content.res.ColorStateList.valueOf(Color.parseColor("#42A5F5")))
            }
            FollowMode.FOLLOW_COURSE -> {
                lc.cameraMode = CameraMode.TRACKING_GPS
                lc.renderMode = RenderMode.GPS
                b.btnGps.setImageResource(R.drawable.ic_nav_arrow)
                ImageViewCompat.setImageTintList(b.btnGps, android.content.res.ColorStateList.valueOf(Color.parseColor("#FF5722")))
            }
        }
    }

    private fun updateCompass() {
        val bearing = mapboxMap?.cameraPosition?.bearing ?: 0.0
        _binding?.compassView?.rotation = (-bearing).toFloat()
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

    private fun distanceM(a: LatLng, b: LatLng): Double {
        val R = 6371000.0
        val lat1 = Math.toRadians(a.latitude); val lat2 = Math.toRadians(b.latitude)
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val x = sin(dLat/2)*sin(dLat/2) + cos(lat1)*cos(lat2)*sin(dLon/2)*sin(dLon/2)
        return R * 2 * atan2(sqrt(x), sqrt(1-x))
    }

    private fun bearingBetween(a: LatLng, b: LatLng): Double {
        val lat1 = Math.toRadians(a.latitude); val lat2 = Math.toRadians(b.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1)*sin(lat2) - sin(lat1)*cos(lat2)*cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    private fun checkForUpdates() {
        lifecycleScope.launch {
            try {
                val latest = withContext(Dispatchers.IO) {
                    JSONObject(URL("https://api.github.com/repos/andmiro256-cyber/racenav-android/releases/latest").readText()).getString("tag_name")
                }
                val current = "v${requireContext().packageManager.getPackageInfo(requireContext().packageName, 0).versionName}"
                if (latest != current) {
                    Snackbar.make(binding.root, "Доступна версия $latest", Snackbar.LENGTH_LONG)
                        .setAction("Скачать") {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(
                                "https://github.com/andmiro256-cyber/racenav-android/releases/download/$latest/racenav-$latest.apk"
                            )))
                        }.show()
                }
            } catch (e: Exception) {
                Log.d("RaceNav", "Update check: ${e.message}")
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
    override fun onDestroyView() { super.onDestroyView(); _binding?.mapView?.onDestroy(); _binding = null }
}
