package com.andreykoff.racenav

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.graphics.*
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import fi.iki.elonen.NanoHTTPD
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
import com.mapbox.mapboxsdk.style.layers.FillLayer
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
    var followMode = FollowMode.FREE

    // Track recording — данные хранятся в TrackingService
    private val trackPoints get() = TrackingService.trackPoints
        .map { LatLng(it.first, it.second) }
        .toMutableList()
    private val isRecording get() = TrackingService.isRunning
    private var lastArrowLat = 0.0
    private var lastArrowLon = 0.0
    private val arrowPoints = mutableListOf<Pair<LatLng, Float>>()
    private val trackLengthM get() = TrackingService.trackLengthM
    private val recordingStartMs get() = TrackingService.startTimeMs

    // BroadcastReceiver для получения GPS из TrackingService когда приложение на экране
    private val locationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != TrackingService.BROADCAST_LOCATION) return
            val b = _binding ?: return
            val lat      = intent.getDoubleExtra(TrackingService.EXTRA_LAT, 0.0)
            val lon      = intent.getDoubleExtra(TrackingService.EXTRA_LON, 0.0)
            val speed    = intent.getFloatExtra(TrackingService.EXTRA_SPEED, 0f)
            val bearing  = intent.getFloatExtra(TrackingService.EXTRA_BEARING, 0f)
            val altitude = intent.getDoubleExtra(TrackingService.EXTRA_ALTITUDE, 0.0)
            val hasSpeed = intent.getBooleanExtra(TrackingService.EXTRA_HAS_SPEED, false)
            val hasAlt   = intent.getBooleanExtra(TrackingService.EXTRA_HAS_ALTITUDE, false)

            // Обновляем трек на карте
            updateTrackOnMap()

            // Обновляем виджеты
            val speedKmh = (speed * 3.6).toInt()
            b.widgetSpeed.text = if (speed > 0.5f) speedKmh.toString() else "--"
            b.widgetBearing.text = "${bearing.toInt()}°"
            b.widgetDirectionArrow.rotation = bearing
            if (hasAlt) b.widgetAltitude.text = altitude.toInt().toString()

            // Длина трека
            val lenKm = TrackingService.trackLengthM / 1000.0
            b.widgetTrackLen.text = if (lenKm < 10) String.format("%.1f", lenKm) else lenKm.toInt().toString()
        }
    }
    private var chronoRunnable: Runnable? = null
    private val chronoHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var timeTickRunnable: Runnable? = null
    private val timeHandler = android.os.Handler(android.os.Looper.getMainLooper())
    var isScreenLocked = false
        private set
    private val loadedTrackPoints = mutableListOf<com.mapbox.mapboxsdk.geometry.LatLng>()

    private var initialZoomDone = false
    var autoRecenterEnabled = false
    var tilt3dEnabled = false       // 3D tilt when driving in FOLLOW_COURSE
    var autoZoomLevel = 0           // 0=off, 1-10; controls zoom amplitude with speed
    private var userBaseZoom = -1.0 // user's preferred zoom; auto-zoom adjusts relative to this
    private var userDragged = false  // true = user moved map manually, pause following
    private var smoothedBearing = -1.0  // EMA-сглаженный курс, -1 = не инициализирован
    private var tileServer: TileServer? = null
    private val offlineMaps = mutableListOf<OfflineMapInfo>()
    private var lastKnownGpsPoint: LatLng? = null
    private val recenterHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var recenterRunnable: Runnable? = null

    // Waypoints (КП) from GPX/WPT
    private val waypoints = mutableListOf<Waypoint>()
    private var activeWpIndex = 0  // current target CP index
    var navActive = false           // waypoint navigation bar visible
    private var cameraTopPadding = 0  // px; updated by applyCursorOffset(), applied in every camera move
    private var wasInApproachRadius = false  // track radius entry to fire sound once

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
        const val DEFAULT_MARKER_COLOR = "#FFD600"
        const val DEFAULT_MARKER_SIZE = 28
        const val PREF_CAMERA_LAT = "camera_lat"
        const val PREF_CAMERA_LON = "camera_lon"
        const val PREF_CAMERA_ZOOM = "camera_zoom"
        const val PREF_AUTO_RECENTER = "auto_recenter"
        const val PREF_FOLLOW_MODE = "follow_mode"
        const val PREF_LAST_LAT = "last_lat"
        const val PREF_LAST_LON = "last_lon"
        const val PREF_LAST_BEARING = "last_bearing"
        const val PREF_KEEP_SCREEN = "keep_screen_on"
        const val PREF_TRACK_INTERVAL = "track_interval_sec"  // seconds, default 1
        const val PREF_LOADED_TRACK_VISIBLE = "loaded_track_visible"
        const val PREF_LOADED_WP_VISIBLE = "loaded_wp_visible"
        const val PREF_LOADED_TRACK_NAME = "loaded_track_name"
        const val PREF_LOADED_WP_NAME = "loaded_wp_name"
        const val PREF_TRACK_COLOR = "track_rec_color"
        const val PREF_TRACK_WIDTH = "track_rec_width"
        const val PREF_LOADED_TRACK_COLOR = "loaded_track_color"
        const val PREF_LOADED_TRACK_WIDTH = "loaded_track_width"
        const val DEFAULT_TRACK_COLOR = "#FF2200"
        const val DEFAULT_TRACK_WIDTH = 4f
        const val DEFAULT_LOADED_TRACK_COLOR = "#2196F3"
        const val DEFAULT_LOADED_TRACK_WIDTH = 3f
        const val PREF_OFFLINE_MAP_PATH = "offline_map_path"  // legacy, kept for migration
        const val PREF_OFFLINE_MAPS_JSON = "offline_maps_json" // JSON array of {key,name,path}
        const val PREF_CURSOR_OFFSET = "cursor_offset"   // 1-10, 1=center, 10=near bottom
        const val PREF_RECENTER_DELAY = "recenter_delay" // seconds, default 3
        const val PREF_TILE_CACHE_MB  = "tile_cache_mb"  // MB, default 200
        const val PREF_3D_TILT        = "3d_tilt_enabled"  // bool, default false
        const val PREF_AUTO_ZOOM      = "auto_zoom_level"   // 0=disabled, 1-10
        const val PREF_WAS_RECORDING  = "was_recording"     // bool: app was closed during recording
        const val TRACK_TMP_FILENAME  = "current_track_tmp.gpx" // temp file while recording
        const val OFFLINE_TILE_KEY = "offline"
        const val TILE_SERVER_PORT = 18564

        data class OfflineMapInfo(val key: String, val name: String, val path: String) {
            val index: Int get() = key.removePrefix(OFFLINE_TILE_KEY + "_").toIntOrNull() ?: 0
        }

        const val PREF_TILE_KEY = "tile_key"
        const val PREF_OVERLAY_KEY = "overlay_key"
        const val PREF_WIDGET_SPEED = "widget_speed"
        const val PREF_WIDGET_BEARING = "widget_bearing"
        const val PREF_WIDGET_TRACKLEN = "widget_tracklen"
        const val PREF_WIDGET_NEXTCP = "widget_nextcp"
        const val PREF_WIDGET_ALTITUDE = "widget_altitude"
        const val PREF_WIDGET_CHRONO = "widget_chrono"
        const val PREF_WIDGET_TIME = "widget_time"
        const val PREF_WIDGET_REMAIN_KM = "widget_remain_km"
        const val PREF_WIDGET_NEXTCP_NAME = "widget_nextcp_name"
        const val PREF_WIDGET_ORDER = "widget_order"
        const val PREF_NAV_ACTIVE = "nav_active"
        const val PREF_WP_APPROACH_RADIUS = "wp_approach_radius"  // metres, default 25
        const val DEFAULT_WP_APPROACH_RADIUS = 25
        const val PREF_SYNC_API_KEY = "sync_api_key"
        const val SYNC_BASE_URL = "http://87.120.84.254:9222"
        const val LOADED_TRACK_SOURCE_ID = "loaded-track-source"
        const val LOADED_TRACK_LAYER_ID = "loaded-track-layer"

        val ALL_WIDGET_KEYS = listOf("speed","bearing","tracklen","nextcp","altitude","chrono","time","remain_km","nextcp_name")

        const val NAV_LINE_SOURCE_ID = "nav-line-source"
        const val NAV_LINE_LAYER_ID = "nav-line-layer"
        const val ROUTE_LINE_SOURCE_ID = "route-line-source"
        const val ROUTE_LINE_LAYER_ID  = "route-line-layer"
        const val WP_RADIUS_SOURCE_ID = "wp-radius-source"
        const val WP_RADIUS_LAYER_ID = "wp-radius-layer"
        const val PREF_NAV_LINE_COLOR = "nav_line_color"    // hex, default "#FF6F00"
        const val PREF_NAV_LINE_WIDTH = "nav_line_width"    // dp, default 3

        // Persistence of loaded data across restarts
        const val PREF_SAVED_WAYPOINTS_JSON = "saved_waypoints_json"
        const val PREF_SAVED_TRACK_JSON = "saved_track_json"
        const val PREF_ACTIVE_WP_INDEX = "active_wp_index"

        // Sound notifications
        const val PREF_SOUND_APPROACH = "sound_approach"   // bool, default true
        const val PREF_SOUND_TAKEN    = "sound_taken"      // bool, default true
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
            "https://c.tiles.nakarte.me/ggc500/{z}/{x}/{y}"), tms = true),
        "ggc1000"      to TileSource("ГосГисЦентр 1км", listOf(
            "https://a.tiles.nakarte.me/ggc1000/{z}/{x}/{y}",
            "https://b.tiles.nakarte.me/ggc1000/{z}/{x}/{y}",
            "https://c.tiles.nakarte.me/ggc1000/{z}/{x}/{y}"), tms = true),
        "topo250"      to TileSource("Топо 250м", listOf(
            "https://a.tiles.nakarte.me/topo250/{z}/{x}/{y}",
            "https://b.tiles.nakarte.me/topo250/{z}/{x}/{y}",
            "https://c.tiles.nakarte.me/topo250/{z}/{x}/{y}"), tms = true),
        "topo500"      to TileSource("Топо 500м", listOf(
            "https://a.tiles.nakarte.me/topo500/{z}/{x}/{y}",
            "https://b.tiles.nakarte.me/topo500/{z}/{x}/{y}",
            "https://c.tiles.nakarte.me/topo500/{z}/{x}/{y}"), tms = true)
    )

    // Overlay sources (transparent, shown on top of base)
    data class OverlaySource(val label: String, val urls: List<String>, val tms: Boolean = false, val opacity: Float = 0.7f)

    private val overlaySources = linkedMapOf(
        "none"     to OverlaySource("Нет", emptyList()),
        "osm_gps"  to OverlaySource("OSM GPS треки", listOf(
            "https://gps-a.tile.openstreetmap.org/lines/{z}/{x}/{y}.png",
            "https://gps-b.tile.openstreetmap.org/lines/{z}/{x}/{y}.png",
            "https://gps-c.tile.openstreetmap.org/lines/{z}/{x}/{y}.png"), opacity = 0.7f),
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
        updateRouteLineOnMap()
        updateNavLine()
        updateRadiusCircles()
        updateNextCpWidget()
        if (wps.isNotEmpty()) {
            Toast.makeText(context, "Загружено ${wps.size} точек", Toast.LENGTH_SHORT).show()
        }
        saveWaypointsToPrefs()
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

        // Adjust top bar padding for status bar (only when NOT in fullscreen)
        ViewCompat.setOnApplyWindowInsetsListener(binding.topBar) { v, insets ->
            val prefs = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val fs = prefs?.getBoolean(PREF_FULLSCREEN, false) ?: false
            val statusBarHeight = if (fs) 0 else insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            v.setPadding(v.paddingLeft, statusBarHeight, v.paddingRight, v.paddingBottom)
            insets
        }

        // Apply fullscreen mode from prefs
        applyFullscreenPref()
        applyWidgetPrefs()

        binding.mapView.onCreate(savedInstanceState)
        applyCacheSize()

        binding.mapView.getMapAsync { map ->
            mapboxMap = map
            map.uiSettings.isCompassEnabled = false
            map.uiSettings.isAttributionEnabled = false
            map.uiSettings.isLogoEnabled = false
            val tilePrefs = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val savedTile = tilePrefs?.getString(PREF_TILE_KEY, "osm") ?: "osm"
            val savedOverlay = tilePrefs?.getString(PREF_OVERLAY_KEY, "none") ?: "none"
            // Load offline maps BEFORE loadTileStyle so tile sources are ready
            loadOfflineMapsFromPrefs()
            loadTileStyle(savedTile, savedOverlay)

            // Restore camera position if saved
            val savedLat = tilePrefs?.getFloat(PREF_CAMERA_LAT, Float.MIN_VALUE) ?: Float.MIN_VALUE
            val savedLon = tilePrefs?.getFloat(PREF_CAMERA_LON, Float.MIN_VALUE) ?: Float.MIN_VALUE
            val savedZoom = tilePrefs?.getFloat(PREF_CAMERA_ZOOM, -1f) ?: -1f
            if (savedLat != Float.MIN_VALUE && savedZoom > 0) {
                map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(savedLat.toDouble(), savedLon.toDouble()), savedZoom.toDouble()))
                initialZoomDone = true
            }

            autoRecenterEnabled = tilePrefs?.getBoolean(PREF_AUTO_RECENTER, false) ?: false
            tilt3dEnabled = tilePrefs?.getBoolean(PREF_3D_TILT, false) ?: false
            autoZoomLevel = tilePrefs?.getInt(PREF_AUTO_ZOOM, 0) ?: 0

            setupButtons(map)
            // Check if app was closed while recording — offer resume/save
            if (!isRecording) checkForUnfinishedTrack()
            // Long press on map toggles UI bars
            map.addOnMapLongClickListener {
                val topVisible = _binding?.topBar?.visibility == android.view.View.VISIBLE
                _binding?.topBar?.visibility = if (topVisible) android.view.View.GONE else android.view.View.VISIBLE
                _binding?.bottomBar?.visibility = if (topVisible) android.view.View.GONE else android.view.View.VISIBLE
                true
            }
        }

        // Restore lock state after rotation
        if (savedInstanceState?.getBoolean("screen_locked", false) == true) {
            lockScreen()
        }
    }

    fun applyFullscreenPref() {
        val prefs = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) ?: return
        val fs = prefs.getBoolean(PREF_FULLSCREEN, false)
        val window = activity?.window ?: return
        if (fs) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                window.insetsController?.hide(
                    android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.navigationBars()
                )
                window.insetsController?.systemBarsBehavior =
                    android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
            }
        } else {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                window.insetsController?.show(
                    android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.navigationBars()
                )
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
            }
        }
        // App bars stay visible; use long press on map to temporarily hide them
        _binding?.topBar?.visibility = View.VISIBLE
        _binding?.bottomBar?.visibility = View.VISIBLE
    }

    private data class WidgetDef(val key: String, val prefKey: String, val defaultOn: Boolean)

    private fun widgetContainer(key: String): android.view.View? {
        val b = _binding ?: return null
        return when (key) {
            "speed"       -> b.widgetSpeedContainer
            "bearing"     -> b.widgetBearingContainer
            "tracklen"    -> b.widgetTrackLenContainer
            "nextcp"      -> b.widgetNextCpContainer
            "altitude"    -> b.widgetAltitudeContainer
            "chrono"      -> b.widgetChronoContainer
            "time"        -> b.widgetTimeContainer
            "remain_km"   -> b.widgetRemainKmContainer
            "nextcp_name" -> b.widgetNextCpNameContainer
            else -> null
        }
    }

    private fun makeDivider(): android.view.View {
        val dp = resources.displayMetrics.density
        return android.view.View(requireContext()).apply {
            val lp = android.widget.LinearLayout.LayoutParams((1 * dp + 0.5f).toInt(), (40 * dp + 0.5f).toInt())
            lp.marginStart = (1 * dp + 0.5f).toInt()
            lp.marginEnd   = (1 * dp + 0.5f).toInt()
            layoutParams = lp
            setBackgroundColor(0xFF333333.toInt())
        }
    }

    fun applyWidgetPrefs() {
        val prefs = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) ?: return
        val b = _binding ?: return

        val widgetDefs = listOf(
            WidgetDef("speed",       PREF_WIDGET_SPEED,       true),
            WidgetDef("bearing",     PREF_WIDGET_BEARING,     true),
            WidgetDef("tracklen",    PREF_WIDGET_TRACKLEN,    true),
            WidgetDef("nextcp",      PREF_WIDGET_NEXTCP,      true),
            WidgetDef("altitude",    PREF_WIDGET_ALTITUDE,    true),
            WidgetDef("chrono",      PREF_WIDGET_CHRONO,      false),
            WidgetDef("time",        PREF_WIDGET_TIME,        false),
            WidgetDef("remain_km",   PREF_WIDGET_REMAIN_KM,   false),
            WidgetDef("nextcp_name", PREF_WIDGET_NEXTCP_NAME, false),
        )

        val defaultOrder = ALL_WIDGET_KEYS.joinToString(",")
        val savedOrder = prefs.getString(PREF_WIDGET_ORDER, defaultOrder) ?: defaultOrder
        val orderedKeys = savedOrder.split(",")
        val ordered = orderedKeys.mapNotNull { key -> widgetDefs.find { it.key == key } } +
            widgetDefs.filter { w -> w.key !in orderedKeys }

        val bar = b.bottomBar
        bar.removeAllViews()
        var added = 0
        for (w in ordered) {
            if (!prefs.getBoolean(w.prefKey, w.defaultOn)) continue
            val container = widgetContainer(w.key) ?: continue
            // Containers from XML may have visibility=GONE (chrono/time/etc.) — force visible
            container.visibility = View.VISIBLE
            if (added > 0) bar.addView(makeDivider())
            // Explicit LayoutParams: width=0dp, height=match_parent, weight=1
            // (ensures equal distribution even after detach/re-attach cycle)
            bar.addView(container, android.widget.LinearLayout.LayoutParams(
                0, android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1f
            ))
            added++
        }

        val timeOn = prefs.getBoolean(PREF_WIDGET_TIME, false)
        if (timeOn) startTimeTicker() else stopTimeTicker()
    }

    private fun startTimeTicker() {
        timeTickRunnable?.let { timeHandler.removeCallbacks(it) }
        val r = object : Runnable {
            override fun run() {
                val cal = java.util.Calendar.getInstance()
                _binding?.widgetTime?.text = String.format("%d:%02d", cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE))
                timeHandler.postDelayed(this, 10000)
            }
        }
        timeTickRunnable = r
        timeHandler.post(r)
    }

    private fun stopTimeTicker() {
        timeTickRunnable?.let { timeHandler.removeCallbacks(it) }
        timeTickRunnable = null
    }

    fun zoomIn() {
        val cur = mapboxMap?.cameraPosition?.zoom ?: 14.0
        userBaseZoom = (if (userBaseZoom > 0) userBaseZoom else cur) + 1.0
        mapboxMap?.animateCamera(CameraUpdateFactory.zoomIn())
    }
    fun zoomOut() {
        val cur = mapboxMap?.cameraPosition?.zoom ?: 14.0
        userBaseZoom = ((if (userBaseZoom > 0) userBaseZoom else cur) - 1.0).coerceAtLeast(2.0)
        mapboxMap?.animateCamera(CameraUpdateFactory.zoomOut())
    }

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

        return """{"version":8,"glyphs":"https://fonts.openmaptiles.org/{fontstack}/{range}.pbf","sources":{$sources},"layers":[$layers]}"""
    }

    private fun loadTileStyle(baseKey: String, overlayKey: String) {
        currentTileKey = baseKey
        currentOverlayKey = overlayKey
        // Save selection so it survives rotation/restart
        context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)?.edit()
            ?.putString(PREF_TILE_KEY, baseKey)
            ?.putString(PREF_OVERLAY_KEY, overlayKey)
            ?.apply()
        val json = buildStyleJson(baseKey, overlayKey)
        mapboxMap?.setStyle(Style.Builder().fromJson(json)) { style ->
            enableLocation(style)
            setupTrackLayers(style)
            setupWaypointLayers(style)
            setupGpsArrowLayer(style)  // LAST — always on top of all layers
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
        applyCursorOffset()

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
        // Restore arrow at last known position (e.g. after screen rotation)
        val lastLat = prefs.getFloat(PREF_LAST_LAT, Float.MIN_VALUE)
        val lastLon = prefs.getFloat(PREF_LAST_LON, Float.MIN_VALUE)
        val lastBearing = prefs.getFloat(PREF_LAST_BEARING, 0f)
        if (lastLat != Float.MIN_VALUE) {
            updateGpsArrow(lastLat.toDouble(), lastLon.toDouble(), lastBearing)
        }
    }

    /** EMA-сглаживание курса с корректной обработкой перехода 0°/360° */
    private fun smoothBearing(raw: Float, alpha: Float = 0.25f): Double {
        if (smoothedBearing < 0) { smoothedBearing = raw.toDouble(); return smoothedBearing }
        var delta = raw - smoothedBearing
        while (delta > 180) delta -= 360
        while (delta < -180) delta += 360
        smoothedBearing = (smoothedBearing + alpha * delta + 360) % 360
        return smoothedBearing
    }

    private fun updateGpsArrow(lat: Double, lon: Double, bearing: Float) {
        val style = mapboxMap?.style ?: return
        val geoJson = JSONObject()
            .put("type", "Feature")
            .put("geometry", JSONObject().put("type", "Point")
                .put("coordinates", JSONArray().put(lon).put(lat)))
            .put("properties", JSONObject().put("bearing", bearing))
            .toString()
        style.getSourceAs<GeoJsonSource>(GPS_ARROW_SOURCE_ID)?.setGeoJson(geoJson)
        // Persist so arrow can be restored after rotation
        context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)?.edit()
            ?.putFloat(PREF_LAST_LAT, lat.toFloat())
            ?.putFloat(PREF_LAST_LON, lon.toFloat())
            ?.putFloat(PREF_LAST_BEARING, bearing)
            ?.apply()
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
        // Loaded track layer (color/width from prefs, default blue)
        style.addSource(GeoJsonSource(LOADED_TRACK_SOURCE_ID))
        style.addLayer(LineLayer(LOADED_TRACK_LAYER_ID, LOADED_TRACK_SOURCE_ID).withProperties(
            PropertyFactory.lineColor("#2196F3"),
            PropertyFactory.lineWidth(3f),
            PropertyFactory.lineCap("round"),
            PropertyFactory.lineJoin("round"),
            PropertyFactory.lineOpacity(0.85f)
        ))
        // Apply saved track styles from prefs
        applyTrackStyle()
        applyLoadedTrackStyle()
        // Clear stale loaded-track name prefs (track is gone after app restart)
        if (loadedTrackPoints.isEmpty()) {
            context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)?.edit()
                ?.remove(PREF_LOADED_TRACK_NAME)?.remove(PREF_LOADED_WP_NAME)?.apply()
        }
        if (trackPoints.isNotEmpty()) updateTrackOnMap()
        if (loadedTrackPoints.isNotEmpty()) updateLoadedTrackOnMap()
    }

    private fun setupWaypointLayers(style: Style) {
        // Restore persisted data on style (re)load
        if (waypoints.isEmpty()) restoreWaypointsFromPrefs()
        val trackWasEmpty = loadedTrackPoints.isEmpty()
        if (trackWasEmpty) restoreTrackFromPrefs()

        // Route polyline: connects all waypoints in order
        style.addSource(GeoJsonSource(ROUTE_LINE_SOURCE_ID))
        style.addLayer(LineLayer(ROUTE_LINE_LAYER_ID, ROUTE_LINE_SOURCE_ID).withProperties(
            PropertyFactory.lineColor("#FF6F00"),
            PropertyFactory.lineWidth(2f),
            PropertyFactory.lineOpacity(0.6f),
            PropertyFactory.lineDasharray(arrayOf(6f, 4f))
        ))

        // Approach radius circles (behind waypoint dots)
        style.addSource(GeoJsonSource(WP_RADIUS_SOURCE_ID))
        style.addLayer(FillLayer(WP_RADIUS_LAYER_ID, WP_RADIUS_SOURCE_ID).withProperties(
            PropertyFactory.fillColor("#FF6F00"),
            PropertyFactory.fillOpacity(0.15f),
            PropertyFactory.fillOutlineColor("#FF6F00")
        ))
        // Navigation line: GPS → active waypoint
        style.addSource(GeoJsonSource(NAV_LINE_SOURCE_ID))
        val navLineColor = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.getString(PREF_NAV_LINE_COLOR, "#FF6F00") ?: "#FF6F00"
        val navLineWidth = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.getInt(PREF_NAV_LINE_WIDTH, 3)?.toFloat() ?: 3f
        style.addLayer(LineLayer(NAV_LINE_LAYER_ID, NAV_LINE_SOURCE_ID).withProperties(
            PropertyFactory.lineColor(navLineColor),
            PropertyFactory.lineWidth(navLineWidth)
        ))

        style.addSource(GeoJsonSource(WP_SOURCE_ID))
        // Bitmap icon layer: circle with number + name drawn into bitmap (fully offline)
        style.addLayer(SymbolLayer(WP_LABEL_LAYER_ID, WP_SOURCE_ID).withProperties(
            PropertyFactory.iconImage(com.mapbox.mapboxsdk.style.expressions.Expression.get("icon_id")),
            PropertyFactory.iconSize(0.6f),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true),
            PropertyFactory.iconAnchor("top")
        ))
        if (waypoints.isNotEmpty()) {
            updateWaypointsOnMap()
            updateRouteLineOnMap()
            updateRadiusCircles()
        }
        updateNavLine()
        // If track was just restored from prefs, render it now (setupTrackLayers ran before restore)
        if (trackWasEmpty && loadedTrackPoints.isNotEmpty()) {
            updateLoadedTrackOnMap()
        }
    }

    private fun updateWaypointsOnMap() {
        val style = mapboxMap?.style ?: return
        val source = style.getSourceAs<GeoJsonSource>(WP_SOURCE_ID) ?: return

        // Register bitmap icons for each waypoint (always update — getImage may return non-null for missing images)
        waypoints.forEach { wp ->
            style.addImage("wp-icon-${wp.index}", createWaypointBitmap(wp.index, wp.name))
        }

        val features = JSONArray()
        waypoints.forEach { wp ->
            val feature = JSONObject()
                .put("type", "Feature")
                .put("geometry", JSONObject().put("type", "Point")
                    .put("coordinates", JSONArray().put(wp.lon).put(wp.lat)))
                .put("properties", JSONObject()
                    .put("icon_id", "wp-icon-${wp.index}")
                    .put("name", wp.name.ifBlank { "" }))
            features.put(feature)
        }
        source.setGeoJson(JSONObject().put("type", "FeatureCollection").put("features", features).toString())
    }

    private fun createWaypointBitmap(index: Int, name: String = ""): android.graphics.Bitmap {
        val circleR = 28f
        val circleDiam = (circleR * 2).toInt()
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

        // Measure name text
        paint.textSize = 24f
        paint.isFakeBoldText = false
        val displayName = if (name.length > 14) name.take(14) + "…" else name
        val nameW = if (name.isNotBlank()) paint.measureText(displayName) else 0f
        val nameH = if (name.isNotBlank()) (paint.textSize + 8f).toInt() else 0

        val bmpW = maxOf(circleDiam + 8, nameW.toInt() + 8)
        val bmpH = circleDiam + nameH + 4
        val bmp = android.graphics.Bitmap.createBitmap(bmpW, bmpH, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        val cx = bmpW / 2f
        val cy = circleDiam / 2f + 2f

        // Orange fill
        paint.style = android.graphics.Paint.Style.FILL
        paint.color = android.graphics.Color.parseColor("#FF6F00")
        canvas.drawCircle(cx, cy, circleR - 2f, paint)

        // White stroke
        paint.style = android.graphics.Paint.Style.STROKE
        paint.color = android.graphics.Color.WHITE
        paint.strokeWidth = 3.5f
        canvas.drawCircle(cx, cy, circleR - 2f, paint)

        // Index number
        paint.style = android.graphics.Paint.Style.FILL
        paint.color = android.graphics.Color.WHITE
        val indexStr = index.toString()
        paint.textSize = if (indexStr.length > 2) 18f else 24f
        paint.textAlign = android.graphics.Paint.Align.CENTER
        paint.isFakeBoldText = true
        val textY = cy - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(indexStr, cx, textY, paint)

        // Name text below circle
        if (name.isNotBlank()) {
            paint.isFakeBoldText = false
            paint.textSize = 24f
            paint.textAlign = android.graphics.Paint.Align.CENTER
            // White halo (shadow) for readability on any map
            paint.style = android.graphics.Paint.Style.STROKE
            paint.strokeWidth = 4f
            paint.color = android.graphics.Color.WHITE
            val nameY = circleDiam.toFloat() + 6f + paint.textSize * 0.75f
            canvas.drawText(displayName, cx, nameY, paint)
            // Black text on top
            paint.style = android.graphics.Paint.Style.FILL
            paint.color = android.graphics.Color.BLACK
            paint.strokeWidth = 0f
            canvas.drawText(displayName, cx, nameY, paint)
        }

        return bmp
    }

    private fun updateRouteLineOnMap() {
        val style = mapboxMap?.style ?: return
        val source = style.getSourceAs<GeoJsonSource>(ROUTE_LINE_SOURCE_ID) ?: return
        if (waypoints.size < 2) {
            source.setGeoJson("{\"type\":\"FeatureCollection\",\"features\":[]}")
            return
        }
        val coords = JSONArray()
        waypoints.forEach { wp -> coords.put(JSONArray().put(wp.lon).put(wp.lat)) }
        val geojson = JSONObject()
            .put("type", "Feature")
            .put("geometry", JSONObject().put("type", "LineString").put("coordinates", coords))
            .put("properties", JSONObject())
        source.setGeoJson(geojson.toString())
    }

    fun updateNavLine() {
        val style = mapboxMap?.style ?: return
        val source = style.getSourceAs<GeoJsonSource>(NAV_LINE_SOURCE_ID) ?: return
        val gps = lastKnownGpsPoint
        if (gps == null || waypoints.isEmpty() || activeWpIndex >= waypoints.size) {
            source.setGeoJson("{\"type\":\"FeatureCollection\",\"features\":[]}")
            return
        }
        val wp = waypoints[activeWpIndex]
        val coords = JSONArray()
            .put(JSONArray().put(gps.longitude).put(gps.latitude))
            .put(JSONArray().put(wp.lon).put(wp.lat))
        val geojson = JSONObject()
            .put("type", "Feature")
            .put("geometry", JSONObject().put("type", "LineString").put("coordinates", coords))
            .put("properties", JSONObject())
        source.setGeoJson(geojson.toString())
    }

    fun updateRadiusCircles() {
        val style = mapboxMap?.style ?: return
        val source = style.getSourceAs<GeoJsonSource>(WP_RADIUS_SOURCE_ID) ?: return
        val radiusM = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.getInt(PREF_WP_APPROACH_RADIUS, DEFAULT_WP_APPROACH_RADIUS)?.toDouble()
            ?: DEFAULT_WP_APPROACH_RADIUS.toDouble()
        val features = JSONArray()
        waypoints.forEach { wp ->
            val poly = buildCirclePolygon(wp.lat, wp.lon, radiusM)
            features.put(poly)
        }
        source.setGeoJson(JSONObject().put("type", "FeatureCollection").put("features", features).toString())
    }

    private fun buildCirclePolygon(lat: Double, lon: Double, radiusM: Double): JSONObject {
        val n = 36
        val latR = Math.toRadians(lat)
        val coords = JSONArray()
        for (i in 0..n) {
            val angle = 2 * Math.PI * i / n
            val dLat = radiusM * Math.cos(angle) / 111320.0
            val dLon = radiusM * Math.sin(angle) / (111320.0 * Math.cos(latR))
            coords.put(JSONArray().put(lon + dLon).put(lat + dLat))
        }
        val ring = JSONArray().put(coords)
        return JSONObject()
            .put("type", "Feature")
            .put("geometry", JSONObject().put("type", "Polygon").put("coordinates", ring))
            .put("properties", JSONObject())
    }

    fun applyNavLineStyle() {
        val prefs = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) ?: return
        val color = prefs.getString(PREF_NAV_LINE_COLOR, "#FF6F00") ?: "#FF6F00"
        val width = prefs.getInt(PREF_NAV_LINE_WIDTH, 3).toFloat()
        val style = mapboxMap?.style ?: return
        style.getLayerAs<LineLayer>(NAV_LINE_LAYER_ID)?.setProperties(
            PropertyFactory.lineColor(color),
            PropertyFactory.lineWidth(width)
        )
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
        binding.btnAddWaypoint.setOnClickListener { addWaypointAtCurrentPosition() }

        // Restore follow mode from prefs
        val savedMode = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.getString(PREF_FOLLOW_MODE, "free") ?: "free"
        followMode = when (savedMode) {
            "north" -> FollowMode.FOLLOW_NORTH
            "course" -> FollowMode.FOLLOW_COURSE
            else -> FollowMode.FREE
        }
        applyFollowMode()

        binding.btnLayers.setOnClickListener { showLayerPicker() }

        binding.compassView.setOnClickListener {
            when (followMode) {
                FollowMode.FREE -> map.animateCamera(CameraUpdateFactory.bearingTo(0.0))
                FollowMode.FOLLOW_NORTH -> { followMode = FollowMode.FOLLOW_COURSE; applyFollowMode() }
                FollowMode.FOLLOW_COURSE -> { followMode = FollowMode.FOLLOW_NORTH; applyFollowMode() }
            }
        }

        binding.btnRec.setOnClickListener { toggleRecording() }

        binding.btnLock.setOnClickListener { lockScreen() }

        binding.btnSettings.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .add(R.id.container, SettingsFragment())
                .addToBackStack(null)
                .commit()
        }

        // Tap next CP widget to advance to next waypoint
        binding.widgetNextCp.setOnClickListener { advanceWaypoint() }

        // Waypoint navigation bar buttons
        binding.btnPrevWp.setOnClickListener { prevWaypoint() }
        binding.btnNextWp.setOnClickListener { advanceWaypoint() }

        // Restore nav active state
        val prefs2 = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        navActive = prefs2?.getBoolean(PREF_NAV_ACTIVE, false) ?: false
        updateWaypointNavBar()

        map.addOnCameraMoveListener { updateCompass() }
        map.addOnCameraIdleListener {
            updateCompass()
            // When user manually zooms (pinch/double-tap), record their preferred zoom
            if (autoZoomLevel > 0 &&
                map.cameraPosition.zoom > 0 &&
                !userDragged) {
                // Only update base zoom if not in a follow-mode animation
                // (follow-mode animations set the target themselves)
            }
        }
        map.addOnCameraMoveStartedListener { reason ->
            if (reason == MapboxMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                // User manually moved/zoomed — record their zoom preference
                userBaseZoom = map.cameraPosition.zoom
                if (followMode != FollowMode.FREE) {
                    userDragged = true
                    if (autoRecenterEnabled) scheduleAutoRecenter()
                }
            }
        }
    }

    private fun scheduleAutoRecenter() {
        recenterRunnable?.let { recenterHandler.removeCallbacks(it) }
        recenterRunnable = Runnable {
            userDragged = false
            val gps = lastKnownGpsPoint ?: return@Runnable
            mapboxMap?.animateCamera(CameraUpdateFactory.newLatLng(gps), 800)
        }
        val delaySec = context?.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
            ?.getInt(PREF_RECENTER_DELAY, 3) ?: 3
        recenterHandler.postDelayed(recenterRunnable!!, delaySec * 1000L)
    }

    private fun lockScreen() {
        isScreenLocked = true
        _binding?.lockOverlay?.visibility = View.VISIBLE
        ImageViewCompat.setImageTintList(
            _binding!!.btnLock,
            android.content.res.ColorStateList.valueOf(Color.parseColor("#FFD600"))
        )
    }

    /** Add an offline map and return its key. Returns null on failure. */
    fun addOfflineMap(path: String, displayName: String): String? {
        val index = if (offlineMaps.isEmpty()) 0 else offlineMaps.maxOf { it.index } + 1
        val key = "${OFFLINE_TILE_KEY}_$index"
        ensureTileServer()
        val ok = tileServer?.openDatabase(index, path) ?: false
        if (!ok) return null
        val info = OfflineMapInfo(key, displayName, path)
        offlineMaps.add(info)
        tileSources[key] = TileSource(displayName, listOf("http://127.0.0.1:$TILE_SERVER_PORT/$index/{z}/{x}/{y}.png"))
        saveOfflineMapsToPrefs()
        return key
    }

    fun removeOfflineMap(key: String) {
        val info = offlineMaps.find { it.key == key } ?: return
        tileServer?.closeDatabase(info.index)
        offlineMaps.remove(info)
        tileSources.remove(key)
        if (currentTileKey == key) loadTileStyle("osm", currentOverlayKey)
        saveOfflineMapsToPrefs()
        // Delete the actual file to free storage
        java.io.File(info.path).takeIf { it.exists() }?.delete()
    }

    fun getOfflineMaps(): List<OfflineMapInfo> = offlineMaps.toList()

    private fun saveOfflineMapsToPrefs() {
        val arr = JSONArray()
        offlineMaps.forEach { m ->
            arr.put(JSONObject().put("key", m.key).put("name", m.name).put("path", m.path))
        }
        context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)?.edit()
            ?.putString(PREF_OFFLINE_MAPS_JSON, arr.toString())?.apply()
    }

    private fun loadOfflineMapsFromPrefs() {
        val prefs = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) ?: return
        val json = prefs.getString(PREF_OFFLINE_MAPS_JSON, null)
            ?: prefs.getString(PREF_OFFLINE_MAP_PATH, null)?.let { legacyPath ->
                // Migrate legacy single-map pref
                val name = legacyPath.substringAfterLast('/')
                JSONArray().put(JSONObject().put("key","${OFFLINE_TILE_KEY}_0").put("name",name).put("path",legacyPath)).toString()
            } ?: return
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val key = obj.getString("key")
                val name = obj.getString("name")
                val path = obj.getString("path")
                if (java.io.File(path).exists()) {
                    val idx = key.removePrefix("${OFFLINE_TILE_KEY}_").toIntOrNull() ?: continue
                    ensureTileServer()
                    if (tileServer?.openDatabase(idx, path) == true) {
                        offlineMaps.add(OfflineMapInfo(key, name, path))
                        tileSources[key] = TileSource(name, listOf("http://127.0.0.1:$TILE_SERVER_PORT/$idx/{z}/{x}/{y}.png"))
                    }
                }
            }
        } catch (_: Exception) {}
    }

    private fun ensureTileServer() {
        if (tileServer != null) return
        try {
            val server = TileServer(TILE_SERVER_PORT)
            server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            tileServer = server
        } catch (e: Exception) {
            Log.e("TileServer", "Failed to start tile server: ${e.message}")
        }
    }

    fun setLoadedTrackVisible(visible: Boolean) {
        mapboxMap?.style?.getLayer(LOADED_TRACK_LAYER_ID)
            ?.setProperties(PropertyFactory.visibility(if (visible) "visible" else "none"))
        context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)?.edit()
            ?.putBoolean(PREF_LOADED_TRACK_VISIBLE, visible)?.apply()
    }

    fun setLoadedWpVisible(visible: Boolean) {
        mapboxMap?.style?.getLayer(WP_LABEL_LAYER_ID)
            ?.setProperties(PropertyFactory.visibility(if (visible) "visible" else "none"))
        context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)?.edit()
            ?.putBoolean(PREF_LOADED_WP_VISIBLE, visible)?.apply()
    }

    fun applyTrackStyle() {
        val prefs = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) ?: return
        val color = prefs.getString(PREF_TRACK_COLOR, DEFAULT_TRACK_COLOR) ?: DEFAULT_TRACK_COLOR
        val width = prefs.getFloat(PREF_TRACK_WIDTH, DEFAULT_TRACK_WIDTH)
        mapboxMap?.style?.getLayerAs<com.mapbox.mapboxsdk.style.layers.LineLayer>(TRACK_LAYER_ID)
            ?.setProperties(PropertyFactory.lineColor(color), PropertyFactory.lineWidth(width))
    }

    fun applyLoadedTrackStyle() {
        val prefs = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) ?: return
        val color = prefs.getString(PREF_LOADED_TRACK_COLOR, DEFAULT_LOADED_TRACK_COLOR) ?: DEFAULT_LOADED_TRACK_COLOR
        val width = prefs.getFloat(PREF_LOADED_TRACK_WIDTH, DEFAULT_LOADED_TRACK_WIDTH)
        mapboxMap?.style?.getLayerAs<com.mapbox.mapboxsdk.style.layers.LineLayer>(LOADED_TRACK_LAYER_ID)
            ?.setProperties(PropertyFactory.lineColor(color), PropertyFactory.lineWidth(width))
    }

    fun hasLoadedTrack() = loadedTrackPoints.isNotEmpty()

    fun unlockScreen() {
        isScreenLocked = false
        _binding?.lockOverlay?.visibility = View.GONE
        ImageViewCompat.setImageTintList(
            _binding!!.btnLock,
            android.content.res.ColorStateList.valueOf(Color.WHITE)
        )
    }

    fun loadTrack(points: List<Pair<Double, Double>>) {
        loadedTrackPoints.clear()
        loadedTrackPoints.addAll(points.map { LatLng(it.first, it.second) })
        updateLoadedTrackOnMap()
        applyLoadedTrackStyle()
        if (points.isNotEmpty()) {
            mapboxMap?.animateCamera(
                CameraUpdateFactory.newLatLngZoom(loadedTrackPoints.first(), 12.0), 1000
            )
            Toast.makeText(context, "Трек загружен: ${points.size} точек", Toast.LENGTH_SHORT).show()
        }
        saveTrackToPrefs()
    }

    private fun updateLoadedTrackOnMap() {
        val style = mapboxMap?.style ?: return
        if (loadedTrackPoints.size < 2) return
        val coords = JSONArray()
        loadedTrackPoints.forEach { coords.put(JSONArray().put(it.longitude).put(it.latitude)) }
        style.getSourceAs<GeoJsonSource>(LOADED_TRACK_SOURCE_ID)?.setGeoJson(
            JSONObject().put("type", "Feature")
                .put("geometry", JSONObject().put("type", "LineString").put("coordinates", coords))
                .put("properties", JSONObject()).toString()
        )
    }

    private fun advanceWaypoint() {
        if (waypoints.isEmpty()) return
        activeWpIndex = (activeWpIndex + 1) % waypoints.size
        wasInApproachRadius = false  // reset so approach sound fires for new КП
        context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)?.let { prefs ->
            prefs.edit().putInt(PREF_ACTIVE_WP_INDEX, activeWpIndex).apply()
            if (prefs.getBoolean(PREF_SOUND_TAKEN, true)) playWaypointTakenSound()
        }
        updateNavLine()
        updateWaypointNavBar()
        Toast.makeText(context, "КП ${waypoints[activeWpIndex].index}: ${waypoints[activeWpIndex].name}", Toast.LENGTH_SHORT).show()
        // Immediately refresh distance widget with new target
        val b = _binding ?: return
        val wp = waypoints.getOrNull(activeWpIndex)
        if (wp != null) {
            val gps = lastKnownGpsPoint
            if (gps != null) {
                val distM = distanceM(gps, LatLng(wp.lat, wp.lon))
                b.widgetNextCp.text = if (distM < 1000) "${distM.toInt()}м" else String.format("%.1fкм", distM / 1000)
            } else {
                b.widgetNextCp.text = "--"
            }
            b.widgetNextCpName.text = wp.name.takeIf { it.isNotBlank() } ?: "КП ${activeWpIndex + 1}"
        }
    }

    private fun addWaypointAtCurrentPosition() {
        val pos = lastKnownGpsPoint
        if (pos == null) {
            Toast.makeText(context, "GPS недоступен", Toast.LENGTH_SHORT).show()
            return
        }
        val newIndex = waypoints.size
        val newWp = Waypoint(
            lat = pos.latitude,
            lon = pos.longitude,
            name = "КП ${newIndex + 1}",
            index = newIndex,
            description = ""
        )
        waypoints.add(newWp)
        // Save to prefs so name persists
        context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.edit()?.putString(PREF_LOADED_WP_NAME, "КП: вручную (${waypoints.size})")?.apply()
        updateWaypointsOnMap()
        updateWaypointNavBar()
        Toast.makeText(context, "Добавлен КП ${newIndex + 1}", Toast.LENGTH_SHORT).show()
    }

    fun prevWaypoint() {
        if (waypoints.isEmpty()) return
        activeWpIndex = if (activeWpIndex > 0) activeWpIndex - 1 else waypoints.size - 1
        context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.edit()?.putInt(PREF_ACTIVE_WP_INDEX, activeWpIndex)?.apply()
        updateNavLine()
        updateWaypointNavBar()
        Toast.makeText(context, "КП ${waypoints[activeWpIndex].index}: ${waypoints[activeWpIndex].name}", Toast.LENGTH_SHORT).show()
        // Immediately refresh distance widget with new target
        val b = _binding ?: return
        val wp = waypoints.getOrNull(activeWpIndex)
        if (wp != null) {
            val gps = lastKnownGpsPoint
            if (gps != null) {
                val distM = distanceM(gps, LatLng(wp.lat, wp.lon))
                b.widgetNextCp.text = if (distM < 1000) "${distM.toInt()}м" else String.format("%.1fкм", distM / 1000)
            } else {
                b.widgetNextCp.text = "--"
            }
            b.widgetNextCpName.text = wp.name.takeIf { it.isNotBlank() } ?: "КП ${activeWpIndex + 1}"
        }
    }

    fun startNavigation() {
        navActive = true
        context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)?.edit()
            ?.putBoolean(PREF_NAV_ACTIVE, true)?.apply()
        updateWaypointNavBar()
    }

    fun stopNavigation() {
        navActive = false
        context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)?.edit()
            ?.putBoolean(PREF_NAV_ACTIVE, false)?.apply()
        updateWaypointNavBar()
    }

    fun updateWaypointNavBar() {
        val b = _binding ?: return
        val show = navActive && waypoints.isNotEmpty()
        b.waypointNavBar.visibility = if (show) View.VISIBLE else View.GONE
        if (show) {
            val wp = waypoints.getOrNull(activeWpIndex)
            val name = wp?.name?.takeIf { it.isNotBlank() } ?: "КП ${activeWpIndex + 1}"
            b.txtWpNavInfo.text = "КП ${activeWpIndex + 1}/${waypoints.size}: $name"
        }
    }

    private fun saveWaypointsToPrefs() {
        val prefs = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) ?: return
        if (waypoints.isEmpty()) {
            prefs.edit().remove(PREF_SAVED_WAYPOINTS_JSON).apply()
            return
        }
        val arr = org.json.JSONArray()
        waypoints.forEach { wp ->
            arr.put(org.json.JSONObject()
                .put("name", wp.name)
                .put("lat", wp.lat)
                .put("lon", wp.lon)
                .put("index", wp.index)
                .put("description", wp.description))
        }
        prefs.edit()
            .putString(PREF_SAVED_WAYPOINTS_JSON, arr.toString())
            .putInt(PREF_ACTIVE_WP_INDEX, activeWpIndex)
            .apply()
    }

    private fun saveTrackToPrefs() {
        val prefs = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) ?: return
        if (loadedTrackPoints.isEmpty()) {
            prefs.edit().remove(PREF_SAVED_TRACK_JSON).apply()
            return
        }
        // Save up to 5000 points to avoid prefs size limits
        val pts = if (loadedTrackPoints.size > 5000) loadedTrackPoints.take(5000) else loadedTrackPoints
        val arr = org.json.JSONArray()
        pts.forEach { arr.put(org.json.JSONArray().put(it.latitude).put(it.longitude)) }
        prefs.edit().putString(PREF_SAVED_TRACK_JSON, arr.toString()).apply()
    }

    private fun restoreWaypointsFromPrefs() {
        val prefs = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) ?: return
        val json = prefs.getString(PREF_SAVED_WAYPOINTS_JSON, null) ?: return
        try {
            val arr = org.json.JSONArray(json)
            val restored = mutableListOf<Waypoint>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                restored.add(Waypoint(
                    name = o.optString("name", ""),
                    lat = o.getDouble("lat"),
                    lon = o.getDouble("lon"),
                    index = o.optInt("index", i + 1),
                    description = o.optString("description", "")
                ))
            }
            if (restored.isNotEmpty()) {
                waypoints.clear()
                waypoints.addAll(restored)
                activeWpIndex = prefs.getInt(PREF_ACTIVE_WP_INDEX, 0).coerceIn(0, restored.size - 1)
            }
        } catch (e: Exception) {
            Log.w("MapFragment", "Failed to restore waypoints: ${e.message}")
        }
    }

    private fun restoreTrackFromPrefs() {
        val prefs = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) ?: return
        val json = prefs.getString(PREF_SAVED_TRACK_JSON, null) ?: return
        try {
            val arr = org.json.JSONArray(json)
            val pts = mutableListOf<com.mapbox.mapboxsdk.geometry.LatLng>()
            for (i in 0 until arr.length()) {
                val pt = arr.getJSONArray(i)
                pts.add(com.mapbox.mapboxsdk.geometry.LatLng(pt.getDouble(0), pt.getDouble(1)))
            }
            if (pts.isNotEmpty()) {
                loadedTrackPoints.clear()
                loadedTrackPoints.addAll(pts)
            }
        } catch (e: Exception) {
            Log.w("MapFragment", "Failed to restore track: ${e.message}")
        }
    }

    private fun calcRemainingKm(currentPos: LatLng): Double {
        if (waypoints.isEmpty() || activeWpIndex >= waypoints.size) return 0.0
        var totalM = 0.0
        val curLoc = android.location.Location("").apply {
            latitude = currentPos.latitude; longitude = currentPos.longitude
        }
        val firstWp = waypoints[activeWpIndex]
        val firstLoc = android.location.Location("").apply {
            latitude = firstWp.lat; longitude = firstWp.lon
        }
        totalM += curLoc.distanceTo(firstLoc)
        for (i in activeWpIndex until waypoints.size - 1) {
            val a = android.location.Location("").apply {
                latitude = waypoints[i].lat; longitude = waypoints[i].lon
            }
            val bLoc = android.location.Location("").apply {
                latitude = waypoints[i + 1].lat; longitude = waypoints[i + 1].lon
            }
            totalM += a.distanceTo(bLoc)
        }
        return totalM / 1000.0
    }

    @SuppressLint("MissingPermission")
    private fun setupLocationTracking(map: MapboxMap) {
        val engine = map.locationComponent.locationEngine ?: return
        val intervalSec = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.getInt(PREF_TRACK_INTERVAL, 1) ?: 1
        val intervalMs = (intervalSec.coerceIn(1, 60) * 1000L)
        engine.requestLocationUpdates(
            com.mapbox.mapboxsdk.location.engine.LocationEngineRequest.Builder(intervalMs)
                .setPriority(com.mapbox.mapboxsdk.location.engine.LocationEngineRequest.PRIORITY_HIGH_ACCURACY)
                .setMaxWaitTime(intervalMs * 2)
                .build(),
            object : com.mapbox.mapboxsdk.location.engine.LocationEngineCallback<com.mapbox.mapboxsdk.location.engine.LocationEngineResult> {
                override fun onSuccess(result: com.mapbox.mapboxsdk.location.engine.LocationEngineResult) {
                    val loc = result.lastLocation ?: return
                    val newPoint = LatLng(loc.latitude, loc.longitude)
                    lastKnownGpsPoint = newPoint
                    val b = _binding ?: return

                    // Первый GPS-фикс — прыгаем на зум 12
                    if (!initialZoomDone) {
                        initialZoomDone = true
                        mapboxMap?.animateCamera(
                            CameraUpdateFactory.newLatLngZoom(newPoint, 12.0), 1000
                        )
                    }

                    // Smooth bearing via EMA — eliminates GPS bearing jitter
                    val bearing = smoothBearing(loc.bearing).toFloat()

                    // Update custom GPS arrow with smoothed bearing
                    updateGpsArrow(loc.latitude, loc.longitude, bearing)

                    // Move camera in follow modes — animated for smooth driving experience
                    // userDragged = true means user panned map manually; we pause until recenter
                    if (initialZoomDone && !userDragged) {
                        val speedKmh = loc.speed * 3.6

                        // 3D tilt: 0° stopped → 45° at 60+ km/h (only in FOLLOW_COURSE if enabled)
                        val tilt = if (tilt3dEnabled && followMode == FollowMode.FOLLOW_COURSE)
                            (speedKmh.coerceIn(0.0, 60.0) / 60.0 * 45.0)
                        else 0.0

                        // Auto-zoom: relative to user's preferred zoom (userBaseZoom)
                        // At speed 0 → zoom = userBaseZoom (unchanged)
                        // At 120 km/h, level 10 → zoom = userBaseZoom - 4.0
                        // At 120 km/h, level 1  → zoom = userBaseZoom - 0.4
                        val targetZoom = if (autoZoomLevel > 0) {
                            val base = if (userBaseZoom > 0) userBaseZoom
                                       else mapboxMap?.cameraPosition?.zoom ?: 14.0
                            val maxDelta = autoZoomLevel * 0.4  // level 10 = max 4 zoom levels
                            val delta = speedKmh.coerceIn(0.0, 120.0) / 120.0 * maxDelta
                            (base - delta).coerceIn(base - maxDelta, base)
                        } else null

                        when (followMode) {
                            FollowMode.FOLLOW_NORTH -> {
                                val builder = com.mapbox.mapboxsdk.camera.CameraPosition.Builder()
                                    .target(newPoint)
                                    .bearing(0.0)
                                    .tilt(tilt)
                                    .padding(doubleArrayOf(0.0, cameraTopPadding.toDouble(), 0.0, 0.0))
                                if (targetZoom != null) builder.zoom(targetZoom)
                                mapboxMap?.animateCamera(
                                    CameraUpdateFactory.newCameraPosition(builder.build()), 900)
                            }
                            FollowMode.FOLLOW_COURSE -> {
                                val builder = com.mapbox.mapboxsdk.camera.CameraPosition.Builder()
                                    .target(newPoint)
                                    .bearing(bearing.toDouble())
                                    .tilt(tilt)
                                    .padding(doubleArrayOf(0.0, cameraTopPadding.toDouble(), 0.0, 0.0))
                                if (targetZoom != null) builder.zoom(targetZoom)
                                mapboxMap?.animateCamera(
                                    CameraUpdateFactory.newCameraPosition(builder.build()), 900)
                            }
                            FollowMode.FREE -> { /* user controls camera */ }
                        }
                    }

                    // Update widgets
                    val speedKmhInt = (loc.speed * 3.6).toInt()
                    b.widgetSpeed.text = if (loc.speed > 0.5f) speedKmhInt.toString() else "--"
                    b.widgetBearing.text = "${bearing.toInt()}°"
                    b.widgetDirectionArrow.rotation = bearing
                    if (loc.hasAltitude()) b.widgetAltitude.text = loc.altitude.toInt().toString()

                    // Next CP distance + name + remaining km + auto-advance
                    if (waypoints.isNotEmpty() && activeWpIndex < waypoints.size) {
                        val wp = waypoints[activeWpIndex]
                        val distM = distanceM(newPoint, LatLng(wp.lat, wp.lon))
                        b.widgetNextCp.text = if (distM < 1000) "${distM.toInt()}м" else String.format("%.1f", distM / 1000)
                        b.widgetNextCpName.text = wp.name.takeIf { it.isNotBlank() } ?: "КП ${activeWpIndex + 1}"
                        val remKm = calcRemainingKm(newPoint)
                        b.widgetRemainKm.text = if (remKm < 10) String.format("%.1f", remKm) else remKm.toInt().toString()
                        // Auto-advance when within approach radius (only during active navigation)
                        val ctx = context
                        val prefs = ctx?.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
                        val approachRadius = prefs?.getInt(PREF_WP_APPROACH_RADIUS, DEFAULT_WP_APPROACH_RADIUS)?.toDouble()
                            ?: DEFAULT_WP_APPROACH_RADIUS.toDouble()
                        val inRadius = distM <= approachRadius
                        // Sound: entering radius (fire once per waypoint)
                        if (inRadius && !wasInApproachRadius) {
                            if (prefs?.getBoolean(PREF_SOUND_APPROACH, true) == true) playApproachSound()
                        }
                        wasInApproachRadius = inRadius
                        if (navActive && inRadius) {
                            val nextIndex = activeWpIndex + 1
                            if (nextIndex < waypoints.size) {
                                advanceWaypoint()
                            } else {
                                stopNavigation()
                                Toast.makeText(ctx, "Маршрут завершён!", Toast.LENGTH_LONG).show()
                            }
                        }
                    } else {
                        b.widgetNextCpName.text = "--"
                        b.widgetRemainKm.text = "--"
                    }

                    updateNavLine()

                    // Запись трека — выполняется в TrackingService (фоновая служба)
                    // Здесь синхронно обновляем трек и стрелки направления на карте
                    if (isRecording) {
                        val svcPoints = TrackingService.trackPoints
                        if (svcPoints.size >= 2) {
                            val last = LatLng(svcPoints.last().first, svcPoints.last().second)
                            if (lastArrowLat == 0.0 || distanceM(LatLng(lastArrowLat, lastArrowLon), last) >= ARROW_DISTANCE_M) {
                                val prev = LatLng(svcPoints[svcPoints.size - 2].first, svcPoints[svcPoints.size - 2].second)
                                arrowPoints.add(Pair(last, bearingBetween(prev, last).toFloat()))
                                lastArrowLat = svcPoints.last().first; lastArrowLon = svcPoints.last().second
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
        val ctx = context ?: return
        if (!isRecording) {
            // Старт — запускаем foreground service
            arrowPoints.clear(); lastArrowLat = 0.0; lastArrowLon = 0.0
            val intent = Intent(ctx, TrackingService::class.java).apply { action = TrackingService.ACTION_START }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(intent)
            else ctx.startService(intent)
            binding.btnRec.setImageResource(R.drawable.ic_rec)
            binding.widgetTrackLen.text = "0.0"
            binding.widgetChrono.text = "0:00"
            startChronoTicker()
            Toast.makeText(ctx, "⏺ Запись трека начата", Toast.LENGTH_SHORT).show()
        } else {
            // Стоп — останавливаем сервис (он сделает финальный авто-сейв в tmp файл)
            ctx.startService(Intent(ctx, TrackingService::class.java).apply { action = TrackingService.ACTION_STOP })
            stopChronoTicker()
            arrowPoints.clear()
            updateTrackOnMap()
            binding.btnRec.setImageResource(R.drawable.ic_rec_start)
            // Показываем диалог сохранения
            showSaveTrackDialog()
        }
    }

    // ─── Track save / resume ──────────────────────────────────────────────────

    /** Called when user stops recording — offer to save GPX */
    private fun showSaveTrackDialog() {
        val ctx = context ?: return
        val pts = TrackingService.trackPoints.toList()
        val kmStr = String.format("%.1f", TrackingService.trackLengthM / 1000)
        if (pts.size < 2) {
            Toast.makeText(ctx, "⏹ Запись остановлена (нет точек)", Toast.LENGTH_SHORT).show()
            clearTmpTrack()
            return
        }
        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle("Запись остановлена")
            .setMessage("${pts.size} точек • $kmStr км\n\nСохранить трек в файл?")
            .setPositiveButton("Сохранить") { _, _ -> saveTrackToFile(pts) }
            .setNegativeButton("Не сохранять") { _, _ -> clearTmpTrack() }
            .setCancelable(false)
            .show()
    }

    /** Check if app was killed during recording — offer resume / save / discard */
    private fun checkForUnfinishedTrack() {
        val ctx = context ?: return
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(PREF_WAS_RECORDING, false)) return
        val tmpFile = java.io.File(ctx.filesDir, TRACK_TMP_FILENAME)
        if (!tmpFile.exists() || tmpFile.length() == 0L) {
            prefs.edit().putBoolean(PREF_WAS_RECORDING, false).apply()
            return
        }
        val savedPoints = try {
            GpxParser.parseGpxFull(tmpFile.inputStream()).trackPoints
        } catch (_: Exception) {
            tmpFile.delete()
            prefs.edit().putBoolean(PREF_WAS_RECORDING, false).apply()
            return
        }
        if (savedPoints.isEmpty()) {
            tmpFile.delete()
            prefs.edit().putBoolean(PREF_WAS_RECORDING, false).apply()
            return
        }
        val km = calcTrackKm(savedPoints)
        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle("Незаконченная запись")
            .setMessage("Найден трек: ${savedPoints.size} точек, ${String.format("%.1f", km)} км\n\nЧто сделать?")
            .setPositiveButton("Продолжить запись") { _, _ ->
                // Restore points and resume recording
                TrackingService.trackPoints.clear()
                TrackingService.trackPoints.addAll(savedPoints)
                TrackingService.trackLengthM = km * 1000.0
                val intent = Intent(ctx, TrackingService::class.java).apply { action = TrackingService.ACTION_RESUME }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(intent)
                else ctx.startService(intent)
                _binding?.btnRec?.setImageResource(R.drawable.ic_rec)
                _binding?.widgetChrono?.text = "0:00"
                startChronoTicker()
                updateTrackOnMap()
            }
            .setNeutralButton("Сохранить") { _, _ -> saveTrackToFile(savedPoints) }
            .setNegativeButton("Удалить") { _, _ -> clearTmpTrack() }
            .setCancelable(false)
            .show()
    }

    /** Save track points to GPX file in app's external tracks folder */
    fun saveTrackToFile(points: List<Pair<Double, Double>>? = null) {
        val ctx = context ?: return
        val pts = points ?: TrackingService.trackPoints.toList()
        if (pts.isEmpty()) {
            Toast.makeText(ctx, "Нет точек для сохранения", Toast.LENGTH_SHORT).show()
            return
        }
        val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault())
            .format(java.util.Date())
        val filename = "racenav_$timestamp.gpx"
        val gpxContent = GpxParser.writeGpx(pts, "RaceNav $timestamp")
        try {
            val dir = ctx.getExternalFilesDir("tracks")
            dir?.mkdirs()
            java.io.File(dir, filename).writeText(gpxContent)
            clearTmpTrack()
            Toast.makeText(ctx,
                "✅ Сохранён: Android/data/com.andreykoff.racenav/files/tracks/$filename",
                Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(ctx, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /** Delete temp file and clear was_recording flag */
    private fun clearTmpTrack() {
        val ctx = context ?: return
        java.io.File(ctx.filesDir, TRACK_TMP_FILENAME).takeIf { it.exists() }?.delete()
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(PREF_WAS_RECORDING, false).apply()
    }

    /** Calculate track length in km from list of lat/lon pairs */
    private fun calcTrackKm(pts: List<Pair<Double, Double>>): Double {
        if (pts.size < 2) return 0.0
        var m = 0.0
        for (i in 1 until pts.size) {
            val a = pts[i - 1]; val b = pts[i]
            val R = 6371000.0
            val lat1 = Math.toRadians(a.first); val lat2 = Math.toRadians(b.first)
            val dLat = lat2 - lat1; val dLon = Math.toRadians(b.second - a.second)
            val x = Math.sin(dLat / 2) * Math.sin(dLat / 2) + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2) * Math.sin(dLon / 2)
            m += 2 * R * Math.asin(Math.sqrt(x))
        }
        return m / 1000.0
    }

    // ─────────────────────────────────────────────────────────────────────────

    private fun startChronoTicker() {
        chronoRunnable?.let { chronoHandler.removeCallbacks(it) }
        val r = object : Runnable {
            override fun run() {
                if (!isRecording) return
                val elapsed = (System.currentTimeMillis() - recordingStartMs) / 1000L
                val h = elapsed / 3600; val m = (elapsed % 3600) / 60; val s = elapsed % 60
                _binding?.widgetChrono?.text = if (h > 0) String.format("%d:%02d:%02d", h, m, s)
                                               else String.format("%d:%02d", m, s)
                chronoHandler.postDelayed(this, 1000)
            }
        }
        chronoRunnable = r
        chronoHandler.post(r)
    }

    private fun stopChronoTicker() {
        chronoRunnable?.let { chronoHandler.removeCallbacks(it) }
        chronoRunnable = null
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

    fun applyFollowMode() {
        val lc = mapboxMap?.locationComponent ?: return
        // Always NONE — we drive the camera manually in the GPS callback to avoid cursor jumps
        lc.cameraMode = CameraMode.NONE
        lc.renderMode = RenderMode.GPS
        userDragged = false  // reset drag flag when follow mode changes
        recenterRunnable?.let { recenterHandler.removeCallbacks(it) }
    }

    fun applyCursorOffset() {
        val position = context?.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
            ?.getInt(PREF_CURSOR_OFFSET, 1) ?: 1  // 1-10
        val screenHeight = resources.displayMetrics.heightPixels
        // position 1 = no offset (cursor at center), position 10 = cursor near bottom
        // top padding shifts the camera center DOWN → cursor appears lower on screen
        val fraction = (position - 1) / 9f * 0.42f  // up to 42% of screen height as top padding
        cameraTopPadding = (screenHeight * fraction).toInt()
        mapboxMap?.setPadding(0, cameraTopPadding, 0, 0)
    }

    private fun playApproachSound() {
        try {
            val tg = android.media.ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION, 90)
            tg.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 400)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ tg.release() }, 500)
        } catch (e: Exception) { /* ignore if audio unavailable */ }
    }

    private fun playWaypointTakenSound() {
        try {
            val tg = android.media.ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION, 100)
            // Two beeps: short + longer
            tg.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 150)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                tg.startTone(android.media.ToneGenerator.TONE_PROP_BEEP2, 400)
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ tg.release() }, 500)
            }, 250)
        } catch (e: Exception) { /* ignore if audio unavailable */ }
    }

    fun applyCacheSize() {
        val ctx = context ?: return
        val mb = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(PREF_TILE_CACHE_MB, 200).coerceIn(100, 4000)
        val bytes = mb.toLong() * 1024 * 1024
        try {
            com.mapbox.mapboxsdk.offline.OfflineManager.getInstance(ctx)
                .setMaximumAmbientCacheSize(bytes,
                    object : com.mapbox.mapboxsdk.offline.OfflineManager.FileSourceCallback {
                        override fun onSuccess() {}
                        override fun onError(message: String) {
                            Log.w("TileCache", "setMaximumAmbientCacheSize error: $message")
                        }
                    })
        } catch (e: Exception) {
            Log.w("TileCache", "Cache size API not available: ${e.message}")
        }
    }

    private fun updateCompass() {
        _binding?.compassView?.rotation = (-(mapboxMap?.cameraPosition?.bearing ?: 0.0)).toFloat()
    }

    private fun showLayerPicker() {
        val dialog = BottomSheetDialog(requireContext(), R.style.BottomSheetTheme)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_layers, null)
        dialog.setContentView(view)

        // Online base layers (left column)
        val baseGroup = view.findViewById<RadioGroup>(R.id.layerRadioGroup)
        tileSources.filter { !it.key.startsWith(OFFLINE_TILE_KEY) }.forEach { (key, source) ->
            baseGroup.addView(RadioButton(requireContext()).apply {
                text = source.label; tag = key
                isChecked = key == currentTileKey
                setTextColor(0xFFFFFFFF.toInt()); textSize = 13f
                setPadding(16, 14, 16, 14); id = View.generateViewId()
            })
        }
        baseGroup.setOnCheckedChangeListener { group, id ->
            val key = group.findViewById<RadioButton>(id)?.tag as? String ?: return@setOnCheckedChangeListener
            loadTileStyle(key, currentOverlayKey)
            dialog.dismiss()
        }

        // Offline layers (right column)
        val offlineGroup = view.findViewById<RadioGroup>(R.id.offlineRadioGroup)
        if (offlineMaps.isEmpty()) {
            offlineGroup.addView(android.widget.TextView(requireContext()).apply {
                text = "Не загружено"; setTextColor(0xFF888888.toInt()); textSize = 12f
                setPadding(16, 14, 16, 14)
            })
        } else {
            offlineMaps.forEach { info ->
                offlineGroup.addView(RadioButton(requireContext()).apply {
                    text = info.name; tag = info.key
                    isChecked = info.key == currentTileKey
                    setTextColor(0xFFFFFFFF.toInt()); textSize = 13f
                    setPadding(16, 14, 16, 14); id = View.generateViewId()
                })
            }
            offlineGroup.setOnCheckedChangeListener { group, id ->
                val key = group.findViewById<RadioButton>(id)?.tag as? String ?: return@setOnCheckedChangeListener
                loadTileStyle(key, currentOverlayKey)
                dialog.dismiss()
            }
        }

        // Overlay layers
        val overlayGroup = view.findViewById<RadioGroup>(R.id.overlayRadioGroup)
        overlaySources.forEach { (key, source) ->
            overlayGroup.addView(RadioButton(requireContext()).apply {
                text = source.label; tag = key
                isChecked = key == currentOverlayKey
                setTextColor(if (key == "none") 0xFF888888.toInt() else 0xFFFFFFFF.toInt())
                textSize = 13f; setPadding(16, 14, 16, 14); id = View.generateViewId()
            })
        }
        overlayGroup.setOnCheckedChangeListener { group, id ->
            val key = group.findViewById<RadioButton>(id)?.tag as? String ?: return@setOnCheckedChangeListener
            loadTileStyle(currentTileKey, key)
            dialog.dismiss()
        }

        dialog.show()
    }

    /** Simple triangle navigation arrow: tip up, white border, solid fill */
    private fun makeArrowBitmap(sizeDp: Int, color: Int): Bitmap {
        val density = resources.displayMetrics.density
        val size = (sizeDp * density).toInt().coerceAtLeast(24)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val cx = size / 2f
        val cy = size / 2f
        val rad = size / 2f * 0.88f

        // Triangle with small V-notch at bottom and slightly wider base
        val arrowPath = Path().apply {
            moveTo(cx, cy - rad)                        // tip (top center)
            lineTo(cx + rad * 0.72f, cy + rad)          // bottom-right corner
            lineTo(cx + rad * 0.18f, cy + rad * 0.70f)  // notch right
            lineTo(cx,               cy + rad * 0.82f)  // notch center (V tip)
            lineTo(cx - rad * 0.18f, cy + rad * 0.70f)  // notch left
            lineTo(cx - rad * 0.72f, cy + rad)          // bottom-left corner
            close()
        }

        // 1. White border
        canvas.drawPath(arrowPath, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = rad * 0.18f
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
        })

        // 2. Solid fill
        canvas.drawPath(arrowPath, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.FILL
        })

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

    /** Pull waypoints/tracks from TND Sync server and load them into map */
    fun syncPull(apiKey: String, onResult: (ok: Boolean, message: String) -> Unit) {
        lifecycleScope.launch {
            try {
                val json = withContext(Dispatchers.IO) {
                    val conn = java.net.URL("$SYNC_BASE_URL/api/state").openConnection() as java.net.HttpURLConnection
                    conn.requestMethod = "GET"
                    conn.setRequestProperty("X-Api-Key", apiKey)
                    conn.setRequestProperty("X-Client-Id", "android")
                    conn.connectTimeout = 10000
                    conn.readTimeout = 10000
                    if (conn.responseCode != 200) throw Exception("HTTP ${conn.responseCode}")
                    JSONObject(conn.inputStream.bufferedReader().readText())
                }
                val syncWaypoints = mutableListOf<Waypoint>()
                val wptArray = json.optJSONArray("waypoints")
                if (wptArray != null) {
                    for (i in 0 until wptArray.length()) {
                        val w = wptArray.getJSONObject(i)
                        syncWaypoints.add(Waypoint(
                            lat = w.getDouble("lat"),
                            lon = w.getDouble("lng"),
                            name = w.optString("name", "КП ${i + 1}"),
                            index = i,
                            description = w.optString("desc", "")
                        ))
                    }
                }
                val syncTrackPts = mutableListOf<Pair<Double, Double>>()
                val tracksArray = json.optJSONArray("tracks")
                if (tracksArray != null) {
                    for (i in 0 until tracksArray.length()) {
                        val t = tracksArray.getJSONObject(i)
                        val pts = t.optJSONArray("points")
                        if (pts != null) {
                            for (j in 0 until pts.length()) {
                                val p = pts.getJSONObject(j)
                                syncTrackPts.add(Pair(p.getDouble("lat"), p.getDouble("lng")))
                            }
                        }
                    }
                }
                // Routes — if no tracks, use routes as track polyline + extract labeled points as waypoints
                var syncRoutesCount = 0
                val routesArray = json.optJSONArray("routes")
                if (routesArray != null && routesArray.length() > 0) {
                    syncRoutesCount = routesArray.length()
                    for (i in 0 until routesArray.length()) {
                        val r = routesArray.getJSONObject(i)
                        val pts = r.optJSONArray("points") ?: continue
                        val labels = r.optJSONArray("labels")
                        for (j in 0 until pts.length()) {
                            val p = pts.getJSONObject(j)
                            if (syncTrackPts.isEmpty() || i > 0) {
                                // Only add to track if no tracks already, or appending subsequent routes
                                syncTrackPts.add(Pair(p.getDouble("lat"), p.getDouble("lng")))
                            }
                            // If waypoints not loaded from waypoints[], use labeled route points
                            val label = labels?.optString(j, "")?.takeIf { it.isNotBlank() }
                            if (label != null && syncWaypoints.isEmpty()) {
                                syncWaypoints.add(Waypoint(
                                    lat = p.getDouble("lat"),
                                    lon = p.getDouble("lng"),
                                    name = label,
                                    index = syncWaypoints.size,
                                    description = r.optString("name", "")
                                ))
                            }
                        }
                    }
                }
                withContext(Dispatchers.Main) {
                    val ctx = context ?: return@withContext
                    val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    if (syncWaypoints.isNotEmpty()) {
                        loadWaypoints(syncWaypoints)
                        prefs.edit().putString(PREF_LOADED_WP_NAME, "КП: синхронизация (${syncWaypoints.size})").apply()
                    }
                    if (syncTrackPts.isNotEmpty()) {
                        loadTrack(syncTrackPts)
                        prefs.edit().putString(PREF_LOADED_TRACK_NAME, "Трек: синхронизация (${syncTrackPts.size} точек)").apply()
                    }
                    val msg = buildString {
                        if (syncWaypoints.isNotEmpty()) append("${syncWaypoints.size} КП")
                        if (syncWaypoints.isNotEmpty() && syncTrackPts.isNotEmpty()) append(", ")
                        if (syncTrackPts.isNotEmpty()) append("трек (${syncTrackPts.size} точек)")
                        if (syncRoutesCount > 0 && syncWaypoints.isEmpty() && syncTrackPts.isEmpty()) append("$syncRoutesCount маршрутов")
                        if (isEmpty()) append("нет данных")
                    }
                    onResult(true, "Получено: $msg")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onResult(false, "Ошибка: ${e.message}")
                }
            }
        }
    }

    /** Push current recording track to TND Sync server */
    fun syncPush(apiKey: String, onResult: (ok: Boolean, message: String) -> Unit) {
        val pts = TrackingService.trackPoints.toList()
        if (pts.isEmpty()) {
            onResult(false, "Нет активного трека для отправки")
            return
        }
        lifecycleScope.launch {
            try {
                val pointsArray = JSONArray()
                pts.forEach { (lat, lon) ->
                    pointsArray.put(JSONObject().put("lat", lat).put("lng", lon))
                }
                val trackObj = JSONObject()
                    .put("id", 1).put("name", "Android трек")
                    .put("color", "#FF2200").put("width", 4)
                    .put("pointSize", 0).put("points", pointsArray)
                    .put("pointsData", JSONArray()).put("visible", true)
                val body = JSONObject()
                    .put("version", 1)
                    .put("waypoints", JSONArray())
                    .put("tracks", JSONArray().put(trackObj))
                    .put("routes", JSONArray())
                    .put("waypointSets", JSONArray())
                    .put("counters", JSONObject())
                withContext(Dispatchers.IO) {
                    val conn = java.net.URL("$SYNC_BASE_URL/api/state").openConnection() as java.net.HttpURLConnection
                    conn.requestMethod = "PUT"
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.setRequestProperty("X-Api-Key", apiKey)
                    conn.setRequestProperty("X-Client-Id", "android")
                    conn.connectTimeout = 10000
                    conn.readTimeout = 10000
                    conn.doOutput = true
                    val bodyBytes = body.toString().toByteArray(Charsets.UTF_8)
                    conn.outputStream.write(bodyBytes)
                    if (conn.responseCode != 200) throw Exception("HTTP ${conn.responseCode}")
                }
                withContext(Dispatchers.Main) {
                    onResult(true, "Отправлено ${pts.size} точек")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onResult(false, "Ошибка: ${e.message}")
                }
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        if (requestCode == 100 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            mapboxMap?.style?.let { style ->
                enableLocation(style)
                setupGpsArrowLayer(style)
            }
        }
    }

    override fun onStart() { super.onStart(); _binding?.mapView?.onStart() }
    override fun onResume() {
        super.onResume()
        _binding?.mapView?.onResume()
        applyFullscreenPref()
        applyWidgetPrefs()
        // Регистрируем приёмник GPS от сервиса
        val filter = IntentFilter(TrackingService.BROADCAST_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context?.registerReceiver(locationReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context?.registerReceiver(locationReceiver, filter)
        }
        // Синхронизируем UI если сервис пишет трек в фоне
        if (isRecording) {
            binding.btnRec.setImageResource(R.drawable.ic_rec)
            startChronoTicker()
            updateTrackOnMap()
            val lenKm = TrackingService.trackLengthM / 1000.0
            _binding?.widgetTrackLen?.text = if (lenKm < 10) String.format("%.1f", lenKm) else lenKm.toInt().toString()
        }
        updateWaypointNavBar()
    }

    override fun onPause() {
        super.onPause()
        _binding?.mapView?.onPause()
        try { context?.unregisterReceiver(locationReceiver) } catch (_: Exception) {}
    }
    override fun onStop() {
        super.onStop()
        _binding?.mapView?.onStop()
        // Save camera position so it's restored when coming back from Settings
        mapboxMap?.cameraPosition?.let { cam ->
            val target = cam.target ?: return@let
            context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)?.edit()
                ?.putFloat(PREF_CAMERA_LAT, target.latitude.toFloat())
                ?.putFloat(PREF_CAMERA_LON, target.longitude.toFloat())
                ?.putFloat(PREF_CAMERA_ZOOM, cam.zoom.toFloat())
                ?.apply()
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        _binding?.mapView?.onSaveInstanceState(outState)
        outState.putBoolean("screen_locked", isScreenLocked)
    }
    override fun onLowMemory() { super.onLowMemory(); _binding?.mapView?.onLowMemory() }
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        val isLandscape = newConfig.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        _binding?.bottomBar?.let { bar ->
            val px = (if (isLandscape) 44 else 68) * resources.displayMetrics.density
            bar.layoutParams.height = px.toInt()
            bar.requestLayout()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopChronoTicker(); stopTimeTicker()
        tileServer?.cleanup(); tileServer = null
        _binding?.mapView?.onDestroy(); _binding = null
    }
}
