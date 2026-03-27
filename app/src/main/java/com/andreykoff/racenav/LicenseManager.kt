package com.andreykoff.racenav

import android.annotation.SuppressLint
import android.content.Context
import android.media.MediaDrm
import android.os.Build
import android.provider.Settings
import java.io.File
import java.security.MessageDigest
import java.util.UUID

object LicenseManager {

    private const val PREFS_NAME = "racenav_license"
    private const val KEY_INSTALL_TIME = "install_time"
    private const val KEY_LICENSE = "license_key"
    private const val KEY_ACTIVATED = "activated"
    private const val KEY_DEVICE_UUID = "device_uuid"
    // Backup in a separate prefs file to survive "Clear Data"
    private const val BACKUP_PREFS = "rnav_sys"
    private const val KEY_BACKUP_INSTALL = "bi"
    private const val KEY_BACKUP_UUID = "bu"

    // Server license check
    private const val KEY_LICENSE_STATUS = "license_status"      // "active"|"expired"|"trial"
    private const val KEY_LICENSE_UNTIL = "license_until"         // ISO date string
    private const val KEY_SERVER_STATUS = "server_status"         // "active"|"expired"|"none"
    private const val KEY_SERVER_UNTIL = "server_until"           // ISO date string
    private const val KEY_PLAN = "license_plan"                   // "full"|"license"|null
    private const val KEY_LAST_CHECK = "last_license_check"      // timestamp ms
    private const val LICENSE_API = "http://87.120.84.254/api/license"

    const val TRIAL_DAYS = 20
    private const val CONTACT_TELEGRAM = "https://t.me/Andreykoff"
    private const val CONTACT_EMAIL = "snowwolf888@gmail.com"

    // Master keys — light obfuscation (not in plain text)
    private val MK = arrayOf(
        // TNAV-MASTER-2026-XRAY
        byteArrayOf(84,78,65,86,45,77,65,83,84,69,82,45,50,48,50,54,45,88,82,65,89),
        // TNAV-BETA-TEST-FREE
        byteArrayOf(84,78,65,86,45,66,69,84,65,45,84,69,83,84,45,70,82,69,69)
    )
    private val MASTER_KEYS: Set<String> by lazy {
        MK.map { String(it) }.toSet()
    }

    fun getContactUrl(): String = CONTACT_TELEGRAM
    fun getContactEmail(): String = CONTACT_EMAIL

    /**
     * Reliable device ID: self-generated UUID stored in prefs.
     * Falls back to ANDROID_ID only as seed for initial generation.
     * Works on all tablets, phones, and devices without Google Play.
     */
    private fun getOrCreateDeviceId(context: Context): String {
        val prefs = getPrefs(context)
        val backupPrefs = context.getSharedPreferences(BACKUP_PREFS, Context.MODE_PRIVATE)

        // Check main prefs first
        var uuid = prefs.getString(KEY_DEVICE_UUID, null)
        if (uuid != null) return uuid

        // Check backup prefs (survives app data clear in some cases)
        uuid = backupPrefs.getString(KEY_BACKUP_UUID, null)
        if (uuid != null) {
            // Restore to main prefs
            prefs.edit().putString(KEY_DEVICE_UUID, uuid).apply()
            return uuid
        }

        // Check external file (survives Clear Data, not uninstall)
        uuid = readExternalUuid(context)
        if (uuid != null) {
            prefs.edit().putString(KEY_DEVICE_UUID, uuid).apply()
            backupPrefs.edit().putString(KEY_BACKUP_UUID, uuid).apply()
            return uuid
        }

        // Generate new UUID, seeded with ANDROID_ID for extra uniqueness
        val androidId = getAndroidId(context)
        uuid = if (androidId.isNotEmpty() && androidId != "9774d56d682e549c") {
            // Use ANDROID_ID as seed for deterministic UUID per device
            UUID.nameUUIDFromBytes("racenav:$androidId".toByteArray()).toString()
        } else {
            // Fallback: random UUID (tablets without proper ANDROID_ID)
            UUID.randomUUID().toString()
        }

        // Store in all three locations
        prefs.edit().putString(KEY_DEVICE_UUID, uuid).apply()
        backupPrefs.edit().putString(KEY_BACKUP_UUID, uuid).apply()
        writeExternalUuid(context, uuid)
        return uuid
    }

    private fun readExternalUuid(context: Context): String? {
        return try {
            val file = File(context.getExternalFilesDir(null), "device_id.txt")
            if (file.exists()) {
                val id = file.readText().trim()
                if (id.length >= 32) id else null
            } else null
        } catch (_: Exception) { null }
    }

    private fun writeExternalUuid(context: Context, uuid: String) {
        try {
            val file = File(context.getExternalFilesDir(null), "device_id.txt")
            file.writeText(uuid)
        } catch (_: Exception) { }
    }

