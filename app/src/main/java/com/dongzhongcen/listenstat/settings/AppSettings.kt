package com.dongzhongcen.listenstat.settings

import android.content.Context
import android.content.SharedPreferences
import com.dongzhongcen.listenstat.sync.SyncApi

/** Small UI/settings store (separate from the tracking service's prefs). */
class AppSettings(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    var autoUpdateCheck: Boolean
        get() = prefs.getBoolean(KEY_AUTO_UPDATE, true)
        set(v) = prefs.edit().putBoolean(KEY_AUTO_UPDATE, v).apply()

    var lastUpdateCheckMs: Long
        get() = prefs.getLong(KEY_LAST_CHECK, 0L)
        set(v) = prefs.edit().putLong(KEY_LAST_CHECK, v).apply()

    /** Blur radius in dp, 0..25. */
    var wallpaperBlur: Int
        get() = prefs.getInt(KEY_WP_BLUR, 12)
        set(v) = prefs.edit().putInt(KEY_WP_BLUR, v.coerceIn(0, 25)).apply()

    /** Dim percentage, 0..80. */
    var wallpaperDim: Int
        get() = prefs.getInt(KEY_WP_DIM, 20)
        set(v) = prefs.edit().putInt(KEY_WP_DIM, v.coerceIn(0, 80)).apply()

    /**
     * Optional cloud sync. Default **off**. When false the sync client must not
     * open any network connection for sync (update-check remains separate).
     */
    var cloudSyncEnabled: Boolean
        get() = prefs.getBoolean(KEY_CLOUD_SYNC, false)
        set(v) = prefs.edit().putBoolean(KEY_CLOUD_SYNC, v).apply()

    /** Optional display name sent on register / PATCH /v1/me. */
    var displayName: String
        get() = prefs.getString(KEY_DISPLAY_NAME, "") ?: ""
        set(v) = prefs.edit().putString(KEY_DISPLAY_NAME, v.trim().take(128)).apply()

    /**
     * Sync API base URL (no trailing slash). Defaults to the production HTTPS host.
     * Debug builds may use `http://IP` for lab testing (cleartext allowed only in debug).
     */
    var syncBaseUrl: String
        get() {
            val stored = prefs.getString(KEY_SYNC_BASE, null)?.trim().orEmpty()
            return stored.ifBlank { SyncApi.DEFAULT_BASE_URL }
        }
        set(v) {
            val cleaned = v.trim().trimEnd('/')
            prefs.edit().putString(KEY_SYNC_BASE, cleaned.ifBlank { SyncApi.DEFAULT_BASE_URL }).apply()
        }

    companion object {
        private const val NAME = "listenstat_settings"
        const val KEY_AUTO_UPDATE = "auto_update_check"
        const val KEY_LAST_CHECK = "last_update_check_ms"
        const val KEY_WP_BLUR = "wallpaper_blur"
        const val KEY_WP_DIM = "wallpaper_dim"
        const val KEY_CLOUD_SYNC = "cloud_sync_enabled"
        const val KEY_DISPLAY_NAME = "display_name"
        const val KEY_SYNC_BASE = "sync_base_url"
        const val UPDATE_INTERVAL_MS = 24L * 60 * 60 * 1000
    }
}
