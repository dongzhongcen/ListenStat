package com.dongzhongcen.listenstat.service

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.dongzhongcen.listenstat.data.AppDatabase
import com.dongzhongcen.listenstat.data.PlayDao
import com.dongzhongcen.listenstat.data.PlaySession
import com.dongzhongcen.listenstat.tracking.LiveSegment
import com.dongzhongcen.listenstat.tracking.LiveTracker
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Uses notification-listener permission to attach MediaSessionManager callbacks
 * and accumulate local play time. Nothing is uploaded.
 *
 * Low-power design (event-driven, no polling):
 * - No periodic timer while playing. The start of a segment is kept in memory and
 *   the database is written once, when the segment ends (pause / stop / session gone /
 *   title change / listener disconnect).
 * - Crash/kill safety: when a segment starts, one tiny SharedPreferences entry is
 *   written (async apply()). On the next listener connect, leftover entries are
 *   finalized. End time = process-exit timestamp from ApplicationExitInfo (API 30+);
 *   on older systems, the last checkpoint that was piggybacked on a media event
 *   (throttled, never timer-driven). Conservative: we never invent time.
 * - Exactly one MediaController.Callback per session token; unregistered when the
 *   session disappears and on listener disconnect.
 * - All state handling runs on one background HandlerThread; DB writes on Dispatchers.IO.
 */
class MediaTrackService : NotificationListenerService() {

    private class Segment(
        val id: String,
        val token: MediaSession.Token,
        val packageName: String,
        val appLabel: String,
        val title: String,
        val artist: String,
        val startedAtMs: Long,
        val startedElapsed: Long,
        /** elapsedRealtime when a "transient" state (buffering, seeking...) began, else -1. */
        var transientSinceElapsed: Long = -1
    )

    private data class Tracked(val controller: MediaController, val callback: MediaController.Callback)

    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dao: PlayDao by lazy { AppDatabase.get(this).playDao() }
    private val prefs: SharedPreferences by lazy {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    // Only touched on [handler]'s thread.
    private val tracked = HashMap<MediaSession.Token, Tracked>()
    private val open = HashMap<MediaSession.Token, Segment>()
    private val graceTimers = HashMap<MediaSession.Token, Runnable>()
    private val labelCache = HashMap<String, String>()
    private var lastCheckpointElapsed = 0L
    /** Segment ids whose DB insert is in flight (guards against double-recovery). */
    private val inFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private var sessionManager: MediaSessionManager? = null
    private val listener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        // Delivered on [handler] (passed at registration).
        syncControllers(controllers ?: emptyList())
    }

    override fun onCreate() {
        super.onCreate()
        thread = HandlerThread("ListenStat-track").also { it.start() }
        handler = Handler(thread.looper)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        handler.post {
            recoverPending()
            val mgr = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
            sessionManager = mgr
            val cn = ComponentName(this, MediaTrackService::class.java)
            try {
                mgr.removeOnActiveSessionsChangedListener(listener)
                mgr.addOnActiveSessionsChangedListener(listener, cn, handler)
                syncControllers(mgr.getActiveSessions(cn))
            } catch (_: SecurityException) {
                // Permission not granted yet
            }
        }
    }

    override fun onListenerDisconnected() {
        handler.post { teardown() }
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        handler.post { teardown() }
        thread.quitSafely()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) = Unit
    override fun onNotificationRemoved(sbn: StatusBarNotification?) = Unit

    // ---- controller bookkeeping (handler thread) ----

    private fun teardown() {
        sessionManager?.removeOnActiveSessionsChangedListener(listener)
        sessionManager = null
        tracked.keys.toList().forEach { untrack(it) }
        publishLive()
    }

