package com.dongzhongcen.listenstat.sync

import android.content.Context
import android.util.Log
import com.dongzhongcen.listenstat.data.AppDatabase
import com.dongzhongcen.listenstat.data.PlaySession
import com.dongzhongcen.listenstat.settings.AppSettings
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Optional cloud sync. When [AppSettings.cloudSyncEnabled] is false, every public
 * entry point returns immediately and makes **no** network calls.
 *
 * Merge strategy (documented for the PR):
 * - Push: local day aggregates (SUM duration by package+title+day) upserted to the server.
 * - Pull: for each remote row, if remote.duration_ms > local SUM for the same
 *   (package, title, day), insert/extend a synthetic PlaySession for the gap only.
 *   Never deletes or shortens local rows → local-only listening is preserved;
 *   effective total is max(local, remote) after merge.
 * - Wallpaper images are never uploaded.
 *
 * All public methods are blocking / suspend-friendly — call from Dispatchers.IO.
 */
class SyncManager(context: Context) {
    private val appContext = context.applicationContext
    private val settings = AppSettings(appContext)
    private val credentials = SyncCredentials(appContext)
    private val db = AppDatabase.get(appContext)
    private val api = SyncApi { settings.syncBaseUrl.ifBlank { SyncApi.DEFAULT_BASE_URL } }
    private val busy = AtomicBoolean(false)

    sealed class Outcome {
        data object Skipped : Outcome()
        data class Ok(val detail: String) : Outcome()
        data class Err(val message: String) : Outcome()
    }

    /** Register (if needed), full pull+merge, then push all local aggregates. */
    suspend fun enableAndSync(): Outcome {
        if (!settings.cloudSyncEnabled) return Outcome.Skipped
        if (!busy.compareAndSet(false, true)) return Outcome.Skipped
        return try {
            ensureRegistered()
            val pulled = pullAndMerge(full = true)
            val pushed = pushAll()
            Outcome.Ok("拉取 $pulled · 推送 $pushed")
        } catch (e: Exception) {
            Log.w(TAG, "enableAndSync failed: ${e.javaClass.simpleName}")
            Outcome.Err(friendly(e))
        } finally {
            busy.set(false)
        }
    }

    /** Lightweight push used when leaving UI / daily. No-op when sync is off. */
    suspend fun maybePush(): Outcome {
        if (!settings.cloudSyncEnabled) return Outcome.Skipped
        if (credentials.deviceToken.isNullOrBlank()) return Outcome.Skipped
        if (!busy.compareAndSet(false, true)) return Outcome.Skipped
        return try {
            val n = pushAll()
            Outcome.Ok("推送 $n")
        } catch (e: Exception) {
            Log.w(TAG, "maybePush failed: ${e.javaClass.simpleName}")
            Outcome.Err(friendly(e))
        } finally {
            busy.set(false)
        }
    }

    /** Push if last push was more than 24h ago (and sync is on). */
    suspend fun maybeDailyPush(): Outcome {
        if (!settings.cloudSyncEnabled) return Outcome.Skipped
        val now = System.currentTimeMillis()
        if (now - credentials.lastPushMs < DAY_MS) return Outcome.Skipped
        return maybePush()
    }

    suspend fun patchDisplayName(name: String): Outcome {
        if (!settings.cloudSyncEnabled) return Outcome.Skipped
        val token = credentials.deviceToken ?: return Outcome.Skipped
        if (name.isBlank()) return Outcome.Skipped
        return try {
            api.patchMe(token, name)
            Outcome.Ok("name")
        } catch (e: Exception) {
            Log.w(TAG, "patchDisplayName failed: ${e.javaClass.simpleName}")
            Outcome.Err(friendly(e))
        }
    }

    private fun ensureRegistered() {
        val existing = credentials.deviceToken
        if (!existing.isNullOrBlank() && !credentials.userId.isNullOrBlank()) return
        val name = settings.displayName.trim().ifBlank { null }
        val result = api.register(credentials.deviceId(), name)
        credentials.userId = result.userId
        credentials.deviceToken = result.deviceToken
        if (settings.displayName.isBlank() && result.displayName.isNotBlank()) {
            settings.displayName = result.displayName
        }
    }

    private suspend fun pushAll(): Int {
        val token = credentials.deviceToken ?: throw IllegalStateException("not registered")
        val aggregates = db.playDao().dayAggregatesForSync()
        var upserted = 0
        aggregates.chunked(SyncApi.PUSH_BATCH).forEach { chunk ->
            val records = chunk.map { row ->
                SyncDayRecord(
                    recordId = SyncApi.recordId(row.packageName, row.title, row.dayKey),
                    packageName = row.packageName,
                    title = row.title,
                    day = row.dayKey,
                    durationMs = row.totalMs.coerceAtLeast(0L)
                )
            }
            val result = api.push(token, records)
            upserted += result.upserted
        }
        credentials.lastPushMs = System.currentTimeMillis()
        return upserted
    }

    private suspend fun pullAndMerge(full: Boolean): Int {
        val token = credentials.deviceToken ?: throw IllegalStateException("not registered")
        val since = if (full) null else credentials.lastPullServerTime
        val result = api.pull(token, since)
        var merged = 0
        for (remote in result.records) {
            if (mergeOne(remote)) merged++
        }
        if (result.serverTime.isNotBlank()) credentials.lastPullServerTime = result.serverTime
        return merged
    }

    /**
     * If remote duration exceeds local SUM for (package, title, day), insert a
     * synthetic session for the positive gap. Returns true when a row was written.
     */
    private suspend fun mergeOne(remote: SyncDayRecord): Boolean {
        val local = db.playDao().sumDuration(remote.packageName, remote.title, remote.day) ?: 0L
        val gap = remote.durationMs - local
        if (gap <= 0L) return false
        val start = syntheticStartMs(remote.recordId)
        val existing = db.playDao().findByKey(start, remote.packageName, remote.title)
        if (existing != null) {
            val newDuration = existing.durationMs + gap
            existing.durationMs = newDuration
            existing.endedAtMs = start + newDuration
            db.playDao().update(existing)
        } else {
            db.playDao().insert(
                PlaySession(
                    packageName = remote.packageName,
                    appLabel = remote.packageName,
                    title = remote.title,
                    artist = "",
                    dayKey = remote.day,
                    startedAtMs = start,
                    endedAtMs = start + gap,
                    durationMs = gap
                )
            )
        }
        return true
    }

    private fun friendly(e: Exception): String = when (e) {
        is SyncApi.ApiException -> e.message ?: "HTTP ${e.code}"
        else -> e.message ?: e.javaClass.simpleName
    }

    companion object {
        private const val TAG = "ListenStatSync"
        private const val DAY_MS = 24L * 60 * 60 * 1000

        /** Stable synthetic startedAtMs derived from record_id. */
        fun syntheticStartMs(recordId: String): Long {
            val u = runCatching { UUID.fromString(recordId) }.getOrNull()
            val bits = u?.mostSignificantBits?.xor(u.leastSignificantBits) ?: recordId.hashCode().toLong()
            return 1_000_000_000_000L + (bits and 0x7FFF_FFFF_FFFFL) % 800_000_000_000L
        }
    }
}
