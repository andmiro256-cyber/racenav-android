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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.ImageViewCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
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
import com.mapbox.mapboxsdk.style.layers.PropertyValue
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
    private var liveUsersPoller: LiveUsersPoller? = null
    var lastLiveDevices: List<LiveUsersPoller.LiveDevice> = emptyList()
        private set

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> if (uri != null) loadFileFromPicker(uri) }

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
    private var autoRecordDone = false  // prevent repeated auto-start on style change

    // BroadcastReceiver для получения GPS из TrackingService когда приложение на экране
    private val locationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != TrackingService.BROADCAST_LOCATION) return
            handleLocationUpdate(LocationUpdate(
                lat = intent.getDoubleExtra(TrackingService.EXTRA_LAT, 0.0),
                lon = intent.getDoubleExtra(TrackingService.EXTRA_LON, 0.0),
                speed = intent.getFloatExtra(TrackingService.EXTRA_SPEED, 0f),
                bearing = intent.getFloatExtra(TrackingService.EXTRA_BEARING, 0f),
                altitude = intent.getDoubleExtra(TrackingService.EXTRA_ALTITUDE, 0.0),
                hasSpeed = intent.getBooleanExtra(TrackingService.EXTRA_HAS_SPEED, false),
                hasAltitude = intent.getBooleanExtra(TrackingService.EXTRA_HAS_ALTITUDE, false)
            ))
        }
    }

    /** Shared handler for location updates from both BroadcastReceiver and StateFlow */
    private fun handleLocationUpdate(update: LocationUpdate) {
        val b = _binding ?: return

        // Обновляем трек на карте
        updateTrackOnMap()

        // Обновляем виджеты
        val speedKmh = (update.speed * 3.6).toInt()
        b.widgetSpeed.text = if (update.speed > 0.5f) speedKmh.toString() else "--"
        b.widgetBearing.text = "${update.bearing.toInt()}°"
        b.widgetDirectionArrow.rotation = update.bearing
        if (update.hasAltitude) b.widgetAltitude.text = update.altitude.toInt().toString()

        // Длина трека
        val lenKm = TrackingService.trackLengthM / 1000.0
        b.widgetTrackLen.text = if (lenKm < 10) String.format("%.1f", lenKm) else lenKm.toInt().toString()

        // Tripmaster
        Log.d("Tripmaster", "update: tripmasterLastPoint=$tripmasterLastPoint tripmasterDistM=$tripmasterDistM")
        val gpsPoint = LatLng(update.lat, update.lon)
        tripmasterLastPoint?.let { prev ->
            tripmasterDistM += distanceM(prev, gpsPoint)
        }
        tripmasterLastPoint = gpsPoint
        val tripKm = tripmasterDistM / 1000.0
        b.widgetTripmaster.text = if (tripKm < 10) String.format("%.1f", tripKm) else tripKm.toInt().toString()
    }
    // BroadcastReceiver для статуса синхронизации с сервером
    private val traccarStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != TraccarService.BROADCAST_TRACCAR_STATUS) return
            val statusName = intent.getStringExtra(TraccarService.EXTRA_TRACCAR_STATUS) ?: return
            val color = when (statusName) {
                "OK" -> 0xFF4CAF50.toInt()       // green
                "SYNCING" -> 0xFFFFEB3B.toInt()  // yellow
                "ERROR" -> 0xFFF44336.toInt()     // red
                else -> 0xFF888888.toInt()         // grey = idle
            }
            val statusText = when (statusName) {
                "OK" -> "онлайн"
                "SYNCING" -> "отправка"
                "ERROR" -> "ошибка"
                else -> "сервер"
            }
            // Update top bar server indicator
            _binding?.topBarServerDot?.background?.setTint(color)
            // Update server status widget in bottom bar
            _binding?.widgetServerDot?.background?.setTint(color)
            _binding?.widgetServerText?.text = statusText
            _binding?.widgetServerText?.setTextColor(color)
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
    private var bearingFrozen = true      // true = курсор заморожен (стоим)
    private var lastValidBearing = 0f     // последний валидный bearing для freeze
    private var tileServer: TileServer? = null
    private val offlineMaps = mutableListOf<OfflineMapInfo>()
    private var lastKnownGpsPoint: LatLng? = null
    fun getLastGpsPoint(): LatLng? = lastKnownGpsPoint
    private val recenterHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var recenterRunnable: Runnable? = null

    // Waypoints (КП) from GPX/WPT
    private val waypoints = mutableListOf<Waypoint>()
    private var activeWpIndex = 0  // current target CP index
    var navActive = false           // waypoint navigation bar visible
    private var cameraTopPadding = 0  // px; updated by applyCursorOffset(), applied in every camera move
    private var wasInApproachRadius = false  // track radius entry to fire sound once

    // User points — placed on map, editable name
    data class UserPoint(var name: String, val position: LatLng)
    private val userMarkers = mutableListOf<UserPoint>()

    // Tripmaster — resettable distance counter
    private var tripmasterDistM = 0.0
    private var tripmasterLastPoint: LatLng? = null

    // Download mode state
    private var isDownloadSelecting = false
    private var downloadFirstCorner: LatLng? = null
    private var downloadBounds: BoundsRect? = null
    private var downloadRectSource: GeoJsonSource? = null

    companion object {
        const val TRACK_SOURCE_ID = "track-source"
        const val TRACK_ARROWS_SOURCE_ID = "track-arrows-source"
        const val TRACK_LAYER_ID = "track-layer"
        const val TRACK_ARROWS_LAYER_ID = "track-arrows-layer"
        const val TRACK_ARROW_ICON = "track-arrow-icon"
        const val MAX_WAYPOINTS = 200
        const val WP_SOURCE_ID = "wp-source"
        const val WP_CIRCLE_LAYER_ID = "wp-circle-layer"
        const val WP_LAYER_ID = "wp-layer"
        const val WP_LABEL_LAYER_ID = "wp-label-layer"
        const val GPS_ARROW_SOURCE_ID = "gps-arrow-source"
        const val GPS_ARROW_LAYER_ID = "gps-arrow-layer"
        const val GPS_ACCURACY_SOURCE_ID = "gps-accuracy-source"
        const val GPS_ACCURACY_LAYER_ID = "gps-accuracy-layer"
        const val GPS_ARROW_ICON = "gps-arrow-icon"
        const val ARROW_DISTANCE_M = 80.0
        const val PREFS_NAME = "racenav_prefs"
        const val PREF_VOLUME_ZOOM = "volume_zoom_enabled"
        const val PREF_VOLUME_LOCK = "volume_lock_enabled"
        const val PREF_VOLUME_MAP_SWITCH = "volume_map_switch_enabled"  // default true
        const val PREF_FULLSCREEN = "fullscreen_enabled"
        const val PREF_MARKER_COLOR = "marker_color"
        const val PREF_MARKER_SIZE = "marker_size"
        const val DEFAULT_MARKER_COLOR = "#FF4444"
        const val DEFAULT_MARKER_SIZE = 3
        const val PREF_CAMERA_LAT = "camera_lat"
        const val PREF_CAMERA_LON = "camera_lon"
        const val PREF_CAMERA_ZOOM = "camera_zoom"
        const val PREF_AUTO_RECENTER = "auto_recenter"
        const val PREF_FOLLOW_MODE = "follow_mode"
        const val PREF_LAST_LAT = "last_lat"
        const val PREF_LAST_LON = "last_lon"
        const val PREF_LAST_BEARING = "last_bearing"
        const val PREF_KEEP_SCREEN = "keep_screen_on"
        const val PREF_ORIENTATION = "screen_orientation" // 0=auto, 1=portrait, 2=landscape
        const val PREF_TRACK_INTERVAL = "track_interval_sec"  // seconds, default 1
        const val PREF_TRACK_MIN_DISTANCE = "track_min_distance"  // meters, default 2
        const val PREF_TRACK_MIN_ACCURACY = "track_min_accuracy"  // meters, default 50
        const val PREF_TRACK_ONLY_MOVING = "track_only_moving"    // boolean, default false
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
        const val PREF_AUTO_RECORD    = "auto_record"        // bool: auto-start recording, default true
        const val PREF_WAS_RECORDING  = "was_recording"     // bool: app was closed during recording
        const val TRACK_TMP_FILENAME  = "current_track_tmp.gpx" // temp file while recording
        const val OFFLINE_TILE_KEY = "offline"
        const val TILE_SERVER_PORT = 18564

        data class OfflineMapInfo(val key: String, val name: String, val path: String) {
            val index: Int get() = key.removePrefix(OFFLINE_TILE_KEY + "_").toIntOrNull() ?: 0
        }

        const val PREF_TILE_KEY = "tile_key"
        const val PREF_OVERLAY_KEY = "overlay_key"
        const val PREF_WIDGET_FONT_SCALE = "widget_font_scale"  // 1-10, default 5
        const val PREF_WIDGET_SPEED = "widget_speed"
        const val PREF_WIDGET_BEARING = "widget_bearing"
        const val PREF_WIDGET_TRACKLEN = "widget_tracklen"
        const val PREF_WIDGET_NEXTCP = "widget_nextcp"
        const val PREF_WIDGET_ALTITUDE = "widget_altitude"
        const val PREF_WIDGET_CHRONO = "widget_chrono"
        const val PREF_WIDGET_TIME = "widget_time"
        const val PREF_WIDGET_REMAIN_KM = "widget_remain_km"
        const val PREF_WIDGET_NEXTCP_NAME = "widget_nextcp_name"
        const val PREF_WIDGET_TRIPMASTER = "widget_tripmaster"
        const val PREF_WIDGET_ORDER = "widget_order"
        const val PREF_NAV_ACTIVE = "nav_active"
        const val PREF_WP_APPROACH_RADIUS = "wp_approach_radius"  // metres, default 25
        const val DEFAULT_WP_APPROACH_RADIUS = 25
        const val PREF_SYNC_API_KEY = "sync_api_key"
        const val SYNC_BASE_URL = "http://87.120.84.254:9222"

        // Traccar live monitoring
        const val PREF_TRACCAR_ENABLED     = "traccar_enabled"      // bool, default false
        const val PREF_TRACCAR_URL         = "traccar_server_url"   // e.g. "http://217.60.1.225:5055"
        const val PREF_TRACCAR_DEVICE_ID   = "traccar_device_id"    // Traccar uniqueId used by OsmAnd protocol
        const val PREF_TRACCAR_DEVICE_NAME = "traccar_device_name"  // human-readable name

        const val LOADED_TRACK_SOURCE_ID = "loaded-track-source"
        const val LOADED_TRACK_LAYER_ID = "loaded-track-layer"

        val ALL_WIDGET_KEYS = listOf("speed","bearing","tracklen","nextcp","altitude","chrono","time","remain_km","nextcp_name","tripmaster","server_status")

        // Top bar button visibility prefs
        const val PREF_BTN_COMPASS = "btn_compass"
        const val PREF_BTN_ZOOM = "btn_zoom"
        const val PREF_BTN_WAYPOINT = "btn_waypoint"
        const val PREF_BTN_QUICK = "btn_quick_action"
        const val PREF_BTN_LAYERS = "btn_layers"
        const val PREF_BTN_REC = "btn_rec"
        const val PREF_BTN_LOCK = "btn_lock"

        const val PREF_LIVE_USERS_ENABLED = "live_users_enabled"
        const val PREF_LIVE_USERS_ONLINE_ONLY = "live_users_online_only" // show only online devices
        const val PREF_LIVE_USERS_URL = "live_users_url"
        const val PREF_LIVE_USER_SIZE = "live_user_size"              // Int 1-10, default 3
        const val PREF_LIVE_USER_LABEL_SIZE = "live_user_label_size"  // Int 1-10, default 3
        const val DEFAULT_LIVE_USER_SIZE = 3
        const val DEFAULT_LIVE_USER_LABEL_SIZE = 3

        /** Convert 1-10 scale to dp for marker sizes: 1=24dp, 3=40dp, 5=56dp, 10=96dp */
        fun markerScaleToDp(scale: Int): Int = scale * 8 + 16

        /** Convert 1-10 scale to sp for label sizes: 1=8sp, 3=11sp, 5=14sp, 10=21.5sp */
        fun labelScaleToSp(scale: Int): Float = scale * 1.5f + 6.5f
        const val PREF_CROSSHAIR_ENABLED = "crosshair_enabled"
        const val PREF_CROSSHAIR_SIZE = "crosshair_size"       // dp, default 60
        const val PREF_USER_POINTS_JSON = "user_points_json"   // persisted user points
        const val PREF_UI_SCALE = "ui_scale"                   // 1-10, default 5 (normal)
        const val PREF_COORDS_ENABLED = "coords_label_enabled"
        const val PREF_DISTANCE_LINE_ENABLED = "distance_line_enabled" // default true
        const val PREF_HEADING_LINE_ENABLED = "heading_line_enabled"
        const val PREF_HEADING_LINE_COLOR = "heading_line_color"
        const val PREF_HEADING_LINE_WIDTH = "heading_line_width"
        const val HEADING_LINE_SOURCE_ID = "heading-line-source"
        const val HEADING_LINE_LAYER_ID = "heading-line-layer"
        const val PREF_DISTANCE_LINE_COLOR = "distance_line_color"
        const val PREF_DISTANCE_LINE_WIDTH = "distance_line_width"
        const val DISTANCE_LINE_SOURCE_ID = "distance-line-source"
        const val DISTANCE_LINE_LAYER_ID = "distance-line-layer"
        const val LIVE_USERS_SOURCE_ID = "live-users-source"
        const val LIVE_USERS_LAYER_ID = "live-users-layer"
        const val LIVE_USERS_LABELS_LAYER_ID = "live-users-labels-layer"

        const val PREF_WIDGET_SERVER_STATUS = "widget_server_status"
        const val PREF_BTN_MAP_SWITCH = "btn_map_switch"
        const val PREF_BTN_SERVER_DOT = "btn_server_dot"
        const val PREF_MAP_SWITCH_A = "map_switch_a"  // first map key
        const val PREF_MAP_SWITCH_B = "map_switch_b"  // second map key

        const val NAV_LINE_SOURCE_ID = "nav-line-source"
        const val NAV_LINE_LAYER_ID = "nav-line-layer"
        const val ROUTE_LINE_SOURCE_ID = "route-line-source"
        const val ROUTE_LINE_LAYER_ID  = "route-line-layer"
        const val WP_RADIUS_SOURCE_ID = "wp-radius-source"
        const val WP_RADIUS_LAYER_ID = "wp-radius-layer"
        const val WP_RADIUS_OUTLINE_LAYER_ID = "wp-radius-outline-layer"
        const val WP_PROXIMITY_SOURCE_ID = "wp-proximity-source"
        const val WP_PROXIMITY_LAYER_ID = "wp-proximity-layer"
        const val WP_PROXIMITY_OUTLINE_LAYER_ID = "wp-proximity-outline-layer"
        const val PREF_NAV_LINE_COLOR = "nav_line_color"    // hex, default "#FF6F00"
        const val PREF_NAV_LINE_WIDTH = "nav_line_width"    // dp, default 3

        // Persistence of loaded data across restarts
        const val PREF_SAVED_WAYPOINTS_JSON = "saved_waypoints_json"
        const val PREF_SAVED_TRACK_JSON = "saved_track_json"
        const val PREF_ACTIVE_WP_INDEX = "active_wp_index"

        // Sound notifications
        const val PREF_SOUND_APPROACH = "sound_approach"   // bool, default true
        const val PREF_SOUND_TAKEN    = "sound_taken"      // bool, default true
        const val PREF_HINTS_ENABLED  = "hints_enabled"    // bool, default true

        const val PREF_ROUTE_NAME = "route_name"           // display name of loaded route/waypoint set
        const val PREF_ROUTE_LINE_COLOR = "route_line_color"  // hex, default "#FF6F00"
        const val PREF_ROUTE_LINE_WIDTH = "route_line_width"  // dp, default 2
        const val PREF_WP_LABEL_SIZE = "wp_label_size"        // 1-10 scale, default 3
        const val PREF_WP_CIRCLE_SIZE = "wp_circle_size"      // 1-10 scale, default 3
        const val USER_MARKER_SOURCE_ID = "user-marker-source"
        const val USER_MARKER_LAYER_ID = "user-marker-layer"
        const val USER_MARKER_ICON = "user-marker-icon"

        data class TileSourceInfo(val urls: List<String>, val tms: Boolean = false, val maxZoom: Int = 19)

        fun getRaceNavDir(ctx: android.content.Context, subfolder: String): java.io.File {
            val base = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOCUMENTS)
            return java.io.File(base, "RaceNav/$subfolder").also { it.mkdirs() }
        }
    }

    data class TileSource(val label: String, val urls: List<String>, val tms: Boolean = false, val maxZoom: Int = 19)

    private val tileSources = linkedMapOf(
        "osm"          to TileSource("OpenStreetMap", listOf(
            "https://a.tile.openstreetmap.org/{z}/{x}/{y}.png",
            "https://b.tile.openstreetmap.org/{z}/{x}/{y}.png",
            "https://c.tile.openstreetmap.org/{z}/{x}/{y}.png"), maxZoom = 19),
        "satellite"    to TileSource("Спутник ESRI", listOf(
            "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"), maxZoom = 18),
        "topo"         to TileSource("OpenTopoMap", listOf("https://tile.opentopomap.org/{z}/{x}/{y}.png"), maxZoom = 17),
        "google"       to TileSource("Google Спутник", listOf(
            "https://mt0.google.com/vt/lyrs=s&x={x}&y={y}&z={z}",
            "https://mt1.google.com/vt/lyrs=s&x={x}&y={y}&z={z}",
            "https://mt2.google.com/vt/lyrs=s&x={x}&y={y}&z={z}",
            "https://mt3.google.com/vt/lyrs=s&x={x}&y={y}&z={z}"), maxZoom = 20),
        "yandex_sat"   to TileSource("Яндекс Спутник", listOf(
            "https://sat01.maps.yandex.net/tiles?l=sat&x={x}&y={y}&z={z}&lang=ru_RU",
            "https://sat02.maps.yandex.net/tiles?l=sat&x={x}&y={y}&z={z}&lang=ru_RU",
            "https://sat03.maps.yandex.net/tiles?l=sat&x={x}&y={y}&z={z}&lang=ru_RU",
            "https://sat04.maps.yandex.net/tiles?l=sat&x={x}&y={y}&z={z}&lang=ru_RU"), maxZoom = 19),
        "yandex_map"   to TileSource("Яндекс Карта", listOf(
            "https://vec01.maps.yandex.net/tiles?l=map&x={x}&y={y}&z={z}&scale=1&lang=ru-RU",
            "https://vec02.maps.yandex.net/tiles?l=map&x={x}&y={y}&z={z}&scale=1&lang=ru-RU",
            "https://vec03.maps.yandex.net/tiles?l=map&x={x}&y={y}&z={z}&scale=1&lang=ru-RU",
            "https://vec04.maps.yandex.net/tiles?l=map&x={x}&y={y}&z={z}&scale=1&lang=ru-RU"), maxZoom = 19),
        "kosmosnimki_relief" to TileSource("Космоснимки рельеф", listOf(
            "https://atilecart.kosmosnimki.ru/rw/{z}/{x}/{y}.png",
            "https://btilecart.kosmosnimki.ru/rw/{z}/{x}/{y}.png",
            "https://ctilecart.kosmosnimki.ru/rw/{z}/{x}/{y}.png",
            "https://dtilecart.kosmosnimki.ru/rw/{z}/{x}/{y}.png"), maxZoom = 13),
        "lomaps"        to TileSource("LoMaps (Thunderforest)", listOf(
            "https://a.tile.thunderforest.com/locus-4za/{z}/{x}/{y}.png?apikey=7c352c8ff1244dd8b732e349e0b0fe8d",
            "https://b.tile.thunderforest.com/locus-4za/{z}/{x}/{y}.png?apikey=7c352c8ff1244dd8b732e349e0b0fe8d",
            "https://c.tile.thunderforest.com/locus-4za/{z}/{x}/{y}.png?apikey=7c352c8ff1244dd8b732e349e0b0fe8d"), maxZoom = 22),
        "cyclosm"       to TileSource("CyclOSM", listOf(
            "https://a.tile-cyclosm.openstreetmap.fr/cyclosm/{z}/{x}/{y}.png",
            "https://b.tile-cyclosm.openstreetmap.fr/cyclosm/{z}/{x}/{y}.png",
            "https://c.tile-cyclosm.openstreetmap.fr/cyclosm/{z}/{x}/{y}.png"), maxZoom = 19),
        "tf_outdoors"   to TileSource("Thunderforest Outdoors", listOf(
            "https://a.tile.thunderforest.com/outdoors/{z}/{x}/{y}.png?apikey=6170aad10dfd42a38d4d8c709a536f38",
            "https://b.tile.thunderforest.com/outdoors/{z}/{x}/{y}.png?apikey=6170aad10dfd42a38d4d8c709a536f38",
            "https://c.tile.thunderforest.com/outdoors/{z}/{x}/{y}.png?apikey=6170aad10dfd42a38d4d8c709a536f38"), maxZoom = 22),
        "esri_clarity"  to TileSource("ESRI Clarity (спутник)", listOf(
            "https://clarity.maptiles.arcgis.com/arcgis/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}?blankTile=false"), maxZoom = 19),
        "google_maps"   to TileSource("Google Карты", listOf(
            "https://mt0.google.com/vt/lyrs=m&hl=ru&x={x}&y={y}&z={z}",
            "https://mt1.google.com/vt/lyrs=m&hl=ru&x={x}&y={y}&z={z}",
            "https://mt2.google.com/vt/lyrs=m&hl=ru&x={x}&y={y}&z={z}",
            "https://mt3.google.com/vt/lyrs=m&hl=ru&x={x}&y={y}&z={z}"), maxZoom = 20),
        "google_terrain" to TileSource("Google Рельеф", listOf(
            "https://mt0.google.com/vt/lyrs=t&hl=ru&x={x}&y={y}&z={z}",
            "https://mt1.google.com/vt/lyrs=t&hl=ru&x={x}&y={y}&z={z}",
            "https://mt2.google.com/vt/lyrs=t&hl=ru&x={x}&y={y}&z={z}",
            "https://mt3.google.com/vt/lyrs=t&hl=ru&x={x}&y={y}&z={z}"), maxZoom = 20),
        "google_hybrid" to TileSource("Google Гибрид", listOf(
            "https://mt0.google.com/vt/lyrs=y&hl=ru&x={x}&y={y}&z={z}",
            "https://mt1.google.com/vt/lyrs=y&hl=ru&x={x}&y={y}&z={z}",
            "https://mt2.google.com/vt/lyrs=y&hl=ru&x={x}&y={y}&z={z}",
            "https://mt3.google.com/vt/lyrs=y&hl=ru&x={x}&y={y}&z={z}"), maxZoom = 20),
        "tf_transport"  to TileSource("TF Transport", listOf(
            "https://a.tile.thunderforest.com/transport/{z}/{x}/{y}.png?apikey=6170aad10dfd42a38d4d8c709a536f38",
            "https://b.tile.thunderforest.com/transport/{z}/{x}/{y}.png?apikey=6170aad10dfd42a38d4d8c709a536f38",
            "https://c.tile.thunderforest.com/transport/{z}/{x}/{y}.png?apikey=6170aad10dfd42a38d4d8c709a536f38"), maxZoom = 22),
        "tf_cycle"      to TileSource("TF Велосипед", listOf(
            "https://a.tile.thunderforest.com/cycle/{z}/{x}/{y}.png?apikey=6170aad10dfd42a38d4d8c709a536f38",
            "https://b.tile.thunderforest.com/cycle/{z}/{x}/{y}.png?apikey=6170aad10dfd42a38d4d8c709a536f38",
            "https://c.tile.thunderforest.com/cycle/{z}/{x}/{y}.png?apikey=6170aad10dfd42a38d4d8c709a536f38"), maxZoom = 22),
        "osm_hot"       to TileSource("OSM Humanitarian", listOf(
            "https://tile-a.openstreetmap.fr/hot/{z}/{x}/{y}.png",
            "https://tile-b.openstreetmap.fr/hot/{z}/{x}/{y}.png"), maxZoom = 19),
        "mtbmap"        to TileSource("MTB Map", listOf(
            "http://tile.mtbmap.cz/mtbmap_tiles/{z}/{x}/{y}.png"), maxZoom = 18),
        "2gis"          to TileSource("2GIS", listOf(
            "https://tile0.maps.2gis.com/tiles?x={x}&y={y}&z={z}&r=g&ts=online_1",
            "https://tile1.maps.2gis.com/tiles?x={x}&y={y}&z={z}&r=g&ts=online_1",
            "https://tile2.maps.2gis.com/tiles?x={x}&y={y}&z={z}&r=g&ts=online_1"), maxZoom = 19),
        "michelin"      to TileSource("Michelin", listOf(
            "https://map1.viamichelin.com/map/mapdirect?map=viamichelin&z={z}&x={x}&y={y}&format=png&version=201901161110&layer=background&locale=default&cs=1&protocol=https"), maxZoom = 18),
        "genshtab250"  to TileSource("Генштаб 250м", listOf(
            "https://a.tiles.nakarte.me/g250/{z}/{x}/{y}",
            "https://b.tiles.nakarte.me/g250/{z}/{x}/{y}",
            "https://c.tiles.nakarte.me/g250/{z}/{x}/{y}"), tms = true, maxZoom = 15),
        "genshtab500"  to TileSource("Генштаб 500м", listOf(
            "https://a.tiles.nakarte.me/g500/{z}/{x}/{y}",
            "https://b.tiles.nakarte.me/g500/{z}/{x}/{y}",
            "https://c.tiles.nakarte.me/g500/{z}/{x}/{y}"), tms = true, maxZoom = 14),
        "ggc500"       to TileSource("ГосГисЦентр 500м", listOf(
            "https://a.tiles.nakarte.me/ggc500/{z}/{x}/{y}",
            "https://b.tiles.nakarte.me/ggc500/{z}/{x}/{y}",
            "https://c.tiles.nakarte.me/ggc500/{z}/{x}/{y}"), tms = true, maxZoom = 14),
        "ggc1000"      to TileSource("ГосГисЦентр 1км", listOf(
            "https://a.tiles.nakarte.me/ggc1000/{z}/{x}/{y}",
            "https://b.tiles.nakarte.me/ggc1000/{z}/{x}/{y}",
            "https://c.tiles.nakarte.me/ggc1000/{z}/{x}/{y}"), tms = true, maxZoom = 13),
        "topo250"      to TileSource("Топо 250м", listOf(
            "https://a.tiles.nakarte.me/topo250/{z}/{x}/{y}",
            "https://b.tiles.nakarte.me/topo250/{z}/{x}/{y}",
            "https://c.tiles.nakarte.me/topo250/{z}/{x}/{y}"), tms = true, maxZoom = 15),
        "topo500"      to TileSource("Топо 500м", listOf(
            "https://a.tiles.nakarte.me/topo500/{z}/{x}/{y}",
            "https://b.tiles.nakarte.me/topo500/{z}/{x}/{y}",
            "https://c.tiles.nakarte.me/topo500/{z}/{x}/{y}"), tms = true, maxZoom = 14)
    )

    // Overlay sources (transparent, shown on top of base)
    data class OverlaySource(val label: String, val urls: List<String>, val tms: Boolean = false, val opacity: Float = 0.7f, val maxZoom: Int = 19)

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
            "https://b.tiles.nakarte.me/osm/{z}/{x}/{y}"), opacity = 0.6f),
        "windy_relief"  to OverlaySource("Windy рельеф", listOf(
            "https://tiles.windy.com/tiles/v8.1/darkmap/{z}/{x}/{y}.png"), opacity = 0.5f),
        "snowmap"       to OverlaySource("Горнолыжные трассы", listOf(
            "https://tiles.opensnowmap.org/pistes/{z}/{x}/{y}.png")),
        "labels_light" to OverlaySource("Подписи (светлые)", listOf(
            "https://a.basemaps.cartocdn.com/light_only_labels/{z}/{x}/{y}.png",
            "https://b.basemaps.cartocdn.com/light_only_labels/{z}/{x}/{y}.png",
            "https://c.basemaps.cartocdn.com/light_only_labels/{z}/{x}/{y}.png"), opacity = 1.0f),
        "labels_dark" to OverlaySource("Подписи (тёмные)", listOf(
            "https://a.basemaps.cartocdn.com/dark_only_labels/{z}/{x}/{y}.png",
            "https://b.basemaps.cartocdn.com/dark_only_labels/{z}/{x}/{y}.png",
            "https://c.basemaps.cartocdn.com/dark_only_labels/{z}/{x}/{y}.png"), opacity = 1.0f),
        "voyager_labels" to OverlaySource("Подписи (цветные)", listOf(
            "https://a.basemaps.cartocdn.com/rastertiles/voyager_only_labels/{z}/{x}/{y}.png",
            "https://b.basemaps.cartocdn.com/rastertiles/voyager_only_labels/{z}/{x}/{y}.png",
            "https://c.basemaps.cartocdn.com/rastertiles/voyager_only_labels/{z}/{x}/{y}.png"), opacity = 1.0f)
    )

    private var currentTileKey = "osm"
    private var currentOverlayKeys = mutableSetOf<String>()

    fun reloadCustomSources() {
        val prefs = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) ?: return
        // Remove old custom entries
        tileSources.keys.filter { it.startsWith("custom_") }.toList().forEach { tileSources.remove(it) }
        overlaySources.keys.filter { it.startsWith("custom_") }.toList().forEach { overlaySources.remove(it) }

        val json = prefs.getString("custom_sources_json", "[]") ?: "[]"
        val arr = try { org.json.JSONArray(json) } catch (_: Exception) { return }
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val name = obj.optString("name", "Custom $i")
            val url = obj.optString("url", "")
            val type = obj.optString("type", "base")
            val tms = obj.optBoolean("tms", false)
            val opacity = obj.optDouble("opacity", 0.7).toFloat()
            val key = "custom_$i"
            if (type == "overlay") {
                overlaySources[key] = OverlaySource(name, listOf(url), tms, opacity)
            } else {
                tileSources[key] = TileSource(name, listOf(url), tms)
            }
        }
    }

    // Public method to load waypoints from SettingsFragment
    fun loadWaypoints(wps: List<Waypoint>) {
        waypoints.clear()
        val limited = if (wps.size > MAX_WAYPOINTS) {
            Toast.makeText(context, "Обрезано до $MAX_WAYPOINTS точек (было ${wps.size})", Toast.LENGTH_LONG).show()
            wps.take(MAX_WAYPOINTS)
        } else wps
        waypoints.addAll(limited)
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


        // Migrate size prefs from old dp/sp values to 1-10 scale
        migrateSizePrefs()

        // Create app working directory on external storage
        ensureAppDirectory()

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

        // Adjust bottom bar margin for navigation bar (only if virtual buttons present)
        ViewCompat.setOnApplyWindowInsetsListener(binding.bottomBar) { v, insets ->
            val navBarInset = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            // Only add margin if system actually has visible navigation bar
            // On devices with physical buttons, navBarInset should be 0
            val params = v.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
            params.bottomMargin = navBarInset
            v.layoutParams = params
            android.util.Log.d("BottomBar", "navBarInset=$navBarInset")
            insets
        }

        // Apply fullscreen mode from prefs
        applyFullscreenPref()
        applyWidgetPrefs()
        applyUiScale()
        applyWidgetFontScale()
        applyTopBarPrefs()
        applyCrosshairPrefs()

        binding.mapView.onCreate(savedInstanceState)
        applyCacheSize()

        binding.mapView.getMapAsync { map ->
            mapboxMap = map
            map.uiSettings.isCompassEnabled = false
            map.uiSettings.isAttributionEnabled = false
            map.uiSettings.isLogoEnabled = false
            val tilePrefs = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val savedTile = tilePrefs?.getString(PREF_TILE_KEY, "osm") ?: "osm"
            val savedOverlayStr = tilePrefs?.getString(PREF_OVERLAY_KEY, "") ?: ""
            val savedOverlays = savedOverlayStr.split(",").filter { it.isNotBlank() && it != "none" }.toSet()
            // Load custom and offline maps BEFORE loadTileStyle so tile sources are ready
            reloadCustomSources()
            loadOfflineMapsFromPrefs()
            loadTileStyle(savedTile, savedOverlays)

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
            // Tap on live user marker → show info card
            map.addOnMapClickListener { latLng ->
                if (isDownloadSelecting) {
                    handleDownloadTap(latLng)
                    return@addOnMapClickListener true
                }
                handleLiveUserClick(map, latLng)
            }
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
            "tripmaster"  -> b.widgetTripmasterContainer
            "server_status" -> b.widgetServerStatusContainer
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
            WidgetDef("tripmaster",  PREF_WIDGET_TRIPMASTER,  true),
            WidgetDef("server_status", PREF_WIDGET_SERVER_STATUS, false),
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

        // Update server status widget color based on TraccarService state
        val serverOn = prefs.getBoolean(PREF_WIDGET_SERVER_STATUS, false)
        if (serverOn) {
            val dot = b.widgetServerDot
            val text = b.widgetServerText
            if (TraccarService.isRunning) {
                dot.background?.setTint(0xFF4CAF50.toInt())
                text.text = "онлайн"
                text.setTextColor(0xFF4CAF50.toInt())
            } else {
                dot.background?.setTint(0xFF888888.toInt())
                text.text = "сервер"
                text.setTextColor(0xFF888888.toInt())
            }
        }
    }

    fun applyCrosshairAndDistancePrefs() {
        applyCrosshairPrefs()
        updateCrosshairInfo()
    }

    fun applyTopBarPrefs() {
        val prefs = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) ?: return
        val b = _binding ?: return

        b.compassView.visibility = if (prefs.getBoolean(PREF_BTN_COMPASS, true)) View.VISIBLE else View.GONE
        b.btnZoomIn.visibility = if (prefs.getBoolean(PREF_BTN_ZOOM, true)) View.VISIBLE else View.GONE
        b.btnZoomOut.visibility = if (prefs.getBoolean(PREF_BTN_ZOOM, true)) View.VISIBLE else View.GONE
        b.btnAddWaypoint.visibility = if (prefs.getBoolean(PREF_BTN_WAYPOINT, true)) View.VISIBLE else View.GONE
        b.btnQuickAction.visibility = if (prefs.getBoolean(PREF_BTN_QUICK, true)) View.VISIBLE else View.GONE
        b.btnLayers.visibility = if (prefs.getBoolean(PREF_BTN_LAYERS, true)) View.VISIBLE else View.GONE
        b.btnRec.visibility = if (prefs.getBoolean(PREF_BTN_REC, true)) View.VISIBLE else View.GONE
        b.btnLock.visibility = if (prefs.getBoolean(PREF_BTN_LOCK, true)) View.VISIBLE else View.GONE
        b.btnMapSwitch.visibility = if (prefs.getBoolean(PREF_BTN_MAP_SWITCH, false)) View.VISIBLE else View.GONE
        b.topBarServerDot.visibility = if (prefs.getBoolean(PREF_BTN_SERVER_DOT, false)) View.VISIBLE else View.GONE
        // Update server dot color
        if (TraccarService.isRunning) {
            b.topBarServerDot.background?.setTint(0xFF4CAF50.toInt())
        } else {
            b.topBarServerDot.background?.setTint(0xFF888888.toInt())
        }
        // btnSettings always visible — user needs access to settings
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

    private fun buildStyleJson(baseKey: String, overlayKeys: Set<String>): String {
        val base = tileSources[baseKey] ?: return ""
        val baseTiles = base.urls.joinToString(",") { "\"$it\"" }
        val baseScheme = if (base.tms) ",\"scheme\":\"tms\"" else ""

        val sources = StringBuilder()
        sources.append("\"rt\":{\"type\":\"raster\",\"tiles\":[$baseTiles],\"tileSize\":256,\"maxzoom\":${base.maxZoom}$baseScheme}")

        val layers = StringBuilder()
        layers.append("{\"id\":\"rl\",\"type\":\"raster\",\"source\":\"rt\",\"minzoom\":0,\"maxzoom\":22}")

        overlayKeys.filter { it != "none" }.forEachIndexed { idx, key ->
            val ov = overlaySources[key] ?: return@forEachIndexed
            if (ov.urls.isEmpty()) return@forEachIndexed
            val ovTiles = ov.urls.joinToString(",") { "\"$it\"" }
            val ovScheme = if (ov.tms) ",\"scheme\":\"tms\"" else ""
            sources.append(",\"ov$idx\":{\"type\":\"raster\",\"tiles\":[$ovTiles],\"tileSize\":256,\"maxzoom\":${ov.maxZoom}$ovScheme}")
            layers.append(",{\"id\":\"ol$idx\",\"type\":\"raster\",\"source\":\"ov$idx\",\"minzoom\":0,\"maxzoom\":22,\"paint\":{\"raster-opacity\":${ov.opacity}}}")
        }

        return """{"version":8,"glyphs":"https://fonts.openmaptiles.org/{fontstack}/{range}.pbf","sources":{$sources},"layers":[$layers]}"""
    }

    fun switchMap(tileKey: String) {
        val saved = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.getString(PREF_OVERLAY_KEY, "") ?: ""
        val keys = saved.split(",").filter { it.isNotBlank() && it != "none" }.toSet()
        loadTileStyle(tileKey, keys)
    }

    fun getCurrentTileKey(): String = currentTileKey

    private fun loadTileStyle(baseKey: String, overlayKeys: Set<String>) {
        currentTileKey = baseKey
        currentOverlayKeys = overlayKeys.toMutableSet()
        // Save selection so it survives rotation/restart
        context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)?.edit()
            ?.putString(PREF_TILE_KEY, baseKey)
            ?.putString(PREF_OVERLAY_KEY, overlayKeys.joinToString(","))
            ?.apply()
        val json = buildStyleJson(baseKey, overlayKeys)
        mapboxMap?.setStyle(Style.Builder().fromJson(json)) { style ->
            enableLocation(style)
            setupTrackLayers(style)
            setupWaypointLayers(style)
            setupLiveUsersLayer(style)
            setupDistanceLineLayer(style)
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

        // Auto-start track recording if enabled (default: true) — only once per fragment lifecycle
        if (!autoRecordDone) {
            autoRecordDone = true
            val autoRecord = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(PREF_AUTO_RECORD, true)
            if (autoRecord && !isRecording) {
                toggleRecording()
            }
        }
    }

    // ── Live Users layer (other participants on map) ──

    private fun makeLiveUserArrowBitmap(): Bitmap {
        // Same shape as main GPS arrow but smaller (22dp) and blue
        return makeArrowBitmap(22, Color.parseColor("#3b82f6"))
    }

    private var liveUserPopup: android.widget.PopupWindow? = null

    /** Handle click on live user circle — query features at point */
    private fun handleLiveUserClick(map: MapboxMap, latLng: LatLng): Boolean {
        // Dismiss previous popup
        liveUserPopup?.dismiss()
        liveUserPopup = null

        val screenPoint = map.projection.toScreenLocation(latLng)
        val features = map.queryRenderedFeatures(screenPoint, LIVE_USERS_LAYER_ID)
        if (features.isNotEmpty()) {
            val props = features[0].properties() ?: return false
            val name = props.get("name")?.asString ?: "?"
            val speed = props.get("speed")?.asDouble ?: 0.0
            val status = props.get("status")?.asString ?: "unknown"
            showLiveUserPopup(name, speed, status, latLng, screenPoint)
            return true
        }
        return false
    }

    private fun formatTimeAgo(isoDate: String?): String {
        if (isoDate == null) return "—"
        return try {
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
            sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
            val dt = sdf.parse(isoDate.substringBefore('+').substringBefore('Z')) ?: return "—"
            val sec = (System.currentTimeMillis() - dt.time) / 1000
            when {
                sec < 60 -> "${sec} сек"
                sec < 3600 -> "${sec / 60} мин"
                sec < 86400 -> "${sec / 3600} ч"
                else -> "${sec / 86400} д"
            }
        } catch (_: Exception) { "—" }
    }

    /** Show popup card near the marker on map */
    private fun showLiveUserPopup(name: String, speed: Double, status: String, latLng: LatLng, screenPt: android.graphics.PointF) {
        val ctx = context ?: return
        val device = lastLiveDevices.find { it.name == name }
        val density = resources.displayMetrics.density
        val pad = (12 * density).toInt()

        val stText = if (status == "online") "● Онлайн" else "○ Офлайн"
        val stColor = if (status == "online") "#22C55E" else "#EF4444"
        val spd = if (speed > 0) "➤ ${"%.0f".format(speed)} км/ч" else "🅿️ На стоянке"
        val batt = device?.battery?.let { "$it%" } ?: "—"
        val timeAgo = formatTimeAgo(device?.lastUpdate)

        val root = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.parseColor("#CC1A1A2E"))
        }

        // Rounded background
        val bg = android.graphics.drawable.GradientDrawable().apply {
            setColor(Color.parseColor("#E61A1A2E"))
            cornerRadius = 12 * density
            setStroke((1.5f * density).toInt(), Color.parseColor("#3b82f6"))
        }
        root.background = bg

        // Name header
        root.addView(android.widget.TextView(ctx).apply {
            text = name
            setTextColor(Color.WHITE)
            textSize = 15f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, (6 * density).toInt())
        })

        // Info lines
        val lines = listOf(
            stText to stColor,
            "$timeAgo  •  $spd" to "#CCCCCC",
            "🔋 $batt" to "#CCCCCC",
            "${formatDM(latLng.latitude, true)}  ${formatDM(latLng.longitude, false)}" to "#888888"
        )
        for ((text, color) in lines) {
            root.addView(android.widget.TextView(ctx).apply {
                this.text = text
                setTextColor(Color.parseColor(color))
                textSize = 12f
            })
        }

        // Measure view
        root.measure(
            android.view.View.MeasureSpec.makeMeasureSpec((250 * density).toInt(), android.view.View.MeasureSpec.AT_MOST),
            android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED)
        )
        val popupW = root.measuredWidth
        val popupH = root.measuredHeight

        val popup = android.widget.PopupWindow(root, popupW, popupH, true)
        popup.isOutsideTouchable = true
        popup.elevation = 8 * density

        // Position above the marker
        val mapView = _binding?.mapView ?: return
        val loc = IntArray(2)
        mapView.getLocationOnScreen(loc)
        val x = (screenPt.x - popupW / 2).toInt() + loc[0]
        val y = (screenPt.y - popupH - 20 * density).toInt() + loc[1]

        popup.showAtLocation(mapView, android.view.Gravity.NO_GRAVITY, x, y)
        liveUserPopup = popup

        // Auto-dismiss after 5 seconds
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            popup.dismiss()
        }, 5000)
    }

    /** Called from loadTileStyle */
    private fun setupLiveUsersLayer(style: Style) {
        // Source and layer are created dynamically in poller callback (recreated each update)
        val ctx = context ?: return
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(PREF_LIVE_USERS_ENABLED, false)) {
            startLiveUsersPoller()
        }
    }

    /** Create bitmap: blue arrow (like GPS marker) + name text below */
    private fun createLiveUserBitmap(name: String, status: String = "online"): Bitmap {
        val prefs = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val markerSizeScale = prefs?.getInt(PREF_LIVE_USER_SIZE, DEFAULT_LIVE_USER_SIZE) ?: DEFAULT_LIVE_USER_SIZE
        val density = resources.displayMetrics.density

        // Arrow part (same shape as GPS arrow)
        val arrowSizePx = (markerScaleToDp(markerSizeScale) * density).toInt().coerceAtLeast(24)
        val rad = arrowSizePx / 2f * 0.88f

        // Text part
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val labelSizeScale = prefs?.getInt(PREF_LIVE_USER_LABEL_SIZE, DEFAULT_LIVE_USER_LABEL_SIZE) ?: DEFAULT_LIVE_USER_LABEL_SIZE
        val textSize = labelScaleToSp(labelSizeScale) * density
        paint.textSize = textSize
        paint.isFakeBoldText = true
        val displayName = if (name.length > 14) name.take(14) + "…" else name.ifBlank { "?" }
        val textWidth = paint.measureText(displayName)
        val textHeight = paint.descent() - paint.ascent()
        val textGap = 3 * density

        // Combined bitmap: arrow + gap + text
        val bmpW = maxOf(arrowSizePx, (textWidth + 8 * density).toInt())
        val bmpH = arrowSizePx + textGap.toInt() + textHeight.toInt() + (2 * density).toInt()
        val bmp = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val cx = bmpW / 2f

        // Draw arrow centered at top
        val arrowCy = arrowSizePx / 2f
        val arrowPath = Path().apply {
            moveTo(cx, arrowCy - rad)
            lineTo(cx + rad * 0.72f, arrowCy + rad)
            lineTo(cx + rad * 0.18f, arrowCy + rad * 0.70f)
            lineTo(cx, arrowCy + rad * 0.82f)
            lineTo(cx - rad * 0.18f, arrowCy + rad * 0.70f)
            lineTo(cx - rad * 0.72f, arrowCy + rad)
            close()
        }
        // White border
        canvas.drawPath(arrowPath, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.STROKE
            strokeWidth = rad * 0.18f; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
        })
        // Blue fill
        canvas.drawPath(arrowPath, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(if (status == "online") "#3b82f6" else "#666666"); style = Paint.Style.FILL
        })

        // Draw name below arrow
        val textY = arrowSizePx + textGap - paint.ascent()
        paint.color = Color.WHITE; paint.textAlign = Paint.Align.CENTER; paint.style = Paint.Style.FILL
        // Text shadow/halo
        paint.setShadowLayer(3f * density, 0f, 0f, Color.parseColor("#CC000000"))
        canvas.drawText(displayName, cx, textY, paint)

        return bmp
    }

    private fun resolveLiveUsersBaseUrl(liveUrl: String): String {
        val fallback = "http://217.60.1.225"
        val raw = liveUrl.trim()
        if (raw.isEmpty()) return fallback
        return try {
            val uri = android.net.Uri.parse(raw)
            if (uri.scheme != null && uri.host != null) raw.trimEnd('/')
            else fallback
        } catch (_: Exception) {
            fallback
        }
    }

    fun startLiveUsersPoller() {
        val ctx = context ?: return
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // Live API URL is independent from Traccar sending URL
        val liveBaseUrl = resolveLiveUsersBaseUrl(prefs.getString(PREF_LIVE_USERS_URL, "") ?: "")
        val myDeviceId = prefs.getString(PREF_TRACCAR_DEVICE_ID, "") ?: ""
        liveUsersPoller?.stop()
        liveUsersPoller = null
        Log.d("LiveUsers", "startLiveUsersPoller baseUrl=$liveBaseUrl myDeviceId=$myDeviceId")
        liveUsersPoller = LiveUsersPoller(liveBaseUrl, myDeviceId, onError = { err ->
            Log.w("LiveUsers", "poller error: $err")
        }) { geoJson, devices ->
            try {
                lastLiveDevices = devices
                val style = mapboxMap?.style ?: return@LiveUsersPoller
                val onlineOnly = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    ?.getBoolean(PREF_LIVE_USERS_ONLINE_ONLY, false) ?: false
                val filtered = if (onlineOnly) devices.filter { it.status == "online" } else devices
                // Remove old layers/source
                try { style.removeLayer(LIVE_USERS_LAYER_ID) } catch (_: Exception) {}
                try { style.removeSource(LIVE_USERS_SOURCE_ID) } catch (_: Exception) {}
                // Build GeoJSON with per-device bitmap icons
                val features = JSONArray()
                filtered.forEach { d ->
                    val iconId = "live-user-${d.deviceId}"
                    style.addImage(iconId, createLiveUserBitmap(d.name, d.status))
                    features.put(JSONObject()
                        .put("type", "Feature")
                        .put("geometry", JSONObject().put("type", "Point")
                            .put("coordinates", JSONArray().put(d.lon).put(d.lat)))
                        .put("properties", JSONObject()
                            .put("icon", iconId)
                            .put("name", d.name)
                            .put("speed", d.speed)
                            .put("status", d.status)
                            .put("deviceId", d.deviceId)))
                }
                val fc = JSONObject().put("type", "FeatureCollection").put("features", features).toString()
                style.addSource(GeoJsonSource(LIVE_USERS_SOURCE_ID, fc))
                style.addLayer(SymbolLayer(LIVE_USERS_LAYER_ID, LIVE_USERS_SOURCE_ID).withProperties(
                    PropertyFactory.iconImage(com.mapbox.mapboxsdk.style.expressions.Expression.get("icon")),
                    PropertyFactory.iconAllowOverlap(true),
                    PropertyFactory.iconIgnorePlacement(true),
                    PropertyFactory.iconAnchor("center"),
                    *wpIconSizeProps()
                ))
            } catch (e: Exception) {
                Log.w("LiveUsers", "update failed: ${e.message}")
            }
        }
        liveUsersPoller?.start()
    }

    fun stopLiveUsersPoller() {
        liveUsersPoller?.stop()
        liveUsersPoller = null
        mapboxMap?.style?.getSourceAs<GeoJsonSource>(LIVE_USERS_SOURCE_ID)
            ?.setGeoJson("""{"type":"FeatureCollection","features":[]}""")
    }

    private fun setupDistanceLineLayer(style: Style) {
        val ctx = context ?: return
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // Distance line (GPS → map center)
        if (style.getSource(DISTANCE_LINE_SOURCE_ID) == null) {
            style.addSource(GeoJsonSource(DISTANCE_LINE_SOURCE_ID))
            val color = Color.parseColor(prefs.getString(PREF_DISTANCE_LINE_COLOR, "#FFFF00") ?: "#FFFF00")
            val width = prefs.getFloat(PREF_DISTANCE_LINE_WIDTH, 2f)
            style.addLayer(LineLayer(DISTANCE_LINE_LAYER_ID, DISTANCE_LINE_SOURCE_ID).withProperties(
                PropertyFactory.lineColor(color),
                PropertyFactory.lineWidth(width),
                PropertyFactory.lineDasharray(arrayOf(4f, 4f)),
                PropertyFactory.lineOpacity(0.8f)
            ))
        }
        // Heading line (predicted direction from cursor) — white outline + colored inner
        if (style.getSource(HEADING_LINE_SOURCE_ID) == null) {
            style.addSource(GeoJsonSource(HEADING_LINE_SOURCE_ID))
            val hColor = Color.parseColor(prefs.getString(PREF_HEADING_LINE_COLOR, "#00BFFF") ?: "#00BFFF")
            val hWidth = prefs.getFloat(PREF_HEADING_LINE_WIDTH, 2f)
            // White outline (thicker)
            style.addLayer(LineLayer(HEADING_LINE_LAYER_ID + "-outline", HEADING_LINE_SOURCE_ID).withProperties(
                PropertyFactory.lineColor(Color.WHITE),
                PropertyFactory.lineWidth(hWidth + 2f),
                PropertyFactory.lineOpacity(0.5f)
            ))
            // Colored inner line
            style.addLayer(LineLayer(HEADING_LINE_LAYER_ID, HEADING_LINE_SOURCE_ID).withProperties(
                PropertyFactory.lineColor(hColor),
                PropertyFactory.lineWidth(hWidth),
                PropertyFactory.lineOpacity(0.8f)
            ))
        }
    }

    /** Apply distance/heading line style from prefs (call after settings change) */
    fun applyLineStyles() {
        val style = mapboxMap?.style ?: return
        val prefs = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) ?: return
        // Distance line
        style.getLayerAs<LineLayer>(DISTANCE_LINE_LAYER_ID)?.setProperties(
            PropertyFactory.lineColor(Color.parseColor(prefs.getString(PREF_DISTANCE_LINE_COLOR, "#FFFF00") ?: "#FFFF00")),
            PropertyFactory.lineWidth(prefs.getFloat(PREF_DISTANCE_LINE_WIDTH, 2f))
        )
        // Heading line + outline
        val hWidth = prefs.getFloat(PREF_HEADING_LINE_WIDTH, 2f)
        style.getLayerAs<LineLayer>(HEADING_LINE_LAYER_ID)?.setProperties(
            PropertyFactory.lineColor(Color.parseColor(prefs.getString(PREF_HEADING_LINE_COLOR, "#00BFFF") ?: "#00BFFF")),
            PropertyFactory.lineWidth(hWidth)
        )
        style.getLayerAs<LineLayer>(HEADING_LINE_LAYER_ID + "-outline")?.setProperties(
            PropertyFactory.lineWidth(hWidth + 2f)
        )
    }

    private fun setupGpsArrowLayer(style: Style) {
        val ctx = context ?: return
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val markerColor = Color.parseColor(prefs.getString(PREF_MARKER_COLOR, DEFAULT_MARKER_COLOR) ?: DEFAULT_MARKER_COLOR)
        val markerSize = prefs.getInt(PREF_MARKER_SIZE, DEFAULT_MARKER_SIZE)
        style.addImage(GPS_ARROW_ICON, makeArrowBitmap(markerScaleToDp(markerSize), markerColor))
        // GPS accuracy circle — drawn UNDER the arrow
        if (style.getSource(GPS_ACCURACY_SOURCE_ID) == null) {
            style.addSource(GeoJsonSource(GPS_ACCURACY_SOURCE_ID))
            style.addLayer(com.mapbox.mapboxsdk.style.layers.FillLayer(GPS_ACCURACY_LAYER_ID, GPS_ACCURACY_SOURCE_ID).withProperties(
                PropertyFactory.fillColor(Color.argb(40, 100, 150, 255)),  // semi-transparent blue
                PropertyFactory.fillAntialias(true)
            ))
        }
        if (style.getSource(GPS_ARROW_SOURCE_ID) == null) {
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

    /** Update accuracy circle polygon (circle approximation as 36-point polygon) */
    private fun updateAccuracyCircle(lat: Double, lon: Double, accuracyM: Float) {
        val style = mapboxMap?.style ?: return
        // Hide circle when accuracy is good (< 10m) — it would be invisible under the arrow anyway
        if (accuracyM < 10f) {
            style.getSourceAs<GeoJsonSource>(GPS_ACCURACY_SOURCE_ID)
                ?.setGeoJson("{\"type\":\"FeatureCollection\",\"features\":[]}")
            return
        }
        // Build circle polygon: convert meters to degrees (approximate)
        val latRad = Math.toRadians(lat)
        val mPerDegLat = 111320.0
        val mPerDegLon = 111320.0 * Math.cos(latRad)
        val dLat = accuracyM / mPerDegLat
        val dLon = accuracyM / mPerDegLon
        val coords = JSONArray()
        val ring = JSONArray()
        val steps = 36
        for (i in 0..steps) {
            val angle = Math.toRadians(i * 360.0 / steps)
            ring.put(JSONArray().put(lon + dLon * Math.cos(angle)).put(lat + dLat * Math.sin(angle)))
        }
        coords.put(ring)
        val geoJson = JSONObject()
            .put("type", "Feature")
            .put("geometry", JSONObject().put("type", "Polygon").put("coordinates", coords))
            .toString()
        style.getSourceAs<GeoJsonSource>(GPS_ACCURACY_SOURCE_ID)?.setGeoJson(geoJson)
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
        val routeLinePrefs = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val routeLineColor = routeLinePrefs?.getString(PREF_ROUTE_LINE_COLOR, "#B388FF") ?: "#FF6F00"
        val routeLineWidth = routeLinePrefs?.getInt(PREF_ROUTE_LINE_WIDTH, 2)?.toFloat() ?: 2f
        style.addSource(GeoJsonSource(ROUTE_LINE_SOURCE_ID))
        style.addLayer(LineLayer(ROUTE_LINE_LAYER_ID, ROUTE_LINE_SOURCE_ID).withProperties(
            PropertyFactory.lineColor(routeLineColor),
            PropertyFactory.lineWidth(routeLineWidth),
            PropertyFactory.lineOpacity(0.8f),
            PropertyFactory.lineDasharray(arrayOf(6f, 4f))
        ))

        // Approach radius circles (behind waypoint dots)
        style.addSource(GeoJsonSource(WP_RADIUS_SOURCE_ID))
        style.addLayer(FillLayer(WP_RADIUS_LAYER_ID, WP_RADIUS_SOURCE_ID).withProperties(
            PropertyFactory.fillColor("#00BFFF"),
            PropertyFactory.fillOpacity(0.12f)
        ))
        // White outline for approach radius circles — visible on any background
        style.addLayer(LineLayer(WP_RADIUS_OUTLINE_LAYER_ID, WP_RADIUS_SOURCE_ID).withProperties(
            PropertyFactory.lineColor("#FFFFFF"),
            PropertyFactory.lineWidth(3.0f),
            PropertyFactory.lineOpacity(0.7f)
        ))
        // Proximity circles from waypoint properties (blue, different from approach radius)
        style.addSource(GeoJsonSource(WP_PROXIMITY_SOURCE_ID))
        style.addLayer(FillLayer(WP_PROXIMITY_LAYER_ID, WP_PROXIMITY_SOURCE_ID).withProperties(
            PropertyFactory.fillColor("#3b82f6"),
            PropertyFactory.fillOpacity(0.08f)
        ))
        style.addLayer(LineLayer(WP_PROXIMITY_OUTLINE_LAYER_ID, WP_PROXIMITY_SOURCE_ID).withProperties(
            PropertyFactory.lineColor("#3b82f6"),
            PropertyFactory.lineWidth(2.0f),
            PropertyFactory.lineOpacity(0.6f)
        ))
        // Navigation line: GPS → active waypoint
        style.addSource(GeoJsonSource(NAV_LINE_SOURCE_ID))
        val navLineColor = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.getString(PREF_NAV_LINE_COLOR, "#1565C0") ?: "#FF6F00"
        val navLineWidth = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.getInt(PREF_NAV_LINE_WIDTH, 3)?.toFloat() ?: 3f
        style.addLayer(LineLayer(NAV_LINE_LAYER_ID, NAV_LINE_SOURCE_ID).withProperties(
            PropertyFactory.lineColor(navLineColor),
            PropertyFactory.lineWidth(navLineWidth)
        ))

        style.addSource(GeoJsonSource(WP_SOURCE_ID))
        // Bitmap icons with KP name inside circle — works offline (no glyphs needed)
        // iconSize zoom interpolation handles scaling
        style.addLayer(SymbolLayer(WP_LAYER_ID, WP_SOURCE_ID).withProperties(
            PropertyFactory.iconImage(com.mapbox.mapboxsdk.style.expressions.Expression.get("icon")),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true),
            PropertyFactory.iconAnchor("left"),
            *wpIconSizeProps()
        ))
        // User markers layer
        style.addSource(GeoJsonSource(USER_MARKER_SOURCE_ID))
        style.addLayer(SymbolLayer(USER_MARKER_LAYER_ID, USER_MARKER_SOURCE_ID).withProperties(
            PropertyFactory.iconImage(com.mapbox.mapboxsdk.style.expressions.Expression.get("icon")),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true),
            PropertyFactory.iconAnchor("left"),
            *wpIconSizeProps()
        ))

        if (waypoints.isNotEmpty()) {
            updateWaypointsOnMap()
            updateRouteLineOnMap()
            updateRadiusCircles()
        }
        restoreUserPoints()
        if (userMarkers.isNotEmpty()) updateUserMarkersOnMap()
        updateNavLine()
        // If track was just restored from prefs, render it now (setupTrackLayers ran before restore)
        if (trackWasEmpty && loadedTrackPoints.isNotEmpty()) {
            updateLoadedTrackOnMap()
        }
    }

    private fun updateWaypointsOnMap() {
        val style = mapboxMap?.style ?: return
        val source = style.getSourceAs<GeoJsonSource>(WP_SOURCE_ID) ?: return

        // Remove old wp icons
        for (i in 0 until lastWpIconCount) {
            try { style.removeImage("wp-icon-$i") } catch (_: Exception) {}
        }

        val features = JSONArray()
        waypoints.forEachIndexed { i, wp ->
            val iconId = "wp-icon-$i"
            val bmp = createWaypointBitmap(wp)
            style.addImage(iconId, bmp)
            val feature = JSONObject()
                .put("type", "Feature")
                .put("geometry", JSONObject().put("type", "Point")
                    .put("coordinates", JSONArray().put(wp.lon).put(wp.lat)))
                .put("properties", JSONObject()
                    .put("icon", iconId)
                    .put("name", wp.name.ifBlank { "•" }))
            features.put(feature)
        }
        source.setGeoJson(JSONObject().put("type", "FeatureCollection").put("features", features).toString())
        lastWpIconCount = waypoints.size
    }

    private val wpBitmapCache = mutableMapOf<String, android.graphics.Bitmap>()
    private var lastWpIconCount = 0

    private fun createWaypointBitmap(wp: Waypoint, sizeLevel: Int = -1): android.graphics.Bitmap {
        val prefs = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val labelLvl = if (sizeLevel < 0) prefs?.getInt(PREF_WP_LABEL_SIZE, 3) ?: 3 else sizeLevel
        val circleLvl = prefs?.getInt(PREF_WP_CIRCLE_SIZE, 3) ?: 3
        fun scaleOf(lvl: Int) = when (lvl) {
            1 -> 0.5f; 2 -> 0.7f; 3 -> 1.0f; 4 -> 1.4f; 5 -> 1.8f
            6 -> 2.3f; 7 -> 2.8f; 8 -> 3.3f; 9 -> 3.9f; 10 -> 4.5f
            else -> 1.0f
        }
        val labelScale = scaleOf(labelLvl)
        val circleScale = scaleOf(circleLvl)
        val fillColor = if (wp.color.isNotBlank()) wp.color else "#FF6F00"
        val upperName = wp.name.uppercase()
        val displayName = if (upperName.length > 14) upperName.take(14) + "…" else upperName
        val cacheKey = "$fillColor|${wp.symbol}|$displayName|$labelLvl|$circleLvl"
        wpBitmapCache[cacheKey]?.let { return it }

        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        val circleR = 18f * circleScale
        val circleDiam = (circleR * 2).toInt()

        // Measure label text
        val labelSize = 13f * labelScale
        paint.textSize = labelSize
        paint.isFakeBoldText = true
        val labelWidth = if (displayName.isNotBlank()) paint.measureText(displayName) else 0f
        val labelPadH = 6f * labelScale
        val labelPadV = 3f * labelScale
        val labelH = labelSize + labelPadV * 2
        val gap = 4f * circleScale

        // Total bitmap size: circle + gap + label
        val labelBoxW = if (labelWidth > 0) gap + labelWidth + labelPadH * 2 else 0f
        val circleBlockW = circleDiam + 4 * circleScale
        val totalW = (circleBlockW + labelBoxW).toInt()
        val totalH = maxOf(circleDiam + (4 * circleScale).toInt(), labelH.toInt() + 4)
        val bmp = android.graphics.Bitmap.createBitmap(maxOf(totalW, 1), maxOf(totalH, 1), android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        val cx = circleR + 2f * circleScale
        val cy = totalH / 2f

        // Circle fill
        paint.style = android.graphics.Paint.Style.FILL
        try { paint.color = android.graphics.Color.parseColor(fillColor) } catch (_: Exception) { paint.color = android.graphics.Color.parseColor("#FF6F00") }
        canvas.drawCircle(cx, cy, circleR, paint)

        // Circle stroke
        paint.style = android.graphics.Paint.Style.STROKE
        paint.color = android.graphics.Color.WHITE
        paint.strokeWidth = 2.5f * circleScale
        canvas.drawCircle(cx, cy, circleR, paint)

        // Draw symbol inside circle
        paint.style = android.graphics.Paint.Style.FILL
        paint.color = android.graphics.Color.WHITE
        drawSymbolInCircle(canvas, paint, cx, cy, circleR * 0.55f, wp.symbol, circleScale)

        // Draw label to the right of circle (semi-transparent background)
        if (displayName.isNotBlank()) {
            val labelX = cx + circleR + gap
            val labelY = cy - labelH / 2f
            // Background rect
            paint.color = android.graphics.Color.argb(200, 40, 40, 40)
            paint.style = android.graphics.Paint.Style.FILL
            val rr = 4f * labelScale
            canvas.drawRoundRect(labelX, labelY, labelX + labelWidth + labelPadH * 2, labelY + labelH, rr, rr, paint)
            // Text
            paint.color = android.graphics.Color.WHITE
            paint.textSize = labelSize
            paint.textAlign = android.graphics.Paint.Align.LEFT
            paint.isFakeBoldText = true
            canvas.drawText(displayName, labelX + labelPadH, labelY + labelPadV - paint.ascent(), paint)
        }

        wpBitmapCache[cacheKey] = bmp
        return bmp
    }

    private fun drawSymbolInCircle(canvas: android.graphics.Canvas, paint: android.graphics.Paint, cx: Float, cy: Float, r: Float, symbol: String, scale: Float) {
        val ctx = context ?: return
        val drawableId = when (symbol.lowercase()) {
            "triangle" -> R.drawable.ic_sym_triangle
            "flag" -> R.drawable.ic_sym_flag
            "star" -> R.drawable.ic_sym_star
            "cross" -> R.drawable.ic_sym_cross
            "square" -> R.drawable.ic_sym_square
            "diamond" -> R.drawable.ic_sym_diamond
            "pin" -> R.drawable.ic_sym_pin
            else -> R.drawable.ic_sym_crosshair
        }
        val drawable = androidx.core.content.ContextCompat.getDrawable(ctx, drawableId) ?: return
        val size = (r * 1.4f).toInt()
        drawable.setBounds((cx - size / 2).toInt(), (cy - size / 2).toInt(), (cx + size / 2).toInt(), (cy + size / 2).toInt())
        drawable.setTint(android.graphics.Color.WHITE)
        drawable.draw(canvas)
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
        val approachSource = style.getSourceAs<GeoJsonSource>(WP_RADIUS_SOURCE_ID) ?: return
        val proximitySource = style.getSourceAs<GeoJsonSource>(WP_PROXIMITY_SOURCE_ID)
        val globalRadiusM = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.getInt(PREF_WP_APPROACH_RADIUS, DEFAULT_WP_APPROACH_RADIUS)?.toDouble()
            ?: DEFAULT_WP_APPROACH_RADIUS.toDouble()
        // Approach radius circles (orange) — global setting, same for all
        val approachFeatures = JSONArray()
        waypoints.forEach { wp ->
            approachFeatures.put(buildCirclePolygon(wp.lat, wp.lon, globalRadiusM))
        }
        approachSource.setGeoJson(JSONObject().put("type", "FeatureCollection").put("features", approachFeatures).toString())
        // Proximity circles (blue) — per-waypoint from file properties
        val proxFeatures = JSONArray()
        waypoints.forEach { wp ->
            if (wp.proximity > 0) {
                proxFeatures.put(buildCirclePolygon(wp.lat, wp.lon, wp.proximity))
            }
        }
        proximitySource?.setGeoJson(JSONObject().put("type", "FeatureCollection").put("features", proxFeatures).toString())
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
        val color = prefs.getString(PREF_NAV_LINE_COLOR, "#1565C0") ?: "#FF6F00"
        val width = prefs.getInt(PREF_NAV_LINE_WIDTH, 3).toFloat()
        val style = mapboxMap?.style ?: return
        style.getLayerAs<LineLayer>(NAV_LINE_LAYER_ID)?.setProperties(
            PropertyFactory.lineColor(color),
            PropertyFactory.lineWidth(width)
        )
    }

    fun applyRouteLineStyle() {
        val prefs = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) ?: return
        val color = prefs.getString(PREF_ROUTE_LINE_COLOR, "#B388FF") ?: "#FF6F00"
        val width = prefs.getInt(PREF_ROUTE_LINE_WIDTH, 2).toFloat()
        val style = mapboxMap?.style ?: return
        style.getLayerAs<LineLayer>(ROUTE_LINE_LAYER_ID)?.setProperties(
            PropertyFactory.lineColor(color),
            PropertyFactory.lineWidth(width)
        )
    }

    /** Scale factor 1-10 → multiplier */
    private fun wpScale(lvl: Int): Float = when (lvl) {
        1 -> 0.5f;  2 -> 0.7f;  3 -> 1.0f;  4 -> 1.3f;  5 -> 1.7f
        6 -> 2.1f;  7 -> 2.6f;  8 -> 3.1f;  9 -> 3.7f; 10 -> 4.3f
        else -> 1.0f
    }

    /** Icon size expressions interpolated by zoom — bitmap icons scale with map zoom */
    private fun wpIconSizeProps(): Array<PropertyValue<*>> {
        val expr = com.mapbox.mapboxsdk.style.expressions.Expression.interpolate(
            com.mapbox.mapboxsdk.style.expressions.Expression.linear(),
            com.mapbox.mapboxsdk.style.expressions.Expression.zoom(),
            com.mapbox.mapboxsdk.style.expressions.Expression.stop(6,  0.3f),
            com.mapbox.mapboxsdk.style.expressions.Expression.stop(10, 0.5f),
            com.mapbox.mapboxsdk.style.expressions.Expression.stop(14, 0.8f),
            com.mapbox.mapboxsdk.style.expressions.Expression.stop(18, 1.2f)
        )
        return arrayOf(PropertyFactory.iconSize(expr))
    }

    fun applyWpLabelSize() {
        wpBitmapCache.clear()
        updateWaypointsOnMap()
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
        binding.btnQuickAction.setOnClickListener { showQuickActionMenu() }

        // Restore follow mode from prefs
        val savedMode = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.getString(PREF_FOLLOW_MODE, "free") ?: "free"
        followMode = when (savedMode) {
            "north" -> FollowMode.FOLLOW_NORTH
            "course" -> FollowMode.FOLLOW_COURSE
            else -> FollowMode.FREE
        }
        applyFollowMode()
        updateCompassIndicator()

        binding.btnLayers.setOnClickListener { showLayerPicker() }

        // Quick map switch button
        binding.btnMapSwitch.setOnClickListener {
            val prefs = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) ?: return@setOnClickListener
            val mapA = prefs.getString(PREF_MAP_SWITCH_A, "ggc500") ?: "ggc500"
            val mapB = prefs.getString(PREF_MAP_SWITCH_B, "google") ?: "google"
            val current = prefs.getString(PREF_TILE_KEY, "osm") ?: "osm"
            val overlayKeys = prefs.getString(PREF_OVERLAY_KEY, "none") ?: "none"
            val next = if (current == mapA) mapB else mapA
            val nextName = tileSources[next]?.label ?: next
            loadTileStyle(next, overlayKeys.split(",").toSet())
            showHint("🗺 $nextName")
        }

        binding.compassView.setOnClickListener {
            when (followMode) {
                FollowMode.FREE -> {
                    followMode = FollowMode.FOLLOW_NORTH
                    applyFollowMode()
                    showHint("🧭 Следование: север вверху")
                }
                FollowMode.FOLLOW_NORTH -> {
                    followMode = FollowMode.FOLLOW_COURSE
                    applyFollowMode()
                    showHint("🧭 Следование: по курсу движения")
                }
                FollowMode.FOLLOW_COURSE -> {
                    followMode = FollowMode.FREE
                    applyFollowMode()
                    map.animateCamera(CameraUpdateFactory.bearingTo(0.0))
                    showHint("🧭 Свободный режим карты")
                }
            }
        }

        binding.btnRec.setOnClickListener { toggleRecording() }

        binding.btnLock.setOnClickListener { lockScreen() }