    private fun syncControllers(controllers: List<MediaController>) {
        val current = controllers.associateBy { it.sessionToken }
        tracked.keys.filter { it !in current }.forEach { untrack(it) }

        current.forEach { (token, controller) ->
            if (token in tracked) return@forEach
            val cb = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) {
                    handleState(token, state)
                }

                override fun onMetadataChanged(metadata: MediaMetadata?) {
                    handleMetadata(token, metadata)
                }

                override fun onSessionDestroyed() {
                    untrack(token)
                    publishLive()
                }
            }
            controller.registerCallback(cb, handler)
            tracked[token] = Tracked(controller, cb)
            handleState(token, controller.playbackState)
        }
        publishLive()
    }

    private fun untrack(token: MediaSession.Token) {
        open[token]?.let { endSegment(it, nowAt(it)) }
        tracked.remove(token)?.let { it.controller.unregisterCallback(it.callback) }
        cancelGrace(token)
    }

    // ---- state machine (handler thread) ----

    private fun handleState(token: MediaSession.Token, state: PlaybackState?) {
        val t = tracked[token] ?: return
        val cur = open[token]
        when (state?.state ?: PlaybackState.STATE_NONE) {
            PlaybackState.STATE_PLAYING -> {
                cancelGrace(token)
                cur?.transientSinceElapsed = -1
                val meta = readMeta(t.controller.metadata)
                if (cur == null) {
                    startSegment(t.controller, meta)
                } else if (cur.title != meta.first || cur.artist != meta.second) {
                    endSegment(cur, nowAt(cur))
                    startSegment(t.controller, meta)
                }
            }
            in TRANSIENT_STATES -> {
                // Briefly still "playing" (buffering / seeking / skipping). If it lasts
                // longer than the grace period we close the segment at the moment it began.
                if (cur != null && cur.transientSinceElapsed < 0) {
                    cur.transientSinceElapsed = SystemClock.elapsedRealtime()
                    scheduleGrace(token)
                }
            }
            else -> if (cur != null) {
                cancelGrace(token)
                endSegment(cur, nowAt(cur))
            }
        }
        maybeCheckpoint()
        publishLive()
    }

    private fun handleMetadata(token: MediaSession.Token, metadata: MediaMetadata?) {
        val t = tracked[token] ?: return
        val cur = open[token] ?: return // not playing: nothing to split
        val meta = readMeta(metadata)
        if (cur.title == meta.first && cur.artist == meta.second) return // artwork/duration update
        val wasTransient = cur.transientSinceElapsed >= 0
        cancelGrace(token)
        endSegment(cur, nowAt(cur))
        val next = startSegment(t.controller, meta)
        if (wasTransient && t.controller.playbackState?.state != PlaybackState.STATE_PLAYING) {
            next.transientSinceElapsed = SystemClock.elapsedRealtime()
            scheduleGrace(token)
        }
        publishLive()
    }

    private fun scheduleGrace(token: MediaSession.Token) {
        cancelGrace(token)
        val r = Runnable {
            graceTimers.remove(token)
            val cur = open[token] ?: return@Runnable
            if (cur.transientSinceElapsed >= 0) {
                endSegment(cur, wallAt(cur, cur.transientSinceElapsed))
                publishLive()
            }
        }
        graceTimers[token] = r
        handler.postDelayed(r, TRANSIENT_GRACE_MS) // one-shot, only while buffering
    }

    private fun cancelGrace(token: MediaSession.Token) {
        graceTimers.remove(token)?.let { handler.removeCallbacks(it) }
    }

    private fun startSegment(controller: MediaController, meta: Pair<String, String>): Segment {
        val seg = Segment(
            id = UUID.randomUUID().toString(),
            token = controller.sessionToken,
            packageName = controller.packageName,
            appLabel = appLabel(controller.packageName),
            title = meta.first,
            artist = meta.second,
            startedAtMs = System.currentTimeMillis(),
            startedElapsed = SystemClock.elapsedRealtime()
        )
        open[seg.token] = seg
        // The only "in progress" disk write: one small async prefs entry per segment.
        prefs.edit().putString(KEY_SEG_PREFIX + seg.id, seg.toJson().toString()).apply()
        return seg
    }

    private fun endSegment(seg: Segment, endedAtMs: Long) {
        open.remove(seg.token)
        persist(seg.id, seg.packageName, seg.appLabel, seg.title, seg.artist, seg.startedAtMs, endedAtMs)
    }

    /** Writes the finished segment (split at local midnight) and clears its prefs entry. */
    private fun persist(
        id: String, pkg: String, label: String, title: String, artist: String,
        startMs: Long, endMs: Long
    ) {
        if (endMs - startMs < MIN_SEGMENT_MS) {
            prefs.edit().remove(KEY_SEG_PREFIX + id).apply()
            return
        }
        if (!inFlight.add(id)) return
        val rows = splitByDay(pkg, label, title, artist, startMs, endMs)
        io.launch {
            try {
                dao.insertAll(rows)
                prefs.edit().remove(KEY_SEG_PREFIX + id).apply()
            } catch (_: Exception) {
                // keep prefs entry; it will be retried on next connect
            } finally {
                inFlight.remove(id)
            }
        }
    }

    private fun splitByDay(
        pkg: String, label: String, title: String, artist: String, startMs: Long, endMs: Long
    ): List<PlaySession> {
        val zone = ZoneId.systemDefault()
        val out = ArrayList<PlaySession>(1)
        var s = startMs
        while (s < endMs) {
            val date = Instant.ofEpochMilli(s).atZone(zone).toLocalDate()
            val nextMidnight = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val e = minOf(endMs, nextMidnight)
            out += PlaySession(
                packageName = pkg,
                appLabel = label,
                title = title,
                artist = artist,
                dayKey = date.format(DAY_FMT),
                startedAtMs = s,
                endedAtMs = e,
                durationMs = e - s
            )
            s = e
        }
        return out
    }

    // ---- crash / kill recovery ----

    private fun recoverPending() {
        val openIds = open.values.map { it.id }.toSet()
        val entries = prefs.all.filterKeys {
            it.startsWith(KEY_SEG_PREFIX) &&
                it.removePrefix(KEY_SEG_PREFIX).let { id -> id !in openIds && id !in inFlight }
        }
        if (entries.isEmpty()) return
        val now = System.currentTimeMillis()
        val exitTimes = processExitTimes()
        val checkpoint = prefs.getLong(KEY_CHECKPOINT, 0L)
        entries.forEach { (key, value) ->
            val id = key.removePrefix(KEY_SEG_PREFIX)
            val json = runCatching { JSONObject(value as String) }.getOrNull()
            if (json == null) {
                prefs.edit().remove(key).apply(); return@forEach
            }
            val start = json.optLong("start")
            val end = (exitTimes.filter { it >= start }.minOrNull()
                ?: checkpoint.takeIf { it >= start }
                ?: start).coerceAtMost(now)
            persist(
                id, json.optString("pkg"), json.optString("label"),
                json.optString("title"), json.optString("artist"), start, end
            )
        }
    }

    private fun processExitTimes(): List<Long> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
        return try {
            val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
            am.getHistoricalProcessExitReasons(null, 0, 8).map { it.timestamp }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Pre-Android 11 fallback only; piggybacks on media events, never on a timer. */
    private fun maybeCheckpoint() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R || open.isEmpty()) return
        val nowEl = SystemClock.elapsedRealtime()
        if (nowEl - lastCheckpointElapsed < CHECKPOINT_MIN_INTERVAL_MS) return
        lastCheckpointElapsed = nowEl
        prefs.edit().putLong(KEY_CHECKPOINT, System.currentTimeMillis()).apply()
    }

    // ---- helpers ----

    private fun publishLive() {
        LiveTracker.publish(open.values.map {
            LiveSegment(it.packageName, it.appLabel, it.title, it.startedAtMs)
        })
    }

    /** Wall-clock end computed from the monotonic clock (robust to clock changes). */
    private fun nowAt(seg: Segment) = wallAt(seg, SystemClock.elapsedRealtime())

    private fun wallAt(seg: Segment, elapsed: Long) =
        seg.startedAtMs + (elapsed - seg.startedElapsed).coerceAtLeast(0)

    private fun readMeta(meta: MediaMetadata?): Pair<String, String> {
        val title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
            .ifBlank { meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE).orEmpty() }
        val artist = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
            .ifBlank { meta?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST).orEmpty() }
        return title to artist
    }

    private fun Segment.toJson() = JSONObject()
        .put("pkg", packageName).put("label", appLabel)
        .put("title", title).put("artist", artist).put("start", startedAtMs)

    private fun appLabel(pkg: String): String = labelCache.getOrPut(pkg) {
        try {
            val pm = packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            pkg
        }
    }

    private companion object {
        const val PREFS = "listenstat_tracking"
        const val KEY_SEG_PREFIX = "seg:"
        const val KEY_CHECKPOINT = "checkpoint"
        const val MIN_SEGMENT_MS = 3_000L
        const val TRANSIENT_GRACE_MS = 10_000L
        const val CHECKPOINT_MIN_INTERVAL_MS = 5 * 60_000L
        val DAY_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        val TRANSIENT_STATES = setOf(
            PlaybackState.STATE_BUFFERING,
            PlaybackState.STATE_CONNECTING,
            PlaybackState.STATE_FAST_FORWARDING,
            PlaybackState.STATE_REWINDING,
            PlaybackState.STATE_SKIPPING_TO_NEXT,
            PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
            PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM
        )
    }
}
