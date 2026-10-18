package com.dongzhongcen.listenstat.settings

import android.content.Context
import android.content.SharedPreferences

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

    companion object {
        private const val NAME = "listenstat_settings"
        const val KEY_AUTO_UPDATE = "auto_update_check"
        const val KEY_LAST_CHECK = "last_update_check_ms"
        const val KEY_WP_BLUR = "wallpaper_blur"
        const val KEY_WP_DIM = "wallpaper_dim"
        const val UPDATE_INTERVAL_MS = 24L * 60 * 60 * 1000
    }
}