// Download indicator tap — show details dialog
        binding.downloadIndicator.setOnClickListener {
            showDownloadDetailsDialog()
        }
        binding.btnSettings.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .add(R.id.container, SettingsFragment())
                .addToBackStack(null)
                .commit()
        }

        binding.compassView.setOnLongClickListener { showDiagnostics(); true }

        // Long-press hints for all buttons
        binding.btnZoomIn.setOnLongClickListener { showHint("Приблизить карту"); true }
        binding.btnZoomOut.setOnLongClickListener { showHint("Отдалить карту"); true }
        binding.btnAddWaypoint.setOnLongClickListener { showHint("Поставить точку в центре карты"); true }
        binding.btnQuickAction.setOnLongClickListener { showHint("Управление данными: маршрут, трек, точки"); true }
        binding.btnLayers.setOnLongClickListener { showHint("Выбор слоёв карты"); true }
        binding.btnRec.setOnLongClickListener { showHint("Запись / остановка трека"); true }
        binding.btnLock.setOnLongClickListener { showHint("Блокировка экрана от случайных нажатий"); true }
        binding.btnSettings.setOnLongClickListener { showHint("Настройки приложения"); true }
        binding.btnPrevWp.setOnLongClickListener { showHint("Предыдущая точка маршрута"); true }
        binding.btnNextWp.setOnLongClickListener { showHint("Следующая точка маршрута"); true }
        binding.txtWpNavInfo.setOnLongClickListener { showHint("Нажми чтобы открыть список WP"); true }

        // Tripmaster reset on tap
        binding.widgetTripmaster.setOnClickListener {
            tripmasterDistM = 0.0
            tripmasterLastPoint = null
            binding.widgetTripmaster.text = "0.0"
            Toast.makeText(context, "Триппмастер сброшен", Toast.LENGTH_SHORT).show()
        }

        // Tap next CP widget to advance to next waypoint
        binding.widgetNextCp.setOnClickListener { advanceWaypoint() }

        // Waypoint navigation bar buttons
        binding.btnPrevWp.setOnClickListener { prevWaypoint() }
        binding.btnNextWp.setOnClickListener { advanceWaypoint() }
        binding.txtWpNavInfo.setOnClickListener { showWaypointList() }

        // Top bar GO / STOP buttons
        binding.btnWidgetGo.setOnClickListener { startNavigation() }
        binding.btnWidgetStop.setOnClickListener { stopNavigation() }
        // Init colors (grey = inactive)
        binding.btnWidgetGo.setTextColor(0xFF666666.toInt())
        binding.btnWidgetStop.setTextColor(0xFF666666.toInt())

        // Restore nav active state
        val prefs2 = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        navActive = prefs2?.getBoolean(PREF_NAV_ACTIVE, false) ?: false
        updateWaypointNavBar()

        map.addOnCameraMoveListener { updateCompass(); updateCrosshairInfo() }
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

    fun quickSwitchMap() {
        val prefs = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) ?: return
        val mapA = prefs.getString(PREF_MAP_SWITCH_A, "ggc500") ?: "ggc500"
        val mapB = prefs.getString(PREF_MAP_SWITCH_B, "google") ?: "google"
        val current = prefs.getString(PREF_TILE_KEY, "osm") ?: "osm"
        val overlayKeys = prefs.getString(PREF_OVERLAY_KEY, "none") ?: "none"
        val next = if (current == mapA) mapB else mapA
        val nextName = tileSources[next]?.label ?: next
        loadTileStyle(next, overlayKeys.split(",").toSet())
        showHint("🗺 $nextName")
    }

        fun lockScreen() {
        isScreenLocked = true
        _binding?.lockOverlay?.visibility = View.VISIBLE  // Always show overlay (blocks touches)
        // Show lock icon only when bottom bar is hidden
        val bottomBarVisible = _binding?.bottomBar?.visibility == View.VISIBLE
        val lockIcon = _binding?.lockOverlay?.getChildAt(0) as? android.widget.ImageView
        lockIcon?.visibility = if (bottomBarVisible) View.GONE else View.VISIBLE
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
        if (currentTileKey == key) loadTileStyle("osm", currentOverlayKeys)
        saveOfflineMapsToPrefs()
        // Delete the actual file to free storage
        java.io.File(info.path).takeIf { it.exists() }?.delete()
    }

    fun getOfflineMaps(): List<OfflineMapInfo> = offlineMaps.toList()

    fun getTileSources(): Map<String, TileSource> = tileSources

    fun getTileSourceInfoMap(): Map<String, Companion.TileSourceInfo> {
        val map = mutableMapOf<String, Companion.TileSourceInfo>()
        tileSources.filter { !it.key.startsWith(OFFLINE_TILE_KEY) && !it.key.startsWith("custom_") }
            .forEach { (k, v) -> map[k] = Companion.TileSourceInfo(v.urls, v.tms, v.maxZoom) }
        overlaySources.filter { it.key != "none" }
            .forEach { (k, v) -> map[k] = Companion.TileSourceInfo(v.urls, v.tms, v.maxZoom) }
        Log.d("TileDownload", "getTileSourceInfoMap: ${map.size} sources, keys=${map.keys}")
        return map
    }

    fun getCurrentZoom(): Double = mapboxMap?.cameraPosition?.zoom ?: 10.0

    fun getOverlaySources(): Map<String, OverlaySource> = overlaySources

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
        val vis = if (visible) "visible" else "none"
        val style = mapboxMap?.style
        style?.getLayer(WP_LAYER_ID)?.setProperties(PropertyFactory.visibility(vis))
        style?.getLayer(ROUTE_LINE_LAYER_ID)?.setProperties(PropertyFactory.visibility(vis))
        style?.getLayer(WP_RADIUS_LAYER_ID)?.setProperties(PropertyFactory.visibility(vis))
        style?.getLayer(WP_RADIUS_OUTLINE_LAYER_ID)?.setProperties(PropertyFactory.visibility(vis))
        style?.getLayer(WP_PROXIMITY_LAYER_ID)?.setProperties(PropertyFactory.visibility(vis))
        style?.getLayer(WP_PROXIMITY_OUTLINE_LAYER_ID)?.setProperties(PropertyFactory.visibility(vis))
        style?.getLayer(NAV_LINE_LAYER_ID)?.setProperties(PropertyFactory.visibility(vis))
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
    fun hasLoadedWaypoints() = waypoints.isNotEmpty()

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
        val nextIndex = activeWpIndex + 1
        if (nextIndex >= waypoints.size) {
            // Reached last waypoint — stop navigation and show summary
            stopNavigation()
            showRaceSummary()
            return
        }
        activeWpIndex = nextIndex
        wasInApproachRadius = false  // reset so approach sound fires for new КП
        context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)?.let { prefs ->
            prefs.edit().putInt(PREF_ACTIVE_WP_INDEX, activeWpIndex).apply()
            if (prefs.getBoolean(PREF_SOUND_TAKEN, true)) playWaypointTakenSound()
        }
        updateNavLine()
        updateWaypointNavBar()
        Toast.makeText(context, "WP${waypoints[activeWpIndex].index}: ${waypoints[activeWpIndex].name}", Toast.LENGTH_SHORT).show()
        // Immediately refresh distance widget with new target
        val b = _binding ?: return
        val wp = waypoints.getOrNull(activeWpIndex)
        if (wp != null) {
            val gps = lastKnownGpsPoint
            if (gps != null) {
                val distM = distanceM(gps, LatLng(wp.lat, wp.lon))
                b.widgetNextCp.text = if (distM < 1000) "${distM.toInt()}м" else String.format("%.1fкм", distM / 1000)
                val remKm = calcRemainingKm(gps)
                b.widgetRemainKm.text = if (remKm < 10) String.format("%.1f", remKm) else remKm.toInt().toString()
            } else {
                b.widgetNextCp.text = "--"
                b.widgetRemainKm.text = "--"
            }
            b.widgetNextCpName.text = wp.name.takeIf { it.isNotBlank() } ?: "WP${activeWpIndex + 1}"
        }
    }

    private fun addWaypointAtCurrentPosition() {
        // Place point at crosshair (map center), not GPS position
        val pos = mapboxMap?.cameraPosition?.target
        if (pos == null) {
            Toast.makeText(context, "Карта не готова", Toast.LENGTH_SHORT).show()
            return
        }
        val num = userMarkers.size + 1
        val wpName = "WP%02d".format(num)
        userMarkers.add(UserPoint(wpName, pos))
        updateUserMarkersOnMap()
        Toast.makeText(context, "$wpName поставлена", Toast.LENGTH_SHORT).show()
    }

    private fun updateUserMarkersOnMap() {
        val style = mapboxMap?.style ?: return
        val source = style.getSourceAs<GeoJsonSource>(USER_MARKER_SOURCE_ID) ?: return
        val features = JSONArray()
        userMarkers.forEachIndexed { i, pt ->
            val iconId = "um-icon-$i"
            val bmp = createMarkerBitmap(pt.name.ifBlank { "${i + 1}" })
            style.addImage(iconId, bmp)
            features.put(JSONObject()
                .put("type", "Feature")
                .put("geometry", JSONObject().put("type", "Point")
                    .put("coordinates", JSONArray().put(pt.position.longitude).put(pt.position.latitude)))
                .put("properties", JSONObject().put("icon", iconId)))
        }
        source.setGeoJson(JSONObject().put("type", "FeatureCollection").put("features", features).toString())
        saveUserPoints()
    }


    /** One-time migration: old dp/sp size values → 1-10 scale */
    private fun migrateSizePrefs() {
        val prefs = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) ?: return
        val migrated = prefs.getBoolean("size_scale_migrated", false)
        if (migrated) return
        val editor = prefs.edit()
        // PREF_MARKER_SIZE: old range 24-96 (dp), new 1-10
        val marker = prefs.getInt(PREF_MARKER_SIZE, DEFAULT_MARKER_SIZE)
        if (marker > 10) editor.putInt(PREF_MARKER_SIZE, DEFAULT_MARKER_SIZE)
        // PREF_LIVE_USER_SIZE: old range 20-80 (dp), new 1-10
        val liveUser = prefs.getInt(PREF_LIVE_USER_SIZE, DEFAULT_LIVE_USER_SIZE)
        if (liveUser > 10) editor.putInt(PREF_LIVE_USER_SIZE, DEFAULT_LIVE_USER_SIZE)
        // PREF_LIVE_USER_LABEL_SIZE: old range 8-20 (sp), new 1-10
        val liveLabel = prefs.getInt(PREF_LIVE_USER_LABEL_SIZE, DEFAULT_LIVE_USER_LABEL_SIZE)
        if (liveLabel > 10) editor.putInt(PREF_LIVE_USER_LABEL_SIZE, DEFAULT_LIVE_USER_LABEL_SIZE)
        // Reset colors to new defaults (one-time with this migration)
        if (!prefs.getBoolean("colors_v216_migrated", false)) {
            editor.putString(PREF_MARKER_COLOR, DEFAULT_MARKER_COLOR)  // red
            editor.remove(PREF_NAV_LINE_COLOR)     // blue default
            editor.remove(PREF_ROUTE_LINE_COLOR)   // purple default
            editor.putBoolean("colors_v216_migrated", true)
        }
        editor.putBoolean("size_scale_migrated", true).apply()
    }

    /** Create RaceNav directory on device storage for maps, tracks, points */
    private fun ensureAppDirectory() {
        val ctx = context ?: return
        val dirs = listOf("maps", "tracks", "points", "routes")
        val base = java.io.File(android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_DOCUMENTS), "RaceNav")
        dirs.forEach { java.io.File(base, it).mkdirs() }
    }

    private fun saveUserPoints() {
        val arr = JSONArray()
        userMarkers.forEach { p ->
            arr.put(JSONObject()
                .put("name", p.name)
                .put("lat", p.position.latitude)
                .put("lon", p.position.longitude))
        }
        context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)?.edit()
            ?.putString(PREF_USER_POINTS_JSON, arr.toString())?.apply()
    }

    private fun restoreUserPoints() {
        val json = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.getString(PREF_USER_POINTS_JSON, null) ?: return
        try {
            val arr = JSONArray(json)
            userMarkers.clear()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                userMarkers.add(UserPoint(
                    o.getString("name"),
                    LatLng(o.getDouble("lat"), o.getDouble("lon"))
                ))
            }
            if (userMarkers.isNotEmpty()) updateUserMarkersOnMap()
        } catch (_: Exception) {}
    }

    /** Blue circle bitmap with text label for user points (uses КП size settings) */
    private fun createMarkerBitmap(label: String): android.graphics.Bitmap {
        // Reuse createWaypointBitmap with blue color and crosshair symbol
        val tempWp = Waypoint(name = label, lat = 0.0, lon = 0.0, index = 0, color = "#1565C0", symbol = "")
        return createWaypointBitmap(tempWp)
    }

    fun hasUserMarkers() = userMarkers.isNotEmpty()

    fun getUserMarkersCopy(): List<Waypoint> = userMarkers.mapIndexed { i, pt ->
        Waypoint(pt.name, pt.position.latitude, pt.position.longitude, i + 1, color = "#1565C0")
    }

    /** Calculate total polyline length in meters */
    private fun calcPolylineLength(points: List<LatLng>): Double {
        var total = 0.0
        for (i in 1 until points.size) total += distanceM(points[i - 1], points[i])
        return total
    }

    private fun loadFileFromPicker(uri: Uri) {
        val ctx = context ?: return
        val cursor = ctx.contentResolver.query(uri, null, null, null, null)
        val name = cursor?.use {
            if (it.moveToFirst()) it.getString(it.getColumnIndexOrThrow(android.provider.OpenableColumns.DISPLAY_NAME))
            else null
        } ?: uri.lastPathSegment ?: "file"
        val ext = name.substringAfterLast('.', "").lowercase()
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val bytes = ctx.contentResolver.openInputStream(uri)?.readBytes()
                    ?: throw Exception("Не удалось открыть файл")

                when (ext) {
                    "gpx" -> {
                        val result = GpxParser.parseGpxFull(bytes.inputStream())
                        withContext(Dispatchers.Main) {
                            if (result.trackPoints.isEmpty() && result.waypoints.isEmpty()) {
                                Toast.makeText(ctx, "Файл пустой: $name", Toast.LENGTH_SHORT).show()
                                return@withContext
                            }
                            if (result.trackPoints.isNotEmpty()) {
                                loadTrack(result.trackPoints)
                                prefs.edit().putString(PREF_LOADED_TRACK_NAME, name).apply()
                            }
                            if (result.waypoints.isNotEmpty()) {
                                loadWaypoints(result.waypoints)
                                prefs.edit().putString(PREF_ROUTE_NAME, name.substringBeforeLast('.')).apply()
                            }
                            Toast.makeText(ctx, "Загружено: $name", Toast.LENGTH_SHORT).show()
                        }
                    }
                    "wpt" -> {
                        val wpts = GpxParser.parseWpt(bytes.inputStream())
                        withContext(Dispatchers.Main) {
                            if (wpts.isNotEmpty()) {
                                loadWaypoints(wpts)
                                prefs.edit().putString(PREF_ROUTE_NAME, name.substringBeforeLast('.')).apply()
                                Toast.makeText(ctx, "Загружено WP: ${wpts.size}", Toast.LENGTH_SHORT).show()
                            } else Toast.makeText(ctx, "Файл пустой: $name", Toast.LENGTH_SHORT).show()
                        }
                    }
                    "rte" -> {
                        val result = try { GpxParser.parseGpxFull(bytes.inputStream()) } catch (_: Exception) { null }
                        val wpts = if (result != null && result.waypoints.isNotEmpty()) result.waypoints
                            else GpxParser.parseRteOzi(bytes.inputStream())
                        val trackPts = result?.trackPoints
                        withContext(Dispatchers.Main) {
                            if (trackPts != null && trackPts.isNotEmpty()) {
                                loadTrack(trackPts)
                                prefs.edit().putString(PREF_LOADED_TRACK_NAME, name).apply()
                            }
                            if (wpts.isNotEmpty()) {
                                loadWaypoints(wpts)
                                prefs.edit().putString(PREF_ROUTE_NAME, name.substringBeforeLast('.')).apply()
                                Toast.makeText(ctx, "Загружено WP: ${wpts.size}", Toast.LENGTH_SHORT).show()
                            } else if (trackPts.isNullOrEmpty()) {
                                Toast.makeText(ctx, "Файл пустой: $name", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    "plt" -> {
                        val pts = GpxParser.parsePltTrack(bytes.inputStream())
                        withContext(Dispatchers.Main) {
                            if (pts.isNotEmpty()) {
                                loadTrack(pts)
                                prefs.edit().putString(PREF_LOADED_TRACK_NAME, name).apply()
                                Toast.makeText(ctx, "Загружен трек: ${pts.size} точек", Toast.LENGTH_SHORT).show()
                            } else Toast.makeText(ctx, "Файл пустой: $name", Toast.LENGTH_SHORT).show()
                        }
                    }
                    "kml", "kmz" -> withContext(Dispatchers.Main) {
                        Toast.makeText(ctx, "Формат .$ext: используйте GPX/WPT/PLT/RTE", Toast.LENGTH_SHORT).show()
                    }
                    else -> withContext(Dispatchers.Main) {
                        Toast.makeText(ctx, "Неизвестный формат: .$ext", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(ctx, "Ошибка загрузки: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showQuickActionMenu() {
        val ctx = context ?: return
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val dialog = BottomSheetDialog(ctx)
        val pad = 24

        val root = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(android.graphics.Color.parseColor("#1A1A1A"))
        }

        // Title
        root.addView(android.widget.TextView(ctx).apply {
            text = "📋 Загруженные данные"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 18f
            setPadding(0, 0, 0, 16)
        })

        // --- ROUTE SECTION ---
        val routeName = prefs.getString(PREF_ROUTE_NAME, null)
        if (waypoints.isNotEmpty()) {
            val wpLatLngs = waypoints.map { LatLng(it.lat, it.lon) }
            val routeLenM = calcPolylineLength(wpLatLngs)
            val routeLenKm = routeLenM / 1000.0
            val wpVisible = prefs.getBoolean(PREF_LOADED_WP_VISIBLE, true)

            root.addView(android.widget.TextView(ctx).apply {
                text = "🗺 Маршрут: ${routeName ?: "Без имени"}"
                setTextColor(android.graphics.Color.parseColor("#FF6F00"))
                textSize = 15f
                setPadding(0, 8, 0, 4)
            })
            root.addView(android.widget.TextView(ctx).apply {
                text = "   WP: ${waypoints.size}  •  Длина: ${"%.1f".format(routeLenKm)} км"
                setTextColor(android.graphics.Color.parseColor("#CCCCCC"))
                textSize = 13f
            })

            // Buttons row
            val routeRow = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                setPadding(0, 8, 0, 12)
            }
            val btnWrapLp = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

            // Show/Hide
            routeRow.addView(android.widget.Button(ctx).apply {
                text = if (wpVisible) "👁 Скрыть" else "👁 Показать"
                textSize = 12f; isAllCaps = false
                layoutParams = btnWrapLp
                setOnClickListener {
                    val newVis = !prefs.getBoolean(PREF_LOADED_WP_VISIBLE, true)
                    setLoadedWpVisible(newVis)
                    setLoadedTrackVisible(newVis) // route line too
                    text = if (newVis) "👁 Скрыть" else "👁 Показать"
                }
            })

            // Start/Stop nav
            routeRow.addView(android.widget.Button(ctx).apply {
                text = if (navActive) "⏹ Стоп" else "▶ Старт"
                textSize = 12f; isAllCaps = false
                layoutParams = btnWrapLp
                setOnClickListener {
                    if (navActive) stopNavigation() else startNavigation()
                    text = if (navActive) "⏹ Стоп" else "▶ Старт"
                }
            })

            // Clear route from map (with confirmation)
            routeRow.addView(android.widget.Button(ctx).apply {
                text = "🗑 Очистить"
                textSize = 12f; isAllCaps = false
                layoutParams = btnWrapLp
                setOnClickListener {
                    android.app.AlertDialog.Builder(ctx)
                        .setTitle("Очистить маршрут?")
                        .setMessage("Маршрут «${routeName ?: "Без имени"}» (${waypoints.size} WP) будет убран с карты")
                        .setPositiveButton("Очистить") { _, _ ->
                            stopNavigation()
                            waypoints.clear()
                            activeWpIndex = 0
                            updateWaypointsOnMap()
                            updateRouteLineOnMap()
                            updateRadiusCircles()
                            updateNavLine()
                            updateWaypointNavBar()
                            saveWaypointsToPrefs()
                            prefs.edit().remove(PREF_ROUTE_NAME).remove(PREF_LOADED_WP_NAME).apply()
                            dialog.dismiss()
                            Toast.makeText(ctx, "Маршрут очищен", Toast.LENGTH_SHORT).show()
                        }
                        .setNegativeButton("Отмена", null)
                        .show()
                }
            })
            root.addView(routeRow)
        } else {
            root.addView(android.widget.TextView(ctx).apply {
                text = "🗺 Маршрут не загружен"
                setTextColor(android.graphics.Color.parseColor("#888888"))
                textSize = 14f
                setPadding(0, 8, 0, 12)
            })
        }

        // Divider
        root.addView(android.view.View(ctx).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1)
            setBackgroundColor(android.graphics.Color.parseColor("#444444"))
        })

        // --- TRACK SECTION ---
        if (loadedTrackPoints.isNotEmpty()) {
            val trackLenM = calcPolylineLength(loadedTrackPoints)
            val trackLenKm = trackLenM / 1000.0
            val trkVisible = prefs.getBoolean(PREF_LOADED_TRACK_VISIBLE, true)
            val trackName = prefs.getString(PREF_LOADED_TRACK_NAME, "Трек") ?: "Трек"

            root.addView(android.widget.TextView(ctx).apply {
                text = "📍 $trackName"
                setTextColor(android.graphics.Color.parseColor("#2196F3"))
                textSize = 15f
                setPadding(0, 12, 0, 4)
            })
            root.addView(android.widget.TextView(ctx).apply {
                text = "   Точек: ${loadedTrackPoints.size}  •  Длина: ${"%.1f".format(trackLenKm)} км"
                setTextColor(android.graphics.Color.parseColor("#CCCCCC"))
                textSize = 13f
            })

            val trackRow = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                setPadding(0, 8, 0, 12)
            }
            val btnLp = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

            trackRow.addView(android.widget.Button(ctx).apply {
                text = if (trkVisible) "👁 Скрыть" else "👁 Показать"
                textSize = 12f; isAllCaps = false
                layoutParams = btnLp
                setOnClickListener {
                    val newVis = !prefs.getBoolean(PREF_LOADED_TRACK_VISIBLE, true)
                    setLoadedTrackVisible(newVis)
                    text = if (newVis) "👁 Скрыть" else "👁 Показать"
                }
            })

            trackRow.addView(android.widget.Button(ctx).apply {
                text = "🗑 Очистить"
                textSize = 12f; isAllCaps = false
                layoutParams = btnLp
                setOnClickListener {
                    android.app.AlertDialog.Builder(ctx)
                        .setTitle("Очистить трек?")
                        .setMessage("Трек «$trackName» (${loadedTrackPoints.size} точек) будет убран с карты")
                        .setPositiveButton("Очистить") { _, _ ->
                            loadedTrackPoints.clear()
                            mapboxMap?.style?.getSourceAs<GeoJsonSource>(LOADED_TRACK_SOURCE_ID)
                                ?.setGeoJson("{\"type\":\"FeatureCollection\",\"features\":[]}")
                            saveTrackToPrefs()
                            prefs.edit().remove(PREF_LOADED_TRACK_NAME).apply()
                            dialog.dismiss()
                            Toast.makeText(ctx, "Трек очищен", Toast.LENGTH_SHORT).show()
                        }
                        .setNegativeButton("Отмена", null)
                        .show()
                }
            })
            root.addView(trackRow)
        } else {
            root.addView(android.widget.TextView(ctx).apply {
                text = "📍 Трек не загружен"
                setTextColor(android.graphics.Color.parseColor("#888888"))
                textSize = 14f
                setPadding(0, 12, 0, 12)
            })
        }

        // Divider
        root.addView(android.view.View(ctx).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1)
            setBackgroundColor(android.graphics.Color.parseColor("#444444"))
        })

        // --- POINTS SECTION ---
        root.addView(android.widget.TextView(ctx).apply {
            text = "📌 Точки: ${userMarkers.size}"
            setTextColor(android.graphics.Color.parseColor("#FFD600"))
            textSize = 15f
            setPadding(0, 12, 0, 4)
        })
        if (userMarkers.isNotEmpty()) {
            // List of all points
            userMarkers.forEachIndexed { i, pt ->
                val pointRow = android.widget.LinearLayout(ctx).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    setPadding(8, 6, 8, 6)
                    gravity = android.view.Gravity.CENTER_VERTICAL
                }
                // Point name (clickable to edit)
                pointRow.addView(android.widget.TextView(ctx).apply {
                    text = "  ${i+1}. ${pt.name}"
                    setTextColor(android.graphics.Color.parseColor("#CCCCCC"))
                    textSize = 13f
                    layoutParams = android.widget.LinearLayout.LayoutParams(0,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    // Click to edit name
                    setOnClickListener {
                        val input = android.widget.EditText(ctx).apply {
                            setText(pt.name)
                            setTextColor(0xFFFFFFFF.toInt())
                            setBackgroundColor(0xFF2A2A2A.toInt())
                            setPadding(24, 16, 24, 16)
                        }
                        android.app.AlertDialog.Builder(ctx)
                            .setTitle("Имя точки")
                            .setView(input)
                            .setPositiveButton("OK") { _, _ ->
                                pt.name = input.text.toString().ifBlank { "Точка ${i+1}" }
                                updateUserMarkersOnMap()
                                dialog.dismiss()
                                showQuickActionMenu()
                            }
                            .setNegativeButton("Отмена", null)
                            .show()
                    }
                })
                // Edit icon
                pointRow.addView(android.widget.TextView(ctx).apply {
                    text = "✏️"; textSize = 14f; setPadding(4, 0, 4, 0)
                    setOnClickListener {
                        val input = android.widget.EditText(ctx).apply {
                            setText(pt.name); setTextColor(0xFFFFFFFF.toInt())
                            setBackgroundColor(0xFF2A2A2A.toInt()); setPadding(24, 16, 24, 16)
                        }
                        android.app.AlertDialog.Builder(ctx)
                            .setTitle("Имя точки")
                            .setView(input)
                            .setPositiveButton("OK") { _, _ ->
                                pt.name = input.text.toString().ifBlank { "Точка ${i+1}" }
                                updateUserMarkersOnMap()
                                dialog.dismiss(); showQuickActionMenu()
                            }
                            .setNegativeButton("Отмена", null)
                            .show()
                    }
                })
                // Navigate to point
                pointRow.addView(android.widget.TextView(ctx).apply {
                    text = "🧭"; textSize = 14f; setPadding(4, 0, 4, 0)
                    setOnClickListener {
                        dialog.dismiss()
                        // Navigate to this single point
                        startNavigationToPoint(pt.position, pt.name)
                    }
                })
                // Go to point on map
                pointRow.addView(android.widget.TextView(ctx).apply {
                    text = "📍"; textSize = 14f; setPadding(4, 0, 4, 0)
                    setOnClickListener {
                        dialog.dismiss()
                        mapboxMap?.animateCamera(CameraUpdateFactory.newLatLng(pt.position))
                    }
                })
                // Delete point
                pointRow.addView(android.widget.TextView(ctx).apply {
                    text = "✕"; textSize = 14f
                    setTextColor(android.graphics.Color.parseColor("#EF4444"))
                    setPadding(4, 0, 4, 0)
                    setOnClickListener {
                        userMarkers.removeAt(i)
                        updateUserMarkersOnMap()
                        dialog.dismiss(); showQuickActionMenu()
                    }
                })
                root.addView(pointRow)
            }
            // Buttons row
            val markerRow = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                setPadding(0, 8, 0, 12)
            }
            val mLp = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

            markerRow.addView(android.widget.Button(ctx).apply {
                text = "📤 Экспорт GPX"
                textSize = 12f; isAllCaps = false
                layoutParams = mLp
                setOnClickListener {
                    val wps = userMarkers.mapIndexed { idx, p ->
                        Waypoint(p.name, p.position.latitude, p.position.longitude, idx+1)
                    }
                    val gpx = GpxParser.writeWaypointsGpx(wps, "Точки")
                    val dir = getRaceNavDir(ctx, "points")
                    val file = java.io.File(dir, "points_${System.currentTimeMillis()}.gpx")
                    file.writeText(gpx)
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        ctx, "${ctx.packageName}.provider", file)
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/gpx+xml"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(intent, "Отправить точки"))
                }
            })

            markerRow.addView(android.widget.Button(ctx).apply {
                text = "🗑 Очистить все"
                textSize = 12f; isAllCaps = false
                layoutParams = mLp
                setOnClickListener {
                    android.app.AlertDialog.Builder(ctx)
                        .setTitle("Очистить все точки?")
                        .setMessage("${userMarkers.size} точек будут удалены")
                        .setPositiveButton("Очистить") { _, _ ->
                            userMarkers.clear()
                            updateUserMarkersOnMap()
                            dialog.dismiss()
                            Toast.makeText(ctx, "Точки очищены", Toast.LENGTH_SHORT).show()
                        }
                        .setNegativeButton("Отмена", null)
                        .show()
                }
            })
            root.addView(markerRow)
        } else {
            root.addView(android.widget.TextView(ctx).apply {
                text = "   Нет установленных точек"
                setTextColor(android.graphics.Color.parseColor("#888888"))
                textSize = 13f
                setPadding(0, 4, 0, 12)
            })
        }

        // --- ROUTE EDITOR BUTTON ---
        root.addView(android.widget.Button(ctx).apply {
            text = "✏️ Редактор маршрута"
            textSize = 14f; isAllCaps = false
            setPadding(0, 12, 0, 0)
            setOnClickListener {
                dialog.dismiss()
                showRouteEditor()
            }
        })

        // --- LOAD FILE BUTTON ---
        root.addView(android.widget.Button(ctx).apply {
            text = "📂 Загрузить файл"
            textSize = 14f; isAllCaps = false
            setPadding(0, 12, 0, 0)
            setOnClickListener {
                dialog.dismiss()
                filePickerLauncher.launch(arrayOf("*/*", "application/gpx+xml", "application/octet-stream"))
            }
        })

        // Apply dark background to BottomSheet
        val scroll = android.widget.ScrollView(ctx).apply {
            addView(root)
            setBackgroundColor(android.graphics.Color.parseColor("#1A1A1A"))
        }
        dialog.setContentView(scroll)
        dialog.window?.navigationBarColor = android.graphics.Color.parseColor("#1A1A1A")
        (scroll.parent as? android.view.View)?.setBackgroundColor(android.graphics.Color.parseColor("#1A1A1A"))
        dialog.show()
        dialog.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
    }

    /** Full list of waypoints with statuses — tap on nav bar to open */
    private fun showWaypointList() {
        val ctx = context ?: return
        if (waypoints.isEmpty()) {
            Toast.makeText(ctx, "Маршрут не загружен", Toast.LENGTH_SHORT).show()
            return
        }

        val dialog = BottomSheetDialog(ctx)
        val pad = 24
        val root = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        val routeName = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREF_ROUTE_NAME, "Маршрут") ?: "Маршрут"

        root.addView(android.widget.TextView(ctx).apply {
            text = "🗺 $routeName — ${waypoints.size} WP"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 12)
        })

        val gps = lastKnownGpsPoint

        waypoints.forEachIndexed { i, wp ->
            val isPassed = i < activeWpIndex
            val isCurrent = i == activeWpIndex
            val isFuture = i > activeWpIndex

            val row = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                setPadding(0, 8, 0, 8)
                gravity = android.view.Gravity.CENTER_VERTICAL
                if (isCurrent) setBackgroundColor(android.graphics.Color.parseColor("#1AFF6F00"))
            }

            // Status icon
            val status = when {
                isPassed -> "✅"
                isCurrent -> "🎯"
                else -> "⬜"
            }
            row.addView(android.widget.TextView(ctx).apply {
                text = status
                textSize = 16f
                setPadding(0, 0, 12, 0)
            })

            // Info column
            val info = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                layoutParams = android.widget.LinearLayout.LayoutParams(0,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            info.addView(android.widget.TextView(ctx).apply {
                text = "${i + 1}. ${wp.name}"
                textSize = 14f
                setTextColor(when {
                    isPassed -> android.graphics.Color.parseColor("#666666")
                    isCurrent -> android.graphics.Color.parseColor("#FF6F00")
                    else -> android.graphics.Color.WHITE
                })
                if (isCurrent) setTypeface(null, android.graphics.Typeface.BOLD)
            })

            // Distance from GPS
            if (gps != null) {
                val dist = distanceM(gps, LatLng(wp.lat, wp.lon))
                val distStr = if (dist < 1000) "${dist.toInt()} м" else String.format("%.1f км", dist / 1000)
                info.addView(android.widget.TextView(ctx).apply {
                    text = distStr
                    textSize = 11f
                    setTextColor(android.graphics.Color.parseColor("#888888"))
                })
            }
            row.addView(info)

            // Go-to button
            row.addView(android.widget.Button(ctx).apply {
                text = "→"
                textSize = 14f
                setTextColor(android.graphics.Color.parseColor("#FF6F00"))
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                setOnClickListener {
                    activeWpIndex = i
                    ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit().putInt(PREF_ACTIVE_WP_INDEX, i).apply()
                    updateNavLine()
                    updateWaypointNavBar()
                    if (gps != null) {
                        val b = _binding ?: return@setOnClickListener
                        val distM = distanceM(gps, LatLng(wp.lat, wp.lon))
                        b.widgetNextCp.text = if (distM < 1000) "${distM.toInt()}м" else String.format("%.1f", distM / 1000)
                        val remKm = calcRemainingKm(gps)
                        b.widgetRemainKm.text = if (remKm < 10) String.format("%.1f", remKm) else remKm.toInt().toString()
                        b.widgetNextCpName.text = wp.name.takeIf { it.isNotBlank() } ?: "WP${i + 1}"
                    }
                    dialog.dismiss()
                    Toast.makeText(ctx, "→ WP${i + 1}: ${wp.name}", Toast.LENGTH_SHORT).show()
                }
            })

            root.addView(row)
        }

        val scroll = android.widget.ScrollView(ctx).apply { addView(root) }
        dialog.setContentView(scroll)
        dialog.window?.navigationBarColor = android.graphics.Color.parseColor("#1A1A1A")
        (scroll.parent as? android.view.View)?.setBackgroundColor(android.graphics.Color.parseColor("#1A1A1A"))
        dialog.show()
    }

    /** Full route editor — create/edit/delete waypoints, set radius, save as GPX */
    fun showRouteEditor() {
        val ctx = context ?: return
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val dialog = BottomSheetDialog(ctx)
        val pad = 24

        // Editing copy of waypoints — if empty, import user markers
        val editWps = if (waypoints.isNotEmpty()) {
            waypoints.map { it.copy() }.toMutableList()
        } else {
            userMarkers.mapIndexed { i, pt ->
                Waypoint(pt.name, pt.position.latitude, pt.position.longitude, i + 1, color = "#1565C0")
            }.toMutableList()
        }
        val approachRadius = prefs.getInt(PREF_WP_APPROACH_RADIUS, DEFAULT_WP_APPROACH_RADIUS)

        val dp = resources.displayMetrics.density
        val root = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(android.graphics.Color.parseColor("#1A1A1A"))
        }

        // Title
        root.addView(android.widget.TextView(ctx).apply {
            text = "✏️ Редактор маршрута"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 12)
        })

        // Approach radius setting
        val radiusRow = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 4, 0, 12)
        }
        radiusRow.addView(android.widget.TextView(ctx).apply {
            text = "Радиус сближения: "
            setTextColor(android.graphics.Color.parseColor("#CCCCCC"))
            textSize = 14f
        })
        val radiusInput = android.widget.EditText(ctx).apply {
            setText(approachRadius.toString())
            setTextColor(android.graphics.Color.WHITE)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            layoutParams = android.widget.LinearLayout.LayoutParams(120, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT)
            textSize = 14f
        }
        radiusRow.addView(radiusInput)
        radiusRow.addView(android.widget.TextView(ctx).apply {
            text = " м"
            setTextColor(android.graphics.Color.parseColor("#CCCCCC"))
            textSize = 14f
        })
        root.addView(radiusRow)

        // Hint for drag
        root.addView(android.widget.TextView(ctx).apply {
            text = "≡ — перетащите для сортировки"
            setTextColor(android.graphics.Color.parseColor("#666666"))
            textSize = 11f
            setPadding(0, 0, 0, 8)
        })

        // RecyclerView with drag & drop
        val recyclerView = androidx.recyclerview.widget.RecyclerView(ctx).apply {
            layoutManager = androidx.recyclerview.widget.LinearLayoutManager(ctx)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val adapter = RouteEditorAdapter(
            items = editWps,
            dp = dp,
            distanceCalc = { a, b -> distanceM(LatLng(a.lat, a.lon), LatLng(b.lat, b.lon)).toFloat() },
            onEdit = { i ->
                showEditWpDialog(editWps, i) { recyclerView.adapter?.notifyDataSetChanged() }
            },
            onDelete = { i ->
                if (i in editWps.indices) {
                    editWps.removeAt(i)
                    recyclerView.adapter?.notifyDataSetChanged()
                }
            }
        )
        recyclerView.adapter = adapter

        val touchCallback = RouteItemTouchCallback(adapter)
        val itemTouchHelper = androidx.recyclerview.widget.ItemTouchHelper(touchCallback)
        itemTouchHelper.attachToRecyclerView(recyclerView)
        adapter.dragStartListener = { vh -> itemTouchHelper.startDrag(vh) }

        root.addView(recyclerView)

        // Buttons
        val btnRow = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setPadding(0, 12, 0, 4)
        }
        val btnLp = android.widget.LinearLayout.LayoutParams(0,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

        btnRow.addView(android.widget.Button(ctx).apply {
            text = "📌 Добавить WP с карты"; textSize = 12f; isAllCaps = false
            layoutParams = btnLp
            setOnClickListener {
                if (userMarkers.isEmpty()) {
                    Toast.makeText(ctx, "Нет точек на карте. Поставьте точки в свободном режиме", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                if (editWps.size >= MAX_WAYPOINTS) {
                    Toast.makeText(ctx, "Максимум $MAX_WAYPOINTS точек", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                // Show checklist of user markers
                val names = userMarkers.map { it.name }.toTypedArray()
                val checked = BooleanArray(names.size) { true }
                androidx.appcompat.app.AlertDialog.Builder(ctx, androidx.appcompat.R.style.Theme_AppCompat_Dialog)
                    .setTitle("Выберите точки")
                    .setMultiChoiceItems(names, checked) { _, which, isChecked -> checked[which] = isChecked }
                    .setPositiveButton("Добавить") { _, _ ->
                        var added = 0
                        userMarkers.forEachIndexed { idx, pt ->
                            if (checked[idx] && editWps.size < MAX_WAYPOINTS) {
                                val num = editWps.size + 1
                                editWps.add(Waypoint(pt.name, pt.position.latitude, pt.position.longitude, num, color = "#1565C0"))
                                added++
                            }
                        }
                        adapter.notifyDataSetChanged()
                        Toast.makeText(ctx, "Добавлено $added точек", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Отмена", null)
                    .show()
            }
        })
        root.addView(btnRow)

        // Apply + Save buttons
        val saveRow = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setPadding(0, 8, 0, 0)
        }

        saveRow.addView(android.widget.Button(ctx).apply {
            text = "✅ Применить"
            textSize = 13f; isAllCaps = false
            layoutParams = btnLp
            setOnClickListener {
                val newRadius = radiusInput.text.toString().toIntOrNull() ?: approachRadius
                prefs.edit().putInt(PREF_WP_APPROACH_RADIUS, newRadius).apply()
                waypoints.clear()
                editWps.forEachIndexed { idx, wp -> waypoints.add(wp.copy(index = idx + 1)) }
                activeWpIndex = 0
                wpBitmapCache.clear()
                updateWaypointsOnMap()
                updateRouteLineOnMap()
                updateRadiusCircles()
                updateNavLine()
                updateWaypointNavBar()
                saveWaypointsToPrefs()
                dialog.dismiss()
                Toast.makeText(ctx, "Маршрут обновлён: ${waypoints.size} WP", Toast.LENGTH_SHORT).show()
            }
        })

        saveRow.addView(android.widget.Button(ctx).apply {
            text = "💾 Сохранить GPX"
            textSize = 13f; isAllCaps = false
            layoutParams = btnLp
            setOnClickListener {
                if (editWps.isEmpty()) {
                    Toast.makeText(ctx, "Нет точек для сохранения", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val routeName = prefs.getString(PREF_ROUTE_NAME, "Маршрут") ?: "Маршрут"
                val reindexed = editWps.mapIndexed { idx, wp -> wp.copy(index = idx + 1) }
                val gpx = GpxParser.writeWaypointsGpx(reindexed, routeName)
                val dir = getRaceNavDir(ctx, "routes")
                val file = java.io.File(dir, "route_${System.currentTimeMillis()}.gpx")
                file.writeText(gpx)
                val uri = androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.provider", file)
                val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "application/gpx+xml"
                    putExtra(android.content.Intent.EXTRA_STREAM, uri)
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(android.content.Intent.createChooser(intent, "Сохранить маршрут"))
            }
        })
        root.addView(saveRow)

        dialog.setContentView(root)
        dialog.window?.navigationBarColor = android.graphics.Color.parseColor("#1A1A1A")
        (root.parent as? android.view.View)?.setBackgroundColor(android.graphics.Color.parseColor("#1A1A1A"))
        dialog.show()
        dialog.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
    }

    /** Dialog to edit a single waypoint's name and coordinates */
    private fun showEditWpDialog(wps: MutableList<Waypoint>, index: Int, onDone: () -> Unit) {
        val ctx = context ?: return
        val wp = wps.getOrNull(index) ?: return
        val pad = 24

        val root = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(android.graphics.Color.parseColor("#1A1A1A"))
        }

        val inputName = android.widget.EditText(ctx).apply {
            hint = "Имя точки"
            setText(wp.name)
            setTextColor(android.graphics.Color.WHITE)
            setHintTextColor(android.graphics.Color.parseColor("#666666"))
        }
        root.addView(inputName)

        val inputLat = android.widget.EditText(ctx).apply {
            hint = "Широта"
            setText(wp.lat.toString())
            setTextColor(android.graphics.Color.WHITE)
            setHintTextColor(android.graphics.Color.parseColor("#666666"))
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
        }
        root.addView(inputLat)

        val inputLon = android.widget.EditText(ctx).apply {
            hint = "Долгота"
            setText(wp.lon.toString())
            setTextColor(android.graphics.Color.WHITE)
            setHintTextColor(android.graphics.Color.parseColor("#666666"))
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
        }
        root.addView(inputLon)

        val inputDesc = android.widget.EditText(ctx).apply {
            hint = "Описание (необязательно)"
            setText(wp.description)
            setTextColor(android.graphics.Color.WHITE)
            setHintTextColor(android.graphics.Color.parseColor("#666666"))
        }
        root.addView(inputDesc)

        androidx.appcompat.app.AlertDialog.Builder(ctx, androidx.appcompat.R.style.Theme_AppCompat_Dialog)
            .setTitle("Редактировать WP ${index + 1}")
            .setView(root)
            .setPositiveButton("OK") { _, _ ->
                val name = inputName.text.toString().trim().ifBlank { "WP${index + 1}" }
                val lat = inputLat.text.toString().toDoubleOrNull() ?: wp.lat
                val lon = inputLon.text.toString().toDoubleOrNull() ?: wp.lon
                val desc = inputDesc.text.toString().trim()
                wps[index] = wp.copy(name = name, lat = lat, lon = lon, index = index + 1, description = desc)
                onDone()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    /** Enhanced edit dialog with color, symbol, proximity */
    private fun showEnhancedEditWpDialog(index: Int) {
        val ctx = context ?: return
        val wp = waypoints.getOrNull(index) ?: return
        val pad = 24
        val dp = resources.displayMetrics.density

        val root = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(android.graphics.Color.parseColor("#1A1A1A"))
        }

        fun label(text: String) = android.widget.TextView(ctx).apply {
            this.text = text
            setTextColor(android.graphics.Color.parseColor("#AAAAAA"))
            textSize = 12f
            setPadding(0, 12, 0, 2)
        }

        // Name
        root.addView(label("Имя"))
        val inputName = android.widget.EditText(ctx).apply {
            setText(wp.name); setTextColor(android.graphics.Color.WHITE)
            setHintTextColor(android.graphics.Color.parseColor("#666666"))
            hint = "Имя точки"
        }
        root.addView(inputName)

        // Description
        root.addView(label("Описание"))
        val inputDesc = android.widget.EditText(ctx).apply {
            setText(wp.description); setTextColor(android.graphics.Color.WHITE)
            setHintTextColor(android.graphics.Color.parseColor("#666666"))
            hint = "Описание (необязательно)"
            minLines = 2; inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        root.addView(inputDesc)

        // Coordinates
        val coordRow = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
        }
        root.addView(label("Координаты"))
        val inputLat = android.widget.EditText(ctx).apply {
            hint = "Широта"; setText(wp.lat.toString()); setTextColor(android.graphics.Color.WHITE)
            setHintTextColor(android.graphics.Color.parseColor("#666666"))
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
            layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val inputLon = android.widget.EditText(ctx).apply {
            hint = "Долгота"; setText(wp.lon.toString()); setTextColor(android.graphics.Color.WHITE)
            setHintTextColor(android.graphics.Color.parseColor("#666666"))
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
            layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        coordRow.addView(inputLat); coordRow.addView(inputLon)
        root.addView(coordRow)

        // Symbol selector
        root.addView(label("Символ"))
        val symbols = listOf("" to "⊕", "triangle" to "△", "flag" to "⚑", "star" to "★", "cross" to "✚", "square" to "■", "diamond" to "◆", "pin" to "📍")
        var selectedSymbol = wp.symbol
        val symbolGrid = android.widget.GridLayout(ctx).apply { columnCount = 4; setPadding(0, 4, 0, 8) }
        val symbolViews = mutableListOf<android.widget.TextView>()
        val swatchSz = (44 * dp).toInt()
        val gapSz = (6 * dp).toInt()
        symbols.forEach { (key, label) ->
            val tv = android.widget.TextView(ctx).apply {
                text = label; textSize = 20f; gravity = android.view.Gravity.CENTER
                setTextColor(if (key == selectedSymbol) android.graphics.Color.parseColor("#FF6F00") else android.graphics.Color.WHITE)
                setBackgroundColor(if (key == selectedSymbol) android.graphics.Color.parseColor("#333333") else android.graphics.Color.TRANSPARENT)
                val lp = android.widget.GridLayout.LayoutParams().apply { width = swatchSz; height = swatchSz; setMargins(gapSz, gapSz, gapSz, gapSz) }
                layoutParams = lp
            }
            tv.setOnClickListener {
                selectedSymbol = key
                symbolViews.forEach { v ->
                    val isSelected = v == tv
                    v.setTextColor(if (isSelected) android.graphics.Color.parseColor("#FF6F00") else android.graphics.Color.WHITE)
                    v.setBackgroundColor(if (isSelected) android.graphics.Color.parseColor("#333333") else android.graphics.Color.TRANSPARENT)
                }
            }
            symbolViews.add(tv)
            symbolGrid.addView(tv)
        }
        root.addView(symbolGrid)

        // Color picker
        root.addView(label("Цвет"))
        val colors = listOf("#FF6F00", "#FFFF00", "#FFFFFF", "#00FF00", "#FF4444", "#00BFFF",
            "#FF00FF", "#1565C0", "#00E676", "#FF8A80", "#B388FF", "#CCCCCC")
        var selectedColor = wp.color.ifBlank { "#FF6F00" }
        val colorGrid = android.widget.GridLayout(ctx).apply { columnCount = 6; setPadding(0, 4, 0, 8) }
        val colorViews = mutableListOf<android.view.View>()
        val colorSz = (36 * dp).toInt()
        colors.forEach { hex ->
            val swatch = android.view.View(ctx).apply {
                setBackgroundColor(android.graphics.Color.parseColor(hex))
                val lp = android.widget.GridLayout.LayoutParams().apply { width = colorSz; height = colorSz; setMargins(gapSz, gapSz, gapSz, gapSz) }
                layoutParams = lp
                if (hex == selectedColor) {
                    foreground = android.graphics.drawable.GradientDrawable().apply {
                        setStroke((3 * dp).toInt(), android.graphics.Color.WHITE)
                        cornerRadius = 4 * dp
                    }
                }
            }
            swatch.setOnClickListener {
                selectedColor = hex
                colorViews.forEach { v ->
                    v.foreground = null
                }
                swatch.foreground = android.graphics.drawable.GradientDrawable().apply {
                    setStroke((3 * dp).toInt(), android.graphics.Color.WHITE)
                    cornerRadius = 4 * dp
                }
            }
            colorViews.add(swatch)
            colorGrid.addView(swatch)
        }
        root.addView(colorGrid)

        // Proximity radius
        root.addView(label("Радиус сближения"))
        val proxRow = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val inputProx = android.widget.EditText(ctx).apply {
            setText(if (wp.proximity > 0) wp.proximity.toInt().toString() else "")
            hint = "0 = глобальный"
            setTextColor(android.graphics.Color.WHITE)
            setHintTextColor(android.graphics.Color.parseColor("#666666"))
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        proxRow.addView(inputProx)
        proxRow.addView(android.widget.TextView(ctx).apply { text = " м"; setTextColor(android.graphics.Color.parseColor("#CCCCCC")) })
        root.addView(proxRow)

        val scroll = android.widget.ScrollView(ctx).apply { addView(root) }

        androidx.appcompat.app.AlertDialog.Builder(ctx, androidx.appcompat.R.style.Theme_AppCompat_Dialog)
            .setTitle("Свойства WP ${index + 1}")
            .setView(scroll)
            .setPositiveButton("OK") { _, _ ->
                val name = inputName.text.toString().trim().ifBlank { "WP${index + 1}" }
                val lat = inputLat.text.toString().toDoubleOrNull() ?: wp.lat
                val lon = inputLon.text.toString().toDoubleOrNull() ?: wp.lon
                val desc = inputDesc.text.toString().trim()
                val prox = inputProx.text.toString().toDoubleOrNull() ?: 0.0
                val newColor = if (selectedColor == "#FF6F00") "" else selectedColor
                val newWp = wp.copy(name = name, lat = lat, lon = lon, description = desc,
                    proximity = prox, color = newColor, symbol = selectedSymbol)
                updateWaypoint(index, newWp)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    fun prevWaypoint() {
        if (waypoints.isEmpty()) return
        activeWpIndex = if (activeWpIndex > 0) activeWpIndex - 1 else waypoints.size - 1
        context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.edit()?.putInt(PREF_ACTIVE_WP_INDEX, activeWpIndex)?.apply()
        updateNavLine()
        updateWaypointNavBar()
        Toast.makeText(context, "WP${waypoints[activeWpIndex].index}: ${waypoints[activeWpIndex].name}", Toast.LENGTH_SHORT).show()
        // Immediately refresh distance widget with new target
        val b = _binding ?: return
        val wp = waypoints.getOrNull(activeWpIndex)
        if (wp != null) {
            val gps = lastKnownGpsPoint
            if (gps != null) {
                val distM = distanceM(gps, LatLng(wp.lat, wp.lon))
                b.widgetNextCp.text = if (distM < 1000) "${distM.toInt()}м" else String.format("%.1fкм", distM / 1000)
                val remKm = calcRemainingKm(gps)
                b.widgetRemainKm.text = if (remKm < 10) String.format("%.1f", remKm) else remKm.toInt().toString()
            } else {
                b.widgetNextCp.text = "--"
                b.widgetRemainKm.text = "--"
            }
            b.widgetNextCpName.text = wp.name.takeIf { it.isNotBlank() } ?: "WP${activeWpIndex + 1}"
        }
    }

    private fun showRaceSummary() {
        val ctx = context ?: return
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val routeName = prefs.getString(PREF_ROUTE_NAME, null) ?: "Маршрут"

        // Track stats
        val distKm = TrackingService.trackLengthM / 1000.0
        val trackCount = TrackingService.trackPoints.size

        // Time from chronometer
        val elapsed = if (recordingStartMs > 0) System.currentTimeMillis() - recordingStartMs else 0L
        val h = elapsed / 3600000; val m = (elapsed % 3600000) / 60000; val s = (elapsed % 60000) / 1000
        val timeStr = if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)

        // Avg speed
        val avgSpeedKmh = if (elapsed > 0 && distKm > 0) distKm / (elapsed / 3600000.0) else 0.0

        // KP list
        val kpList = waypoints.mapIndexed { i, wp -> "✅ ${i + 1}. ${wp.name}" }.joinToString("\n")

        val msg = buildString {
            appendLine("🏁 $routeName")
            appendLine()
            appendLine("📏 Пройдено: ${"%.2f".format(distKm)} км")
            if (elapsed > 0) appendLine("⏱ Время: $timeStr")
            if (avgSpeedKmh > 0) appendLine("⚡ Средняя скорость: ${"%.1f".format(avgSpeedKmh)} км/ч")
            appendLine("🎯 WP пройдено: ${waypoints.size}")
            if (trackCount > 0) appendLine("📍 Точек трека: $trackCount")
            if (kpList.isNotBlank()) {
                appendLine()
                append(kpList)
            }
        }

        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle("🏆 Маршрут завершён!")
            .setMessage(msg.trim())
            .setPositiveButton("OK", null)
            .setNeutralButton("Сохранить трек") { _, _ -> saveTrackToFile() }
            .show()
    }

    private fun showDiagnostics() {
        val ctx = context ?: return
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // GPS info
        val locMgr = ctx.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        val gpsEnabled = locMgr.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)

        // Last known location
        @SuppressLint("MissingPermission")
        val lastLoc = try { locMgr.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER) } catch (_: Exception) { null }
        val accuracy = lastLoc?.accuracy?.let { "%.1f м".format(it) } ?: "—"
        val altitude = lastLoc?.altitude?.let { "%.0f м".format(it) } ?: "—"
        val speed = lastLoc?.speed?.let { "%.1f км/ч".format(it * 3.6f) } ?: "—"
        val locAge = lastLoc?.time?.let {
            val sec = (System.currentTimeMillis() - it) / 1000
            when {
                sec < 5 -> "только что"
                sec < 60 -> "${sec}с назад"
                else -> "${sec / 60}мин назад"
            }
        } ?: "нет данных"

        // Map info
        val mapKey = prefs.getString(PREF_TILE_KEY, "osm") ?: "osm"
        val overlayKey = prefs.getString(PREF_OVERLAY_KEY, "none") ?: "none"
        val offlineMaps = try {
            val json = prefs.getString(PREF_OFFLINE_MAPS_JSON, null)
            if (json != null) org.json.JSONArray(json).length() else 0
        } catch (_: Exception) { 0 }

        // Track & nav info
        val trackPts = TrackingService.trackPoints.size
        val trackKm = "%.2f км".format(TrackingService.trackLengthM / 1000)
        val navStatus = if (navActive) "Активна → WP${activeWpIndex + 1}/${waypoints.size}" else "Не активна"
        val routeName = prefs.getString(PREF_ROUTE_NAME, null) ?: "—"

        val msg = buildString {
            appendLine("📍 GPS: ${if (gpsEnabled) "✅ включён" else "❌ выключен"}")
            appendLine("Точность: $accuracy")
            appendLine("Высота: $altitude")
            appendLine("Скорость: $speed")
            appendLine("Последнее обновление: $locAge")
            appendLine()
            appendLine("🗺 Карта: $mapKey")
            appendLine("Оверлей: $overlayKey")
            appendLine("Офлайн-карт: $offlineMaps")
            appendLine()
            appendLine("🧭 Навигация: $navStatus")
            appendLine("Маршрут: $routeName")
            appendLine("WP загружено: ${waypoints.size}")
            appendLine()
            appendLine("📏 Трек: $trackPts точек ($trackKm)")
        }

        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle("Диагностика")
            .setMessage(msg.trim())
            .setPositiveButton("OK", null)
            .show()
    }

    fun startNavigation() {
        navActive = true
        // Always find nearest waypoint when starting navigation
        val gps = lastKnownGpsPoint
        if (gps != null && waypoints.isNotEmpty()) {
            val nearest = waypoints.indices.minByOrNull { i ->
                distanceM(gps, LatLng(waypoints[i].lat, waypoints[i].lon))
            }
            if (nearest != null) {
                activeWpIndex = nearest
                context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    ?.edit()?.putInt(PREF_ACTIVE_WP_INDEX, activeWpIndex)?.apply()
                Toast.makeText(context, "Старт с ближайшей WP ${waypoints[nearest].index}: ${waypoints[nearest].name}", Toast.LENGTH_SHORT).show()
            }
        }
        wasInApproachRadius = false
        context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)?.edit()
            ?.putBoolean(PREF_NAV_ACTIVE, true)?.apply()
        updateNavLine()
        updateWaypointNavBar()
    }

    /** Navigate to a single point (not route) — creates temp waypoint and starts nav */
    fun startNavigationToPoint(target: LatLng, name: String) {
        stopNavigation()
        waypoints.clear()
        waypoints.add(Waypoint(name, target.latitude, target.longitude, 1))
        activeWpIndex = 0
        updateWaypointsOnMap()
        updateRouteLineOnMap()
        updateRadiusCircles()
        startNavigation()
        Toast.makeText(context, "Навигация к: $name", Toast.LENGTH_SHORT).show()
    }

    fun stopNavigation() {
        navActive = false
        wasInApproachRadius = false
        context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)?.edit()
            ?.putBoolean(PREF_NAV_ACTIVE, false)?.apply()
        updateNavLine()
        updateWaypointNavBar()
    }

    /** Returns a copy of current waypoints list for use in Settings */
    fun getWaypointsCopy(): List<Waypoint> = waypoints.toList()

    /** Zoom camera to a specific waypoint */
    fun zoomToWaypoint(index: Int) {
        val wp = waypoints.getOrNull(index) ?: return
        zoomToCoords(wp.lat, wp.lon)
    }

    /** Zoom camera to coordinates */
    fun zoomToCoords(lat: Double, lon: Double) {
        mapboxMap?.animateCamera(
            com.mapbox.mapboxsdk.camera.CameraUpdateFactory.newLatLngZoom(
                LatLng(lat, lon), 16.0
            ), 500
        )
    }

    /** Remove a user marker by index */
    fun removeUserMarker(index: Int) {
        if (index < 0 || index >= userMarkers.size) return
        userMarkers.removeAt(index)
        updateUserMarkersOnMap()
        saveUserPoints()
    }

    /** Start navigation to a specific waypoint in the loaded route */
    fun navigateToWaypoint(index: Int) {
        if (index < 0 || index >= waypoints.size) return
        activeWpIndex = index
        navActive = true
        wasInApproachRadius = false
        context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)?.edit()
            ?.putBoolean(PREF_NAV_ACTIVE, true)
            ?.putInt(PREF_ACTIVE_WP_INDEX, activeWpIndex)
            ?.apply()
        updateNavLine()
        updateWaypointNavBar()
        val wp = waypoints[index]
        Toast.makeText(context, "Навигация к: ${wp.name}", Toast.LENGTH_SHORT).show()
    }

    /** Remove a waypoint by index, re-index remaining */
    fun removeWaypoint(index: Int) {
        if (index < 0 || index >= waypoints.size) return
        waypoints.removeAt(index)
        // Re-index
        for (i in waypoints.indices) {
            waypoints[i] = waypoints[i].copy(index = i + 1)
        }
        if (activeWpIndex >= waypoints.size) activeWpIndex = maxOf(0, waypoints.size - 1)
        wpBitmapCache.clear()
        updateWaypointsOnMap()
        updateRouteLineOnMap()
        updateRadiusCircles()
        updateNavLine()
        updateWaypointNavBar()
        saveWaypointsToPrefs()
    }

    /** Update a waypoint at index with new data */
    fun updateWaypoint(index: Int, newWp: Waypoint) {
        if (index < 0 || index >= waypoints.size) return
        waypoints[index] = newWp.copy(index = index + 1)
        wpBitmapCache.clear()
        updateWaypointsOnMap()
        updateRouteLineOnMap()
        updateRadiusCircles()
        saveWaypointsToPrefs()
    }

    /** Open edit dialog for a waypoint — callable from SettingsFragment */
    fun showEditWpDialogForIndex(index: Int) {
        if (index < 0 || index >= waypoints.size) return
        showEnhancedEditWpDialog(index)
    }

    fun updateWaypointNavBar() {
        val b = _binding ?: return
        val hasWp = waypoints.isNotEmpty()
        // Show nav bar when waypoints loaded (for GO/STOP), not only when navActive
        b.waypointNavBar.visibility = if (hasWp) View.VISIBLE else View.GONE
        if (hasWp) {
            val routeName = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                ?.getString(PREF_ROUTE_NAME, "") ?: ""
            val header = routeName.ifBlank { "Маршрут" }
            val gps = lastKnownGpsPoint
            val remainStr = if (gps != null && navActive) {
                val remKm = calcRemainingKm(gps)
                if (remKm < 10) " • %.1fкм".format(remKm) else " • ${remKm.toInt()}км"
            } else ""
            if (navActive) {
                b.txtWpNavInfo.text = "$header  ${activeWpIndex + 1}/${waypoints.size}$remainStr"
            } else {
                b.txtWpNavInfo.text = "$header  ${waypoints.size} WP"
            }
        }
        // GO/STOP in top bar — always visible, color indicates state
        b.btnWidgetGo.setTextColor(if (navActive) 0xFF00E676.toInt() else 0xFF666666.toInt())
        b.btnWidgetStop.setTextColor(if (navActive) 0xFFFF4444.toInt() else 0xFF666666.toInt())
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
                .put("description", wp.description)
                .put("color", wp.color)
                .put("symbol", wp.symbol)
                .put("proximity", wp.proximity))
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
                    description = o.optString("description", ""),
                    color = o.optString("color", ""),
                    symbol = o.optString("symbol", ""),
                    proximity = o.optDouble("proximity", 0.0)
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
                    // Skip very inaccurate fixes
                    if (loc.hasAccuracy() && loc.accuracy > 100f) return
                    if (loc.hasAccuracy() && loc.accuracy > 30f) {
                        context?.let { DiagnosticsCollector.logEvent(it, "GPS weak: acc=${loc.accuracy}m") }
                    }
                    val newPoint = LatLng(loc.latitude, loc.longitude)
                    lastKnownGpsPoint = newPoint
                    val b = _binding ?: return

                    // Первый GPS-фикс — прыгаем на зум 12
                    if (!initialZoomDone) {
                        initialZoomDone = true
                        context?.let { DiagnosticsCollector.logEvent(it, "GPS fix: acc=${loc.accuracy}m") }
                        mapboxMap?.animateCamera(
                            CameraUpdateFactory.newLatLngZoom(newPoint, 12.0), 1000
                        )
                    }

                    // Smooth bearing via EMA — eliminates GPS bearing jitter
                    val bearing = smoothBearing(loc.bearing).toFloat()

                    // Update custom GPS arrow with smoothed bearing
                    updateGpsArrow(loc.latitude, loc.longitude, bearing)
                    // Update heading line from GPS
                    val hlPrefs = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    if (hlPrefs?.getBoolean(PREF_HEADING_LINE_ENABLED, false) == true) {
                        updateHeadingLine(LatLng(loc.latitude, loc.longitude))
                    }

                    // Update accuracy circle — shrinks as GPS locks on, hides when < 10m
                    if (loc.hasAccuracy()) {
                        updateAccuracyCircle(loc.latitude, loc.longitude, loc.accuracy)
                    }

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
                                mapboxMap?.easeCamera(
                                    CameraUpdateFactory.newCameraPosition(builder.build()), 300)
                            }
                            FollowMode.FOLLOW_COURSE -> {
                                val speedKmhCourse = loc.speed * 3.6
                                val builder = com.mapbox.mapboxsdk.camera.CameraPosition.Builder()
                                    .target(newPoint)
                                    .tilt(tilt)
                                    .padding(doubleArrayOf(0.0, cameraTopPadding.toDouble(), 0.0, 0.0))
                                // Bearing freeze with hysteresis:
                                // Freeze at < 1 km/h (0.3 m/s), unfreeze at > 3 km/h (0.8 m/s)
                                // Prevents map spinning from GPS noise on stops
                                if (bearingFrozen) {
                                    if (speedKmhCourse > 3.0) {
                                        bearingFrozen = false
                                        lastValidBearing = bearing
                                        builder.bearing(bearing.toDouble())
                                    } else {
                                        builder.bearing(lastValidBearing.toDouble())
                                    }
                                } else {
                                    if (speedKmhCourse < 1.0) {
                                        bearingFrozen = true
                                    } else {
                                        lastValidBearing = bearing
                                        builder.bearing(bearing.toDouble())
                                    }
                                }
                                if (targetZoom != null) builder.zoom(targetZoom)
                                mapboxMap?.easeCamera(
                                    CameraUpdateFactory.newCameraPosition(builder.build()), 300)
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
                        b.widgetNextCpName.text = wp.name.takeIf { it.isNotBlank() } ?: "WP${activeWpIndex + 1}"
                        val remKm = calcRemainingKm(newPoint)
                        b.widgetRemainKm.text = if (remKm < 10) String.format("%.1f", remKm) else remKm.toInt().toString()
                        // Auto-advance when within approach radius (only during active navigation)
                        val ctx = context
                        val prefs = ctx?.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
                        val globalApproachRadius = prefs?.getInt(PREF_WP_APPROACH_RADIUS, DEFAULT_WP_APPROACH_RADIUS)?.toDouble()
                            ?: DEFAULT_WP_APPROACH_RADIUS.toDouble()
                        val approachRadius = if (wp.proximity > 0) wp.proximity else globalApproachRadius
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
        // Don't offer to resume a track with essentially 0 distance
        if (km < 0.05) {
            tmpFile.delete()
            prefs.edit().putBoolean(PREF_WAS_RECORDING, false).apply()
            return
        }
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
            .setNegativeButton("Позже", null)
            .setCancelable(true)
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
            val dir = getRaceNavDir(ctx, "tracks")
            val file = java.io.File(dir, filename)
            file.writeText(gpxContent)
            clearTmpTrack()
            val uri = androidx.core.content.FileProvider.getUriForFile(
                ctx, "com.andreykoff.racenav.provider", file)
            androidx.appcompat.app.AlertDialog.Builder(ctx)
                .setTitle("✅ Трек сохранён")
                .setMessage("$filename\n${pts.size} точек")
                .setPositiveButton("Поделиться") { _, _ ->
                    val share = Intent(Intent.ACTION_SEND).apply {
                        type = "application/gpx+xml"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(share, "Отправить GPX"))
                }
                .setNegativeButton("OK", null)
                .show()
        } catch (e: Exception) {
            Toast.makeText(ctx, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    fun shareRecordedTrack() {
        val ctx = context ?: return
        val pts = TrackingService.trackPoints.toList()
        if (pts.isEmpty()) {
            Toast.makeText(ctx, "Нет точек для отправки", Toast.LENGTH_SHORT).show()
            return
        }
        val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault())
            .format(java.util.Date())
        val filename = "racenav_$timestamp.gpx"
        val gpxContent = GpxParser.writeGpx(pts, "RaceNav $timestamp")
        try {
            val dir = ctx.externalCacheDir ?: ctx.cacheDir
            val file = java.io.File(dir, filename)
            file.writeText(gpxContent)
            val uri = androidx.core.content.FileProvider.getUriForFile(ctx, "com.andreykoff.racenav.provider", file)
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "application/gpx+xml"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(share, "Отправить GPX"))
        } catch (e: Exception) {
            Toast.makeText(ctx, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    fun shareLoadedWaypoints() {
        val ctx = context ?: return
        if (waypoints.isEmpty()) {
            Toast.makeText(ctx, "Нет загруженных точек", Toast.LENGTH_SHORT).show()
            return
        }
        val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault())
            .format(java.util.Date())
        val filename = "waypoints_$timestamp.gpx"
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"RaceNav\">\n")
        waypoints.forEach { wp ->
            sb.append("  <wpt lat=\"${wp.lat}\" lon=\"${wp.lon}\"><name>${wp.name}</name></wpt>\n")
        }
        sb.append("</gpx>\n")
        try {
            val file = java.io.File(ctx.externalCacheDir ?: ctx.cacheDir, filename)
            file.writeText(sb.toString())
            val uri = androidx.core.content.FileProvider.getUriForFile(ctx, "com.andreykoff.racenav.provider", file)
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "application/gpx+xml"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(share, "Отправить точки"))
        } catch (e: Exception) {
            Toast.makeText(ctx, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    fun shareOfflineMap(key: String) {
        val ctx = context ?: return
        val info = offlineMaps.find { it.key == key } ?: return
        val file = java.io.File(info.path)
        if (!file.exists()) {
            Toast.makeText(ctx, "Файл не найден", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(ctx, "com.andreykoff.racenav.provider", file)
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "application/octet-stream"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(share, "Отправить карту"))
        } catch (e: Exception) {
            Toast.makeText(ctx, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /** Delete temp file and clear was_recording flag */

    /** Clear recorded track from map and memory */
    fun clearRecordedTrack() {
        TrackingService.trackPoints.clear()
        TrackingService.trackLengthM = 0.0
        arrowPoints.clear()
        // Clear track line on map
        mapboxMap?.style?.getSourceAs<GeoJsonSource>(TRACK_SOURCE_ID)?.setGeoJson(
            org.json.JSONObject().put("type", "FeatureCollection")
                .put("features", org.json.JSONArray()).toString()
        )
        // Clear arrows
        mapboxMap?.style?.getSourceAs<GeoJsonSource>("arrow-source")?.setGeoJson(
            org.json.JSONObject().put("type", "FeatureCollection")
                .put("features", org.json.JSONArray()).toString()
        )
        // Clear tmp file
        clearTmpTrack()
        // Update widgets
        updateNextCpWidget()
        Toast.makeText(context, "Трек очищен", Toast.LENGTH_SHORT).show()
    }
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
        // Show/hide crosshair based on mode (before locationComponent check to work at startup)
        val crosshairEnabled = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.getBoolean(PREF_CROSSHAIR_ENABLED, true) ?: true
        if (followMode == FollowMode.FREE && crosshairEnabled) {
            _binding?.crosshairView?.visibility = View.VISIBLE
        } else {
            _binding?.crosshairView?.visibility = View.GONE
        }
        userDragged = false
        recenterRunnable?.let { recenterHandler.removeCallbacks(it) }
        updateCompassIndicator()
        val lc = mapboxMap?.locationComponent ?: return
        try {
            lc.cameraMode = CameraMode.NONE
            lc.renderMode = RenderMode.GPS
        } catch (_: Exception) {}
    }

    private fun updateCompassIndicator() {
        val b = _binding ?: return
        b.compassView.mode = when (followMode) {
            FollowMode.FREE -> CompassView.Mode.FREE
            FollowMode.FOLLOW_NORTH -> CompassView.Mode.NORTH
            FollowMode.FOLLOW_COURSE -> CompassView.Mode.COURSE
        }
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
        // Shift crosshair and distance label down to match camera center
        _binding?.crosshairView?.translationY = cameraTopPadding / 2f
        _binding?.distanceLabel?.translationY = cameraTopPadding / 2f
        // coordsLabel stays at top — no translation
        // Force camera update so the offset applies immediately
        mapboxMap?.cameraPosition?.let { cam ->
            cam.target?.let { target ->
                mapboxMap?.moveCamera(CameraUpdateFactory.newLatLng(target))
            }
        }
    }

    private fun playApproachSound() {
        try {
            val tg = android.media.ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION, 90)
            tg.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 400)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ tg.release() }, 500)
        } catch (e: Exception) { /* ignore if audio unavailable */ }
    }

    private fun playWaypointTakenSound() {
        // 4 loud rapid beeps on ALARM stream (bypasses silent mode, max volume)
        val beepMs = 180L
        val pauseMs = 120L
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        for (i in 0 until 4) {
            handler.postDelayed({
                try {
                    val tg = android.media.ToneGenerator(android.media.AudioManager.STREAM_ALARM, 100)
                    tg.startTone(android.media.ToneGenerator.TONE_CDMA_HIGH_PBX_SS, beepMs.toInt())
                    handler.postDelayed({ tg.release() }, beepMs + 50)
                } catch (e: Exception) { }
            }, i * (beepMs + pauseMs))
        }
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

    /** Format coordinate as WGS84 degrees°minutes' (e.g. N 59°52.123') */
    private fun formatDM(coord: Double, isLat: Boolean): String {
        val abs = Math.abs(coord)
        val deg = abs.toInt()
        val min = (abs - deg) * 60.0
        val dir = if (isLat) (if (coord >= 0) "N" else "S") else (if (coord >= 0) "E" else "W")
        return "$dir $deg°${"%.3f".format(min)}'"
    }

    private var crosshairHideRunnable: Runnable? = null
    private val crosshairHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** Update crosshair-related overlays on camera move */
    private fun updateCrosshairInfo() {
        val center = mapboxMap?.cameraPosition?.target ?: return
        val ctx = context ?: return
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Show crosshair ONLY when user drags map manually (not auto-follow/zoom)
        if (prefs.getBoolean(PREF_CROSSHAIR_ENABLED, true) && userDragged) {
            showCrosshairTemporarily()
        }

        // Coordinates + distance only when user is dragging
        if (!userDragged) return

        // Coordinates label
        if (prefs.getBoolean(PREF_COORDS_ENABLED, true)) {
            _binding?.coordsLabel?.visibility = View.VISIBLE
            _binding?.coordsLabel?.text = "${formatDM(center.latitude, true)}  ${formatDM(center.longitude, false)}"
        } else {
            _binding?.coordsLabel?.visibility = View.GONE
        }

        // Distance line from GPS to map center
        val gps = lastKnownGpsPoint
        if (prefs.getBoolean(PREF_DISTANCE_LINE_ENABLED, true) && gps != null) {
            val distM = distanceM(gps, center)
            if (distM > 5) {
                _binding?.distanceLabel?.visibility = View.VISIBLE
                _binding?.distanceLabel?.text = if (distM < 1000) "${distM.toInt()} м"
                    else "${"%.1f".format(distM / 1000)} км"
                updateDistanceLineOnMap(gps, center)
            } else {
                _binding?.distanceLabel?.visibility = View.GONE
                clearDistanceLine()
            }
        } else {
            _binding?.distanceLabel?.visibility = View.GONE
            clearDistanceLine()
        }

        // Heading line (predicted direction from GPS bearing)
        if (prefs.getBoolean(PREF_HEADING_LINE_ENABLED, false) && gps != null) {
            updateHeadingLine(gps)
        } else {
            clearHeadingLine()
        }
    }

    /** Show crosshair — permanent in FREE mode, auto-hide in follow modes */
    private fun showCrosshairTemporarily() {
        _binding?.crosshairView?.visibility = View.VISIBLE
        // Cancel previous hide
        crosshairHideRunnable?.let { crosshairHandler.removeCallbacks(it) }
        // In FREE mode — keep crosshair visible permanently (used for placing points)
        if (followMode == FollowMode.FREE) return
        crosshairHideRunnable = Runnable {
            _binding?.crosshairView?.visibility = View.GONE
            _binding?.coordsLabel?.visibility = View.GONE
            _binding?.distanceLabel?.visibility = View.GONE
            clearDistanceLine()
            clearHeadingLine()
        }
        crosshairHandler.postDelayed(crosshairHideRunnable!!, 1500)
    }

    private fun updateDistanceLineOnMap(from: LatLng, to: LatLng) {
        val style = mapboxMap?.style ?: return
        val source = style.getSourceAs<GeoJsonSource>(DISTANCE_LINE_SOURCE_ID) ?: return
        val geoJson = JSONObject()
            .put("type", "Feature")
            .put("geometry", JSONObject()
                .put("type", "LineString")
                .put("coordinates", JSONArray()
                    .put(JSONArray().put(from.longitude).put(from.latitude))
                    .put(JSONArray().put(to.longitude).put(to.latitude))))
            .toString()
        source.setGeoJson(geoJson)
    }

    private fun clearDistanceLine() {
        mapboxMap?.style?.getSourceAs<GeoJsonSource>(DISTANCE_LINE_SOURCE_ID)
            ?.setGeoJson("""{"type":"FeatureCollection","features":[]}""")
    }

    /** Draw heading line from GPS position to edge of visible map */
    private fun updateHeadingLine(gps: LatLng) {
        val style = mapboxMap?.style ?: return
        val source = style.getSourceAs<GeoJsonSource>(HEADING_LINE_SOURCE_ID) ?: return
        val bearingDeg = smoothedBearing
        if (bearingDeg < 0) { clearHeadingLine(); return }
        // Calculate distance to edge of visible map (diagonal) so line always reaches screen edge
        val bounds = mapboxMap?.projection?.visibleRegion?.latLngBounds
        val distKm = if (bounds != null) {
            // Use diagonal of visible bounds as max distance
            distanceM(
                bounds.southWest,
                bounds.northEast
            ) / 1000.0
        } else 50.0  // fallback 50km
        val bearingRad = Math.toRadians(bearingDeg)
        val latRad = Math.toRadians(gps.latitude)
        val R = 6371.0
        val endLat = Math.toDegrees(Math.asin(
            Math.sin(latRad) * Math.cos(distKm / R) +
            Math.cos(latRad) * Math.sin(distKm / R) * Math.cos(bearingRad)))
        val endLon = gps.longitude + Math.toDegrees(Math.atan2(
            Math.sin(bearingRad) * Math.sin(distKm / R) * Math.cos(latRad),
            Math.cos(distKm / R) - Math.sin(latRad) * Math.sin(Math.toRadians(endLat))))
        val geoJson = JSONObject()
            .put("type", "Feature")
            .put("geometry", JSONObject()
                .put("type", "LineString")
                .put("coordinates", JSONArray()
                    .put(JSONArray().put(gps.longitude).put(gps.latitude))
                    .put(JSONArray().put(endLon).put(endLat))))
            .toString()
        source.setGeoJson(geoJson)
    }

    private fun clearHeadingLine() {
        mapboxMap?.style?.getSourceAs<GeoJsonSource>(HEADING_LINE_SOURCE_ID)
            ?.setGeoJson("""{"type":"FeatureCollection","features":[]}""")
    }

    /** Create crosshair bitmap and apply to ImageView */
    fun applyCrosshairPrefs() {
        val ctx = context ?: return
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val view = _binding?.crosshairView ?: return

        if (!prefs.getBoolean(PREF_CROSSHAIR_ENABLED, true)) {
            view.visibility = View.GONE
            return
        }

        // Draw crosshair: black lines with white outline — visible on any map
        val density = resources.displayMetrics.density
        val crossSize = prefs.getInt(PREF_CROSSHAIR_SIZE, 60)
        val sizePx = (crossSize * density).toInt().coerceAtLeast(40)
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val cx = sizePx / 2f
        val cy = sizePx / 2f
        val armLen = sizePx / 2f - 4 * density
        val gap = 4 * density  // gap around center
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // White outline (thick)
        paint.color = Color.WHITE
        paint.strokeWidth = 3.5f * density
        paint.strokeCap = Paint.Cap.ROUND
        // Horizontal
        canvas.drawLine(cx - armLen, cy, cx - gap, cy, paint)
        canvas.drawLine(cx + gap, cy, cx + armLen, cy, paint)
        // Vertical
        canvas.drawLine(cx, cy - armLen, cx, cy - gap, paint)
        canvas.drawLine(cx, cy + gap, cx, cy + armLen, paint)

        // Black inner line
        paint.color = Color.BLACK
        paint.strokeWidth = 1.5f * density
        canvas.drawLine(cx - armLen, cy, cx - gap, cy, paint)
        canvas.drawLine(cx + gap, cy, cx + armLen, cy, paint)
        canvas.drawLine(cx, cy - armLen, cx, cy - gap, paint)
        canvas.drawLine(cx, cy + gap, cx, cy + armLen, paint)

        // Center dot
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        canvas.drawCircle(cx, cy, 2.5f * density, paint)
        paint.color = Color.BLACK
        canvas.drawCircle(cx, cy, 1.2f * density, paint)

        (view as android.widget.ImageView).setImageBitmap(bmp)
        // Size
        view.layoutParams.width = sizePx
        view.layoutParams.height = sizePx
        view.requestLayout()
        view.visibility = View.GONE  // hidden by default, shown on camera move
    }

    private fun showLayerPicker() {
        val dialog = BottomSheetDialog(requireContext(), R.style.BottomSheetTheme)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_layers, null)
        dialog.setContentView(view)
        // Show at 2/3 screen height
        dialog.behavior.peekHeight = (resources.displayMetrics.heightPixels * 2 / 3)
        dialog.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_COLLAPSED

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
            loadTileStyle(key, currentOverlayKeys)
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
                loadTileStyle(key, currentOverlayKeys)
                dialog.dismiss()
            }
        }

        // Overlay layers — checkboxes (multiple selection)
        val overlayContainer = view.findViewById<RadioGroup>(R.id.overlayRadioGroup)
        // Replace RadioGroup with plain LinearLayout behavior — add CheckBoxes
        overlaySources.filter { it.key != "none" }.forEach { (key, source) ->
            overlayContainer.addView(android.widget.CheckBox(requireContext()).apply {
                text = source.label; tag = key
                isChecked = key in currentOverlayKeys
                setTextColor(0xFFFFFFFF.toInt()); textSize = 13f
                setPadding(16, 14, 16, 14); id = View.generateViewId()
                buttonTintList = android.content.res.ColorStateList.valueOf(0xFFFF6F00.toInt())
                setOnCheckedChangeListener { _, checked ->
                    if (checked) currentOverlayKeys.add(key) else currentOverlayKeys.remove(key)
                    loadTileStyle(currentTileKey, currentOverlayKeys)
                }
            })
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
        style.addImage(GPS_ARROW_ICON, makeArrowBitmap(markerScaleToDp(size), color))
    }

    private fun showHint(text: String) {
        val ctx = context ?: return
        if (!ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(PREF_HINTS_ENABLED, true)) return
        Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show()
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

    fun checkForUpdates(onResult: (latest: String?, current: String, hasUpdate: Boolean, apkUrl: String?, changelog: String?) -> Unit) {
        val current = requireContext().packageManager.getPackageInfo(requireContext().packageName, 0).versionName ?: "0"
        lifecycleScope.launch {
            try {
                val json = withContext(Dispatchers.IO) {
                    JSONObject(URL(UpdateManager.UPDATE_URL).readText())
                }
                val latestVersion = json.getString("version")
                val apkUrl = json.optString("apkUrl", json.optString("url", ""))
                val changelog = json.optString("changelog", null)
                val hasUpdate = UpdateManager.isNewer(latestVersion, current)
                onResult("v$latestVersion", "v$current", hasUpdate, apkUrl, changelog)
            } catch (e: Exception) {
                Log.d("RaceNav", "Update check: ${e.message}")
                onResult(null, "v$current", false, null, null)
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
                            name = w.optString("name", "WP${i + 1}"),
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
                        prefs.edit().putString(PREF_LOADED_WP_NAME, "WP: синхронизация (${syncWaypoints.size})").apply()
                    }
                    if (syncTrackPts.isNotEmpty()) {
                        loadTrack(syncTrackPts)
                        prefs.edit().putString(PREF_LOADED_TRACK_NAME, "Трек: синхронизация (${syncTrackPts.size} точек)").apply()
                    }
                    val msg = buildString {
                        if (syncWaypoints.isNotEmpty()) append("${syncWaypoints.size} WP")
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
        applyUiScale()
        applyWidgetFontScale()
        applyTopBarPrefs()
        // Регистрируем приёмник GPS от сервиса
        val filter = IntentFilter(TrackingService.BROADCAST_LOCATION)
        val traccarFilter = IntentFilter(TraccarService.BROADCAST_TRACCAR_STATUS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context?.registerReceiver(locationReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            context?.registerReceiver(traccarStatusReceiver, traccarFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context?.registerReceiver(locationReceiver, filter)
            context?.registerReceiver(traccarStatusReceiver, traccarFilter)
        }
        // StateFlow collector — works even when broadcast is blocked (Vivo/Android 16)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                TrackingService.locationFlow.collect { update ->
                    if (update != null) {
                        handleLocationUpdate(update)
                    }
                }
            }
        }
        // Update top bar server dot based on TraccarService running state
        _binding?.topBarServerDot?.visibility =
            if (TraccarService.isRunning) View.VISIBLE else View.GONE
        // Синхронизируем UI если сервис пишет трек в фоне
        if (isRecording) {
            binding.btnRec.setImageResource(R.drawable.ic_rec)
            startChronoTicker()
            updateTrackOnMap()
            val lenKm = TrackingService.trackLengthM / 1000.0
            _binding?.widgetTrackLen?.text = if (lenKm < 10) String.format("%.1f", lenKm) else lenKm.toInt().toString()
        }
        updateWaypointNavBar()
        // Restart poller if it was stopped (e.g. onDestroyView) but pref is still enabled
        val resumePrefs = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (liveUsersPoller == null && resumePrefs?.getBoolean(PREF_LIVE_USERS_ENABLED, false) == true) {
            mapboxMap?.style?.let { style ->
                if (style.getSource(LIVE_USERS_SOURCE_ID) != null) startLiveUsersPoller()
            }
        }
        // Auto-start TraccarService if it was enabled but not running (e.g. after app update/restart)
        val traccarCtx = context ?: return
        if (resumePrefs?.getBoolean(PREF_TRACCAR_ENABLED, false) == true && !TraccarService.isRunning) {
            traccarCtx.startForegroundService(
                Intent(traccarCtx, TraccarService::class.java).apply {
                    action = TraccarService.ACTION_START
                }
            )
            Log.d("LiveUsers", "Auto-started TraccarService (was enabled but not running)")
        }
    }

    override fun onPause() {
        super.onPause()
        _binding?.mapView?.onPause()
        try { context?.unregisterReceiver(locationReceiver) } catch (_: Exception) {}
        try { context?.unregisterReceiver(traccarStatusReceiver) } catch (_: Exception) {}
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
        applyCrosshairPrefs()
    }

    // ==================== DOWNLOAD MODE ====================

    fun startDownloadMode() {
        // Request storage permission on Android 9 and below
        if (android.os.Build.VERSION.SDK_INT <= android.os.Build.VERSION_CODES.P) {
            val ctx = context ?: return
            if (androidx.core.content.ContextCompat.checkSelfPermission(ctx,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != 
                    android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), 300)
                Toast.makeText(ctx, "Разрешите доступ к хранилищу", Toast.LENGTH_LONG).show()
                return
            }
        }
        isDownloadSelecting = true
        downloadFirstCorner = null
        downloadBounds = null
        Toast.makeText(context, "Нажмите на карту — первый угол области", Toast.LENGTH_LONG).show()
    }

    private fun handleDownloadTap(latLng: LatLng) {
        if (downloadFirstCorner == null) {
            downloadFirstCorner = latLng
            Toast.makeText(context, "Нажмите на карту — второй угол", Toast.LENGTH_SHORT).show()
        } else {
            val first = downloadFirstCorner!!
            val bounds = BoundsRect(
                north = maxOf(first.latitude, latLng.latitude),
                south = minOf(first.latitude, latLng.latitude),
                east = maxOf(first.longitude, latLng.longitude),
                west = minOf(first.longitude, latLng.longitude)
            )
            isDownloadSelecting = false
            downloadFirstCorner = null
            downloadBounds = bounds
            drawDownloadRect(bounds)
            showDownloadConfirmation(bounds)
        }
    }

    private fun showDownloadConfirmation(bounds: BoundsRect) {
        val view = _binding?.root ?: return
        val snackbar = com.google.android.material.snackbar.Snackbar.make(
            view, "Область выбрана", com.google.android.material.snackbar.Snackbar.LENGTH_INDEFINITE
        )
        snackbar.setAction("Скачать") {
            showDownloadDialog(bounds)
        }
        // Add "Изменить" button via custom view approach — use second action text
        val snackView = snackbar.view
        snackView.setBackgroundColor(0xFF1A1A2E.toInt())
        // Add a second button for "Изменить"
        val layout = snackView as? android.widget.FrameLayout
        val snackTextView = snackView.findViewById<android.widget.TextView>(com.google.android.material.R.id.snackbar_text)
        snackTextView?.setTextColor(0xFFFFFFFF.toInt())
        val snackActionView = snackView.findViewById<android.widget.TextView>(com.google.android.material.R.id.snackbar_action)
        snackActionView?.setTextColor(0xFF4CAF50.toInt())

        // Override dismiss on outside tap — allow re-selection
        snackbar.addCallback(object : com.google.android.material.snackbar.Snackbar.Callback() {
            override fun onDismissed(transientBottomBar: com.google.android.material.snackbar.Snackbar?, event: Int) {
                if (event == DISMISS_EVENT_SWIPE || event == DISMISS_EVENT_MANUAL || event == DISMISS_EVENT_TIMEOUT) {
                    // User swiped away or timed out — treat as cancel, remove rect
                    removeDownloadRect()
                }
            }
        })
        snackbar.show()
    }

    private fun drawDownloadRect(bounds: BoundsRect) {
        val map = mapboxMap ?: return
        val style = map.style ?: return
        Log.d("TileDownload", "drawDownloadRect: N=${bounds.north} S=${bounds.south} E=${bounds.east} W=${bounds.west}")
        val geojson = """{
            "type":"Feature",
            "geometry":{
                "type":"Polygon",
                "coordinates":[[
                    [${bounds.west},${bounds.north}],
                    [${bounds.east},${bounds.north}],
                    [${bounds.east},${bounds.south}],
                    [${bounds.west},${bounds.south}],
                    [${bounds.west},${bounds.north}]
                ]]
            }
        }"""
        val sourceId = "dl-rect-source"
        try {
            val existingSource = style.getSourceAs<GeoJsonSource>(sourceId)
            if (existingSource != null) {
                existingSource.setGeoJson(geojson)
            } else {
                val src = GeoJsonSource(sourceId, geojson)
                style.addSource(src)
                style.addLayer(FillLayer("dl-rect-fill", sourceId).withProperties(
                    PropertyFactory.fillColor(android.graphics.Color.argb(60, 255, 152, 0)),
                    PropertyFactory.fillOpacity(0.4f)
                ))
                style.addLayer(LineLayer("dl-rect-line", sourceId).withProperties(
                    PropertyFactory.lineColor(0xFFFF9800.toInt()),
                    PropertyFactory.lineWidth(2.5f)
                ))
            }
            Log.d("TileDownload", "drawDownloadRect: rectangle drawn successfully")
        } catch (e: Exception) {
            Log.e("TileDownload", "drawDownloadRect failed: ${e.message}", e)
        }
    }

    private fun removeDownloadRect() {
        val style = mapboxMap?.style ?: return
        try { style.removeLayer("dl-rect-fill") } catch (_: Exception) {}
        try { style.removeLayer("dl-rect-line") } catch (_: Exception) {}
        try { style.removeSource("dl-rect-source") } catch (_: Exception) {}
    }

    @SuppressLint("SetTextI18n")
    private fun showDownloadDialog(bounds: BoundsRect) {
        val ctx = context ?: return
        val dialog = BottomSheetDialog(ctx)
        val dp = resources.displayMetrics.density

        val scroll = android.widget.ScrollView(ctx).apply {
            setBackgroundColor(0xFF1A1A2E.toInt())
            isNestedScrollingEnabled = true
            isFillViewport = true
        }
        val root = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding((16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt())
        }
        scroll.addView(root)

        // Title
        root.addView(android.widget.TextView(ctx).apply {
            text = "Скачать карту оффлайн"
            setTextColor(0xFFFFFFFF.toInt()); textSize = 18f
            setPadding(0, 0, 0, (12 * dp).toInt())
        })

        // Map name
        val nameEdit = android.widget.EditText(ctx).apply {
            setText("Карта_${java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())}")
            setTextColor(0xFFFFFFFF.toInt()); textSize = 14f
            setHintTextColor(0xFF888888.toInt()); hint = "Название карты"
            setBackgroundColor(0xFF2A2A3E.toInt())
            setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt())
        }
        root.addView(nameEdit)

        // === Section 1: Base map (RadioGroup) ===
        root.addView(android.widget.TextView(ctx).apply {
            text = "Базовая карта:"
            setTextColor(0xFFCCCCCC.toInt()); textSize = 13f
            setPadding(0, (16 * dp).toInt(), 0, (8 * dp).toInt())
        })

        val allSourceMaxZooms = mutableMapOf<String, Int>()
        val baseRadioGroup = android.widget.RadioGroup(ctx)
        val baseKeys = mutableListOf<String>() // ordered keys matching radio button indices
        // If current map is offline/custom, default to "osm"
        var selectedBaseKey = if (currentTileKey.startsWith(OFFLINE_TILE_KEY) || currentTileKey.startsWith("custom_")) "osm" else currentTileKey

        tileSources.filter { !it.key.startsWith(OFFLINE_TILE_KEY) && !it.key.startsWith("custom_") }.forEach { (key, source) ->
            allSourceMaxZooms[key] = source.maxZoom
            val rb = android.widget.RadioButton(ctx).apply {
                text = source.label
                setTextColor(0xFFFFFFFF.toInt()); textSize = 13f
                buttonTintList = android.content.res.ColorStateList.valueOf(0xFF3b82f6.toInt())
                id = View.generateViewId()
            }
            baseKeys.add(key)
            baseRadioGroup.addView(rb)
            if (key == currentTileKey) {
                rb.isChecked = true
            }
        }
        baseRadioGroup.setOnCheckedChangeListener { group, checkedId ->
            for (i in 0 until group.childCount) {
                val rb = group.getChildAt(i) as android.widget.RadioButton
                if (rb.id == checkedId) {
                    selectedBaseKey = baseKeys[i]
                    break
                }
            }
        }
        root.addView(baseRadioGroup)

        // === Section 2: Overlays (CheckBoxes) ===
        root.addView(android.widget.TextView(ctx).apply {
            text = "Оверлеи:"
            setTextColor(0xFFCCCCCC.toInt()); textSize = 13f
            setPadding(0, (12 * dp).toInt(), 0, (4 * dp).toInt())
        })

        val overlayCheckBoxes = mutableListOf<Pair<String, android.widget.CheckBox>>()
        overlaySources.filter { it.key != "none" }.forEach { (key, source) ->
            allSourceMaxZooms[key] = source.maxZoom
            val cb = android.widget.CheckBox(ctx).apply {
                text = source.label
                setTextColor(0xFFDDDDDD.toInt()); textSize = 13f
                isChecked = currentOverlayKeys.contains(key)
                buttonTintList = android.content.res.ColorStateList.valueOf(0xFFFF9800.toInt())
            }
            overlayCheckBoxes.add(key to cb)
            root.addView(cb)
        }

        // Zoom range - dynamic max from selected sources
        fun calcMaxAllowedZoom(): Int {
            val keys = mutableListOf(selectedBaseKey)
            keys.addAll(overlayCheckBoxes.filter { it.second.isChecked }.map { it.first })
            return keys.mapNotNull { allSourceMaxZooms[it] }.minOrNull() ?: 18
        }
        var maxAllowedZoom = calcMaxAllowedZoom()
        val currentZ = getCurrentZoom().toInt().coerceIn(1, maxAllowedZoom)
        var minZoom = (currentZ - 2).coerceIn(1, maxAllowedZoom)
        var maxZoom = (currentZ + 2).coerceIn(1, maxAllowedZoom)

        root.addView(android.widget.TextView(ctx).apply {
            text = "Диапазон зума:"
            setTextColor(0xFFCCCCCC.toInt()); textSize = 13f
            setPadding(0, (16 * dp).toInt(), 0, (4 * dp).toInt())
        })

        val zoomLabel = android.widget.TextView(ctx).apply {
            text = "Мин: $minZoom — Макс: $maxZoom"
            setTextColor(0xFFFFFFFF.toInt()); textSize = 14f
            setPadding(0, 0, 0, (4 * dp).toInt())
        }
        root.addView(zoomLabel)

        val estimateLabel = android.widget.TextView(ctx).apply {
            setTextColor(0xFFFFAB00.toInt()); textSize = 12f
            setPadding(0, (4 * dp).toInt(), 0, (8 * dp).toInt())
        }
        root.addView(estimateLabel)

        fun updateEstimate() {
            val layerCount = 1 + overlayCheckBoxes.count { it.second.isChecked } // 1 base + overlays
            val tiles = TileDownloadManager.estimateTiles(bounds, minZoom, maxZoom) * layerCount
            val mbEstimate = tiles * 15 / 1024  // ~15 KB per tile average
            estimateLabel.text = "~$tiles тайлов (~${mbEstimate} МБ)"
            if (tiles > 50000) estimateLabel.setTextColor(0xFFFF5252.toInt())
            else estimateLabel.setTextColor(0xFFFFAB00.toInt())
        }

        // Min zoom seekbar
        root.addView(android.widget.TextView(ctx).apply {
            text = "Мин. зум:"; setTextColor(0xFF999999.toInt()); textSize = 11f
        })
        val seekMin = android.widget.SeekBar(ctx).apply {
            max = maxAllowedZoom - 1; progress = minZoom - 1
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar?, p: Int, u: Boolean) {
                    minZoom = p + 1
                    if (minZoom > maxZoom) { maxZoom = minZoom }
                    zoomLabel.text = "Мин: $minZoom — Макс: $maxZoom (до $maxAllowedZoom)"
                    updateEstimate()
                }
                override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
            })
        }
        root.addView(seekMin)

        // Max zoom seekbar
        root.addView(android.widget.TextView(ctx).apply {
            text = "Макс. зум:"; setTextColor(0xFF999999.toInt()); textSize = 11f
        })
        val seekMax = android.widget.SeekBar(ctx).apply {
            max = maxAllowedZoom - 1; progress = maxZoom - 1
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar?, p: Int, u: Boolean) {
                    maxZoom = p + 1
                    if (maxZoom < minZoom) { minZoom = maxZoom }
                    zoomLabel.text = "Мин: $minZoom — Макс: $maxZoom (до $maxAllowedZoom)"
                    updateEstimate()
                }
                override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
            })
        }
        root.addView(seekMax)

        // Update zoom limits when base or overlay selection changes
        fun onSelectionChanged() {
            maxAllowedZoom = calcMaxAllowedZoom()
            seekMin.max = maxAllowedZoom - 1
            seekMax.max = maxAllowedZoom - 1
            if (maxZoom > maxAllowedZoom) {
                maxZoom = maxAllowedZoom
                seekMax.progress = maxZoom - 1
            }
            if (minZoom > maxAllowedZoom) {
                minZoom = maxAllowedZoom
                seekMin.progress = minZoom - 1
            }
            zoomLabel.text = "Мин: $minZoom — Макс: $maxZoom (до $maxAllowedZoom)"
            updateEstimate()
        }

        baseRadioGroup.setOnCheckedChangeListener { group, checkedId ->
            for (i in 0 until group.childCount) {
                val rb = group.getChildAt(i) as android.widget.RadioButton
                if (rb.id == checkedId) {
                    selectedBaseKey = baseKeys[i]
                    break
                }
            }
            onSelectionChanged()
        }

        overlayCheckBoxes.forEach { (_, cb) ->
            cb.setOnCheckedChangeListener { _, _ -> onSelectionChanged() }
        }

        updateEstimate()

        // Download button
        val btnDownload = android.widget.Button(ctx).apply {
            text = "Скачать"
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF1565C0.toInt())
            textSize = 14f
            isAllCaps = false
            val lp = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                (48 * dp).toInt()
            )
            lp.topMargin = (16 * dp).toInt()
            layoutParams = lp
        }
        root.addView(btnDownload)

        // Cancel button
        val btnCancel = android.widget.Button(ctx).apply {
            text = "Отмена"
            setTextColor(0xFF999999.toInt())
            setBackgroundColor(0xFF2A2A3E.toInt())
            textSize = 14f
            isAllCaps = false
            val lp = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                (48 * dp).toInt()
            )
            lp.topMargin = (8 * dp).toInt()
            layoutParams = lp
        }
        root.addView(btnCancel)

        btnCancel.setOnClickListener {
            removeDownloadRect()
            dialog.dismiss()
        }

        btnDownload.setOnClickListener {
            val mapName = nameEdit.text.toString().ifBlank { "Карта" }
            val docsDir = android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOCUMENTS
            )
            val mapsDir = java.io.File(docsDir, "RaceNav/maps")
            mapsDir.mkdirs()

            // Build layers list: base first, then selected overlays
            val layers = mutableListOf<LayerDownload>()

            // Base layer (always exactly one)
            val baseSource = tileSources[selectedBaseKey]
            val baseLabel = baseSource?.label ?: selectedBaseKey
            val baseSafeName = "${mapName}_${baseLabel}".replace(Regex("[^\\w]"), "_")
            layers.add(LayerDownload(selectedBaseKey, baseLabel, java.io.File(mapsDir, "$baseSafeName.mbtiles").absolutePath))

            // Overlay layers
            val selectedOverlays = overlayCheckBoxes.filter { it.second.isChecked }
            for ((key, cb) in selectedOverlays) {
                val label = cb.text.toString()
                val safeName = "${mapName}_${label}".replace(Regex("[^\\w]"), "_")
                layers.add(LayerDownload(key, label, java.io.File(mapsDir, "$safeName.mbtiles").absolutePath))
            }

            val task = DownloadTask(mapName, layers, bounds, minZoom, maxZoom)

            // Provide tile source info to download manager
            TileDownloadManager.tileSourcesRef = getTileSourceInfoMap()
            TileDownloadManager.onProgressUpdate = { progress ->
                updateDownloadIndicator(progress)
            }
            TileDownloadManager.onComplete = {
                onDownloadComplete()
            }
            TileDownloadManager.startDownload(ctx, task)

            // Show indicator
            _binding?.downloadIndicator?.visibility = View.VISIBLE

            removeDownloadRect()
            dialog.dismiss()
            Toast.makeText(ctx, "Загрузка начата: $mapName", Toast.LENGTH_SHORT).show()
        }

        dialog.setContentView(scroll)
        dialog.behavior.peekHeight = resources.displayMetrics.heightPixels
        dialog.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        dialog.show()
        // Dark background for bottom sheet
        dialog.window?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.setBackgroundColor(0xFF1A1A2E.toInt())
    }

    private fun updateDownloadIndicator(progress: DownloadProgress) {
        val b = _binding ?: return
        b.dlProgressText.text = "${progress.percent}% (${progress.downloadedTiles}/${progress.totalTiles})"
        if (!progress.isRunning) {
            if (progress.error != null) {
                b.dlProgressText.text = "Ошибка"
            } else {
                b.dlProgressText.text = "Готово!"
            }
        }
    }

    private fun onDownloadComplete() {
        val b = _binding ?: return
        context?.let { ctx ->
            val task = TileDownloadManager.lastTask
            DiagnosticsCollector.logEvent(ctx, "DL complete: ${task?.name ?: "?"}")
        }
        b.dlProgressText.text = "✅ Готово!"
        b.dlProgress.visibility = View.GONE
        // Auto-hide after 3 seconds
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            b.downloadIndicator.visibility = View.GONE
            b.dlProgress.visibility = View.VISIBLE
        }, 3000)

        // Register downloaded maps: only the base layer as offline map,
        // overlay names included in display name for reference
        val task = TileDownloadManager.lastTask
        if (task != null && task.layers.isNotEmpty()) {
            // First layer is always the base map
            val baseLayer = task.layers.first()
            val baseFile = java.io.File(baseLayer.outputPath)
            if (baseFile.exists() && baseFile.length() > 0) {
                // Build display name: "Name (BaseLabel + overlay1, overlay2)"
                val overlayLabels = task.layers.drop(1).filter {
                    val f = java.io.File(it.outputPath)
                    f.exists() && f.length() > 0
                }.map { it.layerLabel }

                val displayName = if (overlayLabels.isNotEmpty()) {
                    "${task.name} (${baseLayer.layerLabel} + ${overlayLabels.joinToString(", ")})"
                } else {
                    "${task.name} (${baseLayer.layerLabel})"
                }

                val key = addOfflineMap(baseFile.absolutePath, displayName)
                if (key != null) {
                    Log.d("TileDownload", "Registered offline map: $displayName -> $key (${baseFile.length()} bytes)")
                    // Save overlay paths keyed by offline map key for future use
                    val overlayPaths = task.layers.drop(1).filter {
                        val f = java.io.File(it.outputPath)
                        f.exists() && f.length() > 0
                    }.map { org.json.JSONObject().put("key", it.layerKey).put("path", it.outputPath) }
                    // Also register each overlay .mbtiles as a separate offline map
                    task.layers.drop(1).forEach { overlayLayer ->
                        val overlayFile = java.io.File(overlayLayer.outputPath)
                        if (overlayFile.exists() && overlayFile.length() > 0) {
                            val overlayName = "${task.name} — ${overlayLayer.layerLabel}"
                            addOfflineMap(overlayFile.absolutePath, overlayName)
                            Log.d("TileDownload", "Registered overlay: $overlayName (${overlayFile.length()} bytes)")
                        }
                    }
                }
                Toast.makeText(context, "Загружено: $displayName", Toast.LENGTH_LONG).show()
            } else {
                Log.w("TileDownload", "Base layer file missing or empty: ${baseLayer.outputPath}")
                val ctx2 = context
                if (ctx2 != null) {
                    DiagnosticsCollector.logEvent(ctx2, "DL fail: base empty ${baseLayer.outputPath}")
                }
                Toast.makeText(context, "Ошибка: базовый слой не загружен", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(context, "Загрузка завершена", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showDownloadDetailsDialog() {
        val ctx = context ?: return
        val progress = TileDownloadManager.getProgress()
        val task = TileDownloadManager.lastTask ?: return
        val dp = resources.displayMetrics.density

        val root = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding((16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt(), (8 * dp).toInt())
        }

        // Title
        root.addView(android.widget.TextView(ctx).apply {
            text = "📥 ${task.name}"
            setTextColor(0xFFFFFFFF.toInt()); textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, (8 * dp).toInt())
        })

        // Overall progress
        val bytesStr = if (progress.bytesDownloaded > 1024 * 1024)
            String.format("%.1f МБ", progress.bytesDownloaded / (1024.0 * 1024.0))
        else "${progress.bytesDownloaded / 1024} КБ"
        root.addView(android.widget.TextView(ctx).apply {
            text = "Прогресс: ${progress.downloadedTiles}/${progress.totalTiles} (${progress.percent}%)  •  $bytesStr"
            setTextColor(0xFFCCCCCC.toInt()); textSize = 13f
            setPadding(0, 0, 0, (12 * dp).toInt())
        })

        // Progress bar
        root.addView(android.widget.ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; this.progress = progress.percent
            layoutParams = android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, (6 * dp).toInt())
            progressDrawable.setColorFilter(0xFFFF6F00.toInt(), android.graphics.PorterDuff.Mode.SRC_IN)
            setPadding(0, 0, 0, (12 * dp).toInt())
        })

        // Layer list
        root.addView(android.widget.TextView(ctx).apply {
            text = "Слои:"
            setTextColor(0xFF888888.toInt()); textSize = 12f
            setPadding(0, (8 * dp).toInt(), 0, (4 * dp).toInt())
        })

        val tilesPerLayer = if (task.layers.isNotEmpty()) progress.totalTiles / task.layers.size else 0
        var completedLayers = 0
        task.layers.forEachIndexed { idx, layer ->
            val layerDownloaded = minOf(
                maxOf(progress.downloadedTiles - idx * tilesPerLayer, 0),
                tilesPerLayer
            )
            val isCurrent = layer.layerLabel == progress.currentLayer
            val isDone = layerDownloaded >= tilesPerLayer
            if (isDone) completedLayers++
            val icon = when {
                isDone -> "✅"
                isCurrent -> "⏳"
                else -> "⏸"
            }
            val statusText = when {
                isDone -> "готово"
                isCurrent -> "$layerDownloaded/$tilesPerLayer"
                else -> "ожидание"
            }
            val layerRow = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding((8 * dp).toInt(), (4 * dp).toInt(), 0, (4 * dp).toInt())
            }
            val skipped = layer.layerKey in TileDownloadManager.skippedLayers
            layerRow.addView(android.widget.TextView(ctx).apply {
                text = if (skipped) "⛔  ${layer.layerLabel}  —  пропущен" else "$icon  ${layer.layerLabel}  —  $statusText"
                setTextColor(if (skipped) 0xFF666666.toInt() else if (isCurrent) 0xFFFFFFFF.toInt() else 0xFFAAAAAA.toInt())
                textSize = 13f
                layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            if (!isDone && !isCurrent && !skipped && progress.isRunning) {
                layerRow.addView(android.widget.TextView(ctx).apply {
                    text = "✕"
                    setTextColor(0xFFEF4444.toInt())
                    textSize = 16f
                    setPadding((12 * dp).toInt(), 0, (4 * dp).toInt(), 0)
                    setOnClickListener {
                        TileDownloadManager.skipLayer(layer.layerKey)
                        // Refresh dialog
                        (it.parent?.parent?.parent as? android.app.Dialog)?.dismiss()
                        showDownloadDetailsDialog()
                    }
                })
            }
            root.addView(layerRow)
        }

        val dlg = androidx.appcompat.app.AlertDialog.Builder(ctx, androidx.appcompat.R.style.Theme_AppCompat_Dialog)
            .setView(root)
        if (progress.isRunning) {
            dlg.setNegativeButton("⏹ Остановить") { _, _ ->
                TileDownloadManager.stopDownload()
                _binding?.downloadIndicator?.visibility = View.GONE
                Toast.makeText(ctx, "Загрузка остановлена", Toast.LENGTH_SHORT).show()
            }
        }
        dlg.setPositiveButton("Свернуть", null)
        dlg.show()
    }





    /** Apply UI scale to top bar buttons and bottom bar height */
    fun applyUiScale() {
        val b = _binding ?: return
        val prefs = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) ?: return
        val scale = prefs.getInt(PREF_UI_SCALE, 5).coerceIn(1, 10)
        val dm = resources.displayMetrics
        val density = dm.density

        // Bottom bar height: scale 1=48dp, 5=68dp, 10=96dp
        val barHeightDp = (scale * 5.3f + 42.7f)  // 1->48, 5->69.2, 10->95.7
        val params = b.bottomBar.layoutParams
        params.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        b.bottomBar.minimumHeight = (barHeightDp * density).toInt()
        b.bottomBar.layoutParams = params

        // Top bar button sizes: scale 1=32dp, 5=44dp, 10=60dp
        val btnSizeDp = (scale * 3.1f + 28.9f)  // 1->32, 5->44.4, 10->59.9
        val btnSizePx = (btnSizeDp * density).toInt()
        val topButtons = listOf(
            b.btnZoomIn, b.btnZoomOut, b.btnAddWaypoint, b.btnQuickAction,
            b.compassView
        )
        for (btn in topButtons) {
            btn?.layoutParams?.let { lp ->
                lp.width = btnSizePx
                lp.height = btnSizePx
                btn.layoutParams = lp
            }
        }
        val rightButtons = listOf(
            b.btnLayers, b.btnRec, b.btnLock, b.btnMapSwitch, b.btnSettings
        )
        for (btn in rightButtons) {
            btn?.layoutParams?.let { lp ->
                lp.width = btnSizePx
                lp.height = btnSizePx
                btn.layoutParams = lp
            }
        }
    }

    /** Apply user-chosen font scale to bottom bar widgets */
    fun applyWidgetFontScale() {
        val b = _binding ?: return
        val prefs = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) ?: return
        val scale = prefs.getInt(PREF_WIDGET_FONT_SCALE, 5).coerceIn(1, 10)
        val dm = resources.displayMetrics
        // Scale 1=12dp values/6dp labels, 5=20dp/10dp, 10=28dp/14dp
        val valueSizePx = (scale * 2f + 10f) * dm.density
        val labelSizePx = (scale * 1f + 5f) * dm.density
        val bar = b.bottomBar
        for (i in 0 until bar.childCount) {
            val container = bar.getChildAt(i)
            if (container is android.view.ViewGroup) {
                for (j in 0 until container.childCount) {
                    val child = container.getChildAt(j)
                    if (child is android.widget.TextView) {
                        if (child.typeface?.isBold == true) {
                            child.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, valueSizePx)
                        } else {
                            child.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, labelSizePx)
                        }
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        liveUsersPoller?.stop(); liveUsersPoller = null
        stopChronoTicker(); stopTimeTicker()
        tileServer?.cleanup(); tileServer = null
        _binding?.mapView?.onDestroy(); _binding = null
    }
}