    /**
     * Hardware fingerprint: SHA-256 of stable device properties.
     * Survives Clear Data and reinstall. Used for server-side deduplication.
     */
    @SuppressLint("HardwareIds")
    fun getHardwareFingerprint(context: Context): String {
        val parts = mutableListOf<String>()
        parts.add(Build.MANUFACTURER)
        parts.add(Build.MODEL)
        parts.add(Build.BOARD)
        parts.add(Build.HARDWARE)
        parts.add(getAndroidId(context))
        // MediaDrm Widevine ID — stable across reinstalls
        try {
            val widevineUuid = UUID(-0x121074568629b532L, -0x5c37d8232ae2de13L)
            val drm = MediaDrm(widevineUuid)
            try {
                val id = drm.getPropertyByteArray(MediaDrm.PROPERTY_DEVICE_UNIQUE_ID)
                parts.add(id.joinToString("") { "%02x".format(it) })
            } finally {
                drm.close()
            }
        } catch (_: Exception) { }

        val input = parts.joinToString("|")
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    @SuppressLint("HardwareIds")
    private fun getAndroidId(context: Context): String {
        return try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: ""
        } catch (_: Exception) { "" }
    }

    private fun getPrefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Call on every app start — records first launch if not set */
    fun ensureInstallTime(context: Context) {
        val prefs = getPrefs(context)
        val backupPrefs = context.getSharedPreferences(BACKUP_PREFS, Context.MODE_PRIVATE)

        var installTime = prefs.getLong(KEY_INSTALL_TIME, 0L)

        if (installTime == 0L) {
            // Try restore from backup
            installTime = backupPrefs.getLong(KEY_BACKUP_INSTALL, 0L)
        }

        if (installTime == 0L) {
            // First ever launch
            installTime = System.currentTimeMillis()
        }

        // Save to both
        prefs.edit().putLong(KEY_INSTALL_TIME, installTime).apply()
        backupPrefs.edit().putLong(KEY_BACKUP_INSTALL, installTime).apply()

        // Also ensure device UUID is created
        getOrCreateDeviceId(context)
    }

