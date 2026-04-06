package com.andreykoff.racenav

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * Repository for favorites groups: cache in SharedPreferences + server sync via FavoritesApi.
 * Thread-safe for sequential calls. Network calls are blocking — call from background thread.
 */
object FavoritesGroupsRepository {
    private const val TAG = "FavoritesRepo"
    const val ACTIVE_ALL = "all"

    private var cachedDoc: FavoritesDocument? = null
    private var cachedLimits: FavoritesLimits = FavoritesLimits()

    // --- prefs helpers ---

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(MapFragment.PREFS_NAME, Context.MODE_PRIVATE)

    private fun credentials(ctx: Context): Pair<String, String> {
        val p = prefs(ctx)
        val email = p.getString("sync_email", null) ?: ""
        val key = p.getString(MapFragment.PREF_SYNC_API_KEY, null) ?: ""
        return email to key
    }

    fun hasSync(ctx: Context): Boolean {
        val (email, key) = credentials(ctx)
        return email.isNotBlank() && key.isNotBlank()
    }

    // --- cache ---

    fun getCached(ctx: Context): FavoritesDocument {
        cachedDoc?.let { return it }
        val raw = prefs(ctx).getString(MapFragment.PREF_LIVE_USERS_FAVORITES_CACHE, null)
        val doc = if (raw.isNullOrBlank()) {
            FavoritesDocument.empty()
        } else {
            try {
                FavoritesDocument.fromJson(JSONObject(raw))
            } catch (e: Exception) {
                Log.w(TAG, "cache parse failed: ${e.message}")
                FavoritesDocument.empty()
            }
        }
        cachedDoc = doc
        return doc
    }

    fun getLimits(): FavoritesLimits = cachedLimits

    private fun setCache(ctx: Context, doc: FavoritesDocument) {
        cachedDoc = doc
        prefs(ctx).edit()
            .putString(MapFragment.PREF_LIVE_USERS_FAVORITES_CACHE, doc.toJson().toString())
            .putInt(MapFragment.PREF_LIVE_USERS_FAVORITES_VERSION, doc.version)
            .apply()
    }

    // --- active group (local state only) ---

    fun getActiveGroupId(ctx: Context): String {
        val doc = getCached(ctx)
        val localPref = prefs(ctx).getString(MapFragment.PREF_LIVE_USERS_ACTIVE_GROUP_ID, null)
        if (localPref != null) return localPref
        return doc.activeGroupId
    }

    /** Set active group locally (also updates cached doc + triggers SharedPrefListener). */
    fun setActiveGroupId(ctx: Context, groupId: String) {
        val doc = getCached(ctx)
        val updated = doc.copy(activeGroupId = groupId)
        setCache(ctx, updated)
        // Write separate pref key to trigger listener
        prefs(ctx).edit()
            .putString(MapFragment.PREF_LIVE_USERS_ACTIVE_GROUP_ID, groupId)
            .apply()
    }

    /** Returns uniqueId set of active group members, or null if "all" / group not found. */
    fun getActiveGroupMembers(ctx: Context): Set<String>? {
        val doc = getCached(ctx)
        val activeId = getActiveGroupId(ctx)
        if (activeId == ACTIVE_ALL) return null
        val group = doc.groups.firstOrNull { it.id == activeId } ?: return null
        return group.members.toSet()
    }

    fun getActiveGroup(ctx: Context): FavoritesGroup? {
        val activeId = getActiveGroupId(ctx)
        if (activeId == ACTIVE_ALL) return null
        return getCached(ctx).groups.firstOrNull { it.id == activeId }
    }

    // --- network ---

    private val writeLock = Any()

    /** Fetch fresh doc from server, update cache on success. Blocking.
     *  Guards against stale overwrite: if server's version < current cached version,
     *  the cache is NOT overwritten (local save() probably completed while fetch was in flight). */
    fun syncFromServer(ctx: Context): FavoritesResult {
        val (email, key) = credentials(ctx)
        if (email.isBlank() || key.isBlank()) return FavoritesResult.NoSync
        val result = FavoritesApi.fetch(email, key)
        if (result is FavoritesResult.Success) {
            synchronized(writeLock) {
                cachedLimits = result.limits
                val current = getCached(ctx)
                if (result.doc.version >= current.version) {
                    setCache(ctx, result.doc)
                } else {
                    Log.w(TAG, "skip stale sync: server v${result.doc.version} < cached v${current.version}")
                }
            }
        }
        return result
    }

    /** Save doc to server (PUT), update cache on success OR on network error
     *  (local-first — offline edits persist until next successful sync). Blocking. */
    fun saveToServer(ctx: Context, doc: FavoritesDocument): FavoritesResult {
        val (email, key) = credentials(ctx)
        if (email.isBlank() || key.isBlank()) {
            // Local-only: just update cache
            synchronized(writeLock) { setCache(ctx, doc) }
            return FavoritesResult.NoSync
        }
        val result = FavoritesApi.save(email, key, doc)
        synchronized(writeLock) {
            when (result) {
                is FavoritesResult.Success -> setCache(ctx, result.doc)
                is FavoritesResult.NetworkError -> setCache(ctx, doc)  // offline: keep local
                else -> { /* AuthError/RateLimit/VersionConflict/DocumentTooLarge — do NOT touch cache */ }
            }
        }
        return result
    }

    // --- helpers for UI edits ---

    /** Persist a local-only edit to the document (updates cache, does NOT call server). */
    fun updateCachedDoc(ctx: Context, doc: FavoritesDocument) {
        setCache(ctx, doc)
    }

    fun invalidateCache() {
        cachedDoc = null
    }
}
