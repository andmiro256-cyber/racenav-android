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
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
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
import com.mapbox.mapboxsdk.style.layers.CircleLayer
import com.mapbox.mapboxsdk.style.layers.FillLayer
import com.mapbox.mapboxsdk.style.layers.LineLayer
import com.mapbox.mapboxsdk.style.layers.PropertyFactory
import com.mapbox.mapboxsdk.style.layers.PropertyValue
import com.mapbox.mapboxsdk.style.layers.SymbolLayer
import com.mapbox.mapboxsdk.style.sources.GeoJsonSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

        // Battery
        updateBatteryLevel()
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

    // Track editor state
    private var trackEditorMode = false
    private var editMoveMode = false   // перемещение точки через крестик

    // Drag point state (long-press drag in track editor)
    private var isDraggingPoint = false
    private var dragPointIndex = -1
    private var dragStartRunnable: Runnable? = null
    private var renderEditorJob: Job? = null

    // Widget-free mode state (long-press on map → hide bars)
    private var isWidgetFreeMode = false

    // Draw mode state
    private var drawMode = false
    private val drawnPoints = mutableListOf<TrackEditor.TrackPoint>()

    private var initialZoomDone = false
    private var waitingForFirstGps = false
    private var firstGpsAnimDone = false
    private var flyAnimationActive = false
    private var pendingNavResumeCheck = false
    var autoRecenterEnabled = false
    var tilt3dEnabled = false       // 3D tilt when driving in FOLLOW_COURSE
    var autoZoomLevel = 0           // 0=off, 1-10; controls zoom amplitude with speed
    private var userBaseZoom = -1.0 // user's preferred zoom; auto-zoom adjusts relative to this
    private var userDragged = false  // true = user moved map manually, pause following
    private var smoothedBearing = -1.0  // EMA-сглаженный курс, -1 = не инициализирован
    private var bearingFrozen = true      // true = курсор заморожен (стоим)
    private var lastValidBearing = 0f     // последний валидный bearing для freeze
    private var prevFreezeCheckLat = 0.0
    private var prevFreezeCheckLon = 0.0
    private var prevFreezeCheckTime = 0L

    // Magnetometer compass — provides heading when stopped
    private var sensorManager: android.hardware.SensorManager? = null
    @Volatile private var magneticHeading = -1f  // last magnetic heading (true north, 0-360, -1 = no data)
    private var magneticDeclination = 0f          // magnetic → true north correction
    private var smoothedMagHeading = -1.0         // EMA-smoothed magnetic heading
    private val sensorListener = object : android.hardware.SensorEventListener {
        private val rotMatrix = FloatArray(9)
        private val orient = FloatArray(3)
        private var accel: FloatArray? = null
        private var mag: FloatArray? = null
        override fun onSensorChanged(event: android.hardware.SensorEvent) {
            when (event.sensor.type) {
                android.hardware.Sensor.TYPE_ACCELEROMETER -> accel = event.values.clone()
                android.hardware.Sensor.TYPE_MAGNETIC_FIELD -> mag = event.values.clone()
            }
            val a = accel ?: return
            val m = mag ?: return
            if (android.hardware.SensorManager.getRotationMatrix(rotMatrix, null, a, m)) {
                android.hardware.SensorManager.getOrientation(rotMatrix, orient)
                val rawMag = ((Math.toDegrees(orient[0].toDouble()) + 360) % 360).toFloat()
                // Apply declination to get true north + smooth with EMA
                val trueHeading = (rawMag + magneticDeclination + 360) % 360
                smoothedMagHeading = if (smoothedMagHeading < 0) trueHeading.toDouble()
                else {
                    var d = trueHeading - smoothedMagHeading
                    while (d > 180) d -= 360; while (d < -180) d += 360
                    (smoothedMagHeading + 0.06 * d + 360) % 360
                }
                magneticHeading = smoothedMagHeading.toFloat()
            }
        }
        override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) {}
    }

    // Smooth camera loop state (Choreographer-driven, 60 FPS)
    private var lastGpsLat = 0.0
    private var lastGpsLon = 0.0
    private var lastGpsSpeedMs = 0f
    private var lastGpsBearing = 0f       // effectiveBearing from GPS callback
    private var lastGpsTimeNanos = 0L     // System.nanoTime() at GPS fix
    private var lastGpsSpeedKmh = 0.0
    private var cameraLoopRunning = false
    private var lastTelemetrySentMs = 0L   // throttle telemetry to every 5s
    private var locationTrackingStarted = false  // prevent duplicate GPS listeners on style reload
    private var activeLocationCallback: com.mapbox.mapboxsdk.location.engine.LocationEngineCallback<com.mapbox.mapboxsdk.location.engine.LocationEngineResult>? = null
    private var locationEngine: com.mapbox.mapboxsdk.location.engine.LocationEngine? = null
    private val emergencyHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var emergencyRunnable: Runnable? = null
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
    private val warnedWrongWpIndices = mutableSetOf<Int>()  // wrong WP toast fired once per index
    private val wrongWpSoundPlayed = mutableSetOf<Int>()  // wrong WP sound fired once per radius entry
    private var justTakenWpIndex = -1  // WP index just taken — suppress wrong sound while still in radius
    private val visitedMarkerIndices = mutableSetOf<Int>()  // user marker approach fired once per index

    // User points — placed on map, editable name
    data class UserPoint(var name: String, val position: LatLng, var color: String = "#1565C0", var symbol: String = "", var proximity: Double = 0.0)
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
        const val PREF_ROUTE_LINE_VISIBLE = "route_line_visible"
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
        const val PREF_WIDGET_BATTERY = "widget_battery"
        const val PREF_WIDGET_ORDER = "widget_order"
        const val PREF_NAV_ACTIVE = "nav_active"
        const val PREF_WP_APPROACH_RADIUS = "wp_approach_radius"  // metres, default 30 (approach/warning)
        const val DEFAULT_WP_APPROACH_RADIUS = 30
        const val PREF_WP_TAKEN_RADIUS = "wp_taken_radius"    // metres, default 20 (taken/auto-advance)
        const val DEFAULT_WP_TAKEN_RADIUS = 20
        const val PREF_SYNC_API_KEY = "sync_api_key"
        const val SYNC_BASE_URL = "http://87.120.84.254:9222"

        // Traccar live monitoring
        const val PREF_TRACCAR_ENABLED     = "traccar_enabled"      // bool, default false
        const val PREF_TRACCAR_URL         = "traccar_server_url"   // e.g. "http://217.60.1.225:5055"
        const val PREF_TRACCAR_DEVICE_ID   = "traccar_device_id"    // Traccar uniqueId used by OsmAnd protocol
        const val PREF_TRACCAR_DEVICE_NAME = "traccar_device_name"  // human-readable name

        const val LOADED_TRACK_SOURCE_ID = "loaded-track-source"
        const val LOADED_TRACK_LAYER_ID = "loaded-track-layer"

        // Track editor layers
        const val TRACK_EDIT_LINE_SOURCE  = "track-edit-line-source"
        const val TRACK_EDIT_LINE_LAYER   = "track-edit-line-layer"
        const val TRACK_EDIT_POINTS_SOURCE = "track-edit-points-source"
        const val TRACK_EDIT_POINTS_LAYER  = "track-edit-points-layer"

        val ALL_WIDGET_KEYS = listOf("speed","bearing","tracklen","nextcp","altitude","chrono","time","remain_km","nextcp_name","tripmaster","server_status","battery")

        const val PREF_TOP_BAR_ORDER = "top_bar_order"
        val ALL_TOP_BAR_KEYS = listOf("compass","zoom","waypoint","quick","stop","spacer","go","battery","layers","rec","lock","map_switch","server_dot","settings")

        // Top bar button visibility prefs
        const val PREF_BTN_COMPASS = "btn_compass"
        const val PREF_BTN_ZOOM = "btn_zoom"
        const val PREF_BTN_WAYPOINT = "btn_waypoint"
        const val PREF_BTN_QUICK = "btn_quick_action"
        const val PREF_BTN_LAYERS = "btn_layers"
        const val PREF_BTN_REC = "btn_rec"
        const val PREF_BTN_LOCK = "btn_lock"

        const val PREF_XCOVER_KEY_ACTION = "xcover_key_action"

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
        const val PREF_NAV_COMPASS_ENABLED = "nav_compass_enabled"
        const val PREF_NAV_COMPASS_POSITION = "nav_compass_position"
        const val PREF_NAV_COMPASS_SIZE = "nav_compass_size"
        const val PREF_NAV_COMPASS_ALPHA = "nav_compass_alpha"
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
        const val PREF_BTN_BATTERY = "btn_battery"
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
        // === Спутники ===
        "google"       to TileSource("Google Спутник", listOf(
            "https://mt0.google.com/vt/lyrs=s&x={x}&y={y}&z={z}",
            "https://mt1.google.com/vt/lyrs=s&x={x}&y={y}&z={z}",
            "https://mt2.google.com/vt/lyrs=s&x={x}&y={y}&z={z}",
            "https://mt3.google.com/vt/lyrs=s&x={x}&y={y}&z={z}"), maxZoom = 20),
        "google_hybrid" to TileSource("Google Гибрид", listOf(
            "https://mt0.google.com/vt/lyrs=y&hl=ru&x={x}&y={y}&z={z}",
            "https://mt1.google.com/vt/lyrs=y&hl=ru&x={x}&y={y}&z={z}",
            "https://mt2.google.com/vt/lyrs=y&hl=ru&x={x}&y={y}&z={z}",
            "https://mt3.google.com/vt/lyrs=y&hl=ru&x={x}&y={y}&z={z}"), maxZoom = 20),
        "yandex_sat"   to TileSource("Яндекс Спутник", listOf(
            "https://sat01.maps.yandex.net/tiles?l=sat&x={x}&y={y}&z={z}&lang=ru_RU&projection=web_mercator",
            "https://sat02.maps.yandex.net/tiles?l=sat&x={x}&y={y}&z={z}&lang=ru_RU&projection=web_mercator",
            "https://sat03.maps.yandex.net/tiles?l=sat&x={x}&y={y}&z={z}&lang=ru_RU&projection=web_mercator",
            "https://sat04.maps.yandex.net/tiles?l=sat&x={x}&y={y}&z={z}&lang=ru_RU&projection=web_mercator"), maxZoom = 19),
        "satellite"    to TileSource("ESRI Спутник", listOf(
            "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"), maxZoom = 18),
        "esri_clarity"  to TileSource("ESRI Clarity", listOf(
            "https://clarity.maptiles.arcgis.com/arcgis/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}?blankTile=false"), maxZoom = 19),
        // === Карты ===
        "osm"          to TileSource("OpenStreetMap", listOf(
            "https://a.tile.openstreetmap.org/{z}/{x}/{y}.png",
            "https://b.tile.openstreetmap.org/{z}/{x}/{y}.png",
            "https://c.tile.openstreetmap.org/{z}/{x}/{y}.png"), maxZoom = 19),
        "google_maps"   to TileSource("Google Карта", listOf(
            "https://mt0.google.com/vt/lyrs=m&hl=ru&x={x}&y={y}&z={z}",
            "https://mt1.google.com/vt/lyrs=m&hl=ru&x={x}&y={y}&z={z}",
            "https://mt2.google.com/vt/lyrs=m&hl=ru&x={x}&y={y}&z={z}",
            "https://mt3.google.com/vt/lyrs=m&hl=ru&x={x}&y={y}&z={z}"), maxZoom = 20),
        "yandex_map"   to TileSource("Яндекс Карта", listOf(
            "https://vec01.maps.yandex.net/tiles?l=map&x={x}&y={y}&z={z}&lang=ru_RU&projection=web_mercator",
            "https://vec02.maps.yandex.net/tiles?l=map&x={x}&y={y}&z={z}&lang=ru_RU&projection=web_mercator",
            "https://vec03.maps.yandex.net/tiles?l=map&x={x}&y={y}&z={z}&lang=ru_RU&projection=web_mercator",
            "https://vec04.maps.yandex.net/tiles?l=map&x={x}&y={y}&z={z}&lang=ru_RU&projection=web_mercator"), maxZoom = 19),
        "2gis"          to TileSource("2ГИС", listOf(
            "https://tile0.maps.2gis.com/tiles?x={x}&y={y}&z={z}&r=g&ts=online_1",
            "https://tile1.maps.2gis.com/tiles?x={x}&y={y}&z={z}&r=g&ts=online_1",
            "https://tile2.maps.2gis.com/tiles?x={x}&y={y}&z={z}&r=g&ts=online_1"), maxZoom = 19),
        "osm_hot"       to TileSource("OSM HOT", listOf(
            "https://tile-a.openstreetmap.fr/hot/{z}/{x}/{y}.png",
            "https://tile-b.openstreetmap.fr/hot/{z}/{x}/{y}.png"), maxZoom = 19),
        // === Рельеф и топо ===
        "topo"         to TileSource("OpenTopo", listOf("https://tile.opentopomap.org/{z}/{x}/{y}.png"), maxZoom = 17),
        "google_terrain" to TileSource("Google Рельеф", listOf(
            "https://mt0.google.com/vt/lyrs=t&hl=ru&x={x}&y={y}&z={z}",
            "https://mt1.google.com/vt/lyrs=t&hl=ru&x={x}&y={y}&z={z}",
            "https://mt2.google.com/vt/lyrs=t&hl=ru&x={x}&y={y}&z={z}",
            "https://mt3.google.com/vt/lyrs=t&hl=ru&x={x}&y={y}&z={z}"), maxZoom = 20),
        "kosmosnimki_relief" to TileSource("Космоснимки", listOf(
            "https://atilecart.kosmosnimki.ru/rw/{z}/{x}/{y}.png",
            "https://btilecart.kosmosnimki.ru/rw/{z}/{x}/{y}.png",
            "https://ctilecart.kosmosnimki.ru/rw/{z}/{x}/{y}.png",
            "https://dtilecart.kosmosnimki.ru/rw/{z}/{x}/{y}.png"), maxZoom = 13),
        // === Генштаб ===
        "ggc250"       to TileSource("Генштаб 250м", listOf(
            "https://a.tiles.nakarte.me/ggc250/{z}/{x}/{y}",
            "https://b.tiles.nakarte.me/ggc250/{z}/{x}/{y}",
            "https://c.tiles.nakarte.me/ggc250/{z}/{x}/{y}"), tms = true, maxZoom = 15),
        "ggc500"       to TileSource("Генштаб 500м", listOf(
            "https://a.tiles.nakarte.me/ggc500/{z}/{x}/{y}",
            "https://b.tiles.nakarte.me/ggc500/{z}/{x}/{y}",
            "https://c.tiles.nakarte.me/ggc500/{z}/{x}/{y}"), tms = true, maxZoom = 14),
        "ggc2000"      to TileSource("Генштаб 2км", listOf(
            "https://a.tiles.nakarte.me/ggc2000/{z}/{x}/{y}",
            "https://b.tiles.nakarte.me/ggc2000/{z}/{x}/{y}",
            "https://c.tiles.nakarte.me/ggc2000/{z}/{x}/{y}"), tms = true, maxZoom = 12),
        "topo250"      to TileSource("Топо 250м", listOf(
            "https://a.tiles.nakarte.me/topo250/{z}/{x}/{y}",
            "https://b.tiles.nakarte.me/topo250/{z}/{x}/{y}",
            "https://c.tiles.nakarte.me/topo250/{z}/{x}/{y}"), tms = true, maxZoom = 15),
        "topo001m"     to TileSource("Топо 1:1М", listOf(
            "https://a.tiles.nakarte.me/topo001m/{z}/{x}/{y}",
            "https://b.tiles.nakarte.me/topo001m/{z}/{x}/{y}",
            "https://c.tiles.nakarte.me/topo001m/{z}/{x}/{y}"), tms = true, maxZoom = 13),
        "topomapper"   to TileSource("Topomapper", listOf(
            "http://88.99.52.156/tmg/{z}/{x}/{y}"), maxZoom = 13),
        // === Туристические ===
        "tf_outdoors"   to TileSource("TF Outdoors", listOf(
            "https://a.tile.thunderforest.com/outdoors/{z}/{x}/{y}.png?apikey=6170aad10dfd42a38d4d8c709a536f38",
            "https://b.tile.thunderforest.com/outdoors/{z}/{x}/{y}.png?apikey=6170aad10dfd42a38d4d8c709a536f38",
            "https://c.tile.thunderforest.com/outdoors/{z}/{x}/{y}.png?apikey=6170aad10dfd42a38d4d8c709a536f38"), maxZoom = 22),
        "lomaps"        to TileSource("LoMaps", listOf(
            "https://a.tile.thunderforest.com/locus-4za/{z}/{x}/{y}.png?apikey=7c352c8ff1244dd8b732e349e0b0fe8d",
            "https://b.tile.thunderforest.com/locus-4za/{z}/{x}/{y}.png?apikey=7c352c8ff1244dd8b732e349e0b0fe8d",
            "https://c.tile.thunderforest.com/locus-4za/{z}/{x}/{y}.png?apikey=7c352c8ff1244dd8b732e349e0b0fe8d"), maxZoom = 22),
        "cyclosm"       to TileSource("CyclOSM", listOf(
            "https://a.tile-cyclosm.openstreetmap.fr/cyclosm/{z}/{x}/{y}.png",
            "https://b.tile-cyclosm.openstreetmap.fr/cyclosm/{z}/{x}/{y}.png",
            "https://c.tile-cyclosm.openstreetmap.fr/cyclosm/{z}/{x}/{y}.png"), maxZoom = 19),
        "tf_transport"  to TileSource("TF Transport", listOf(
            "https://a.tile.thunderforest.com/transport/{z}/{x}/{y}.png?apikey=6170aad10dfd42a38d4d8c709a536f38",
            "https://b.tile.thunderforest.com/transport/{z}/{x}/{y}.png?apikey=6170aad10dfd42a38d4d8c709a536f38",
            "https://c.tile.thunderforest.com/transport/{z}/{x}/{y}.png?apikey=6170aad10dfd42a38d4d8c709a536f38"), maxZoom = 22),
        "tf_cycle"      to TileSource("TF Cycle", listOf(
            "https://a.tile.thunderforest.com/cycle/{z}/{x}/{y}.png?apikey=6170aad10dfd42a38d4d8c709a536f38",
            "https://b.tile.thunderforest.com/cycle/{z}/{x}/{y}.png?apikey=6170aad10dfd42a38d4d8c709a536f38",
            "https://c.tile.thunderforest.com/cycle/{z}/{x}/{y}.png?apikey=6170aad10dfd42a38d4d8c709a536f38"), maxZoom = 22),
        "mtbmap"        to TileSource("MTB Map", listOf(
            "http://tile.mtbmap.cz/mtbmap_tiles/{z}/{x}/{y}.png"), maxZoom = 18),
        "michelin"      to TileSource("Michelin", listOf(
            "https://map1.viamichelin.com/map/mapdirect?map=viamichelin&z={z}&x={x}&y={y}&format=png&version=201901161110&layer=background&locale=default&cs=1&protocol=https"), maxZoom = 18)
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
            "https://c.basemaps.cartocdn.com/rastertiles/voyager_only_labels/{z}/{x}/{y}.png"), opacity = 1.0f),
        "yandex_hybrid" to OverlaySource("Яндекс Гибрид", listOf(
            "https://vec01.maps.yandex.net/tiles?l=skl&x={x}&y={y}&z={z}&lang=ru_RU&projection=web_mercator",
            "https://vec02.maps.yandex.net/tiles?l=skl&x={x}&y={y}&z={z}&lang=ru_RU&projection=web_mercator",
            "https://vec03.maps.yandex.net/tiles?l=skl&x={x}&y={y}&z={z}&lang=ru_RU&projection=web_mercator",
            "https://vec04.maps.yandex.net/tiles?l=skl&x={x}&y={y}&z={z}&lang=ru_RU&projection=web_mercator"), opacity = 1.0f)
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

    /**
     * Apply server catalog: overwrite built-in tile/overlay sources with proxy URLs.
     * Preserves offline and custom sources (they are not in the catalog).
     */
    private fun applyCatalog(catalog: TileCatalogManager.Catalog?) {
        if (catalog == null) return
        for (entry in catalog.base) {
            val proxyUrl = TileCatalogManager.buildProxyUrl(entry.proxyPath)
            tileSources[entry.key] = TileSource(entry.label, listOf(proxyUrl), entry.tms, entry.maxZoom)
        }
        for (entry in catalog.overlays) {
            val proxyUrl = TileCatalogManager.buildProxyUrl(entry.proxyPath)
            overlaySources[entry.key] = OverlaySource(entry.label, listOf(proxyUrl), entry.tms, entry.opacity, entry.maxZoom)
        }
        Log.d("TileCatalog", "Applied catalog v${catalog.version}: ${catalog.base.size} base + ${catalog.overlays.size} overlays")
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
        updateNavCompass()
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
            // Reduce prefetch zoom delta from default 4 → 1 to load fewer adjacent tiles on startup
            map.setPrefetchZoomDelta(1)
            val tilePrefs = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val savedTile = tilePrefs?.getString(PREF_TILE_KEY, "osm") ?: "osm"
            val savedOverlayStr = tilePrefs?.getString(PREF_OVERLAY_KEY, "") ?: ""
            val savedOverlays = savedOverlayStr.split(",").filter { it.isNotBlank() && it != "none" }.toSet()
            // Load custom and offline maps BEFORE loadTileStyle so tile sources are ready
            reloadCustomSources()
            loadOfflineMapsFromPrefs()
            // Apply cached catalog instantly (no network), then load style
            val ctx = context ?: return@getMapAsync
            val cachedCatalog = TileCatalogManager.loadCachedCatalog(ctx)
            applyCatalog(cachedCatalog)
            loadTileStyle(savedTile, savedOverlays)
            // Async fetch fresh catalog from server
            val cachedVersion = cachedCatalog?.version ?: -1
            TileCatalogManager.fetchCatalog(ctx) { catalog ->
                if (catalog != null && catalog.version != cachedVersion && isAdded && _binding != null) {
                    applyCatalog(catalog)
                    // Reload current style with updated proxy URLs
                    loadTileStyle(currentTileKey, currentOverlayKeys)
                }
            }

            // Restore camera position if saved
            val savedLat = tilePrefs?.getFloat(PREF_CAMERA_LAT, Float.MIN_VALUE) ?: Float.MIN_VALUE
            val savedLon = tilePrefs?.getFloat(PREF_CAMERA_LON, Float.MIN_VALUE) ?: Float.MIN_VALUE
            val savedZoom = tilePrefs?.getFloat(PREF_CAMERA_ZOOM, -1f) ?: -1f
            // Only restore if zoom ≥ 5 — ignore world-view artifacts saved by onStop
            if (savedLat != Float.MIN_VALUE && savedZoom >= 5f) {
                map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(savedLat.toDouble(), savedLon.toDouble()), savedZoom.toDouble()))
                initialZoomDone = true
            } else {
                // No valid saved position — try last known location from system to avoid world view
                val lm = context?.getSystemService(android.content.Context.LOCATION_SERVICE)
                    as? android.location.LocationManager
                val lastLoc = try {
                    lm?.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
                        ?: lm?.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
                        ?: lm?.getLastKnownLocation(android.location.LocationManager.PASSIVE_PROVIDER)
                } catch (_: SecurityException) { null }
                if (lastLoc != null) {
                    map.moveCamera(CameraUpdateFactory.newLatLngZoom(
                        LatLng(lastLoc.latitude, lastLoc.longitude), 13.0))
                }
                waitingForFirstGps = true
            }

            autoRecenterEnabled = tilePrefs?.getBoolean(PREF_AUTO_RECENTER, false) ?: false
            tilt3dEnabled = tilePrefs?.getBoolean(PREF_3D_TILT, false) ?: false
            autoZoomLevel = tilePrefs?.getInt(PREF_AUTO_ZOOM, 0) ?: 0

            setupButtons(map)
            applyNavCompassPrefs()
            // Check if app was closed while recording — offer resume/save
            if (!isRecording) checkForUnfinishedTrack()
            // Tap on live user marker → show info card
            map.addOnMapClickListener { latLng ->
                if (trackEditorMode && !editMoveMode) {
                    handleEditorTap(map, latLng)
                    return@addOnMapClickListener true
                }
                if (isDownloadSelecting) {
                    handleDownloadTap(latLng)
                    return@addOnMapClickListener true
                }
                handleLiveUserClick(map, latLng)
            }
            // Long press on map: 1.5s → toggle UI bars / drag editor point; 3.5s → emergency gear icon
            var touchDownX = 0f; var touchDownY = 0f
            val density = context?.resources?.displayMetrics?.density ?: 3f
            val moveThr = (30 * density).let { it * it }
            var panelRunnable: Runnable? = null
            binding.mapView.setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        emergencyRunnable?.let { emergencyHandler.removeCallbacks(it) }
                        panelRunnable?.let { emergencyHandler.removeCallbacks(it) }
                        dragStartRunnable?.let { emergencyHandler.removeCallbacks(it) }; dragStartRunnable = null
                        touchDownX = event.x; touchDownY = event.y

                        // In track editor mode: check for long-press on a point → drag it
                        if (trackEditorMode && !editMoveMode && !drawMode) {
                            val nearIdx = findNearestEditPointOnScreen(event.x, event.y, 60 * density)
                            if (nearIdx >= 0) {
                                dragStartRunnable = Runnable {
                                    TrackEditor.startDrag(nearIdx)
                                    dragPointIndex = nearIdx
                                    isDraggingPoint = true
                                    mapboxMap?.uiSettings?.isScrollGesturesEnabled = false
                                    mapboxMap?.uiSettings?.isZoomGesturesEnabled = false
                                    _binding?.editPointPopup?.visibility = android.view.View.GONE
                                    // Haptic feedback
                                    @Suppress("DEPRECATION")
                                    (context?.getSystemService(android.content.Context.VIBRATOR_SERVICE) as? android.os.Vibrator)?.vibrate(40)
                                    renderEditorPoints()
                                }
                                emergencyHandler.postDelayed(dragStartRunnable!!, 400L)
                                // Don't start widget-free timer when near a point
                                return@setOnTouchListener false
                            }
                        }

                        // Widget-free mode toggle (1.5s hold)
                        panelRunnable = Runnable {
                            val nowVisible = _binding?.topBar?.visibility == android.view.View.VISIBLE
                            isWidgetFreeMode = nowVisible  // bars were visible → now going to widget-free
                            _binding?.topBar?.visibility = if (nowVisible) android.view.View.GONE else android.view.View.VISIBLE
                            _binding?.bottomBar?.visibility = if (nowVisible) android.view.View.GONE else android.view.View.VISIBLE
                            applyNavCompassPrefs()
                        }
                        emergencyHandler.postDelayed(panelRunnable!!, 1500L)
                        // 3.5s: show gear icon
                        emergencyRunnable = Runnable {
                            _binding?.btnEmergencySettings?.visibility = android.view.View.VISIBLE
                        }
                        emergencyHandler.postDelayed(emergencyRunnable!!, 3500L)
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        // Dragging an editor point
                        if (isDraggingPoint) {
                            val latLng = mapboxMap?.projection?.fromScreenLocation(
                                android.graphics.PointF(event.x, event.y))
                            if (latLng != null && dragPointIndex in TrackEditor.editPoints.indices) {
                                TrackEditor.editPoints[dragPointIndex] =
                                    TrackEditor.TrackPoint(latLng.latitude, latLng.longitude)
                                renderEditorPoints()
                            }
                            return@setOnTouchListener true
                        }
                        val dx = event.x - touchDownX; val dy = event.y - touchDownY
                        if (dx * dx + dy * dy > moveThr) {
                            panelRunnable?.let { emergencyHandler.removeCallbacks(it) }; panelRunnable = null
                            emergencyRunnable?.let { emergencyHandler.removeCallbacks(it) }; emergencyRunnable = null
                            dragStartRunnable?.let { emergencyHandler.removeCallbacks(it) }; dragStartRunnable = null
                        }
                    }
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                        panelRunnable?.let { emergencyHandler.removeCallbacks(it) }; panelRunnable = null
                        emergencyRunnable?.let { emergencyHandler.removeCallbacks(it) }; emergencyRunnable = null
                        dragStartRunnable?.let { emergencyHandler.removeCallbacks(it) }; dragStartRunnable = null
                        if (isDraggingPoint) {
                            isDraggingPoint = false
                            dragPointIndex = -1
                            mapboxMap?.uiSettings?.isScrollGesturesEnabled = true
                            mapboxMap?.uiSettings?.isZoomGesturesEnabled = true
                            renderEditorPoints()
                            updateEditorUi()
                            return@setOnTouchListener true
                        }
                    }
                }
                false
            }
        }

        // Restore lock state after rotation
        if (savedInstanceState?.getBoolean("screen_locked", false) == true) {
            lockScreen()
        }
        if (savedInstanceState?.getBoolean("widget_free_mode", false) == true) {
            isWidgetFreeMode = true
            _binding?.topBar?.visibility = View.GONE
            _binding?.bottomBar?.visibility = View.GONE
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
        if (!isWidgetFreeMode) {
            _binding?.topBar?.visibility = View.VISIBLE
            _binding?.bottomBar?.visibility = View.VISIBLE
        }
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
            "battery"     -> b.widgetBatteryContainer
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
            WidgetDef("altitude",    PREF_WIDGET_ALTITUDE,    false),
            WidgetDef("chrono",      PREF_WIDGET_CHRONO,      false),
            WidgetDef("time",        PREF_WIDGET_TIME,        false),
            WidgetDef("remain_km",   PREF_WIDGET_REMAIN_KM,   false),
            WidgetDef("nextcp_name", PREF_WIDGET_NEXTCP_NAME, true),
            WidgetDef("tripmaster",  PREF_WIDGET_TRIPMASTER,  false),
            WidgetDef("server_status", PREF_WIDGET_SERVER_STATUS, false),
            WidgetDef("battery",       PREF_WIDGET_BATTERY,       false),
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

        // Battery: bottom widget + top bar indicator (separate prefs)
        updateBatteryLevel()
        b.btnBatteryIndicator.visibility = if (prefs.getBoolean(PREF_BTN_BATTERY, false)) View.VISIBLE else View.GONE
    }

    private fun updateBatteryLevel() {
        val ctx = context ?: return
        val b = _binding ?: return
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
        val level = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val batteryIntent = ctx.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        val plugged = batteryIntent?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val isCharging = plugged != 0
        val color = when {
            isCharging -> 0xFF4CAF50.toInt()  // green when charging
            level > 50 -> 0xFFFFFFFF.toInt()  // white
            level > 20 -> 0xFFFFEB3B.toInt()  // yellow
            else -> 0xFFFF4444.toInt()         // red
        }
        b.widgetBattery.text = "$level"
        b.widgetBattery.setTextColor(color)
        b.btnBatteryIndicator.text = "$level%"
        b.btnBatteryIndicator.setTextColor(color)
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
        b.btnBatteryIndicator.visibility = if (prefs.getBoolean(PREF_BTN_BATTERY, false)) View.VISIBLE else View.GONE
        if (prefs.getBoolean(PREF_BTN_BATTERY, false)) updateBatteryLevel()
        // Update server dot color
        if (TraccarService.isRunning) {
            b.topBarServerDot.background?.setTint(0xFF4CAF50.toInt())
        } else {
            b.topBarServerDot.background?.setTint(0xFF888888.toInt())
        }
        // btnSettings always visible — user needs access to settings

        // Reorder top bar buttons according to saved order
        applyTopBarOrder()
    }

    fun applyTopBarOrder() {
        val prefs = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) ?: return
        val b = _binding ?: return
        val topBar = b.topBar

        val defaultOrder = ALL_TOP_BAR_KEYS.joinToString(",")
        val savedOrder = prefs.getString(PREF_TOP_BAR_ORDER, defaultOrder) ?: defaultOrder
        val orderedKeys = savedOrder.split(",").toMutableList()
        // Add any new keys that may have been added in updates
        ALL_TOP_BAR_KEYS.forEach { k -> if (k !in orderedKeys) orderedKeys.add(k) }

        // Map key -> view(s). "zoom" maps to two buttons (in/out).
        fun viewsForKey(key: String): List<android.view.View> = when (key) {
            "compass"    -> listOf(b.compassView)
            "zoom"       -> listOf(b.btnZoomIn, b.btnZoomOut)
            "waypoint"   -> listOf(b.btnAddWaypoint)
            "quick"      -> listOf(b.btnQuickAction)
            "stop"       -> listOf(b.btnWidgetStop)
            "go"         -> listOf(b.btnWidgetGo)
            "battery"    -> listOf(b.btnBatteryIndicator)
            "layers"     -> listOf(b.btnLayers)
            "rec"        -> listOf(b.btnRec)
            "lock"       -> listOf(b.btnLock)
            "map_switch" -> listOf(b.btnMapSwitch)
            "server_dot" -> listOf(b.topBarServerDot)
            "settings"   -> listOf(b.btnSettings)
            else         -> emptyList()
        }

        // Detach all children from topBar (without destroying them)
        topBar.removeAllViews()

        for (key in orderedKeys) {
            if (key == "spacer") {
                // Spacer: flexible space between left and right groups
                val spacer = android.view.View(requireContext()).apply {
                    layoutParams = android.widget.LinearLayout.LayoutParams(0, 1, 1f)
                }
                topBar.addView(spacer)
                continue
            }

            val views = viewsForKey(key)
            if (views.isEmpty()) continue

            // Skip entirely hidden (disabled) buttons — they remain GONE
            val allGone = views.all { it.visibility == View.GONE }
            if (allGone) continue

            for (v in views) {
                // Detach from parent if still attached somewhere
                (v.parent as? android.view.ViewGroup)?.removeView(v)
                topBar.addView(v)
            }
        }
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
        val base = tileSources[baseKey] ?: tileSources["osm"] ?: return ""
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
        // Save camera position before style reload (setStyle resets camera).
        // Only restore if zoom > 2 — avoids restoring world-view on first launch
        // before prefs-based camera has been applied (race condition on cold start).
        val savedCamera = mapboxMap?.cameraPosition?.takeIf { it.zoom > 2.0 }
        mapboxMap?.setStyle(Style.Builder().fromJson(json)) { style ->
            // Restore camera position after style change
            savedCamera?.let { mapboxMap?.moveCamera(CameraUpdateFactory.newCameraPosition(it)) }
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
        // LocationComponent is NOT activated — CameraMode.NONE + transparent icons = no visible output.
        // Activating it registers 2 internal GPS listeners we don't need.
        // We get the engine directly from LocationEngineDefault (1 listener total).
        applyFollowMode()
        applyCursorOffset()

        // Start location tracking only once per lifecycle
        if (!locationTrackingStarted) {
            locationTrackingStarted = true
            setupLocationTrackingDirect(ctx)
        }

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
            val dt = sdf.parse(isoDate.substringBefore('.').substringBefore('+').substringBefore('Z')) ?: return "—"
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
    /** Determine live user marker color: green=online, yellow=recently active (≤30 min), gray=offline */
    private fun liveUserMarkerColor(status: String, lastUpdate: String?): Int {
        if (status == "online") return Color.parseColor("#22C55E")
        if (lastUpdate != null) {
            try {
                val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
                sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
                val dt = sdf.parse(lastUpdate.substringBefore('.').substringBefore('+').substringBefore('Z'))
                if (dt != null && (System.currentTimeMillis() - dt.time) / 60000 <= 30)
                    return Color.parseColor("#FFD600")
            } catch (_: Exception) {}
        }
        return Color.parseColor("#888888")
    }

    private fun createLiveUserBitmap(name: String, status: String = "online", lastUpdate: String? = null): Bitmap {
        val prefs = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val markerSizeScale = prefs?.getInt(PREF_LIVE_USER_SIZE, DEFAULT_LIVE_USER_SIZE) ?: DEFAULT_LIVE_USER_SIZE
        val density = resources.displayMetrics.density

        val circleSizePx = (markerScaleToDp(markerSizeScale) * density).toInt().coerceAtLeast(28)
        val cr = circleSizePx / 2f  // circle radius

        // Label
        val labelSizeScale = prefs?.getInt(PREF_LIVE_USER_LABEL_SIZE, DEFAULT_LIVE_USER_LABEL_SIZE) ?: DEFAULT_LIVE_USER_LABEL_SIZE
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = labelScaleToSp(labelSizeScale) * density
            isFakeBoldText = true
            setShadowLayer(3f * density, 0f, 0f, Color.parseColor("#CC000000"))
        }
        val displayName = if (name.length > 14) name.take(14) + "…" else name.ifBlank { "?" }
        val textWidth = labelPaint.measureText(displayName)
        val textHeight = labelPaint.descent() - labelPaint.ascent()
        val textGap = 4 * density

        val markerColor = liveUserMarkerColor(status, lastUpdate)
        val bmpW = maxOf(circleSizePx, (textWidth + 8 * density).toInt())
        val bmpH = circleSizePx + textGap.toInt() + textHeight.toInt() + (2 * density).toInt()
        val bmp = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val cx = bmpW / 2f

        // Diamond (rotated square) shape
        val half = cr * 0.92f
        val diamondPath = Path().apply {
            moveTo(cx, cr - half)       // top
            lineTo(cx + half, cr)       // right
            lineTo(cx, cr + half)       // bottom
            lineTo(cx - half, cr)       // left
            close()
        }
        canvas.drawPath(diamondPath, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = markerColor; style = Paint.Style.FILL
        })
        // White border
        canvas.drawPath(diamondPath, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.STROKE
            strokeWidth = 2f * density; strokeJoin = Paint.Join.ROUND
        })
        // Star symbol ✴ inside
        val starPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = circleSizePx * 0.44f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        val starY = cr - (starPaint.ascent() + starPaint.descent()) / 2f
        canvas.drawText("\u2734", cx, starY, starPaint)  // ✴ Eight Pointed Black Star

        // Name label below circle
        labelPaint.color = Color.WHITE
        labelPaint.textAlign = Paint.Align.CENTER
        labelPaint.style = Paint.Style.FILL
        canvas.drawText(displayName, cx, circleSizePx + textGap - labelPaint.ascent(), labelPaint)

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
                    style.addImage(iconId, createLiveUserBitmap(d.name, d.status, d.lastUpdate))
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

    private fun startMagnetometer() {
        val ctx = context ?: return
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as? android.hardware.SensorManager ?: return
        sensorManager = sm
        val accel = sm.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER)
        val mag = sm.getDefaultSensor(android.hardware.Sensor.TYPE_MAGNETIC_FIELD)
        if (accel != null && mag != null) {
            sm.registerListener(sensorListener, accel, android.hardware.SensorManager.SENSOR_DELAY_UI)
            sm.registerListener(sensorListener, mag, android.hardware.SensorManager.SENSOR_DELAY_UI)
        }
    }

    private fun stopMagnetometer() {
        sensorManager?.unregisterListener(sensorListener)
        sensorManager = null
    }

    private fun updateGpsArrow(lat: Double, lon: Double, bearing: Float, persist: Boolean = true) {
        val style = mapboxMap?.style ?: return
        val geoJson = JSONObject()
            .put("type", "Feature")
            .put("geometry", JSONObject().put("type", "Point")
                .put("coordinates", JSONArray().put(lon).put(lat)))
            .put("properties", JSONObject().put("bearing", bearing))
            .toString()
        style.getSourceAs<GeoJsonSource>(GPS_ARROW_SOURCE_ID)?.setGeoJson(geoJson)
        if (persist) {
            // Persist so arrow can be restored after rotation
            context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)?.edit()
                ?.putFloat(PREF_LAST_LAT, lat.toFloat())
                ?.putFloat(PREF_LAST_LON, lon.toFloat())
                ?.putFloat(PREF_LAST_BEARING, bearing)
                ?.apply()
        }
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
            style.addImage(TRACK_ARROW_ICON, it.toBitmap(48, 48))
        }
        style.addSource(GeoJsonSource(TRACK_SOURCE_ID))
        // Casing (dark outline under the track line)
        style.addLayer(LineLayer("track-casing", TRACK_SOURCE_ID).withProperties(
            PropertyFactory.lineColor("#AA1100"),
            PropertyFactory.lineWidth(9f),
            PropertyFactory.lineCap("round"),
            PropertyFactory.lineJoin("round")
        ))
        // Main track line
        style.addLayer(LineLayer(TRACK_LAYER_ID, TRACK_SOURCE_ID).withProperties(
            PropertyFactory.lineColor("#FF3322"),
            PropertyFactory.lineWidth(6f),
            PropertyFactory.lineCap("round"),
            PropertyFactory.lineJoin("round")
        ))
        // Chevrons — Mapbox places & rotates them along the line automatically
        style.addLayer(SymbolLayer(TRACK_ARROWS_LAYER_ID, TRACK_SOURCE_ID).withProperties(
            PropertyFactory.iconImage(TRACK_ARROW_ICON),
            PropertyFactory.symbolPlacement("line"),
            PropertyFactory.symbolSpacing(3f),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true),
            PropertyFactory.iconRotationAlignment("map"),
            PropertyFactory.iconSize(0.22f)
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
        // Track editor layers (always set up, hidden until editor activated)
        setupTrackEditorLayers(style)
        // Clear stale loaded-track name prefs (track is gone after app restart)
        if (loadedTrackPoints.isEmpty()) {
            context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)?.edit()
                ?.remove(PREF_LOADED_TRACK_NAME)?.remove(PREF_LOADED_WP_NAME)?.apply()
        }
        if (trackPoints.isNotEmpty()) updateTrackOnMap()
        if (loadedTrackPoints.isNotEmpty()) updateLoadedTrackOnMap()
    }

    // ─── Track Editor ──────────────────────────────────────────────────────────

    private fun setupTrackEditorLayers(style: Style) {
        // Line source (mirrors loaded track while editing)
        style.addSource(GeoJsonSource(TRACK_EDIT_LINE_SOURCE))
        style.addLayer(com.mapbox.mapboxsdk.style.layers.LineLayer(TRACK_EDIT_LINE_LAYER, TRACK_EDIT_LINE_SOURCE).withProperties(
            PropertyFactory.lineColor("#FF9800"),
            PropertyFactory.lineWidth(3f),
            PropertyFactory.lineCap("round"),
            PropertyFactory.lineJoin("round"),
            PropertyFactory.lineOpacity(0f)  // invisible until editor activated
        ))
        // Points source
        style.addSource(GeoJsonSource(TRACK_EDIT_POINTS_SOURCE))
        val editPointsLayer = com.mapbox.mapboxsdk.style.layers.CircleLayer(TRACK_EDIT_POINTS_LAYER, TRACK_EDIT_POINTS_SOURCE).withProperties(
            PropertyFactory.circleRadius(
                com.mapbox.mapboxsdk.style.expressions.Expression.match(
                    com.mapbox.mapboxsdk.style.expressions.Expression.get("t"),
                    com.mapbox.mapboxsdk.style.expressions.Expression.literal("sel"), com.mapbox.mapboxsdk.style.expressions.Expression.literal(9f),
                    com.mapbox.mapboxsdk.style.expressions.Expression.literal(5f)
                )
            ),
            PropertyFactory.circleColor(
                com.mapbox.mapboxsdk.style.expressions.Expression.match(
                    com.mapbox.mapboxsdk.style.expressions.Expression.get("t"),
                    com.mapbox.mapboxsdk.style.expressions.Expression.literal("start"), com.mapbox.mapboxsdk.style.expressions.Expression.color(android.graphics.Color.parseColor("#4CAF50")),
                    com.mapbox.mapboxsdk.style.expressions.Expression.literal("end"),   com.mapbox.mapboxsdk.style.expressions.Expression.color(android.graphics.Color.parseColor("#F44336")),
                    com.mapbox.mapboxsdk.style.expressions.Expression.literal("sel"),   com.mapbox.mapboxsdk.style.expressions.Expression.color(android.graphics.Color.parseColor("#2196F3")),
                    com.mapbox.mapboxsdk.style.expressions.Expression.color(android.graphics.Color.parseColor("#AAAAAA"))
                )
            ),
            PropertyFactory.circleStrokeWidth(2f),
            PropertyFactory.circleStrokeColor("#FFFFFF"),
            PropertyFactory.visibility("none")  // hidden until editor activated
        )
        editPointsLayer.minZoom = 13f
        style.addLayer(editPointsLayer)
    }

    private fun enterTrackEditMode() {
        val ctx = context ?: return
        if (loadedTrackPoints.isEmpty()) {
            Toast.makeText(ctx, "Нет загруженного трека", Toast.LENGTH_SHORT).show()
            return
        }
        TrackEditor.load(loadedTrackPoints.map { TrackEditor.TrackPoint(it.latitude, it.longitude) })
        trackEditorMode = true

        // Show editor bar, turn on editor layers
        _binding?.trackEditorBar?.visibility = View.VISIBLE
        updateEditorUi()
        renderEditorPoints()

        // Show the edit line overlay (orange) instead of hidden loaded track
        mapboxMap?.style?.getLayerAs<com.mapbox.mapboxsdk.style.layers.LineLayer>(TRACK_EDIT_LINE_LAYER)
            ?.setProperties(PropertyFactory.lineOpacity(0.9f))
        mapboxMap?.style?.getLayerAs<com.mapbox.mapboxsdk.style.layers.CircleLayer>(TRACK_EDIT_POINTS_LAYER)
            ?.setProperties(PropertyFactory.visibility("visible"))

        setupEditorButtons()

        val name = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREF_LOADED_TRACK_NAME, "Трек") ?: "Трек"
        _binding?.trackEditorTitle?.text = "✏️ $name (${TrackEditor.editPoints.size} точек)"

        Toast.makeText(ctx, "Режим редактирования. Тап на точку чтобы выбрать", Toast.LENGTH_SHORT).show()
    }

    private fun exitTrackEditMode() {
        trackEditorMode = false
        editMoveMode = false
        TrackEditor.selectedIndex = -1
        _binding?.trackEditorBar?.visibility = View.GONE
        _binding?.editPointPopup?.visibility = View.GONE
        _binding?.editMoveBar?.visibility = View.GONE

        // Hide editor layers
        mapboxMap?.style?.getLayerAs<com.mapbox.mapboxsdk.style.layers.LineLayer>(TRACK_EDIT_LINE_LAYER)
            ?.setProperties(PropertyFactory.lineOpacity(0f))
        mapboxMap?.style?.getLayerAs<com.mapbox.mapboxsdk.style.layers.CircleLayer>(TRACK_EDIT_POINTS_LAYER)
            ?.setProperties(PropertyFactory.visibility("none"))
        // Clear editor sources
        mapboxMap?.style?.getSourceAs<GeoJsonSource>(TRACK_EDIT_LINE_SOURCE)
            ?.setGeoJson("{\"type\":\"FeatureCollection\",\"features\":[]}")
        mapboxMap?.style?.getSourceAs<GeoJsonSource>(TRACK_EDIT_POINTS_SOURCE)
            ?.setGeoJson("{\"type\":\"FeatureCollection\",\"features\":[]}")
    }

    private fun setupEditorButtons() {
        val b = _binding ?: return
        val ctx = context ?: return

        b.btnEditorSave.setOnClickListener { showEditorSaveDialog() }
        b.btnEditorExit.setOnClickListener {
            android.app.AlertDialog.Builder(ctx)
                .setTitle("Выйти из редактора?")
                .setMessage("Несохранённые изменения будут потеряны")
                .setPositiveButton("Выйти") { _, _ -> exitTrackEditMode() }
                .setNegativeButton("Отмена", null)
                .show()
        }
        b.btnEditorTrim.setOnClickListener { showTrimDialog(ctx) }
        b.btnEditorSimplify.setOnClickListener { showSimplifyDialog(ctx) }
        b.btnEditorUndo.setOnClickListener {
            if (TrackEditor.undo()) {
                renderEditorPoints()
                updateEditorUi()
                Toast.makeText(ctx, "Отменено", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(ctx, "Нечего отменять", Toast.LENGTH_SHORT).show()
            }
        }
        b.btnEditorReverse.setOnClickListener {
            android.app.AlertDialog.Builder(ctx)
                .setTitle("Развернуть трек?")
                .setMessage("Направление трека будет изменено на обратное")
                .setPositiveButton("Развернуть") { _, _ ->
                    TrackEditor.reverse()
                    renderEditorPoints()
                    updateEditorUi()
                }
                .setNegativeButton("Отмена", null)
                .show()
        }
        b.btnEditorMove.setOnClickListener { enterMoveMode() }
        b.btnEditorDeletePoint.setOnClickListener {
            val idx = TrackEditor.selectedIndex
            if (idx < 0) return@setOnClickListener
            TrackEditor.deletePoint(idx)
            _binding?.editPointPopup?.visibility = View.GONE
            renderEditorPoints()
            updateEditorUi()
        }
        b.btnEditorDeselectPoint.setOnClickListener {
            TrackEditor.selectedIndex = -1
            _binding?.editPointPopup?.visibility = View.GONE
            renderEditorPoints()
        }
        b.btnEditorMoveConfirm.setOnClickListener { confirmMovePoint() }
        b.btnEditorMoveCancel.setOnClickListener { cancelMoveMode() }
    }

    private fun updateEditorUi() {
        val b = _binding ?: return
        val n = TrackEditor.editPoints.size
        val km = "%.1f".format(TrackEditor.totalDistanceM() / 1000)
        val ctx = context ?: return
        val name = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREF_LOADED_TRACK_NAME, "Трек") ?: "Трек"
        b.trackEditorTitle.text = "✏️ $name · $n точек · $km км"
        b.btnEditorUndo.alpha = if (TrackEditor.canUndo()) 1f else 0.4f
        b.btnEditorTrim.alpha = if (TrackEditor.selectedIndex >= 0) 1f else 0.5f
    }

    private fun renderEditorPoints() {
        val style = mapboxMap?.style ?: return
        val pts = TrackEditor.editPoints.toList()  // snapshot for background thread
        if (pts.isEmpty()) return
        val sel = TrackEditor.selectedIndex

        renderEditorJob?.cancel()
        renderEditorJob = viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Default) {
            // Build JSON off the UI thread
            val coords = JSONArray()
            pts.forEach { coords.put(JSONArray().put(it.lon).put(it.lat)) }
            val lineJson = JSONObject().put("type", "Feature")
                .put("geometry", JSONObject().put("type", "LineString").put("coordinates", coords))
                .put("properties", JSONObject()).toString()

            val features = JSONArray()
            pts.forEachIndexed { idx, pt ->
                val t = when {
                    idx == sel -> "sel"
                    idx == 0 -> "start"
                    idx == pts.size - 1 -> "end"
                    else -> "n"
                }
                features.put(
                    JSONObject()
                        .put("type", "Feature")
                        .put("geometry", JSONObject().put("type", "Point")
                            .put("coordinates", JSONArray().put(pt.lon).put(pt.lat)))
                        .put("properties", JSONObject().put("idx", idx).put("t", t))
                )
            }
            val pointsJson = JSONObject().put("type", "FeatureCollection").put("features", features).toString()

            withContext(Dispatchers.Main) {
                val s = mapboxMap?.style ?: return@withContext
                s.getSourceAs<GeoJsonSource>(TRACK_EDIT_LINE_SOURCE)?.setGeoJson(lineJson)
                s.getSourceAs<GeoJsonSource>(TRACK_EDIT_POINTS_SOURCE)?.setGeoJson(pointsJson)
            }
        }
    }

    private fun findNearestEditPointOnScreen(x: Float, y: Float, thresholdPx: Float): Int {
        if (TrackEditor.editPoints.isEmpty()) return -1
        var minDist = Float.MAX_VALUE
        var minIdx = -1
        TrackEditor.editPoints.forEachIndexed { idx, pt ->
            val screenPt = mapboxMap?.projection?.toScreenLocation(
                com.mapbox.mapboxsdk.geometry.LatLng(pt.lat, pt.lon)) ?: return@forEachIndexed
            val dx = screenPt.x - x; val dy = screenPt.y - y
            val dist = kotlin.math.sqrt(dx * dx + dy * dy)
            if (dist < minDist) { minDist = dist; minIdx = idx }
        }
        return if (minDist <= thresholdPx) minIdx else -1
    }

    private fun handleEditorTap(map: MapboxMap, latLng: com.mapbox.mapboxsdk.geometry.LatLng) {
        val density = resources.displayMetrics.density
        val pixel = map.projection.toScreenLocation(latLng)
        val r = 30 * density
        val rect = android.graphics.RectF(pixel.x - r, pixel.y - r, pixel.x + r, pixel.y + r)
        val hits = map.queryRenderedFeatures(rect, TRACK_EDIT_POINTS_LAYER)
        if (hits.isNotEmpty()) {
            val idx = hits[0].getNumberProperty("idx")?.toInt() ?: return
            TrackEditor.selectedIndex = idx
            _binding?.editPointPopup?.visibility = View.VISIBLE
        } else {
            TrackEditor.selectedIndex = -1
            _binding?.editPointPopup?.visibility = View.GONE
        }
        renderEditorPoints()
        updateEditorUi()
    }

    private fun showTrimDialog(ctx: android.content.Context) {
        val idx = TrackEditor.selectedIndex
        if (idx < 0) {
            Toast.makeText(ctx, "Сначала выберите точку на треке", Toast.LENGTH_SHORT).show()
            return
        }
        val n = TrackEditor.editPoints.size
        android.app.AlertDialog.Builder(ctx)
            .setTitle("✂ Обрезать трек")
            .setMessage("Точка ${idx + 1} из $n")
            .setPositiveButton("Удалить начало (0..$idx)") { _, _ ->
                if (idx > 0) {
                    TrackEditor.trimFromStart(idx)
                    _binding?.editPointPopup?.visibility = View.GONE
                    renderEditorPoints()
                    updateEditorUi()
                }
            }
            .setNeutralButton("Удалить конец ($idx..${n - 1})") { _, _ ->
                if (idx < n - 1) {
                    TrackEditor.trimFromEnd(idx)
                    _binding?.editPointPopup?.visibility = View.GONE
                    renderEditorPoints()
                    updateEditorUi()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun showSimplifyDialog(ctx: android.content.Context) {
        val container = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val p = (16 * resources.displayMetrics.density).toInt()
            setPadding(p, p / 2, p, 0)
        }
        val toleranceValues = intArrayOf(2, 5, 10, 20, 50)
        var toleranceIdx = 1  // default 5m
        val infoText = android.widget.TextView(ctx).apply {
            textSize = 13f
            setTextColor(android.graphics.Color.parseColor("#CCCCCC"))
        }
        fun updateInfo(tIdx: Int) {
            val tol = toleranceValues[tIdx].toDouble()
            val preview = TrackEditor.simplifyPreview(tol)
            val removed = TrackEditor.editPoints.size - preview
            infoText.text = "Осталось: $preview точек (убрать $removed)"
        }
        updateInfo(toleranceIdx)

        val labels = toleranceValues.map { "${it}м" }.toTypedArray()
        val seekBar = android.widget.SeekBar(ctx).apply {
            max = toleranceValues.size - 1
            progress = toleranceIdx
            setPadding(0, 8, 0, 8)
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
                    toleranceIdx = p; updateInfo(p)
                }
                override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
            })
        }
        val toleranceLabel = android.widget.TextView(ctx).apply {
            text = "Допуск: ${labels.joinToString(" | ")}"
            textSize = 11f
            setTextColor(android.graphics.Color.parseColor("#888888"))
        }
        container.addView(infoText)
        container.addView(seekBar)
        container.addView(toleranceLabel)

        android.app.AlertDialog.Builder(ctx)
            .setTitle("⎘ Упростить трек")
            .setView(container)
            .setPositiveButton("Применить") { _, _ ->
                val removed = TrackEditor.simplify(toleranceValues[toleranceIdx].toDouble())
                renderEditorPoints()
                updateEditorUi()
                Toast.makeText(ctx, "Убрано $removed точек", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun enterMoveMode() {
        val idx = TrackEditor.selectedIndex
        if (idx < 0) return
        editMoveMode = true
        _binding?.editPointPopup?.visibility = View.GONE
        _binding?.editMoveBar?.visibility = View.VISIBLE
        // Show crosshair if not visible
        _binding?.crosshairView?.visibility = View.VISIBLE
    }

    private fun confirmMovePoint() {
        val map = mapboxMap ?: return
        val center = map.cameraPosition.target ?: return
        val idx = TrackEditor.selectedIndex
        if (idx >= 0) {
            TrackEditor.movePoint(idx, center.latitude, center.longitude)
            renderEditorPoints()
            updateEditorUi()
        }
        cancelMoveMode()
    }

    private fun cancelMoveMode() {
        editMoveMode = false
        _binding?.editMoveBar?.visibility = View.GONE
        if (TrackEditor.selectedIndex >= 0) {
            _binding?.editPointPopup?.visibility = View.VISIBLE
        }
        // Restore crosshair to its proper state (may have been off before move mode)
        applyFollowMode()
    }

    private fun showEditorSaveDialog() {
        val ctx = context ?: return
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val origName = prefs.getString(PREF_LOADED_TRACK_NAME, "track") ?: "track"
        // Protect today's "current_YYYYMMDD_*" tracks from overwrite
        val todayPrefix = "current_" + java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.getDefault()).format(java.util.Date())
        val isTodayTrack = origName.startsWith(todayPrefix) || origName.startsWith("current_" + java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.getDefault()).format(java.util.Date()))
        if (isTodayTrack) {
            android.app.AlertDialog.Builder(ctx)
                .setTitle("⛔ Трек защищён")
                .setMessage("Трек сегодняшнего дня «$origName» нельзя изменить.\nСохранить как новый файл?")
                .setPositiveButton("Сохранить копию") { _, _ -> saveEditorAsNewFile(ctx, origName) }
                .setNegativeButton("Отмена", null)
                .show()
            return
        }
        android.app.AlertDialog.Builder(ctx)
            .setTitle("Сохранить трек")
            .setItems(arrayOf("Заменить «$origName»", "Сохранить как новый файл")) { _, which ->
                val pts = TrackEditor.editPoints.map { Pair(it.lat, it.lon) }
                when (which) {
                    0 -> {
                        // Replace in-memory loaded track and exit editor
                        loadedTrackPoints.clear()
                        loadedTrackPoints.addAll(pts.map { com.mapbox.mapboxsdk.geometry.LatLng(it.first, it.second) })
                        updateLoadedTrackOnMap()
                        saveTrackToPrefs()
                        exitTrackEditMode()
                        Toast.makeText(ctx, "Трек обновлён (${pts.size} точек)", Toast.LENGTH_SHORT).show()
                    }
                    1 -> {
                        val gpx = GpxParser.writeGpx(pts, "${origName}_edited")
                        val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault())
                            .format(java.util.Date())
                        val filename = "track_edited_$timestamp.gpx"
                        try {
                            val dir = getRaceNavDir(ctx, "tracks")
                            val file = java.io.File(dir, filename)
                            file.writeText(gpx)
                            exitTrackEditMode()
                            Toast.makeText(ctx, "Сохранено: $filename", Toast.LENGTH_LONG).show()
                        } catch (e: Exception) {
                            Toast.makeText(ctx, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun saveEditorAsNewFile(ctx: android.content.Context, baseName: String) {
        val pts = TrackEditor.editPoints.map { Pair(it.lat, it.lon) }
        val gpx = GpxParser.writeGpx(pts, "${baseName}_edited")
        val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault())
            .format(java.util.Date())
        val filename = "track_edited_$timestamp.gpx"
        try {
            val file = java.io.File(getRaceNavDir(ctx, "tracks"), filename)
            file.writeText(gpx)
            exitTrackEditMode()
            Toast.makeText(ctx, "Сохранено: $filename", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(ctx, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ─── Draw Mode (Режим рисования нового трека) ──────────────────────────────

    private fun showTrackEditorModeDialog(ctx: android.content.Context) {
        val hasTrack = loadedTrackPoints.isNotEmpty()
        val editLabel = if (hasTrack) "✏️ Редактировать загруженный трек" else "✏️ Редактировать трек (нет загруженного)"
        android.app.AlertDialog.Builder(ctx)
            .setTitle("Редактор треков")
            .setItems(arrayOf(editLabel, "🖊 Нарисовать новый трек с нуля")) { _, which ->
                when (which) {
                    0 -> if (hasTrack) enterTrackEditMode()
                         else Toast.makeText(ctx, "Сначала загрузите GPX-трек", Toast.LENGTH_SHORT).show()
                    1 -> enterDrawMode()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun enterDrawMode() {
        val ctx = context ?: return
        if (trackEditorMode) exitTrackEditMode()
        drawMode = true
        drawnPoints.clear()
        _binding?.drawModeBar?.visibility = View.VISIBLE
        // Force FREE mode so crosshair is visible
        followMode = FollowMode.FREE
        _binding?.crosshairView?.visibility = View.VISIBLE
        // Show editor layers
        mapboxMap?.style?.getLayerAs<com.mapbox.mapboxsdk.style.layers.LineLayer>(TRACK_EDIT_LINE_LAYER)
            ?.setProperties(PropertyFactory.lineOpacity(0.9f))
        mapboxMap?.style?.getLayerAs<com.mapbox.mapboxsdk.style.layers.CircleLayer>(TRACK_EDIT_POINTS_LAYER)
            ?.setProperties(PropertyFactory.visibility("visible"))
        updateDrawStats()
        setupDrawModeButtons()
        Toast.makeText(ctx, "Режим рисования. Наведите крестик и нажмите ＋", Toast.LENGTH_SHORT).show()
    }

    private fun exitDrawMode() {
        drawMode = false
        drawnPoints.clear()
        _binding?.drawModeBar?.visibility = View.GONE
        mapboxMap?.style?.getLayerAs<com.mapbox.mapboxsdk.style.layers.LineLayer>(TRACK_EDIT_LINE_LAYER)
            ?.setProperties(PropertyFactory.lineOpacity(0f))
        mapboxMap?.style?.getLayerAs<com.mapbox.mapboxsdk.style.layers.CircleLayer>(TRACK_EDIT_POINTS_LAYER)
            ?.setProperties(PropertyFactory.visibility("none"))
        mapboxMap?.style?.getSourceAs<GeoJsonSource>(TRACK_EDIT_LINE_SOURCE)
            ?.setGeoJson("{\"type\":\"FeatureCollection\",\"features\":[]}")
        mapboxMap?.style?.getSourceAs<GeoJsonSource>(TRACK_EDIT_POINTS_SOURCE)
            ?.setGeoJson("{\"type\":\"FeatureCollection\",\"features\":[]}")
        applyFollowMode()
    }

    private fun setupDrawModeButtons() {
        val b = _binding ?: return
        val ctx = context ?: return
        b.btnDrawAddPoint.setOnClickListener { addDrawPoint() }
        b.btnDrawUndo.setOnClickListener { undoDrawPoint() }
        b.btnDrawSave.setOnClickListener {
            if (drawnPoints.size < 2) Toast.makeText(ctx, "Нужно минимум 2 точки", Toast.LENGTH_SHORT).show()
            else saveDrawnTrack()
        }
        b.btnDrawExit.setOnClickListener {
            if (drawnPoints.isEmpty()) {
                exitDrawMode()
            } else {
                android.app.AlertDialog.Builder(ctx)
                    .setTitle("Выйти из режима рисования?")
                    .setMessage("Нарисованный трек будет потерян")
                    .setPositiveButton("Выйти") { _, _ -> exitDrawMode() }
                    .setNegativeButton("Отмена", null)
                    .show()
            }
        }
        // Color picker button — round circle showing current track color
        val colorView = _binding?.btnDrawColor
        fun applyCircleColor(hex: String) {
            colorView?.background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(android.graphics.Color.parseColor(hex))
                setStroke((2 * (ctx.resources.displayMetrics.density)).toInt(), android.graphics.Color.WHITE)
            }
        }
        val initColor = ctx.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
            .getString(PREF_LOADED_TRACK_COLOR, DEFAULT_TRACK_COLOR) ?: DEFAULT_TRACK_COLOR
        applyCircleColor(initColor)
        colorView?.setOnClickListener {
            showDrawTrackColorPicker(ctx) { hex -> applyCircleColor(hex) }
        }
    }

    private fun addDrawPoint() {
        val center = mapboxMap?.cameraPosition?.target ?: return
        drawnPoints.add(TrackEditor.TrackPoint(center.latitude, center.longitude))
        renderDrawnLine()
        updateDrawStats()
    }

    private fun undoDrawPoint() {
        if (drawnPoints.isEmpty()) return
        drawnPoints.removeAt(drawnPoints.size - 1)
        renderDrawnLine()
        updateDrawStats()
    }

    private fun updateDrawStats() {
        val n = drawnPoints.size
        var distKm = 0.0
        if (n >= 2) {
            for (i in 1 until n) {
                val a = drawnPoints[i - 1]; val b = drawnPoints[i]
                val dLat = Math.toRadians(b.lat - a.lat)
                val dLon = Math.toRadians(b.lon - a.lon)
                val sLat = kotlin.math.sin(dLat / 2); val sLon = kotlin.math.sin(dLon / 2)
                val h = sLat * sLat + kotlin.math.cos(Math.toRadians(a.lat)) *
                        kotlin.math.cos(Math.toRadians(b.lat)) * sLon * sLon
                distKm += 6371.0 * 2 * kotlin.math.asin(kotlin.math.sqrt(h))
            }
        }
        val distText = if (distKm >= 1.0) "${"%.1f".format(distKm)} км" else "${(distKm * 1000).toInt()} м"
        _binding?.drawModeStats?.text = "🖊 Новый трек · $n точек${if (n >= 2) " · $distText" else ""}"
    }

    private fun renderDrawnLine() {
        val style = mapboxMap?.style ?: return
        val snapshot = drawnPoints.toList()  // snapshot on Main thread — no race condition
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Default) {
            val lineJson = if (snapshot.size >= 2) {
                val coords = JSONArray()
                snapshot.forEach { coords.put(JSONArray().put(it.lon).put(it.lat)) }
                JSONObject().put("type", "FeatureCollection").put("features", JSONArray().put(
                    JSONObject().put("type", "Feature").put("properties", JSONObject())
                        .put("geometry", JSONObject().put("type", "LineString").put("coordinates", coords))
                )).toString()
            } else "{\"type\":\"FeatureCollection\",\"features\":[]}"

            val ptsJson = if (snapshot.isNotEmpty()) {
                val features = JSONArray()
                snapshot.forEachIndexed { idx, pt ->
                    val role = when { idx == 0 -> "start"; idx == snapshot.size - 1 -> "end"; else -> "normal" }
                    features.put(JSONObject().put("type", "Feature")
                        .put("properties", JSONObject().put("index", idx).put("role", role))
                        .put("geometry", JSONObject().put("type", "Point")
                            .put("coordinates", JSONArray().put(pt.lon).put(pt.lat))))
                }
                JSONObject().put("type", "FeatureCollection").put("features", features).toString()
            } else "{\"type\":\"FeatureCollection\",\"features\":[]}"

            withContext(Dispatchers.Main) {
                style.getSourceAs<GeoJsonSource>(TRACK_EDIT_LINE_SOURCE)?.setGeoJson(lineJson)
                style.getSourceAs<GeoJsonSource>(TRACK_EDIT_POINTS_SOURCE)?.setGeoJson(ptsJson)
            }
        }
    }

    private fun saveDrawnTrack() {
        val ctx = context ?: return
        val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault()).format(java.util.Date())
        val input = android.widget.EditText(ctx).apply { setText("drawn_$ts"); selectAll() }
        android.app.AlertDialog.Builder(ctx)
            .setTitle("Сохранить трек")
            .setMessage("Введите название файла:")
            .setView(input)
            .setPositiveButton("Сохранить") { _, _ ->
                val name = input.text.toString().trim().ifBlank { "drawn_$ts" }
                val pts = drawnPoints.map { Pair(it.lat, it.lon) }
                val gpx = GpxParser.writeGpx(pts, name)
                try {
                    val file = java.io.File(getRaceNavDir(ctx, "tracks"), "$name.gpx")
                    file.writeText(gpx)
                    loadTrack(pts)
                    context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        ?.edit()?.putString(PREF_LOADED_TRACK_NAME, name)?.apply()
                    exitDrawMode()
                    Toast.makeText(ctx, "Сохранено: $name.gpx (${pts.size} точек)", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Toast.makeText(ctx, "Ошибка сохранения: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun showDrawTrackColorPicker(ctx: android.content.Context, onPicked: (String) -> Unit) {
        val colors = listOf("#FF6F00","#FFFF00","#FFFFFF","#00FF00","#FF4444","#00BFFF",
            "#FF00FF","#1565C0","#00E676","#FF8A80","#B388FF","#CCCCCC")
        val currentColor = ctx.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
            .getString(PREF_LOADED_TRACK_COLOR, DEFAULT_TRACK_COLOR) ?: DEFAULT_TRACK_COLOR
        val dp = ctx.resources.displayMetrics.density
        val swatchSize = (48 * dp).toInt()
        val gap = (8 * dp).toInt()
        val pad = (16 * dp).toInt()
        val grid = android.widget.GridLayout(ctx).apply {
            columnCount = 4
            setPadding(pad, pad, pad, pad)
        }
        val dialog = android.app.AlertDialog.Builder(ctx, androidx.appcompat.R.style.Theme_AppCompat_Dialog)
            .setTitle("Цвет трека")
            .setView(grid)
            .setNegativeButton("Отмена", null)
            .create()
        colors.forEach { hex ->
            val swatch = android.view.View(ctx).apply {
                layoutParams = android.widget.GridLayout.LayoutParams().apply {
                    width = swatchSize; height = swatchSize; setMargins(gap, gap, gap, gap)
                }
                setBackgroundColor(android.graphics.Color.parseColor(hex))
                alpha = if (hex == currentColor) 1f else 0.65f
                if (hex == currentColor) {
                    foreground = android.graphics.drawable.GradientDrawable().apply {
                        setStroke((2 * dp).toInt(), android.graphics.Color.WHITE)
                        cornerRadius = 4 * dp
                    }
                }
                setOnClickListener {
                    ctx.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
                        .edit().putString(PREF_LOADED_TRACK_COLOR, hex).apply()
                    mapboxMap?.style?.getLayerAs<com.mapbox.mapboxsdk.style.layers.LineLayer>(TRACK_EDIT_LINE_LAYER)
                        ?.setProperties(com.mapbox.mapboxsdk.style.layers.PropertyFactory.lineColor(hex))
                    onPicked(hex)
                    dialog.dismiss()
                }
            }
            grid.addView(swatch)
        }
        dialog.show()
    }

    // ─── End Track Editor ──────────────────────────────────────────────────────

    private fun setupWaypointLayers(style: Style) {
        // Restore persisted data on style (re)load
        if (waypoints.isEmpty()) restoreWaypointsFromPrefs()
        val trackWasEmpty = loadedTrackPoints.isEmpty()
        if (trackWasEmpty) restoreTrackFromPrefs()

        // Offer to resume navigation if it was active when app was killed
        if (pendingNavResumeCheck && waypoints.isNotEmpty()) {
            pendingNavResumeCheck = false
            val ctx = context
            if (ctx != null) {
                val wpName = waypoints.getOrNull(activeWpIndex)?.name
                    ?.takeIf { it.isNotBlank() } ?: "WP${activeWpIndex + 1}"
                androidx.appcompat.app.AlertDialog.Builder(ctx)
                    .setTitle("▶ Продолжить навигацию?")
                    .setMessage("Маршрут: ${waypoints.size} точек\nПоследняя цель: $wpName\n\nПриложение было закрыто во время навигации.")
                    .setPositiveButton("▶ Продолжить") { _, _ -> startNavigation() }
                    .setNegativeButton("Нет", null)
                    .show()
            }
        }

        // Route polyline: connects all waypoints in order
        val routeLinePrefs = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val routeLineColor = routeLinePrefs?.getString(PREF_ROUTE_LINE_COLOR, "#B388FF") ?: "#FF6F00"
        val routeLineWidth = routeLinePrefs?.getInt(PREF_ROUTE_LINE_WIDTH, 2)?.toFloat() ?: 2f
        style.addSource(GeoJsonSource(ROUTE_LINE_SOURCE_ID))
        val routeLineVis = if (routeLinePrefs?.getBoolean(PREF_ROUTE_LINE_VISIBLE, true) != false) "visible" else "none"
        style.addLayer(LineLayer(ROUTE_LINE_LAYER_ID, ROUTE_LINE_SOURCE_ID).withProperties(
            PropertyFactory.lineColor(routeLineColor),
            PropertyFactory.lineWidth(routeLineWidth),
            PropertyFactory.lineOpacity(0.8f),
            PropertyFactory.lineDasharray(arrayOf(6f, 4f)),
            PropertyFactory.visibility(routeLineVis)
        ))

        // Approach radius circles (behind waypoint dots)
        // Approach radius (outer circle — blue, warning/approach)
        style.addSource(GeoJsonSource(WP_RADIUS_SOURCE_ID))
        style.addLayer(FillLayer(WP_RADIUS_LAYER_ID, WP_RADIUS_SOURCE_ID).withProperties(
            PropertyFactory.fillColor("#00BFFF"),
            PropertyFactory.fillOpacity(0.10f)
        ))
        style.addLayer(LineLayer(WP_RADIUS_OUTLINE_LAYER_ID, WP_RADIUS_SOURCE_ID).withProperties(
            PropertyFactory.lineColor("#00BFFF"),
            PropertyFactory.lineWidth(2.5f),
            PropertyFactory.lineOpacity(0.8f)
        ))
        // Taken radius (inner circle — orange, auto-advance)
        style.addSource(GeoJsonSource(WP_PROXIMITY_SOURCE_ID))
        style.addLayer(FillLayer(WP_PROXIMITY_LAYER_ID, WP_PROXIMITY_SOURCE_ID).withProperties(
            PropertyFactory.fillColor("#FF6F00"),
            PropertyFactory.fillOpacity(0.15f)
        ))
        style.addLayer(LineLayer(WP_PROXIMITY_OUTLINE_LAYER_ID, WP_PROXIMITY_SOURCE_ID).withProperties(
            PropertyFactory.lineColor("#FF6F00"),
            PropertyFactory.lineWidth(2.5f),
            PropertyFactory.lineOpacity(0.9f)
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
            PropertyFactory.iconAnchor("center"),
            *wpIconSizeProps()
        ))
        // User markers layer
        style.addSource(GeoJsonSource(USER_MARKER_SOURCE_ID))
        style.addLayer(SymbolLayer(USER_MARKER_LAYER_ID, USER_MARKER_SOURCE_ID).withProperties(
            PropertyFactory.iconImage(com.mapbox.mapboxsdk.style.expressions.Expression.get("icon")),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true),
            PropertyFactory.iconAnchor("center"),
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

        // Total bitmap: [padLeft] [circle] [gap] [label] — padLeft = labelBoxW for centering
        val labelBoxW = if (labelWidth > 0) gap + labelWidth + labelPadH * 2 else 0f
        val circleBlockW = circleDiam.toFloat()
        val totalW = (labelBoxW + circleBlockW + labelBoxW).toInt()
        val totalH = maxOf(circleDiam, labelH.toInt() + 4)
        val bmp = android.graphics.Bitmap.createBitmap(maxOf(totalW, 1), maxOf(totalH, 1), android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        val cx = labelBoxW + circleR  // center of circle = center of bitmap
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
        if (!navActive || gps == null || waypoints.isEmpty() || activeWpIndex >= waypoints.size) {
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
        val prefs = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val globalApproachM = prefs?.getInt(PREF_WP_APPROACH_RADIUS, DEFAULT_WP_APPROACH_RADIUS)?.toDouble()
            ?: DEFAULT_WP_APPROACH_RADIUS.toDouble()
        val globalTakenM = prefs?.getInt(PREF_WP_TAKEN_RADIUS, DEFAULT_WP_TAKEN_RADIUS)?.toDouble()
            ?: DEFAULT_WP_TAKEN_RADIUS.toDouble()
        // Approach radius circles (outer, blue) — approach/warning
        val approachFeatures = JSONArray()
        waypoints.forEach { wp ->
            val r = if (wp.proximity > 0) wp.proximity else globalApproachM
            approachFeatures.put(buildCirclePolygon(wp.lat, wp.lon, r))
        }
        approachSource.setGeoJson(JSONObject().put("type", "FeatureCollection").put("features", approachFeatures).toString())
        // Taken radius circles (inner, orange) — ONLY when navigation active
        val takenFeatures = JSONArray()
        if (navActive) {
            waypoints.forEach { wp ->
                takenFeatures.put(buildCirclePolygon(wp.lat, wp.lon, globalTakenM))
            }
        }
        proximitySource?.setGeoJson(JSONObject().put("type", "FeatureCollection").put("features", takenFeatures).toString())
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
        updateUserMarkersOnMap()
    }

    private fun updateNextCpWidget() {
        val b = _binding ?: return
        if (waypoints.isEmpty() || activeWpIndex >= waypoints.size) {
            b.widgetNextCp.text = "--"
            return
        }
        // Distance will be updated on next GPS fix
    }

    private fun dpToPx(dp: Int): Int {
        val density = context?.resources?.displayMetrics?.density ?: 3f
        return (dp * density).toInt()
    }

    private fun applyNavCompassPrefs() {
        val b = _binding ?: return
        val compass = b.navCompass
        val ctx = context ?: return
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean(PREF_NAV_COMPASS_ENABLED, false)
        compass.visibility = if (enabled) View.VISIBLE else View.GONE
        if (!enabled) return

        // Size
        val sizeVal = try { prefs.getInt(PREF_NAV_COMPASS_SIZE, 100) } catch (_: Exception) {
            prefs.edit().remove(PREF_NAV_COMPASS_SIZE).apply(); 100
        }
        val sizeDp = dpToPx(sizeVal)
        compass.layoutParams = compass.layoutParams.apply {
            width = sizeDp
            height = sizeDp
        }

        // Alpha
        val alpha = prefs.getInt(PREF_NAV_COMPASS_ALPHA, 7)
        compass.compassAlpha = alpha / 10f

        // Position
        val position = prefs.getString(PREF_NAV_COMPASS_POSITION, "top-right") ?: "top-right"
        val cs = ConstraintSet()
        cs.clone(b.root as ConstraintLayout)
        val id = R.id.navCompass
        cs.clear(id, ConstraintSet.TOP)
        cs.clear(id, ConstraintSet.BOTTOM)
        cs.clear(id, ConstraintSet.START)
        cs.clear(id, ConstraintSet.END)
        val m8 = dpToPx(8); val m12 = dpToPx(12)
        when (position) {
            "top-left" -> {
                cs.connect(id, ConstraintSet.TOP, R.id.topBar, ConstraintSet.BOTTOM, m8)
                cs.connect(id, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START, m12)
            }
            "top-right" -> {
                cs.connect(id, ConstraintSet.TOP, R.id.topBar, ConstraintSet.BOTTOM, m8)
                cs.connect(id, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END, m12)
            }
            "bottom-left" -> {
                cs.connect(id, ConstraintSet.BOTTOM, R.id.bottomBar, ConstraintSet.TOP, m8)
                cs.connect(id, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START, m12)
            }
            "bottom-right" -> {
                cs.connect(id, ConstraintSet.BOTTOM, R.id.bottomBar, ConstraintSet.TOP, m8)
                cs.connect(id, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END, m12)
            }
        }
        cs.applyTo(b.root as ConstraintLayout)
    }

    fun refreshNavCompass() {
        applyNavCompassPrefs()
    }

    private fun updateNavCompass() {
        val compass = _binding?.navCompass ?: return
        if (compass.visibility != View.VISIBLE) return
        val cameraBearing = mapboxMap?.cameraPosition?.bearing?.toFloat() ?: lastGpsBearing
        // No GPS fix yet
        if (lastGpsLat == 0.0 && lastGpsLon == 0.0) {
            compass.isNavActive = false
            compass.mapBearing = cameraBearing
            return
        }
        // No waypoints or nav not active — show as regular compass (arrow = north)
        if (waypoints.isEmpty() || activeWpIndex >= waypoints.size || !navActive) {
            compass.isNavActive = false
            compass.relativeBearing = 0f  // arrow points to top = north direction on ring
            compass.mapBearing = cameraBearing
            compass.distanceText = ""
            return
        }
        compass.isNavActive = true
        val wp = waypoints[activeWpIndex]
        val bearingToWp = bearingToPoint(lastGpsLat, lastGpsLon, wp.lat, wp.lon)
        // Arrow points relative to screen-up (camera bearing), not GPS course
        val relative = ((bearingToWp - cameraBearing) % 360 + 360) % 360
        compass.relativeBearing = relative
        compass.mapBearing = cameraBearing
        val distM = distanceM(LatLng(lastGpsLat, lastGpsLon), LatLng(wp.lat, wp.lon))
        compass.distanceText = if (distM < 1000) "${distM.toInt()} м" else String.format("%.1f км", distM / 1000)
    }

    private fun bearingToPoint(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val dLon = Math.toRadians(lon2 - lon1)
        val la1 = Math.toRadians(lat1); val la2 = Math.toRadians(lat2)
        val y = Math.sin(dLon) * Math.cos(la2)
        val x = Math.cos(la1) * Math.sin(la2) - Math.sin(la1) * Math.cos(la2) * Math.cos(dLon)
        return ((Math.toDegrees(Math.atan2(y, x)) + 360) % 360).toFloat()
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
        // Emergency settings button — visible when all panels are hidden (long press on map)
        // Allows user to reach settings even if settings button was pushed off-screen
        binding.btnEmergencySettings.setOnClickListener {
            // Restore bars before opening settings
            _binding?.topBar?.visibility = android.view.View.VISIBLE
            _binding?.bottomBar?.visibility = android.view.View.VISIBLE
            _binding?.btnEmergencySettings?.visibility = android.view.View.GONE
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

        // Nav always starts inactive — offer resume dialog after waypoints are loaded
        val prevNavActive = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.getBoolean(PREF_NAV_ACTIVE, false) ?: false
        navActive = false
        context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.edit()?.putBoolean(PREF_NAV_ACTIVE, false)?.apply()
        if (prevNavActive) pendingNavResumeCheck = true
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
            flyToGps()
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
        val b = _binding ?: return
        b.lockOverlay.visibility = View.VISIBLE  // Always show overlay (blocks touches)
        // Always show small yellow lock icon in top-right corner
        val lockIcon = b.lockOverlay.getChildAt(0) as? android.widget.ImageView
        lockIcon?.visibility = View.VISIBLE
        ImageViewCompat.setImageTintList(
            b.btnLock,
            android.content.res.ColorStateList.valueOf(Color.parseColor("#FFD600"))
        )
        // Flash yellow border to confirm lock activation
        flashScreenBorder(Color.parseColor("#FFD600"))
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
        style?.getLayer(WP_RADIUS_LAYER_ID)?.setProperties(PropertyFactory.visibility(vis))
        style?.getLayer(WP_RADIUS_OUTLINE_LAYER_ID)?.setProperties(PropertyFactory.visibility(vis))
        style?.getLayer(WP_PROXIMITY_LAYER_ID)?.setProperties(PropertyFactory.visibility(vis))
        style?.getLayer(WP_PROXIMITY_OUTLINE_LAYER_ID)?.setProperties(PropertyFactory.visibility(vis))
        style?.getLayer(NAV_LINE_LAYER_ID)?.setProperties(PropertyFactory.visibility(vis))
        context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)?.edit()
            ?.putBoolean(PREF_LOADED_WP_VISIBLE, visible)?.apply()
    }

    fun setRouteLineVisible(visible: Boolean) {
        val vis = if (visible) "visible" else "none"
        mapboxMap?.style?.getLayer(ROUTE_LINE_LAYER_ID)?.setProperties(PropertyFactory.visibility(vis))
        context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)?.edit()
            ?.putBoolean(PREF_ROUTE_LINE_VISIBLE, visible)?.apply()
    }

    fun applyTrackStyle() {
        val prefs = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) ?: return
        val color = prefs.getString(PREF_TRACK_COLOR, DEFAULT_TRACK_COLOR) ?: DEFAULT_TRACK_COLOR
        val width = prefs.getFloat(PREF_TRACK_WIDTH, DEFAULT_TRACK_WIDTH)
        mapboxMap?.style?.getLayerAs<com.mapbox.mapboxsdk.style.layers.LineLayer>(TRACK_LAYER_ID)
            ?.setProperties(PropertyFactory.lineColor(color), PropertyFactory.lineWidth(width))
        // Casing: darker shade, wider than main line
        val casingColor = try {
            val c = Color.parseColor(color)
            val hsv = FloatArray(3); Color.colorToHSV(c, hsv)
            hsv[2] = if (hsv[2] < 0.2f) (hsv[2] + 0.25f).coerceIn(0f, 1f) else (hsv[2] * 0.6f)
            String.format("#%06X", 0xFFFFFF and Color.HSVToColor(hsv))
        } catch (_: Exception) { "#AA1100" }
        mapboxMap?.style?.getLayerAs<com.mapbox.mapboxsdk.style.layers.LineLayer>("track-casing")
            ?.setProperties(PropertyFactory.lineColor(casingColor), PropertyFactory.lineWidth(width + 3f))
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
        val b = _binding ?: return
        b.lockOverlay.visibility = View.GONE
        ImageViewCompat.setImageTintList(
            b.btnLock,
            android.content.res.ColorStateList.valueOf(Color.WHITE)
        )
        // Flash green border to confirm unlock
        flashScreenBorder(Color.parseColor("#4CAF50"))
    }

    private fun flashScreenBorder(color: Int) {
        val b = _binding ?: return
        val border = b.lockFlashBorder
        val gd = (border.background as? android.graphics.drawable.GradientDrawable)
            ?: android.graphics.drawable.GradientDrawable().also {
                it.shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                it.cornerRadius = 4f * (context?.resources?.displayMetrics?.density ?: 3f)
                border.background = it
            }
        gd.setStroke((4 * (context?.resources?.displayMetrics?.density ?: 3f)).toInt(), color)
        gd.setColor(Color.TRANSPARENT)
        border.visibility = View.VISIBLE
        border.alpha = 1f
        border.animate().alpha(0f).setDuration(600).withEndAction {
            border.visibility = View.GONE
        }.start()
    }

    /** Handle Samsung XCover Key programmable button action */
    fun handleXCoverAction(action: String) {
        when (action) {
            "camera_mode" -> {
                // Cycle FREE → FOLLOW_NORTH → FOLLOW_COURSE → FREE
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
                        mapboxMap?.animateCamera(CameraUpdateFactory.bearingTo(0.0))
                        showHint("🧭 Свободный режим карты")
                    }
                }
            }
            "add_point" -> {
                // Add waypoint at current GPS position
                if (lastGpsLat != 0.0 || lastGpsLon != 0.0) {
                    val num = userMarkers.size + 1
                    val wpName = "WP%02d".format(num)
                    userMarkers.add(UserPoint(wpName, LatLng(lastGpsLat, lastGpsLon)))
                    updateUserMarkersOnMap()
                    Toast.makeText(context, "$wpName поставлена (GPS)", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Нет GPS-координат", Toast.LENGTH_SHORT).show()
                }
            }
            "toggle_track" -> toggleRecording()
            "screen_lock" -> if (isScreenLocked) unlockScreen() else lockScreen()
        }
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
            playFinishSound()
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
        updateNavCompass()
        _binding?.navCompass?.flashOnWaypointReached()
        // Send telemetry on waypoint advance
        context?.let { ctx ->
            val extra = org.json.JSONObject().apply {
                put("wp_index", activeWpIndex)
                put("wp_total", waypoints.size)
                put("lat", lastGpsLat)
                put("lon", lastGpsLon)
                put("bearing", lastGpsBearing)
                put("speed_kmh", lastGpsSpeedKmh)
            }
            Analytics.sendEvent(ctx, "wp_advance", extra)
        }
        // Toast removed — nav bar shows current WP, no need to overlap
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
            val tempWp = Waypoint(name = pt.name.ifBlank { "WP%02d".format(i + 1) }, lat = 0.0, lon = 0.0, index = 0, color = pt.color, symbol = pt.symbol)
            val bmp = createWaypointBitmap(tempWp)
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
                .put("lon", p.position.longitude)
                .put("color", p.color)
                .put("symbol", p.symbol)
                .put("proximity", p.proximity))
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
                    o.optString("name", "WP%02d".format(i + 1)),
                    LatLng(o.getDouble("lat"), o.getDouble("lon")),
                    color = o.optString("color", "#1565C0"),
                    symbol = o.optString("symbol", ""),
                    proximity = o.optDouble("proximity", 0.0)
                ))
            }
            if (userMarkers.isNotEmpty()) {
                updateUserMarkersOnMap()
                updateUserMarkerRadiusCircles()
            }
        } catch (_: Exception) {}
    }

    /** Draw proximity circles for user markers */
    private fun updateUserMarkerRadiusCircles() {
        val style = mapboxMap?.style ?: return
        val proximitySource = style.getSourceAs<GeoJsonSource>(WP_PROXIMITY_SOURCE_ID) ?: return
        val features = JSONArray()
        // Add waypoint proximity circles
        waypoints.forEach { wp ->
            if (wp.proximity > 0) features.put(buildCirclePolygon(wp.lat, wp.lon, wp.proximity))
        }
        // Add user marker proximity circles
        userMarkers.forEach { pt ->
            if (pt.proximity > 0) features.put(buildCirclePolygon(pt.position.latitude, pt.position.longitude, pt.proximity))
        }
        proximitySource.setGeoJson(JSONObject().put("type", "FeatureCollection").put("features", features).toString())
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
        val dp = resources.displayMetrics.density
        val pad = (16 * dp).toInt()

        val root = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(android.graphics.Color.parseColor("#1A1A1A"))
        }

        // 4 tab buttons in a row
        val tabRow = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, (12 * dp).toInt())
        }
        val tabH = (44 * dp).toInt()
        val tabLp = android.widget.LinearLayout.LayoutParams(0, tabH, 1f).apply {
            marginEnd = (4 * dp).toInt()
        }

        // Content container — changes when tab is tapped
        val content = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
        }

        // Tab definitions
        data class Tab(val label: String, val color: String, val builder: () -> Unit)

        fun clearContent() { content.removeAllViews() }

        val tabs = listOf(
            Tab("📍 WP",  "#FFD600") { clearContent(); buildWpContent(ctx, prefs, content, dialog, dp) },
            Tab("🗺 RTE", "#FF6F00") { clearContent(); buildRteContent(ctx, prefs, content, dialog, dp) },
            Tab("📏 TRK", "#2196F3") { clearContent(); buildTrkContent(ctx, prefs, content, dialog, dp) },
            Tab("📦 GPX", "#CCCCCC") { clearContent(); buildGpxContent(ctx, prefs, content, dialog, dp) }
        )

        val tabButtons = mutableListOf<android.widget.Button>()

        fun selectTab(index: Int) {
            tabs.forEachIndexed { i, tab ->
                val btn = tabButtons[i]
                if (i == index) {
                    // Selected: colored background, dark text
                    val bgColor = android.graphics.Color.parseColor(tab.color)
                    btn.backgroundTintList = android.content.res.ColorStateList.valueOf(bgColor)
                    btn.setTextColor(android.graphics.Color.parseColor("#111111"))
                    btn.textSize = 14f
                } else {
                    // Unselected: dark background, dim colored text
                    btn.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#252525"))
                    btn.setTextColor(android.graphics.Color.parseColor(tab.color).let {
                        android.graphics.Color.argb(160,
                            android.graphics.Color.red(it),
                            android.graphics.Color.green(it),
                            android.graphics.Color.blue(it))
                    })
                    btn.textSize = 13f
                }
            }
            tabs[index].builder()
        }

        tabs.forEachIndexed { i, tab ->
            val btn = android.widget.Button(ctx).apply {
                text = tab.label
                textSize = 13f; isAllCaps = false
                layoutParams = tabLp
                setOnClickListener { selectTab(i) }
            }
            tabButtons.add(btn)
            tabRow.addView(btn)
        }

        root.addView(tabRow)
        root.addView(content)

        // Show WP tab by default
        selectTab(0)

        val scroll = androidx.core.widget.NestedScrollView(ctx).apply {
            addView(root)
            setBackgroundColor(android.graphics.Color.parseColor("#1A1A1A"))
        }
        dialog.setContentView(scroll)
        dialog.window?.navigationBarColor = android.graphics.Color.parseColor("#1A1A1A")
        (scroll.parent as? android.view.View)?.setBackgroundColor(android.graphics.Color.parseColor("#1A1A1A"))
        dialog.show()
        dialog.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
    }

    private fun buildWpContent(ctx: android.content.Context, prefs: android.content.SharedPreferences, root: android.widget.LinearLayout, dialog: BottomSheetDialog, dp: Float) {
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
                    setPadding((4 * dp).toInt(), (6 * dp).toInt(), (4 * dp).toInt(), (6 * dp).toInt())
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    isClickable = true; isFocusable = true
                    if (i % 2 == 0) setBackgroundColor(android.graphics.Color.parseColor("#1E1E1E"))
                }
                // Color dot
                val dotSize = (20 * dp).toInt()
                pointRow.addView(android.view.View(ctx).apply {
                    layoutParams = android.widget.LinearLayout.LayoutParams(dotSize, dotSize).apply {
                        marginEnd = (8 * dp).toInt()
                    }
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(android.graphics.Color.parseColor("#1565C0"))
                        setStroke((2 * dp).toInt(), android.graphics.Color.WHITE)
                    }
                })
                // Name + coords
                val info = android.widget.LinearLayout(ctx).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                info.addView(android.widget.TextView(ctx).apply {
                    text = "${i + 1}. ${pt.name.ifBlank { "WP%02d".format(i + 1) }}"
                    setTextColor(android.graphics.Color.WHITE); textSize = 14f
                })
                info.addView(android.widget.TextView(ctx).apply {
                    text = "%.5f, %.5f".format(pt.position.latitude, pt.position.longitude)
                    setTextColor(android.graphics.Color.parseColor("#888888")); textSize = 11f
                })
                pointRow.addView(info)
                // Tap → popup menu
                pointRow.setOnClickListener { anchor ->
                    val popup = android.widget.PopupMenu(android.view.ContextThemeWrapper(ctx, androidx.appcompat.R.style.Theme_AppCompat_DayNight), anchor)
                    popup.menu.add(0, 1, 0, "Показать на карте")
                    popup.menu.add(0, 2, 1, "Навигация к точке")
                    popup.menu.add(0, 3, 2, "Свойства")
                    popup.menu.add(0, 4, 3, "Переименовать")
                    popup.menu.add(0, 5, 4, "Удалить")
                    popup.setOnMenuItemClickListener { item ->
                        when (item.itemId) {
                            1 -> { dialog.dismiss(); mapboxMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(pt.position, 16.0), 500) }
                            2 -> { dialog.dismiss(); startNavigationToPoint(pt.position, pt.name) }
                            3 -> {
                                dialog.dismiss()
                                showUserMarkerPropertiesDialog(i)
                            }
                            4 -> {
                                val input = android.widget.EditText(ctx).apply {
                                    setText(pt.name); setTextColor(0xFFFFFFFF.toInt())
                                    setBackgroundColor(0xFF2A2A2A.toInt()); setPadding(24, 16, 24, 16)
                                }
                                androidx.appcompat.app.AlertDialog.Builder(ctx, androidx.appcompat.R.style.Theme_AppCompat_Dialog)
                                    .setTitle("Имя точки")
                                    .setView(input)
                                    .setPositiveButton("OK") { _, _ ->
                                        pt.name = input.text.toString().ifBlank { "WP%02d".format(i + 1) }
                                        updateUserMarkersOnMap(); saveUserPoints()
                                        dialog.dismiss(); showQuickActionMenu()
                                    }
                                    .setNegativeButton("Отмена", null)
                                    .show()
                            }
                            5 -> {
                                userMarkers.removeAt(i)
                                updateUserMarkersOnMap(); saveUserPoints()
                                dialog.dismiss(); showQuickActionMenu()
                            }
                        }
                        true
                    }
                    popup.show()
                }
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
    }

    private fun buildRteContent(ctx: android.content.Context, prefs: android.content.SharedPreferences, root: android.widget.LinearLayout, dialog: BottomSheetDialog, dp: Float) {
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
                    setLoadedTrackVisible(newVis)
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

            // Waypoint list with status
            val gps = lastKnownGpsPoint
            waypoints.forEachIndexed { i, wp ->
                val isPassed = i < activeWpIndex
                val isCurrent = i == activeWpIndex
                val row = android.widget.LinearLayout(ctx).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    setPadding(0, (4 * dp).toInt(), 0, (4 * dp).toInt())
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    if (isCurrent) setBackgroundColor(android.graphics.Color.parseColor("#1AFF6F00"))
                }
                val status = when {
                    isPassed -> "✅"
                    isCurrent -> "🎯"
                    else -> "⬜"
                }
                row.addView(android.widget.TextView(ctx).apply {
                    text = status; textSize = 14f; setPadding(0, 0, (8 * dp).toInt(), 0)
                })
                val info = android.widget.LinearLayout(ctx).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                info.addView(android.widget.TextView(ctx).apply {
                    text = "${i + 1}. ${wp.name.ifBlank { "WP%02d".format(i + 1) }}"
                    textSize = 13f
                    setTextColor(when {
                        isPassed -> android.graphics.Color.parseColor("#666666")
                        isCurrent -> android.graphics.Color.parseColor("#FF6F00")
                        else -> android.graphics.Color.WHITE
                    })
                    if (isCurrent) setTypeface(null, android.graphics.Typeface.BOLD)
                })
                if (gps != null) {
                    val dist = distanceM(gps, LatLng(wp.lat, wp.lon))
                    val distStr = if (dist < 1000) "${dist.toInt()} м" else String.format("%.1f км", dist / 1000)
                    info.addView(android.widget.TextView(ctx).apply {
                        text = distStr; textSize = 11f
                        setTextColor(android.graphics.Color.parseColor("#888888"))
                    })
                }
                row.addView(info)
                row.addView(android.widget.TextView(ctx).apply {
                    text = "→"; textSize = 14f
                    setTextColor(android.graphics.Color.parseColor("#FF6F00"))
                    setPadding((8 * dp).toInt(), 0, (8 * dp).toInt(), 0)
                    setOnClickListener {
                        activeWpIndex = i
                        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                            .edit().putInt(PREF_ACTIVE_WP_INDEX, i).apply()
                        updateNavLine()
                        updateWaypointNavBar()
                        dialog.dismiss()
                        Toast.makeText(ctx, "→ WP${i + 1}: ${wp.name}", Toast.LENGTH_SHORT).show()
                    }
                })
                root.addView(row)
            }
        } else {
            root.addView(android.widget.TextView(ctx).apply {
                text = "🗺 Маршрут не загружен"
                setTextColor(android.graphics.Color.parseColor("#888888"))
                textSize = 14f
                setPadding(0, 8, 0, 12)
            })
        }

        // Route editor button
        root.addView(android.widget.Button(ctx).apply {
            text = "✏️ Редактор маршрута"
            textSize = 14f; isAllCaps = false
            setPadding(0, 12, 0, 0)
            setOnClickListener {
                dialog.dismiss()
                showRouteEditor()
            }
        })
    }

    private fun buildTrkContent(ctx: android.content.Context, prefs: android.content.SharedPreferences, root: android.widget.LinearLayout, dialog: BottomSheetDialog, dp: Float) {
        val fullWidthLp = android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            (48 * dp).toInt()
        ).apply { topMargin = (8 * dp).toInt() }

        // ── Редактор треков — всегда первым ──────────────────────────────────
        root.addView(android.widget.Button(ctx).apply {
            text = "✏️  Редактор треков"
            textSize = 14f; isAllCaps = false
            layoutParams = fullWidthLp
            backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#1565C0"))
            setTextColor(android.graphics.Color.WHITE)
            setOnClickListener {
                dialog.dismiss()
                showTrackEditorModeDialog(ctx)
            }
        })

        // ── Загруженный трек ─────────────────────────────────────────────────
        if (loadedTrackPoints.isNotEmpty()) {
            val trackLenKm = calcPolylineLength(loadedTrackPoints) / 1000.0
            val trkVisible = prefs.getBoolean(PREF_LOADED_TRACK_VISIBLE, true)
            val trackName = prefs.getString(PREF_LOADED_TRACK_NAME, "Трек") ?: "Трек"

            root.addView(android.widget.TextView(ctx).apply {
                text = "📏 $trackName"
                setTextColor(android.graphics.Color.parseColor("#2196F3"))
                textSize = 14f
                setPadding(0, (14 * dp).toInt(), 0, 2)
            })
            root.addView(android.widget.TextView(ctx).apply {
                text = "Точек: ${loadedTrackPoints.size}  •  Длина: ${"%.1f".format(trackLenKm)} км"
                setTextColor(android.graphics.Color.parseColor("#AAAAAA"))
                textSize = 12f
                setPadding(0, 0, 0, (6 * dp).toInt())
            })

            val btnLp = android.widget.LinearLayout.LayoutParams(0, (40 * dp).toInt(), 1f).apply {
                marginEnd = (6 * dp).toInt()
            }
            val actionRow = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                setPadding(0, 0, 0, (8 * dp).toInt())
            }
            actionRow.addView(android.widget.Button(ctx).apply {
                text = if (trkVisible) "👁 Скрыть" else "👁 Показать"
                textSize = 12f; isAllCaps = false
                layoutParams = btnLp
                setOnClickListener {
                    val newVis = !prefs.getBoolean(PREF_LOADED_TRACK_VISIBLE, true)
                    setLoadedTrackVisible(newVis)
                    text = if (newVis) "👁 Скрыть" else "👁 Показать"
                }
            })
            actionRow.addView(android.widget.Button(ctx).apply {
                text = "🗑 Очистить"
                textSize = 12f; isAllCaps = false
                layoutParams = btnLp
                setOnClickListener {
                    android.app.AlertDialog.Builder(ctx)
                        .setTitle("Очистить трек?")
                        .setMessage("«$trackName» (${loadedTrackPoints.size} точек) будет убран с карты")
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
            root.addView(actionRow)
        } else {
            root.addView(android.widget.TextView(ctx).apply {
                text = "Трек не загружен."
                setTextColor(android.graphics.Color.parseColor("#666666"))
                textSize = 13f
                setPadding(0, (12 * dp).toInt(), 0, 0)
            })
        }

        // ── Список сохранённых треков ─────────────────────────────────────────
        val tracksDir = getRaceNavDir(ctx, "tracks")
        val gpxFiles = tracksDir.listFiles { f -> f.extension.lowercase() == "gpx" }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
        if (gpxFiles.isNotEmpty()) {
            root.addView(android.widget.TextView(ctx).apply {
                text = "📁 Сохранённые треки (${gpxFiles.size})"
                setTextColor(android.graphics.Color.parseColor("#2196F3"))
                textSize = 14f
                setPadding(0, (14 * dp).toInt(), 0, (4 * dp).toInt())
            })

            val listContainer = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.VERTICAL
            }
            val sdf = java.text.SimpleDateFormat("dd.MM.yy HH:mm", java.util.Locale.getDefault())
            gpxFiles.forEach { file ->
                val trackFileName = file.nameWithoutExtension
                val dateStr = sdf.format(java.util.Date(file.lastModified()))

                val row = android.widget.LinearLayout(ctx).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(0, (4 * dp).toInt(), 0, (4 * dp).toInt())
                }
                val statsText = android.widget.TextView(ctx).apply {
                    text = "$trackFileName\n$dateStr  ···"
                    setTextColor(android.graphics.Color.WHITE)
                    textSize = 12f
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                row.addView(statsText)
                // Async load point count + distance
                viewLifecycleOwner.lifecycleScope.launch {
                    val info = try {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            val pts = GpxParser.parseGpxFull(file.inputStream()).trackPoints
                            if (pts.size < 2) "${pts.size} т." else {
                                var dist = 0.0
                                for (i in 1 until pts.size) {
                                    val a = pts[i - 1]; val b = pts[i]
                                    val dLat = Math.toRadians(b.first - a.first)
                                    val dLon = Math.toRadians(b.second - a.second)
                                    val s = Math.sin(dLat/2).let { it*it } +
                                        Math.cos(Math.toRadians(a.first)) * Math.cos(Math.toRadians(b.first)) *
                                        Math.sin(dLon/2).let { it*it }
                                    dist += 6371000.0 * 2 * Math.asin(Math.sqrt(s))
                                }
                                val km = dist / 1000.0
                                "${pts.size} т. · ${"%.1f".format(km)} км"
                            }
                        }
                    } catch (_: Exception) { "?" }
                    statsText.text = "$trackFileName\n$dateStr  $info"
                }

                // Load button
                row.addView(android.widget.Button(ctx).apply {
                    text = "📂"
                    textSize = 14f; isAllCaps = false
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        (48 * dp).toInt(), (36 * dp).toInt()
                    ).apply { marginStart = (4 * dp).toInt() }
                    backgroundTintList = android.content.res.ColorStateList.valueOf(
                        android.graphics.Color.parseColor("#1565C0"))
                    setTextColor(android.graphics.Color.WHITE)
                    setOnClickListener {
                        viewLifecycleOwner.lifecycleScope.launch {
                            val pts = try {
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    GpxParser.parseGpxFull(file.inputStream()).trackPoints
                                }
                            } catch (e: Exception) {
                                Toast.makeText(ctx, "Ошибка: ${e.message}", Toast.LENGTH_SHORT).show()
                                return@launch
                            }
                            if (pts.isNotEmpty()) {
                                loadTrack(pts)
                                ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                                    .edit().putString(PREF_LOADED_TRACK_NAME, trackFileName).apply()
                                dialog.dismiss()
                                Toast.makeText(ctx, "Загружен: $trackFileName (${pts.size} т.)", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(ctx, "Нет точек в файле", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                })

                // Edit button
                row.addView(android.widget.Button(ctx).apply {
                    text = "✏️"
                    textSize = 14f; isAllCaps = false
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        (48 * dp).toInt(), (36 * dp).toInt()
                    ).apply { marginStart = (4 * dp).toInt() }
                    backgroundTintList = android.content.res.ColorStateList.valueOf(
                        android.graphics.Color.parseColor("#E65100"))
                    setTextColor(android.graphics.Color.WHITE)
                    setOnClickListener {
                        viewLifecycleOwner.lifecycleScope.launch {
                            val pts = try {
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    GpxParser.parseGpxFull(file.inputStream()).trackPoints
                                }
                            } catch (e: Exception) {
                                Toast.makeText(ctx, "Ошибка: ${e.message}", Toast.LENGTH_SHORT).show()
                                return@launch
                            }
                            if (pts.isNotEmpty()) {
                                loadTrack(pts)
                                ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                                    .edit().putString(PREF_LOADED_TRACK_NAME, trackFileName).apply()
                                dialog.dismiss()
                                enterTrackEditMode()
                            } else {
                                Toast.makeText(ctx, "Нет точек в файле", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                })

                // Delete button
                row.addView(android.widget.Button(ctx).apply {
                    text = "🗑"
                    textSize = 14f; isAllCaps = false
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        (48 * dp).toInt(), (36 * dp).toInt()
                    ).apply { marginStart = (4 * dp).toInt() }
                    backgroundTintList = android.content.res.ColorStateList.valueOf(
                        android.graphics.Color.parseColor("#7B0000"))
                    setTextColor(android.graphics.Color.WHITE)
                    setOnClickListener {
                        android.app.AlertDialog.Builder(ctx)
                            .setTitle("Удалить трек?")
                            .setMessage("«$trackFileName» будет удалён без возможности восстановления.")
                            .setPositiveButton("Удалить") { _, _ ->
                                val deleted = file.delete()
                                if (deleted) {
                                    row.visibility = android.view.View.GONE
                                    Toast.makeText(ctx, "Удалён: $trackFileName", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(ctx, "Не удалось удалить файл", Toast.LENGTH_SHORT).show()
                                }
                            }
                            .setNegativeButton("Отмена", null)
                            .show()
                    }
                })

                listContainer.addView(row)
                listContainer.addView(android.view.View(ctx).apply {
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1)
                    setBackgroundColor(android.graphics.Color.parseColor("#333333"))
                })
            }

            val scrollView = android.widget.ScrollView(ctx).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    (220 * dp).toInt())
            }
            scrollView.addView(listContainer)
            root.addView(scrollView)
        }
    }

    private fun buildGpxContent(ctx: android.content.Context, prefs: android.content.SharedPreferences, root: android.widget.LinearLayout, dialog: BottomSheetDialog, dp: Float) {
        root.addView(android.widget.Button(ctx).apply {
            text = "📂 Загрузить файл"
            textSize = 14f; isAllCaps = false
            setPadding(0, 12, 0, 0)
            setOnClickListener {
                dialog.dismiss()
                filePickerLauncher.launch(arrayOf("*/*", "application/gpx+xml", "application/octet-stream"))
            }
        })
    }

    /** Full list of waypoints with statuses — tap on nav bar to open */
    private fun showWaypointList() {
        val ctx = context ?: return
        if (waypoints.isEmpty()) {
            Toast.makeText(ctx, "Маршрут не загружен", Toast.LENGTH_SHORT).show()
            return
        }

        val dialog = BottomSheetDialog(ctx)
        val pad = (16 * resources.displayMetrics.density).toInt()
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
                text = "${i + 1}. ${wp.name.ifBlank { "WP ${i + 1}" }}"
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

        val scroll = androidx.core.widget.NestedScrollView(ctx).apply { addView(root) }
        dialog.setContentView(scroll)
        dialog.window?.navigationBarColor = android.graphics.Color.parseColor("#1A1A1A")
        (scroll.parent as? android.view.View)?.setBackgroundColor(android.graphics.Color.parseColor("#1A1A1A"))
        scroll.setBackgroundColor(android.graphics.Color.parseColor("#1A1A1A"))
        dialog.setOnShowListener {
            val bsView = dialog.findViewById<android.view.View>(com.google.android.material.R.id.design_bottom_sheet)
            bsView?.let { v ->
                val dm = resources.displayMetrics
                v.layoutParams.height = dm.heightPixels
                val b = com.google.android.material.bottomsheet.BottomSheetBehavior.from(v)
                b.peekHeight = (dm.heightPixels * 0.20).toInt()
                b.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_COLLAPSED
                b.skipCollapsed = false
            }
        }
        dialog.show()
    }

    /** Full route editor — create/edit/delete waypoints, set radius, save as GPX */
    fun showRouteEditor() {
        val ctx = context ?: return
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val dialog = BottomSheetDialog(ctx)
        val pad = (16 * resources.displayMetrics.density).toInt()

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

        // Route name
        val currentRouteName = prefs.getString(PREF_ROUTE_NAME, "") ?: ""
        val routeNameInput = android.widget.EditText(ctx).apply {
            hint = "Имя маршрута"
            setText(currentRouteName)
            setTextColor(android.graphics.Color.WHITE)
            setHintTextColor(android.graphics.Color.parseColor("#666666"))
            textSize = 14f
            setSingleLine(true)
            setPadding(16, 12, 16, 12)
        }
        root.addView(routeNameInput)

        // Hide/Clear route buttons
        val routeBtnRow = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setPadding(0, 4, 0, 8)
        }
        val routeBtnLp = android.widget.LinearLayout.LayoutParams(0,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        val routeLineVisible = prefs.getBoolean(PREF_ROUTE_LINE_VISIBLE, true)
        val btnToggleLine = android.widget.Button(ctx).apply {
            text = if (routeLineVisible) "👁 Скрыть линию" else "👁 Показать линию"
            textSize = 12f; isAllCaps = false
            layoutParams = routeBtnLp
            setBackgroundTintList(android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#333333")))
            setTextColor(android.graphics.Color.WHITE)
        }
        btnToggleLine.setOnClickListener {
            val nowVisible = prefs.getBoolean(PREF_ROUTE_LINE_VISIBLE, true)
            setRouteLineVisible(!nowVisible)
            btnToggleLine.text = if (!nowVisible) "👁 Скрыть линию" else "👁 Показать линию"
            Toast.makeText(ctx, if (!nowVisible) "Линия показана" else "Линия скрыта", Toast.LENGTH_SHORT).show()
        }
        routeBtnRow.addView(btnToggleLine)
        routeBtnRow.addView(android.widget.Button(ctx).apply {
            text = "🗑 Очистить маршрут"
            textSize = 12f; isAllCaps = false
            layoutParams = routeBtnLp
            setBackgroundTintList(android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#B71C1C")))
            setTextColor(android.graphics.Color.WHITE)
            setOnClickListener {
                androidx.appcompat.app.AlertDialog.Builder(ctx, androidx.appcompat.R.style.Theme_AppCompat_Dialog)
                    .setTitle("Очистить маршрут?")
                    .setMessage("Все точки маршрута и линии будут удалены")
                    .setPositiveButton("Очистить") { _, _ ->
                        editWps.clear()
                        waypoints.clear()
                        wpBitmapCache.clear()
                        updateWaypointsOnMap()
                        updateRouteLineOnMap()
                        updateRadiusCircles()
                        updateNavLine()
                        updateWaypointNavBar()
                        saveWaypointsToPrefs()
                        dialog.dismiss()
                        Toast.makeText(ctx, "Маршрут очищен", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Отмена", null)
                    .show()
            }
        })
        root.addView(routeBtnRow)

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
            setBackgroundTintList(android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#FF6F00")))
            setTextColor(android.graphics.Color.WHITE)
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
            setBackgroundTintList(android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#1B5E20")))
            setTextColor(android.graphics.Color.WHITE)
            setOnClickListener {
                val newRadius = radiusInput.text.toString().toIntOrNull() ?: approachRadius
                val newRouteName = routeNameInput.text.toString().trim()
                prefs.edit()
                    .putInt(PREF_WP_APPROACH_RADIUS, newRadius)
                    .apply()
                if (newRouteName.isNotEmpty()) {
                    prefs.edit().putString(PREF_ROUTE_NAME, newRouteName).apply()
                }
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
            setBackgroundTintList(android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#1565C0")))
            setTextColor(android.graphics.Color.WHITE)
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
        val pad = (16 * resources.displayMetrics.density).toInt()

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
                val lat = parseDM(inputLat.text.toString(), wp.lat)
                val lon = parseDM(inputLon.text.toString(), wp.lon)
                val desc = inputDesc.text.toString().trim()
                wps[index] = wp.copy(name = name, lat = lat, lon = lon, index = index + 1, description = desc)
                onDone()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    /** Enhanced edit dialog with color, symbol, proximity */
    /** Overload for external callers (userMarkers etc) — passes result via callback */
    private fun showEnhancedEditWpDialog(wp: Waypoint, onResult: (Waypoint) -> Unit) {
        showEnhancedEditWpDialogInternal(wp, -1, onResult)
    }

    private fun showEnhancedEditWpDialog(index: Int) {
        val wp = waypoints.getOrNull(index) ?: return
        showEnhancedEditWpDialogInternal(wp, index, null)
    }

    private fun showEnhancedEditWpDialogInternal(wp: Waypoint, index: Int, onResult: ((Waypoint) -> Unit)?) {
        val ctx = context ?: return
        val pad = (16 * resources.displayMetrics.density).toInt()
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

        // Coordinates (WGS84 degrees-minutes, editable)
        root.addView(label("Координаты (WGS84)"))
        val inputLat = android.widget.EditText(ctx).apply {
            hint = "N 59°52.123'"
            setText(formatDM(wp.lat, true))
            setTextColor(android.graphics.Color.WHITE)
            setHintTextColor(android.graphics.Color.parseColor("#666666"))
            textSize = 14f
        }
        root.addView(inputLat)
        val inputLon = android.widget.EditText(ctx).apply {
            hint = "E 29°45.678'"
            setText(formatDM(wp.lon, false))
            setTextColor(android.graphics.Color.WHITE)
            setHintTextColor(android.graphics.Color.parseColor("#666666"))
            textSize = 14f
        }
        root.addView(inputLon)

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
                val lat = parseDM(inputLat.text.toString(), wp.lat)
                val lon = parseDM(inputLon.text.toString(), wp.lon)
                val desc = inputDesc.text.toString().trim()
                val prox = inputProx.text.toString().toDoubleOrNull() ?: 0.0
                val newColor = if (selectedColor == "#FF6F00") "" else selectedColor
                val newWp = wp.copy(name = name, lat = lat, lon = lon, description = desc,
                    proximity = prox, color = newColor, symbol = selectedSymbol)
                if (onResult != null) {
                    onResult(newWp)
                } else if (index >= 0) {
                    updateWaypoint(index, newWp)
                }
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
        updateNavCompass()
        // Toast removed — nav bar shows current WP, no need to overlap
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
        if (waypoints.isEmpty()) {
            Toast.makeText(context, "Загрузите маршрут", Toast.LENGTH_SHORT).show()
            return
        }
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
        updateRadiusCircles()
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
        updateNavCompass()
        updateRadiusCircles()
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
        updateRadiusCircles()
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

    /** Properties dialog for user markers — same UI as showEnhancedEditWpDialog */
    private fun showUserMarkerPropertiesDialog(markerIndex: Int) {
        val pt = userMarkers.getOrNull(markerIndex) ?: return
        val wp = Waypoint(pt.name, pt.position.latitude, pt.position.longitude, markerIndex + 1,
            color = pt.color, symbol = pt.symbol, proximity = pt.proximity)
        showEnhancedEditWpDialogInternal(wp, markerIndex) { edited ->
            if (markerIndex < userMarkers.size) {
                userMarkers[markerIndex] = UserPoint(edited.name, LatLng(edited.lat, edited.lon),
                    color = edited.color.ifBlank { "#1565C0" }, symbol = edited.symbol, proximity = edited.proximity)
                updateUserMarkersOnMap()
                updateUserMarkerRadiusCircles()
                saveUserPoints()
                wpBitmapCache.clear()
            }
            showQuickActionMenu()
        }
    }

    // (removed deprecated duplicate)

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
                val rawName = o.optString("name", "")
                restored.add(Waypoint(
                    name = rawName.ifBlank { "WP%02d".format(i + 1) },
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
    private fun setupLocationTrackingDirect(ctx: Context) {
        val engine = com.mapbox.mapboxsdk.location.engine.LocationEngineDefault.getDefaultLocationEngine(ctx)
        locationEngine = engine
        // Unsubscribe previous callback to prevent duplicate location listeners
        activeLocationCallback?.let { engine.removeLocationUpdates(it) }
        val intervalSec = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.getInt(PREF_TRACK_INTERVAL, 1) ?: 1
        val intervalMs = (intervalSec.coerceIn(1, 60) * 1000L)
        val callback = object : com.mapbox.mapboxsdk.location.engine.LocationEngineCallback<com.mapbox.mapboxsdk.location.engine.LocationEngineResult> {
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

                    // Первый GPS-фикс
                    if (!initialZoomDone) {
                        initialZoomDone = true
                        // Compute magnetic declination for magnetometer → true north
                        try {
                            val geoField = android.hardware.GeomagneticField(
                                loc.latitude.toFloat(), loc.longitude.toFloat(),
                                loc.altitude.toFloat(), System.currentTimeMillis())
                            magneticDeclination = geoField.declination
                        } catch (_: Exception) {}
                        context?.let { DiagnosticsCollector.logEvent(it, "GPS fix: acc=${loc.accuracy}m") }
                        waitingForFirstGps = false
                    }

                    // Первая анимация залёта к GPS — один раз за сессию (Guru Maps style)
                    if (!firstGpsAnimDone) {
                        firstGpsAnimDone = true
                        flyToGps(15.0)
                    }

                    // Compute speed & bearing from coordinate delta (reliable on ALL devices)
                    val nowMs = System.currentTimeMillis()
                    val dtSec = if (prevFreezeCheckTime > 0) (nowMs - prevFreezeCheckTime) / 1000.0 else 0.0
                    val hasPrevPoint = prevFreezeCheckLat != 0.0 || prevFreezeCheckLon != 0.0
                    val movedM = if (hasPrevPoint) distanceM(LatLng(prevFreezeCheckLat, prevFreezeCheckLon), newPoint) else 0.0
                    val computedSpeedKmh = if (dtSec > 0.5 && hasPrevPoint) (movedM / dtSec) * 3.6 else 0.0
                    val computedBearing = if (hasPrevPoint && movedM > 1.0) bearingToPoint(
                        prevFreezeCheckLat, prevFreezeCheckLon, loc.latitude, loc.longitude
                    ) else -1f  // -1 = no reliable bearing from movement

                    // Use computed speed (works on Samsung where loc.speed=0 while moving)
                    // Fallback to loc.speed if computed is 0 but loc has speed (Xiaomi cache case handled by position check)
                    val speedKmh = if (computedSpeedKmh > 1.0) computedSpeedKmh
                        else if (loc.hasSpeed()) (loc.speed * 3.6).toDouble() else 0.0

                    // Use computed bearing if available, else loc.bearing
                    val rawBearing = if (computedBearing >= 0) computedBearing
                        else if (loc.hasBearing()) loc.bearing else -1f

                    // Update position checkpoint
                    prevFreezeCheckLat = loc.latitude
                    prevFreezeCheckLon = loc.longitude
                    prevFreezeCheckTime = nowMs

                    // Freeze logic: stopped = speed < 2 km/h AND moved < 2m
                    val wasFrozen = bearingFrozen
                    if (speedKmh < 2.0 && movedM < 2.0) {
                        bearingFrozen = true
                    } else if (speedKmh > 3.0 && movedM > 1.0) {
                        bearingFrozen = false
                    }

                    // Bearing selection: frozen → keep last GPS bearing (stable), moving → computed bearing
                    // Magnetometer is unreliable in vehicles (metal), so we DON'T use it while driving
                    val effectiveBearing: Float
                    if (bearingFrozen) {
                        effectiveBearing = lastValidBearing  // keep last known direction, don't jump to magnetometer
                    } else {
                        if (wasFrozen && rawBearing >= 0) {
                            smoothedBearing = rawBearing.toDouble()
                        }
                        val bearing = if (rawBearing >= 0) smoothBearing(rawBearing).toFloat() else lastValidBearing
                        lastValidBearing = bearing
                        effectiveBearing = bearing
                    }

                    // Diagnostic log
                    Log.w("BearingDebug", "cSpd=%.1f cBear=%.0f moved=%.1f frz=%b gpsBear=%.0f magH=%.0f eff=%.0f".format(
                        speedKmh, computedBearing, movedM, bearingFrozen, loc.bearing,
                        magneticHeading, effectiveBearing))
                    // Send telemetry to server every 5 seconds
                    val now = System.currentTimeMillis()
                    if (now - lastTelemetrySentMs > 5000L) {
                        lastTelemetrySentMs = now
                        context?.let { ctx ->
                            val extra = org.json.JSONObject().apply {
                                put("cSpd", "%.1f".format(speedKmh))
                                put("cBear", "%.0f".format(computedBearing))
                                put("moved", "%.1f".format(movedM))
                                put("frozen", bearingFrozen)
                                put("gpsBear", "%.0f".format(loc.bearing))
                                put("gpsSpd", "%.1f".format(loc.speed * 3.6))
                                put("magHead", "%.0f".format(magneticHeading))
                                put("effBear", "%.0f".format(effectiveBearing))
                                put("lat", "%.6f".format(loc.latitude))
                                put("lon", "%.6f".format(loc.longitude))
                            }
                            Analytics.sendEvent(ctx, "telemetry", extra)
                        }
                    }

                    // Update custom GPS arrow with freeze-corrected bearing
                    updateGpsArrow(loc.latitude, loc.longitude, effectiveBearing)
                    // Update heading line from GPS — use same filtered bearing as cursor
                    val hlPrefs = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    if (hlPrefs?.getBoolean(PREF_HEADING_LINE_ENABLED, false) == true) {
                        updateHeadingLine(LatLng(loc.latitude, loc.longitude), effectiveBearing)
                    }

                    // Update accuracy circle — shrinks as GPS locks on, hides when < 10m
                    if (loc.hasAccuracy()) {
                        updateAccuracyCircle(loc.latitude, loc.longitude, loc.accuracy)
                    }

                    // Save GPS state for Choreographer camera loop (smooth 60 FPS interpolation)
                    lastGpsLat = loc.latitude
                    lastGpsLon = loc.longitude
                    lastGpsSpeedMs = loc.speed
                    lastGpsBearing = effectiveBearing
                    lastGpsTimeNanos = System.nanoTime()
                    lastGpsSpeedKmh = if (speedKmh > 1.0) speedKmh else loc.speed * 3.6

                    // Start camera loop if not running
                    if (!cameraLoopRunning && initialZoomDone) startCameraLoop()

                    // Update widgets (use computed speed — works on Samsung where loc.speed=0)
                    val speedKmhInt = speedKmh.toInt()
                    b.widgetSpeed.text = if (speedKmh > 1.0) speedKmhInt.toString() else "--"
                    b.widgetBearing.text = "${effectiveBearing.toInt()}°"
                    b.widgetDirectionArrow.rotation = effectiveBearing
                    if (loc.hasAltitude()) b.widgetAltitude.text = loc.altitude.toInt().toString()
                    updateNavCompass()

                    // Next CP distance + name + remaining km + auto-advance
                    if (waypoints.isNotEmpty() && activeWpIndex < waypoints.size) {
                        val wp = waypoints[activeWpIndex]
                        val distM = distanceM(newPoint, LatLng(wp.lat, wp.lon))
                        b.widgetNextCp.text = if (distM < 1000) "${distM.toInt()}м" else String.format("%.1f", distM / 1000)
                        b.widgetNextCpName.text = wp.name.takeIf { it.isNotBlank() } ?: "WP${activeWpIndex + 1}"
                        val remKm = calcRemainingKm(newPoint)
                        b.widgetRemainKm.text = if (remKm < 10) String.format("%.1f", remKm) else remKm.toInt().toString()
                        // Two radii: approach (warning sound) and taken (auto-advance)
                        val ctx = context
                        val prefs = ctx?.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
                        val globalApproachRadius = prefs?.getInt(PREF_WP_APPROACH_RADIUS, DEFAULT_WP_APPROACH_RADIUS)?.toDouble()
                            ?: DEFAULT_WP_APPROACH_RADIUS.toDouble()
                        val globalTakenRadius = prefs?.getInt(PREF_WP_TAKEN_RADIUS, DEFAULT_WP_TAKEN_RADIUS)?.toDouble()
                            ?: DEFAULT_WP_TAKEN_RADIUS.toDouble()
                        val approachRadius = if (wp.proximity > 0) wp.proximity else globalApproachRadius
                        val takenRadius = globalTakenRadius.coerceAtMost(approachRadius)
                        val inApproach = distM <= approachRadius
                        val inTaken = distM <= takenRadius
                        // Sound: entering approach radius (fire once per waypoint, only when nav active)
                        if (navActive && inApproach && !wasInApproachRadius) {
                            if (prefs?.getBoolean(PREF_SOUND_APPROACH, true) == true) playApproachSound()
                        }
                        wasInApproachRadius = inApproach
                        if (navActive && inTaken) {
                            val nextIndex = activeWpIndex + 1
                            if (nextIndex < waypoints.size) {
                                justTakenWpIndex = activeWpIndex  // remember taken WP to suppress wrong sound
                                advanceWaypoint()
                                warnedWrongWpIndices.clear()
                                wrongWpSoundPlayed.clear()
                            } else {
                                stopNavigation()
                                playFinishSound()
                                Toast.makeText(ctx, "\uD83C\uDFC1 Маршрут завершён!", Toast.LENGTH_LONG).show()
                            }
                        }

                        // Check ALL waypoints for wrong-order entry (only when nav is active)
                        if (navActive) {
                            // Clear justTaken flag when we leave that WP's radius
                            if (justTakenWpIndex >= 0) {
                                val jtWp = waypoints.getOrNull(justTakenWpIndex)
                                if (jtWp != null) {
                                    val jtDist = distanceM(newPoint, LatLng(jtWp.lat, jtWp.lon))
                                    val jtRadius = if (jtWp.proximity > 0) jtWp.proximity else globalApproachRadius
                                    if (jtDist > jtRadius) justTakenWpIndex = -1
                                }
                            }
                            waypoints.forEachIndexed { idx, otherWp ->
                                if (idx != activeWpIndex && idx != justTakenWpIndex) {
                                    val otherDist = distanceM(newPoint, LatLng(otherWp.lat, otherWp.lon))
                                    val otherRadius = if (otherWp.proximity > 0) otherWp.proximity else globalApproachRadius
                                    if (otherDist <= otherRadius) {
                                        // Sound once per radius entry
                                        if (idx !in wrongWpSoundPlayed) {
                                            playWrongWpSound()
                                            wrongWpSoundPlayed.add(idx)
                                        }
                                        if (idx !in warnedWrongWpIndices) {
                                            warnedWrongWpIndices.add(idx)
                                            Toast.makeText(ctx, "⚠\uFE0F Это WP${idx + 1}, след. WP${activeWpIndex + 1}", Toast.LENGTH_LONG).show()
                                        }
                                        return@forEachIndexed
                                    } else {
                                        warnedWrongWpIndices.remove(idx)
                                        wrongWpSoundPlayed.remove(idx)  // reset when leaving — sound again on re-entry
                                    }
                                }
                            }
                        }
                    } else {
                        b.widgetNextCpName.text = "--"
                        b.widgetRemainKm.text = "--"
                    }

                    // Check userMarkers proximity (only when navigation active)
                    if (navActive) {
                        userMarkers.forEachIndexed { idx, marker ->
                            if (idx !in visitedMarkerIndices) {
                                val ctx2 = context
                                val prefs2 = ctx2?.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
                                val globalRadius = prefs2?.getInt(PREF_WP_APPROACH_RADIUS, DEFAULT_WP_APPROACH_RADIUS)?.toDouble()
                                    ?: DEFAULT_WP_APPROACH_RADIUS.toDouble()
                                val dist = distanceM(newPoint, marker.position)
                                if (dist <= globalRadius) {
                                    visitedMarkerIndices.add(idx)
                                    if (prefs2?.getBoolean(PREF_SOUND_APPROACH, true) == true) playApproachSound()
                                }
                            }
                        }
                    }

                    updateNavLine()

                    // Запись трека — выполняется в TrackingService (фоновая служба)
                    // Обновляем трек на карте (шевроны расставляются автоматически через symbolPlacement)
                    if (isRecording) {
                        updateTrackOnMap()
                    }
                }
                override fun onFailure(exception: Exception) {
                    Log.e("RaceNav", "Location error: ${exception.message}")
                }
            }
        activeLocationCallback = callback
        engine.requestLocationUpdates(
            com.mapbox.mapboxsdk.location.engine.LocationEngineRequest.Builder(intervalMs)
                .setPriority(com.mapbox.mapboxsdk.location.engine.LocationEngineRequest.PRIORITY_HIGH_ACCURACY)
                .setMaxWaitTime(intervalMs * 2)
                .build(),
            callback,
            android.os.Looper.getMainLooper()
        )
    }

    private fun toggleRecording() {
        val ctx = context ?: return
        if (!isRecording) {
            // Старт — запускаем foreground service
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
            updateTrackOnMap()
            binding.btnRec.setImageResource(R.drawable.ic_rec_start)
            // Показываем диалог сохранения
            showSaveTrackDialog()
        }
    }

    // ─── Track save / resume ──────────────────────────────────────────────────

    /** Called when user stops recording — offer to save GPX with name input */
    private fun showSaveTrackDialog() {
        val ctx = context ?: return
        val pts = TrackingService.trackPoints.toList()
        val kmStr = String.format("%.1f", TrackingService.trackLengthM / 1000)
        if (pts.size < 2) {
            Toast.makeText(ctx, "⏹ Запись остановлена (нет точек)", Toast.LENGTH_SHORT).show()
            clearTmpTrack()
            return
        }
        val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault()).format(java.util.Date())
        val input = android.widget.EditText(ctx).apply {
            setText("track_$ts")
            selectAll()
            hint = "Название трека"
        }
        val dp = resources.displayMetrics.density
        val container = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val pad = (16 * dp).toInt()
            setPadding(pad, 0, pad, 0)
            addView(android.widget.TextView(ctx).apply {
                text = "${pts.size} точек • $kmStr км"
                setTextColor(android.graphics.Color.parseColor("#AAAAAA"))
                textSize = 13f
                setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt())
            })
            addView(input)
        }
        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle("💾 Сохранить трек")
            .setView(container)
            .setPositiveButton("Сохранить") { _, _ ->
                val name = input.text.toString().trim().ifBlank { "track_$ts" }
                saveTrackToFile(pts, name)
            }
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

    /** Save track points to GPX file in app's external tracks folder. */
    fun saveTrackToFile(points: List<Pair<Double, Double>>? = null, trackName: String? = null) {
        val ctx = context ?: return
        val pts = points ?: TrackingService.trackPoints.toList()
        if (pts.isEmpty()) {
            Toast.makeText(ctx, "Нет точек для сохранения", Toast.LENGTH_SHORT).show()
            return
        }
        val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault())
            .format(java.util.Date())
        val rawName = trackName?.trim()?.ifBlank { null } ?: "current_$timestamp"
        val name = rawName.replace(Regex("[/\\\\:*?\"<>|]"), "_")
        val filename = "$name.gpx"
        val gpxContent = GpxParser.writeGpx(pts, name)
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
        // Clear track line on map
        mapboxMap?.style?.getSourceAs<GeoJsonSource>(TRACK_SOURCE_ID)?.setGeoJson(
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
    }

    /** Guru Maps style fly-in: zoom-out → zoom-in to GPS position. Pauses camera loop. */
    private fun flyToGps(targetZoom: Double? = null) {
        val gps = lastKnownGpsPoint ?: return
        val curZoom = mapboxMap?.cameraPosition?.zoom ?: 14.0
        val toZoom = targetZoom ?: maxOf(curZoom, 15.0)
        val midZoom = maxOf(curZoom - 2.5, 7.0)
        flyAnimationActive = true
        mapboxMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(gps, midZoom), 350)
        emergencyHandler.postDelayed({
            mapboxMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(gps, toZoom), 550)
        }, 370)
        emergencyHandler.postDelayed({ flyAnimationActive = false }, 950)
    }

    fun applyFollowMode() {
        // Show/hide crosshair based on mode (before locationComponent check to work at startup)
        val crosshairEnabled = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.getBoolean(PREF_CROSSHAIR_ENABLED, true) ?: true
        if (followMode == FollowMode.FREE && crosshairEnabled) {
            // Cancel any pending hide from previous mode
            crosshairHideRunnable?.let { crosshairHandler.removeCallbacks(it) }
            _binding?.crosshairView?.visibility = View.VISIBLE
        } else {
            _binding?.crosshairView?.visibility = View.GONE
            // Fly to GPS when switching to follow mode
            if (lastKnownGpsPoint != null) flyToGps()
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
        // 2 loud high-pitched beeps on ALARM stream — distinct from taken sound
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        for (i in 0 until 2) {
            handler.postDelayed({
                try {
                    val tg = android.media.ToneGenerator(android.media.AudioManager.STREAM_ALARM, 100)
                    tg.startTone(android.media.ToneGenerator.TONE_CDMA_HIGH_SS, 250)
                    handler.postDelayed({ tg.release() }, 300)
                } catch (_: Exception) {}
            }, i * 350L)
        }
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

    private fun playWrongWpSound() {
        // Loud error buzzer - 2 low harsh tones
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        for (i in 0 until 2) {
            handler.postDelayed({
                try {
                    val tg = android.media.ToneGenerator(android.media.AudioManager.STREAM_ALARM, 100)
                    tg.startTone(android.media.ToneGenerator.TONE_CDMA_ABBR_REORDER, 400)
                    handler.postDelayed({ tg.release() }, 500)
                } catch (_: Exception) {}
            }, i * 500L)
        }
    }

    private fun playFinishSound() {
        // Stadium horn fanfare — C-E-G-C ascending, loud on ALARM stream
        Thread {
            try {
                val sampleRate = 22050
                val notes = doubleArrayOf(523.25, 659.25, 783.99, 1046.50) // C5-E5-G5-C6
                val durations = intArrayOf(300, 300, 300, 800) // last note held long
                val amp = 0.9

                val totalSamples = durations.sum() * sampleRate / 1000
                val buffer = ShortArray(totalSamples)
                var offset = 0
                for (n in notes.indices) {
                    val numSamples = durations[n] * sampleRate / 1000
                    for (s in 0 until numSamples) {
                        val t = s.toDouble() / sampleRate
                        // Horn = fundamental + harmonics for brass timbre
                        val sample = amp * (
                            0.6 * kotlin.math.sin(2.0 * Math.PI * notes[n] * t) +
                            0.25 * kotlin.math.sin(2.0 * Math.PI * notes[n] * 2 * t) +
                            0.1 * kotlin.math.sin(2.0 * Math.PI * notes[n] * 3 * t) +
                            0.05 * kotlin.math.sin(2.0 * Math.PI * notes[n] * 4 * t)
                        )
                        // Envelope: quick attack, sustain, fade on last note
                        val env = if (n == notes.size - 1 && s > numSamples * 0.7)
                            1.0 - (s - numSamples * 0.7) / (numSamples * 0.3) else 1.0
                        buffer[offset + s] = (sample * env * Short.MAX_VALUE).toInt().toShort()
                    }
                    offset += numSamples
                }

                val track = android.media.AudioTrack(
                    android.media.AudioManager.STREAM_ALARM,
                    sampleRate,
                    android.media.AudioFormat.CHANNEL_OUT_MONO,
                    android.media.AudioFormat.ENCODING_PCM_16BIT,
                    buffer.size * 2,
                    android.media.AudioTrack.MODE_STATIC
                )
                track.write(buffer, 0, buffer.size)
                track.play()
                Thread.sleep(durations.sum().toLong() + 200)
                track.stop()
                track.release()
            } catch (_: Exception) {}
        }.start()
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

    /** Parse "N 59°52.123'" or "59°52.123'" back to decimal degrees */
    private fun parseDM(text: String, fallback: Double): Double {
        try {
            val s = text.trim().uppercase()
            val negative = s.startsWith("S") || s.startsWith("W")
            val clean = s.removePrefix("N").removePrefix("S").removePrefix("E").removePrefix("W").trim()
            val degEnd = clean.indexOf('°')
            if (degEnd < 0) return text.toDoubleOrNull() ?: fallback  // fallback to decimal
            val deg = clean.substring(0, degEnd).trim().toInt()
            val minStr = clean.substring(degEnd + 1).replace("'", "").replace("′", "").trim()
            val min = minStr.toDouble()
            val result = deg + min / 60.0
            return if (negative) -result else result
        } catch (_: Exception) {
            return text.toDoubleOrNull() ?: fallback
        }
    }

    private var crosshairHideRunnable: Runnable? = null
    private val crosshairHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** Update crosshair-related overlays on camera move */
    private fun updateCrosshairInfo() {
        val center = mapboxMap?.cameraPosition?.target ?: return
        val ctx = context ?: return
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Show crosshair: permanently in FREE mode, temporarily when user drags in other modes
        if (prefs.getBoolean(PREF_CROSSHAIR_ENABLED, true)) {
            if (followMode == FollowMode.FREE) {
                _binding?.crosshairView?.visibility = View.VISIBLE
            } else if (userDragged) {
                showCrosshairTemporarily()
            }
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

        // Heading line (predicted direction from GPS bearing — use filtered bearing)
        if (prefs.getBoolean(PREF_HEADING_LINE_ENABLED, false) && gps != null) {
            updateHeadingLine(gps, lastGpsBearing)
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
    private fun updateHeadingLine(gps: LatLng, overrideBearing: Float? = null) {
        val style = mapboxMap?.style ?: return
        val source = style.getSourceAs<GeoJsonSource>(HEADING_LINE_SOURCE_ID) ?: return
        val bearingDeg = overrideBearing?.toDouble() ?: smoothedBearing
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
        startMagnetometer()
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
        // Update top bar server dot based on TraccarService + user pref
        val showServerDot = TraccarService.isRunning &&
            context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)?.getBoolean(PREF_BTN_SERVER_DOT, false) == true
        _binding?.topBarServerDot?.visibility = if (showServerDot) View.VISIBLE else View.GONE
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
        // Resume smooth camera loop if GPS is active
        if (lastGpsTimeNanos > 0 && initialZoomDone) startCameraLoop()

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
        // Release drag lock so gestures are never permanently disabled
        if (isDraggingPoint || dragStartRunnable != null) {
            isDraggingPoint = false
            dragPointIndex = -1
            dragStartRunnable?.let { emergencyHandler.removeCallbacks(it) }; dragStartRunnable = null
            mapboxMap?.uiSettings?.isScrollGesturesEnabled = true
            mapboxMap?.uiSettings?.isZoomGesturesEnabled = true
        }
        stopMagnetometer()
        stopCameraLoop()
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
        outState.putBoolean("widget_free_mode", isWidgetFreeMode)
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

    // ─── Smooth camera loop (Choreographer, ~60 FPS) ───────────────────
    // Extrapolates position between GPS fixes using dead reckoning.
    // GPS callback saves lat/lon/speed/bearing/time; this loop interpolates.

    private val cameraFrameCallback = object : android.view.Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!cameraLoopRunning || _binding == null || !isAdded) return
            try { moveCameraSmooth(System.nanoTime()) } catch (_: Exception) {}
            android.view.Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun startCameraLoop() {
        if (cameraLoopRunning) return
        cameraLoopRunning = true
        android.view.Choreographer.getInstance().postFrameCallback(cameraFrameCallback)
    }

    private fun stopCameraLoop() {
        cameraLoopRunning = false
        android.view.Choreographer.getInstance().removeFrameCallback(cameraFrameCallback)
    }

    private fun moveCameraSmooth(frameTimeNanos: Long) {
        if (flyAnimationActive || userDragged || followMode == FollowMode.FREE) return
        val map = mapboxMap ?: return
        if (lastGpsTimeNanos == 0L) return

        // dt since last GPS fix, clamped to 2s to avoid runaway extrapolation
        val dtSec = ((frameTimeNanos - lastGpsTimeNanos) / 1_000_000_000.0).coerceIn(0.0, 2.0)

        // If GPS stale (>= 2s), don't extrapolate — use last known position
        val speedMs = if (dtSec >= 2.0) 0.0 else lastGpsSpeedMs.toDouble()
        val bearingRad = Math.toRadians(lastGpsBearing.toDouble())
        // Approximate meters → degrees conversion
        val metersPerDegLat = 111_320.0
        val metersPerDegLon = 111_320.0 * Math.cos(Math.toRadians(lastGpsLat))
        val dLat = (speedMs * dtSec * Math.cos(bearingRad)) / metersPerDegLat
        val dLon = (speedMs * dtSec * Math.sin(bearingRad)) / metersPerDegLon.coerceAtLeast(1.0)
        val extLat = lastGpsLat + dLat
        val extLon = lastGpsLon + dLon

        val speedKmh = lastGpsSpeedKmh

        // 3D tilt: 0° stopped → 45° at 60+ km/h (only in FOLLOW_COURSE if enabled)
        val tilt = if (tilt3dEnabled && followMode == FollowMode.FOLLOW_COURSE)
            (speedKmh.coerceIn(0.0, 60.0) / 60.0 * 45.0)
        else 0.0

        // Auto-zoom
        val targetZoom = if (autoZoomLevel > 0) {
            val base = if (userBaseZoom > 0) userBaseZoom
                       else map.cameraPosition?.zoom ?: 14.0
            val maxDelta = autoZoomLevel * 0.4
            val delta = speedKmh.coerceIn(0.0, 120.0) / 120.0 * maxDelta
            (base - delta).coerceIn(base - maxDelta, base)
        } else if (userBaseZoom > 0) {
            // Apply volume-key zoom in follow modes — Choreographer overwrites animateCamera every 16ms
            userBaseZoom
        } else null

        val target = LatLng(extLat, extLon)
        val builder = com.mapbox.mapboxsdk.camera.CameraPosition.Builder()
            .target(target)
            .tilt(tilt)
            .padding(doubleArrayOf(0.0, cameraTopPadding.toDouble(), 0.0, 0.0))

        when (followMode) {
            FollowMode.FOLLOW_NORTH -> builder.bearing(0.0)
            FollowMode.FOLLOW_COURSE -> builder.bearing(lastGpsBearing.toDouble())
            FollowMode.FREE -> return
        }
        if (targetZoom != null) builder.zoom(targetZoom)

        // moveCamera (instant) — Choreographer already provides smooth 60 FPS cadence
        map.moveCamera(CameraUpdateFactory.newCameraPosition(builder.build()))

        // Move GPS arrow to extrapolated position (no persist — 60 FPS would thrash disk)
        updateGpsArrow(extLat, extLon, lastGpsBearing, persist = false)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopCameraLoop()
        // Unsubscribe GPS callback to prevent leak and ghost updates after destroy
        activeLocationCallback?.let { cb ->
            locationEngine?.removeLocationUpdates(cb)
        }
        activeLocationCallback = null
        locationEngine = null
        locationTrackingStarted = false
        emergencyHandler.removeCallbacksAndMessages(null)
        emergencyRunnable = null
        _binding?.lockFlashBorder?.animate()?.cancel()
        liveUsersPoller?.stop(); liveUsersPoller = null
        stopChronoTicker(); stopTimeTicker()
        tileServer?.cleanup(); tileServer = null
        _binding?.mapView?.onDestroy(); _binding = null
    }
}


