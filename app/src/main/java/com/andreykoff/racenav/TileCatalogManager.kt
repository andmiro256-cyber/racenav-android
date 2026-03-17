package com.andreykoff.racenav

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches tile source catalog from central proxy server.
 * Caches catalog in SharedPreferences for offline fallback.
 */
object TileCatalogManager {

    private const val TAG = "TileCatalog"
    private const val CATALOG_URL = "http://87.120.84.254/api/tiles/catalog"
    private const val PREF_CATALOG_JSON = "tile_catalog_json"
    private const val PREF_CATALOG_VERSION = "tile_catalog_version"
    private const val TIMEOUT_MS = 5000

    data class CatalogEntry(
        val key: String,
        val label: String,
        val maxZoom: Int,
        val tms: Boolean,
        val proxyPath: String,
        val opacity: Float = 1.0f
    )

    data class Catalog(
        val version: Int,
        val base: List<CatalogEntry>,
        val overlays: List<CatalogEntry>
    )

    private const val PROXY_BASE = "http://87.120.84.254"

    fun buildProxyUrl(proxyPath: String): String {
        return "$PROXY_BASE$proxyPath"
    }

    /** Load cached catalog from SharedPreferences (instant, no network). */
    fun loadCachedCatalog(context: Context): Catalog? {
        val prefs = context.getSharedPreferences(MapFragment.PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(PREF_CATALOG_JSON, null) ?: return null
        return try {
            parseCatalog(json)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse cached catalog", e)
            null
        }
    }

    /** Fetch catalog from server asynchronously. Calls back on main thread. */
    fun fetchCatalog(context: Context, callback: (Catalog?) -> Unit) {
        val appContext = context.applicationContext
        Thread {
            val catalog = try {
                val conn = URL(CATALOG_URL).openConnection() as HttpURLConnection
                conn.connectTimeout = TIMEOUT_MS
                conn.readTimeout = TIMEOUT_MS
                conn.setRequestProperty("User-Agent", "RaceNav-Android")
                try {
                    if (conn.responseCode == 200) {
                        val json = conn.inputStream.bufferedReader().use { it.readText() }
                        val catalog = parseCatalog(json)
                        // Cache for offline use
                        appContext.getSharedPreferences(MapFragment.PREFS_NAME, Context.MODE_PRIVATE)
                            .edit()
                            .putString(PREF_CATALOG_JSON, json)
                            .putInt(PREF_CATALOG_VERSION, catalog.version)
                            .apply()
                        Log.d(TAG, "Fetched catalog v${catalog.version}: ${catalog.base.size} base, ${catalog.overlays.size} overlays")
                        catalog
                    } else {
                        Log.w(TAG, "Catalog HTTP ${conn.responseCode}")
                        null
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Catalog fetch failed: ${e.message}")
                null
            }

            android.os.Handler(android.os.Looper.getMainLooper()).post {
                callback(catalog)
            }
        }.start()
    }

    fun parseCatalog(json: String): Catalog {
        val root = JSONObject(json)
        val version = root.optInt("version", 1)

        val baseArr = root.optJSONArray("base") ?: org.json.JSONArray()
        val base = mutableListOf<CatalogEntry>()
        for (i in 0 until baseArr.length()) {
            val obj = baseArr.getJSONObject(i)
            base.add(CatalogEntry(
                key = obj.getString("key"),
                label = obj.getString("label"),
                maxZoom = obj.optInt("maxZoom", 19),
                tms = obj.optBoolean("tms", false),
                proxyPath = obj.getString("proxy")
            ))
        }

        val overlayArr = root.optJSONArray("overlays") ?: org.json.JSONArray()
        val overlays = mutableListOf<CatalogEntry>()
        for (i in 0 until overlayArr.length()) {
            val obj = overlayArr.getJSONObject(i)
            overlays.add(CatalogEntry(
                key = obj.getString("key"),
                label = obj.getString("label"),
                maxZoom = obj.optInt("maxZoom", 19),
                tms = obj.optBoolean("tms", false),
                proxyPath = obj.getString("proxy"),
                opacity = obj.optDouble("opacity", 0.7).toFloat()
            ))
        }

        return Catalog(version, base, overlays)
    }
}
