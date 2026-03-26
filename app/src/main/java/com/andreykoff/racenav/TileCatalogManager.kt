package com.andreykoff.racenav

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Fetches tile source catalog from central proxy server.
 * Caches catalog in SharedPreferences for offline fallback.
 * Tries localhost (ADB reverse) first, then direct server.
 */
object TileCatalogManager {

    private const val TAG = "TileCatalog"
    private val CATALOG_URLS = listOf(
        "http://127.0.0.1:9222/api/tiles/catalog",    // ADB reverse (dev) or local proxy
        "http://87.120.84.254/api/tiles/catalog"  // direct server
    )
    private const val PREF_CATALOG_JSON = "tile_catalog_json"
    private const val PREF_CATALOG_VERSION = "tile_catalog_version"

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

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build()

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

    /** Bundled catalog from assets — last resort fallback when no network and no cache. */
    fun loadBundledCatalog(context: Context): Catalog? {
        return try {
            val json = context.assets.open("tile_catalog.json")
                .bufferedReader().use { it.readText() }
            parseCatalog(json)
        } catch (e: Exception) {
            Log.w(TAG, "No bundled catalog", e)
            null
        }
    }

    /** Fetch catalog trying multiple URLs with fallback. */
    fun fetchCatalog(context: Context, callback: (Catalog?) -> Unit) {
        val appContext = context.applicationContext
        Thread {
            var catalog: Catalog? = null
            for (url in CATALOG_URLS) {
                try {
                    val request = Request.Builder()
                        .url(url)
                        .header("User-Agent", "RaceNav-Android")
                        .get()
                        .build()
                    val response = client.newCall(request).execute()
                    response.use { resp ->
                        if (resp.isSuccessful) {
                            val json = resp.body?.string() ?: ""
                            catalog = parseCatalog(json)
                            appContext.getSharedPreferences(MapFragment.PREFS_NAME, Context.MODE_PRIVATE)
                                .edit()
                                .putString(PREF_CATALOG_JSON, json)
                                .putInt(PREF_CATALOG_VERSION, catalog!!.version)
                                .apply()
                            Log.d(TAG, "Fetched catalog v${catalog!!.version} from $url: ${catalog!!.base.size} base, ${catalog!!.overlays.size} overlays")
                        } else {
                            Log.w(TAG, "Catalog HTTP ${resp.code} from $url")
                        }
                    }
                    if (catalog != null) break
                } catch (e: Exception) {
                    Log.w(TAG, "Catalog fetch failed from $url: ${e.message}")
                }
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