    /** Is the app activated (purchased)? */
    fun isActivated(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_ACTIVATED, false)
    }

    /** Days remaining in trial. Returns 0 if expired. */
    fun trialDaysLeft(context: Context): Int {
        val prefs = getPrefs(context)
        val backupPrefs = context.getSharedPreferences(BACKUP_PREFS, Context.MODE_PRIVATE)
        var installTime = prefs.getLong(KEY_INSTALL_TIME, 0L)
        if (installTime == 0L) installTime = backupPrefs.getLong(KEY_BACKUP_INSTALL, 0L)
        if (installTime == 0L) installTime = System.currentTimeMillis()
        val elapsed = System.currentTimeMillis() - installTime
        val daysElapsed = (elapsed / (1000L * 60 * 60 * 24)).toInt()
        return (TRIAL_DAYS - daysElapsed).coerceAtLeast(0)
    }

    /** Is trial still active? */
    fun isTrialActive(context: Context): Boolean {
        return trialDaysLeft(context) > 0
    }

    /** Can the user use the app? Always true — app works in free mode after trial. */
    fun canUse(context: Context): Boolean = true

    /** Full access to all features: trial active OR license purchased. */
    fun hasFullAccess(context: Context): Boolean {
        return isActivated(context) || isTrialActive(context) || canUseCached(context)
    }

    /** Free mode: trial expired and no license. Map works, premium features blocked. */
    fun isFreeMode(context: Context): Boolean = !hasFullAccess(context)

    /** Show trial warning: 5 days or less remaining */
    fun shouldShowTrialWarning(context: Context): Boolean {
        return isTrialActive(context) && !isActivated(context) && trialDaysLeft(context) <= 5
    }

    private const val FREE_MODE_MAX_WAYPOINTS = 5

    /** Max waypoints user can create */
    fun getMaxUserWaypoints(context: Context): Int {
        return if (hasFullAccess(context)) Int.MAX_VALUE else FREE_MODE_MAX_WAYPOINTS
    }

    /** Show "license required" toast */
    fun showLicenseRequired(context: Context) {
        android.widget.Toast.makeText(context, "Требуется лицензия — info@trophynav.ru", android.widget.Toast.LENGTH_LONG).show()
    }

    /**
     * Validate and activate a license key.
     * Key format: TNAV-XXXX-XXXX-XXXX
     */
    fun activate(context: Context, key: String): Boolean {
        val trimmed = key.trim().uppercase()

        // Check master keys
        if (trimmed in MASTER_KEYS) {
            saveActivation(context, trimmed)
            return true
        }

        // Check device-specific key
        val deviceId = getOrCreateDeviceId(context)
        val expectedKey = generateKeyForDevice(deviceId)
        if (trimmed == expectedKey) {
            saveActivation(context, trimmed)
            return true
        }

        return false
    }

    private fun saveActivation(context: Context, key: String) {
        getPrefs(context).edit()
            .putBoolean(KEY_ACTIVATED, true)
            .putString(KEY_LICENSE, key)
            .apply()
    }

    /** Activate license from server response (email-based multi-device) */
    fun activateFromServer(context: Context, plan: String) {
        getPrefs(context).edit()
            .putBoolean(KEY_ACTIVATED, true)
            .putString(KEY_LICENSE_STATUS, "active")
            .putString(KEY_PLAN, plan.ifEmpty { null })
            .putLong(KEY_LAST_CHECK, System.currentTimeMillis())
            .apply()
    }

    /** Generate a valid license key for a given device ID */
    fun generateKeyForDevice(deviceId: String): String {
        val input = "$deviceId:racenav-salt-2026"
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        val hex = digest.joinToString("") { "%02X".format(it) }
        // Format: TNAV-XXXX-XXXX-XXXX (12 chars from hash)
        return "TNAV-${hex.substring(0, 4)}-${hex.substring(4, 8)}-${hex.substring(8, 12)}"
    }

    /** Get device ID for display (short format for user) */
    fun getDeviceIdForUser(context: Context): String {
        val uuid = getOrCreateDeviceId(context)
        // Show short format: first 8 chars of UUID without dashes
        val short = uuid.replace("-", "").take(12).uppercase()
        return "${short.substring(0,4)}-${short.substring(4,8)}-${short.substring(8,12)}"
    }

    /** Get short device ID (8 chars, no dashes, uppercase) for admin/diagnostics */
    fun getShortDeviceId(context: Context): String {
        return getOrCreateDeviceId(context).replace("-", "").take(8).uppercase()
    }

    /** Get raw device ID for key generation (internal) */
    fun getRawDeviceId(context: Context): String {
        return getOrCreateDeviceId(context)
    }

    /** Get stored license key */
    fun getStoredKey(context: Context): String? {
        return getPrefs(context).getString(KEY_LICENSE, null)
    }

    /** Deactivate (for testing) */
    fun deactivate(context: Context) {
        getPrefs(context).edit()
            .putBoolean(KEY_ACTIVATED, false)
            .remove(KEY_LICENSE)
            .apply()
    }

    /** Check license from server. Call from background thread. */
    fun checkLicenseFromServer(context: Context): Boolean {
        val deviceId = getOrCreateDeviceId(context).replace("-", "").take(8).uppercase()
        android.util.Log.d("LicenseManager", "Checking license for deviceId: $deviceId")
        try {
            val url = java.net.URL("$LICENSE_API/$deviceId")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            val response = try {
                if (conn.responseCode !in 200..299) throw Exception("HTTP ${conn.responseCode}")
                conn.inputStream.bufferedReader().readText()
            } finally {
                conn.disconnect()
            }
            android.util.Log.d("LicenseManager", "Server response: $response")

            val json = org.json.JSONObject(response)
            val prefs = getPrefs(context)
            prefs.edit()
                .putString(KEY_LICENSE_STATUS, json.optString("license", "trial"))
                .putString(KEY_LICENSE_UNTIL, json.optString("license_until", ""))
                .putString(KEY_SERVER_STATUS, json.optString("server", "none"))
                .putString(KEY_SERVER_UNTIL, json.optString("server_until", ""))
                .putString(KEY_PLAN, json.optString("plan", ""))
                .putLong(KEY_LAST_CHECK, System.currentTimeMillis())
                .apply()

            // If server says "active", mark as activated locally too
            if (json.optString("license") == "active") {
                prefs.edit().putBoolean(KEY_ACTIVATED, true).apply()
            }

            return json.optString("license") != "expired"
        } catch (e: Exception) {
            // No network -- use cached status
            return canUseCached(context)
        }
    }

    /** Check cached license (when no network) */
    fun canUseCached(context: Context): Boolean {
        val prefs = getPrefs(context)
        val status = prefs.getString(KEY_LICENSE_STATUS, null)

        if (status == "active") {
            // Check if license_until has passed
            val until = prefs.getString(KEY_LICENSE_UNTIL, "") ?: ""
            if (until.isNotEmpty()) {
                try {
                    val clean = until.substringBefore('.').substringBefore('Z')
                    val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
                    fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
                    val date = fmt.parse(clean)
                    if (date != null && date.time > System.currentTimeMillis()) return true
                } catch (_: Exception) {}
            }
            return true // If can't parse date, trust the status
        }

        if (status == "trial") {
            return isTrialActive(context)
        }

        if (status == null) {
            // Never checked server -- use local trial
            return isTrialActive(context) || isActivated(context)
        }

        return false
    }

    /** Get license plan: "full", "license", or null */
    fun getPlan(context: Context): String? {
        val plan = getPrefs(context).getString(KEY_PLAN, null)
        return if (plan.isNullOrEmpty()) null else plan
    }

    /** Is this a full (Pro+Server) plan? */
    fun isFullPlan(context: Context): Boolean = getPlan(context) == "full"

    /** Is server subscription active? */
    fun isServerActive(context: Context): Boolean {
        val prefs = getPrefs(context)
        val status = prefs.getString(KEY_SERVER_STATUS, "none")
        if (status != "active") return false
        val until = prefs.getString(KEY_SERVER_UNTIL, "") ?: ""
        if (until.isEmpty()) return false
        try {
            // Server returns ISO 8601 with ms and Z, e.g. "2026-04-16T20:25:23.012Z"
            val clean = until.substringBefore('.').substringBefore('Z')
            val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
            fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
            val date = fmt.parse(clean)
            return date != null && date.time > System.currentTimeMillis()
        } catch (_: Exception) { return false }
    }

}
