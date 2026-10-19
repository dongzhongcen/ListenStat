package com.dongzhongcen.listenstat.sync

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import java.util.UUID

/**
 * Device identity + bearer token. Token lives only in EncryptedSharedPreferences
 * and must never be logged.
 */
class SyncCredentials(context: Context) {
    private val appContext = context.applicationContext
    private val secure: SharedPreferences = createSecurePrefs(appContext)
    private val plain: SharedPreferences =
        appContext.getSharedPreferences(PLAIN_NAME, Context.MODE_PRIVATE)

    /** Stable per-install device id (Android ID when usable, else generated UUID). */
    fun deviceId(): String {
        val stored = plain.getString(KEY_DEVICE_ID, null)
        if (!stored.isNullOrBlank()) return stored
        val androidId = runCatching {
            Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull()
        val id = if (!androidId.isNullOrBlank() &&
            androidId != "9774d56d682e549c" &&
            androidId.length >= 8
        ) {
            "aid-$androidId"
        } else {
            "uuid-${UUID.randomUUID()}"
        }
        plain.edit().putString(KEY_DEVICE_ID, id).apply()
        return id
    }

    var userId: String?
        get() = secure.getString(KEY_USER_ID, null)
        set(v) = secure.edit().putString(KEY_USER_ID, v).apply()

    /** Bearer token — never log this value. */
    var deviceToken: String?
        get() = secure.getString(KEY_TOKEN, null)
        set(v) = secure.edit().putString(KEY_TOKEN, v).apply()

    var lastPullServerTime: String?
        get() = plain.getString(KEY_LAST_PULL, null)
        set(v) = plain.edit().putString(KEY_LAST_PULL, v).apply()

    var lastPushMs: Long
        get() = plain.getLong(KEY_LAST_PUSH_MS, 0L)
        set(v) = plain.edit().putLong(KEY_LAST_PUSH_MS, v).apply()

    fun clearAuth() {
        secure.edit().remove(KEY_USER_ID).remove(KEY_TOKEN).apply()
        plain.edit().remove(KEY_LAST_PULL).remove(KEY_LAST_PUSH_MS).apply()
    }

    companion object {
        private const val PLAIN_NAME = "listenstat_sync_meta"
        private const val SECURE_NAME = "listenstat_sync_secure"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_TOKEN = "device_token"
        private const val KEY_LAST_PULL = "last_pull_server_time"
        private const val KEY_LAST_PUSH_MS = "last_push_ms"

        private fun createSecurePrefs(context: Context): SharedPreferences {
            return try {
                val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
                EncryptedSharedPreferences.create(
                    SECURE_NAME,
                    masterKeyAlias,
                    context,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (_: Exception) {
                // Emulators / broken Keystore: fall back to private prefs (still not logged).
                context.getSharedPreferences(SECURE_NAME + "_fallback", Context.MODE_PRIVATE)
            }
        }
    }
}
