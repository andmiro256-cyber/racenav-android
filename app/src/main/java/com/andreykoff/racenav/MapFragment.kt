package com.andreykoff.racenav

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
import com.mapbox.mapboxsdk.style.layers.CircleLayer
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

    // Waypoints (КП) from GPX/WPT
    private val waypoints = mutableListOf<Waypoint>()
    private var activeWpIndex = 0  // current target CP index

    companion object {
        const val TRACK_SOURCE_ID = "track-source"
        const val TRACK_ARROWS_SOURCE_ID = "track-arrows-source"
        const val TRACK_LAYER_ID = "track-layer"
        const val TRACK_ARROWS_LAYER_ID = "track-arrows-layer"
        const val TRACK_ARROW_ICON = "track-arrow-icon"
        const val WP_SOURCE_ID = "wp-source"
        const val WP_LAYER_ID = "wp-layer"
        const val WP_LABEL_LAYER_ID = "wp-label-layer"
        const val GPS_ARROW_SOURCE_ID = "gps-arrow-source"
        const val GPS_ARROW_LAYER_ID = "gps-arrow-layer"
        const val GPS_ARROW_ICON = "gps-arrow-icon"
        const val ARROW_DISTANCE_M = 80.0
        const val PREFS_NAME = "racenav_prefs"
        const val PREF_VOLUME_ZOOM = "volume_zoom_enabled"
        const val PREF_FULLSCREEN = "fullscreen_enabled"
        const val PREF_MARKER_COLOR = "marker_color"
        const val PREF_MARKER_SIZE = "marker_size"
        const val DEFAULT_MARKER_COLOR = "#FF2200"
        const val DEFAULT_MARKER_SIZE = 56
    }

    data class TileSource(val label: String, val urls: List<String>, val tms: Boolean = false)

    private val tileSources = linkedMapOf(
        "osm"          to TileSource("OpenStreetMap", listOf(
            "https://a.tile.openstreetmap.org/{z}/{x}/{y}.png",
            "https://b.tile.openstreetmap.org/{z}/{x}/{y}.png",
            "https://c.tile.openstreetmap.org/{z}/{x}/{y}.png")),
        "satellite"    to TileSource("Спутник ESRI", listOf(
            "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}")),
        "topo"         to TileSource("OpenTopoMap", listOf("https://tile.opentopomap.org/{z}/{x}/{y}.png")),
        "google"       to TileSource("Google Спутник", listOf(
            "https://mt0.google.com/vt/lyrs=s&x={x}&y={y}&z={z}",
            "https://mt1.google.com/vt/lyrs=s&x={x}&y={y}&z={z}",
            "https://mt2.google.com/vt/lyrs=s&x={x}&y={y}&z={z}",
            "https://mt3.google.com/vt/lyrs=s&x={x}&y={y}&z={z}")),
        "genshtab250"  to TileSource("Генштаб 250м", listOf(
            "https://a.tiles.nakarte.me/g250/{z}/{x}/{y}",
            "https://b.tiles.nakarte.me/g250/{z}/{x}/{y}",
            "https://c.tiles.nakarte.me/g250/{z}/{x}/{y}"), tms = true),
        "genshtab500"  to TileSource("Генштаб 500м", listOf(
            "https://a.tiles.nakarte.me/g500/{z}/{x}/{y}",
            "https://b.tiles.nakarte.me/g500/{z}/{x}/{y}",
            "https://c.tiles.nakarte.me/g500/{z}/{x}/{y}"), tms = true),
        "ggc500"       to TileSource("ГосГисЦентр 500м", listOf(
            "https://a.tiles.nakarte.me/ggc500/{z}/{x}/{y}",
            "https://b.tiles.nakarte.me/ggc500/{z}/{x}/{y}",
            "https://c.tiles.nakarte.me/ggc500/{z}/{x}/{y}"), tms = true)
    )

    // Overlay sources (transparent, shown on top of base)
    data class OverlaySource(val label: String, val urls: List<String>, val tms: Boolean = false, val opacity: Float = 0.7f)

    private val overlaySources = linkedMapOf(
        "none"     to OverlaySource("Нет", emptyList()),
        "hiking"   to OverlaySource("Пешие маршруты", listOf(
            "https://tile.waymarkedtrails.org/hiking/{z}/{x}/{y}.png")),
        "cycling"  to OverlaySource("Велотреки", listOf(
            "https://tile.waymarkedtrails.org/cycling/{z}/{x}/{y}.png")),
        "osm_ov"   to OverlaySource("OSM дороги", listOf(
            "https://a.tile.openstreetmap.org/{z}/{x}/{y}.png",
            "https://b.tile.openstreetmap.org/{z}/{x}/{y}.png",
            "https://c.tile.openstreetmap.org/{z}/{x}/{y}.png"), opacity = 0.5f),
        "winter"   to OverlaySource("Лыжные трассы", listOf(
            "https://tiles.opensnowmap.org/piste/{z}/{x}/{y}.png")),
        "nakarte"  to OverlaySource("Nakarte треки", listOf(
            "https://a.tiles.nakarte.me/osm/{z}/{x}/{y}",
            "https://b.tiles.nakarte.me/osm/{z}/{x}/{y}"), opacity = 0.6f)
    )

    private var currentTileKey = "osm"
    private var currentOverlayKey = "none"

    // Public method to load waypoints from SettingsFragment
    fun loadWaypoints(wps: List<Waypoint>) {
        waypoints.clear()
        waypoints.addAll(wps)
        activeWpIndex = 0
        updateWaypointsOnMap()
        updateNextCpWidget()
        if (wps.isNotEmpty()) {
            Toast.makeText(context, "Загружено ${wps.size} точек", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentMapBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Show previous crash report if any
        val crashPrefs = context?.getSharedPreferences("crash_report", android.content.Context.MODE_PRIVATE)
        val lastCrash = crashPrefs?.getString("last_crash", null)
        if (lastCrash != null) {
            crashPrefs.edit().remove("last_crash").apply()
            android.app.AlertDialog.Builder(requireContext())
                .setTitle("Crash (отправь мне)")
                .setMessage(lastCrash)
                .setPositiveButton("OK", null)
                .show()
        }

        // Fix top bar overlap with system status bar
        ViewCompat.setOnApplyWindowInsetsListener(binding.topBar) { v, insets ->
            val statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            v.setPadding(v.paddingLeft, statusBarHeight, v.paddingRight, v.paddingBottom)
            insets
        }

        // Apply fullscreen mode from prefs
        applyFullscreenPref()

        binding.mapView.onCreate(savedInstanceState)
        binding.mapView.getMapAsync { map ->
            mapboxMap = map
            map.uiSettings.isCompassEnabled = false
            map.uiSettings.isAttributionEnabled = false
            map.uiSettings.isLogoEnabled = false
            loadTileStyle("osm", "none")
            setupButtons(map)
            // Long press on map toggles UI bars (useful in fullscreen mode)
            map.addOnMapLongClickListener {
                val prefs = context?.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE) ?: return@addOnMapLongClickListener false
                val topVisible = _binding?.topBar?.visibility == android.view.View.VISIBLE
                _binding?.topBar?.visibility = if (topVisible) android.view.View.GONE else android.view.View.VISIBLE
                _binding?.bottomBar?.visibility = if (topVisible) android.view.View.GONE else android.view.View.VISIBLE
                if (!topVisible) Toast.makeText(context, "Долгое нажатие — скрыть панели", Toast.LENGTH_SHORT).show()
                true
            }
        }
    }

    fun applyFullscreenPref() {
        val prefs = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) ?: return
        val fs = prefs.getBoolean(PREF_FULLSCREEN, false)
        _binding?.topBar?.visibility = if (fs) View.GONE else View.VISIBLE
        _binding?.bottomBar?.visibility = if (fs) View.GONE else View.VISIBLE
    }

    fun zoomIn() { mapboxMap?.animateCamera(CameraUpdateFactory.zoomIn()) }
    fun zoomOut() { mapboxMap?.animateCamera(CameraUpdateFactory.zoomOut()) }

    private fun buildStyleJson(baseKey: String, overlayKey: String): String {
        val base = tileSources[baseKey] ?: return ""
        val baseTiles = base.urls.joinToString(",") { "\"$it\"" }
        val baseScheme = if (base.tms) ",\"scheme\":\"tms\"" else ""

        val overlay = overlaySources[overlayKey]
        val hasOverlay = overlay != null && overlay.urls.isNotEmpty()

        val sources = StringBuilder()
        sources.append("\"rt\":{\"type\":\"raster\",\"tiles\":[$baseTiles],\"tileSize\":256$baseScheme}")
        if (hasOverlay) {
            val ovTiles = overlay!!.urls.joinToString(",") { "\"$it\"" }
            val ovScheme = if (overlay.tms) ",\"scheme\":\"tms\"" else ""
            sources.append(",\"ov\":{\"type\":\"raster\",\"tiles\":[$ovTiles],\"tileSize\":256$ovScheme}")
        }

        val layers = StringBuilder()
        layers.append("{\"id\":\"rl\",\"type\":\"raster\",\"source\":\"rt\",\"minzoom\":0,\"maxzoom\":22}")
        if (hasOverlay) {
            val op = overlay!!.opacity
            layers.append(",{\"id\":\"ol\",\"type\":\"raster\",\"source\":\"ov\",\"minzoom\":0,\"maxzoom\":22,\"paint\":{\"raster-opacity\":$op}}")
        }

        return """{"version":8,"sources":{$sources},"layers":[$layers]}"""
    }

    private fun loadTileStyle(baseKey: String, overlayKey: String) {
        currentTileKey = baseKey
        currentOverlayKey = overlayKey
        val json = buildStyleJson(baseKey, overlayKey)
        mapboxMap?.setStyle(Style.Builder().fromJson(json)) { style ->
            enableLocation(style)
            setupTrackLayers(style)
            setupWaypointLayers(style)
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

        // LocationComponent fully invisible — only for camera tracking & GPS engine
        val options = LocationComponentOptions.builder(ctx)
            .foregroundDrawable(R.drawable.ic_transparent)
            .backgroundDrawable(R.drawable.ic_transparent)
            .foregroundTintColor(Color.TRANSPARENT)
            .backgroundTintColor(Color.TRANSPARENT)
            .bearingTintColor(Color.TRANSPARENT)
            .accuracyAlpha(0f)
            .accuracyAnimationEnabled(false)
            .elevation(0f)
            .build()
        val lc = mapboxMap?.locationComponent ?: return
        lc.activateLocationComponent(LocationComponentActivationOptions.builder(ctx, style)
            .locationComponentOptions(options).build())
        lc.isLocationComponentEnabled = true
        applyFollowMode()

        // Setup custom GPS arrow SymbolLayer
        setupGpsArrowLayer(style)

        // Start location tracking AFTER component is activated
        mapboxMap?.let { setupLocationTracking(it) }
    }

    private fun setupGpsArrowLayer(style: Style) {
        val ctx = context ?: return
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val markerColor = Color.parseColor(prefs.getString(PREF_MARKER_COLOR, DEFAULT_MARKER_COLOR) ?: DEFAULT_MARKER_COLOR)
        val markerSize = prefs.getInt(PREF_MARKER_SIZE, DEFAULT_MARKER_SIZE)
        style.addImage(GPS_ARROW_ICON, makeArrowBitmap(markerSize, markerColor))
        style.addSource(GeoJsonSource(GPS_ARROW_SOURCE_ID))
        style.addLayer(SymbolLayer(GPS_ARROW_LAYER_ID, GPS_ARROW_SOURCE_ID).withProperties(
            PropertyFactory.iconImage(GPS_ARROW_ICON),
            PropertyFactory.iconRotationAlignment("map"),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true),
            PropertyFactory.iconRotate(com.mapbox.mapboxsdk.style.expressions.Expression.get("bearing")),
            PropertyFactory.iconSize(1.0f)
        ))
    }

    private fun updateGpsArrow(lat: Double, lon: Double, bearing: Float) {
        val style = mapboxMap?.style ?: return
        style.getSourceAs<GeoJsonSource>(GPS_ARROW_SOURCE_ID)?.setGeoJson(
            JSONObject()
                .put("type", "Feature")
                .put("geometry", JSONObject().put("type", "Point")
                    .put("coordinates", JSONArray().put(lon).put(lat)))
                .put("properties", JSONObject().put("bearing", bearing))
                .toString()
        )
    }

    private fun setupTrackLayers(style: Style) {
        val ctx = context ?: return
        ContextCompat.getDrawable(ctx, R.drawable.ic_track_arrow)?.let {
            style.addImage(TRACK_ARROW_ICON, it.toBitmap(32, 32))
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

    private fun setupWaypointLayers(style: Style) {
        style.addSource(GeoJsonSource(WP_SOURCE_ID))
        style.addLayer(CircleLayer(WP_LAYER_ID, WP_SOURCE_ID).withProperties(
            PropertyFactory.circleRadius(10f),
            PropertyFactory.circleColor("#FF6F00"),
            PropertyFactory.circleStrokeWidth(2f),
            PropertyFactory.circleStrokeColor("#FFFFFF")
        ))
        style.addLayer(SymbolLayer(WP_LABEL_LAYER_ID, WP_SOURCE_ID).withProperties(
            PropertyFactory.textField(com.mapbox.mapboxsdk.style.expressions.Expression.get("label")),
            PropertyFactory.textColor("#FFFFFF"),
            PropertyFactory.textSize(11f),
            PropertyFactory.textAllowOverlap(true),
            PropertyFactory.textOffset(arrayOf(0f, -2.5f)),
            PropertyFactory.textFont(arrayOf("Open Sans Bold", "Arial Unicode MS Regular"))
        ))
        if (waypoints.isNotEmpty()) updateWaypointsOnMap()
    }

    private fun updateWaypointsOnMap() {
        val style = mapboxMap?.style ?: return
        val source = style.getSourceAs<GeoJsonSource>(WP_SOURCE_ID) ?: return
        val features = JSONArray()
        waypoints.forEach { wp ->
            val feature = JSONObject()
                .put("type", "Feature")
                .put("geometry", JSONObject().put("type", "Point")
                    .put("coordinates", JSONArray().put(wp.lon).put(wp.lat)))
                .put("properties", JSONObject()
                    .put("label", wp.index.toString())
                    .put("name", wp.name))
            features.put(feature)
        }
        source.setGeoJson(JSONObject().put("type", "FeatureCollection").put("features", features).toString())
    }

    private fun updateNextCpWidget() {
        val b = _binding ?: return
        if (waypoints.isEmpty() || activeWpIndex >= waypoints.size) {
            b.widgetNextCp.text = "--"
            return
        }
        // Distance will be updated on next GPS fix
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
            Toast.makeText(context, when (followMode) {
                FollowMode.FREE -> "Свободный режим"
                FollowMode.FOLLOW_NORTH -> "Следить — север вверху"
                FollowMode.FOLLOW_COURSE -> "Следить — курс вверху"
            }, Toast.LENGTH_SHORT).show()
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

        // Tap next CP widget to advance to next waypoint
        binding.widgetNextCp.setOnClickListener { advanceWaypoint() }

        map.addOnCameraMoveListener { updateCompass() }
        map.addOnCameraIdleListener { updateCompass() }
    }

    private fun advanceWaypoint() {
        if (waypoints.isEmpty()) return
        activeWpIndex = (activeWpIndex + 1) % waypoints.size
        Toast.makeText(context, "КП ${waypoints[activeWpIndex].index}: ${waypoints[activeWpIndex].name}", Toast.LENGTH_SHORT).show()
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
                    val b = _binding ?: return

                    // Update custom GPS arrow
                    updateGpsArrow(loc.latitude, loc.longitude, loc.bearing)

                    // Update widgets
                    val speedKmh = (loc.speed * 3.6).toInt()
                    b.widgetSpeed.text = if (loc.speed > 0.5f) speedKmh.toString() else "--"
                    b.widgetBearing.text = "${loc.bearing.toInt()}°"
                    b.widgetDirectionArrow.rotation = loc.bearing
                    if (loc.hasAltitude()) b.widgetAltitude.text = loc.altitude.toInt().toString()

                    // Next CP distance
                    if (waypoints.isNotEmpty() && activeWpIndex < waypoints.size) {
                        val wp = waypoints[activeWpIndex]
                        val distM = distanceM(newPoint, LatLng(wp.lat, wp.lon))
                        b.widgetNextCp.text = if (distM < 1000) "${distM.toInt()}м" else String.format("%.1f", distM / 1000)
                    }

                    // Track recording
                    if (isRecording && (trackPoints.isEmpty() || distanceM(trackPoints.last(), newPoint) > 2.0)) {
                        if (trackPoints.isNotEmpty()) trackLengthM += distanceM(trackPoints.last(), newPoint)
                        trackPoints.add(newPoint)
                        val lenKm = trackLengthM / 1000.0
                        b.widgetTrackLen.text = if (lenKm < 10) String.format("%.1f", lenKm) else lenKm.toInt().toString()

                        if (lastArrowLat == 0.0 || distanceM(LatLng(lastArrowLat, lastArrowLon), newPoint) >= ARROW_DISTANCE_M) {
                            if (trackPoints.size >= 2) {
                                val prev = trackPoints[trackPoints.size - 2]
                                arrowPoints.add(Pair(newPoint, bearingBetween(prev, newPoint).toFloat()))
                                lastArrowLat = loc.latitude; lastArrowLon = loc.longitude
                            }
                        }
                        updateTrackOnMap()
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
            trackPoints.clear(); arrowPoints.clear()
            trackLengthM = 0.0; lastArrowLat = 0.0; lastArrowLon = 0.0
            binding.btnRec.setImageResource(R.drawable.ic_rec)
            binding.widgetTrackLen.text = "0.0"
            Toast.makeText(context, "⏺ Запись трека начата", Toast.LENGTH_SHORT).show()
        } else {
            binding.btnRec.setImageResource(R.drawable.ic_rec_start)
            Toast.makeText(context, "⏹ ${trackPoints.size} точек, ${String.format("%.1f", trackLengthM / 1000)} км", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateTrackOnMap() {
        val style = mapboxMap?.style ?: return
        if (trackPoints.size >= 2) {
            val coords = JSONArray()
            trackPoints.forEach { coords.put(JSONArray().put(it.longitude).put(it.latitude)) }
            style.getSourceAs<GeoJsonSource>(TRACK_SOURCE_ID)?.setGeoJson(
                JSONObject().put("type", "Feature")
                    .put("geometry", JSONObject().put("type", "LineString").put("coordinates", coords))
                    .put("properties", JSONObject()).toString()
            )
        }
        val features = JSONArray()
        arrowPoints.forEach { (pt, bearing) ->
            features.put(JSONObject().put("type", "Feature")
                .put("geometry", JSONObject().put("type", "Point")
                    .put("coordinates", JSONArray().put(pt.longitude).put(pt.latitude)))
                .put("properties", JSONObject().put("bearing", bearing)))
        }
        style.getSourceAs<GeoJsonSource>(TRACK_ARROWS_SOURCE_ID)
            ?.setGeoJson(JSONObject().put("type", "FeatureCollection").put("features", features).toString())
    }

    private fun applyFollowMode() {
        val lc = mapboxMap?.locationComponent ?: return
        val b = _binding ?: return
        when (followMode) {
            FollowMode.FREE -> {
                lc.cameraMode = CameraMode.NONE; lc.renderMode = RenderMode.GPS
                b.btnGps.setImageResource(R.drawable.ic_my_location)
                ImageViewCompat.setImageTintList(b.btnGps, android.content.res.ColorStateList.valueOf(Color.WHITE))
            }
            FollowMode.FOLLOW_NORTH -> {
                lc.cameraMode = CameraMode.TRACKING; lc.renderMode = RenderMode.GPS
                b.btnGps.setImageResource(R.drawable.ic_my_location)
                ImageViewCompat.setImageTintList(b.btnGps, android.content.res.ColorStateList.valueOf(Color.parseColor("#42A5F5")))
            }
            FollowMode.FOLLOW_COURSE -> {
                lc.cameraMode = CameraMode.TRACKING_GPS; lc.renderMode = RenderMode.GPS
                b.btnGps.setImageResource(R.drawable.ic_nav_arrow)
                ImageViewCompat.setImageTintList(b.btnGps, android.content.res.ColorStateList.valueOf(Color.parseColor("#FF5722")))
            }
        }
    }

    private fun updateCompass() {
        _binding?.compassView?.rotation = (-(mapboxMap?.cameraPosition?.bearing ?: 0.0)).toFloat()
    }

    private fun showLayerPicker() {
        val dialog = BottomSheetDialog(requireContext(), R.style.BottomSheetTheme)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_layers, null)
        dialog.setContentView(view)

        // Base layers
        val baseGroup = view.findViewById<RadioGroup>(R.id.layerRadioGroup)
        tileSources.forEach { (key, source) ->
            baseGroup.addView(RadioButton(requireContext()).apply {
                text = source.label; tag = key
                isChecked = key == currentTileKey
                setTextColor(0xFFFFFFFF.toInt()); textSize = 15f
                setPadding(32, 20, 32, 20); id = View.generateViewId()
            })
        }
        baseGroup.setOnCheckedChangeListener { group, id ->
            val key = group.findViewById<RadioButton>(id)?.tag as? String ?: return@setOnCheckedChangeListener
            loadTileStyle(key, currentOverlayKey)
            dialog.dismiss()
        }

        // Overlay layers
        val overlayGroup = view.findViewById<RadioGroup>(R.id.overlayRadioGroup)
        overlaySources.forEach { (key, source) ->
            overlayGroup.addView(RadioButton(requireContext()).apply {
                text = source.label; tag = key
                isChecked = key == currentOverlayKey
                setTextColor(if (key == "none") 0xFF888888.toInt() else 0xFFFFFFFF.toInt())
                textSize = 15f; setPadding(32, 20, 32, 20); id = View.generateViewId()
            })
        }
        overlayGroup.setOnCheckedChangeListener { group, id ->
            val key = group.findViewById<RadioButton>(id)?.tag as? String ?: return@setOnCheckedChangeListener
            loadTileStyle(currentTileKey, key)
            dialog.dismiss()
        }

        dialog.show()
    }

    /** Generate Yandex Navigator-style arrow bitmap */
    private fun makeArrowBitmap(sizeDp: Int, color: Int): Bitmap {
        val density = resources.displayMetrics.density
        val size = (sizeDp * density).toInt().coerceAtLeast(24)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val cx = size / 2f; val cy = size / 2f; val r = size / 2f * 0.88f
        // Yandex Navigator arrow: pointed tip at top, wide shoulders, V-notch at tail
        val path = Path().apply {
            moveTo(cx, cy - r)                      // tip (top)
            lineTo(cx + r * 0.72f, cy + r * 0.20f) // right shoulder
            lineTo(cx + r * 0.42f, cy + r * 0.90f) // right tail corner
            lineTo(cx, cy + r * 0.45f)              // V-notch center
            lineTo(cx - r * 0.42f, cy + r * 0.90f) // left tail corner
            lineTo(cx - r * 0.72f, cy + r * 0.20f) // left shoulder
            close()
        }
        // White border stroke
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = r * 0.14f
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
        }
        canvas.drawPath(path, strokePaint)
        // Fill with arrow color
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.FILL
        }
        canvas.drawPath(path, fillPaint)
        return bmp
    }

    /** Re-draw GPS arrow with current color/size from prefs (call from SettingsFragment) */
    fun refreshGpsArrow() {
        val style = mapboxMap?.style ?: return
        val ctx = context ?: return
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val color = Color.parseColor(prefs.getString(PREF_MARKER_COLOR, DEFAULT_MARKER_COLOR) ?: DEFAULT_MARKER_COLOR)
        val size = prefs.getInt(PREF_MARKER_SIZE, DEFAULT_MARKER_SIZE)
        style.addImage(GPS_ARROW_ICON, makeArrowBitmap(size, color))
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
        return (Math.toDegrees(atan2(sin(dLon)*cos(lat2), cos(lat1)*sin(lat2)-sin(lat1)*cos(lat2)*cos(dLon))) + 360) % 360
    }

    fun checkForUpdates(onResult: (latest: String?, current: String, hasUpdate: Boolean) -> Unit) {
        val current = "v${requireContext().packageManager.getPackageInfo(requireContext().packageName, 0).versionName}"
        lifecycleScope.launch {
            try {
                val latest = withContext(Dispatchers.IO) {
                    JSONObject(URL("https://api.github.com/repos/andmiro256-cyber/racenav-android/releases/latest").readText()).getString("tag_name")
                }
                onResult(latest, current, latest != current)
            } catch (e: Exception) {
                Log.d("RaceNav", "Update check: ${e.message}")
                onResult(null, current, false)
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
    override fun onResume() { super.onResume(); _binding?.mapView?.onResume(); applyFullscreenPref() }
    override fun onPause() { super.onPause(); _binding?.mapView?.onPause() }
    override fun onStop() { super.onStop(); _binding?.mapView?.onStop() }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); _binding?.mapView?.onSaveInstanceState(outState) }
    override fun onLowMemory() { super.onLowMemory(); _binding?.mapView?.onLowMemory() }
    override fun onDestroyView() { super.onDestroyView(); _binding?.mapView?.onDestroy(); _binding = null }
}
