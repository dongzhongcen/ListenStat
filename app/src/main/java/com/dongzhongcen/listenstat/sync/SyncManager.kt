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

    sealed class PairCreateOutcome {
        data object Skipped : PairCreateOutcome()
        data class Ok(val code: String, val expiresAt: String) : PairCreateOutcome()
        data class Err(val message: String) : PairCreateOutcome()
    }

    sealed class PairClaimOutcome {
        data object Skipped : PairClaimOutcome()
        data class Ok(val mergedRecords: Int, val detail: String) : PairClaimOutcome()
        data class Err(val message: String) : PairClaimOutcome()
    }

    /** Register (if needed), full pull+merge, then push all local aggregates. */
    suspend fun enableAndSync(): Outcome {
        if (!settings.cloudSyncEnabled) return Outcome.Skipped
        if (!busy.compareAndSet(false, true)) return Outcome.Skipped
        return try {
            ensureRegistered()
            refreshMe()
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
            applyMe(api.patchMe(token, name))
            Outcome.Ok("name")
        } catch (e: Exception) {
            Log.w(TAG, "patchDisplayName failed: ${e.javaClass.simpleName}")
            Outcome.Err(friendly(e))
        }
    }

    /** Generate a short-lived pair code for another device to claim. Requires sync on. */
    suspend fun createPairCode(): PairCreateOutcome {
        if (!settings.cloudSyncEnabled) return PairCreateOutcome.Skipped
        return try {
            ensureRegistered()
            val token = credentials.deviceToken ?: return PairCreateOutcome.Skipped
            val result = api.pairCreate(token)
            // Never log the bearer token; code itself is short-lived and shown in UI.
            PairCreateOutcome.Ok(code = result.code, expiresAt = result.expiresAt)
        } catch (e: Exception) {
            Log.w(TAG, "createPairCode failed: ${e.javaClass.simpleName}")
            PairCreateOutcome.Err(friendly(e))
        }
    }

    /**
     * Claim a pair code from another device, merge into that account, then sync.
     * Enables cloud sync if the caller already turned it on; updates local user id.
     */
    suspend fun claimPairCode(rawCode: String): PairClaimOutcome {
        if (!settings.cloudSyncEnabled) return PairClaimOutcome.Skipped
        val code = rawCode.trim()
        if (code.isBlank()) return PairClaimOutcome.Err("请输入配对码")
        if (!busy.compareAndSet(false, true)) return PairClaimOutcome.Skipped
        return try {
            ensureRegistered()
            val token = credentials.deviceToken ?: return PairClaimOutcome.Skipped
            val result = api.pairClaim(token, code)
            credentials.userId = result.userId
            credentials.paired = true
            credentials.accountUserId = result.userId
            settings.paired = true
            if (result.displayName.isNotBlank() && settings.displayName.isBlank()) {
                settings.displayName = result.displayName
            }
            refreshMe()
            val pulled = pullAndMerge(full = true)
            val pushed = pushAll()
            val detail = "合并 ${result.mergedRecords} · 拉取 $pulled · 推送 $pushed"
            PairClaimOutcome.Ok(mergedRecords = result.mergedRecords, detail = detail)
        } catch (e: Exception) {
            Log.w(TAG, "claimPairCode failed: ${e.javaClass.simpleName}")
            PairClaimOutcome.Err(friendly(e))
        } finally {
            busy.set(false)
        }
    }

    fun isPaired(): Boolean = credentials.paired || settings.paired

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

    private fun refreshMe() {
        val token = credentials.deviceToken ?: return
        try {
            applyMe(api.getMe(token))
        } catch (e: Exception) {
            Log.w(TAG, "refreshMe failed: ${e.javaClass.simpleName}")
        }
    }

    private fun applyMe(me: MeResult) {
        credentials.userId = me.userId
        credentials.paired = me.paired
        credentials.accountUserId = me.accountUserId
        settings.paired = me.paired
        if (me.displayName.isNotBlank() && settings.displayName.isBlank()) {
            settings.displayName = me.displayName
        }
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
