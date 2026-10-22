package com.dongzhongcen.listenstat.service

import android.app.ActivityManager
import android.app.Notification
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
import android.os.Parcelable
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
 * (and media-style notification session tokens as a supplement) and accumulate
 * local play time for any app that publishes a system media session. Skips this
 * app's own package. Nothing is uploaded.
 *
 * Low-power design (event-driven, no polling):
 * - No periodic timer while playing. The start of a segment is kept in memory and
 *   the database is written once, when the segment ends (pause / stop / session gone /
 *   title or mediaId change / position rewind after a track / near natural end /
 *   listener disconnect).
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
        /** Stable track id from MediaMetadata when the player provides one. */
        var mediaId: String = "",
        /** Last observed playback position (ms) while this segment was open. */
        var lastPositionMs: Long = 0,
        /** Track duration from metadata when known; 0 if unknown. */
        var durationMs: Long = 0,
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
    private val nearEndTimers = HashMap<MediaSession.Token, Runnable>()
    /** After near-end close while still "playing", watch for the next item to start. */
    private val orphanWatches = HashMap<MediaSession.Token, Runnable>()
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

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val n = sbn ?: return
        if (n.packageName == packageName) return
        handler.post { maybeTrackFromNotification(n) }
    }

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
        tracked.keys.filter { it !in current }.forEach { token ->
            // Keep sessions discovered via media-style notifications until they
            // stop / destroy; getActiveSessions may lag behind the notification.
            val state = tracked[token]?.controller?.playbackState?.state
            if (state == PlaybackState.STATE_PLAYING || state in TRANSIENT_STATES) return@forEach
            untrack(token)
        }

        current.forEach { (_, controller) ->
            trackController(controller)
        }
        publishLive()
    }

    /**
     * Register one MediaController. Skips ListenStat itself. Also used when a
     * media-style notification exposes a session token that getActiveSessions
     * had not yet delivered.
     */
    private fun trackController(controller: MediaController) {
        val token = controller.sessionToken
        if (token in tracked) return
        if (controller.packageName == packageName) return
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

    /**
     * Small supplement: some players put a MediaSession token on a media-style
     * notification before (or without) it showing up in getActiveSessions.
     * Stay on MediaSession + NotificationListener — no audio capture.
     */
    private fun maybeTrackFromNotification(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val token = mediaSessionToken(sbn.notification) ?: return
        if (token in tracked) {
            // Refresh state: some OEMs drop MediaController callbacks while the
            // notification keeps updating. Re-drive the state machine from the
            // latest controller snapshot so recording can resume without a reopen.
            val t = tracked[token] ?: return
            handleState(token, t.controller.playbackState)
            return
        }
        try {
            trackController(MediaController(this, token))
            publishLive()
        } catch (_: Exception) {
            // Token invalid / session already gone
        }
    }

    @Suppress("DEPRECATION")
    private fun mediaSessionToken(notification: Notification): MediaSession.Token? {
        val extras = notification.extras ?: return null
        val raw: Parcelable? = extras.getParcelable(Notification.EXTRA_MEDIA_SESSION)
        return raw as? MediaSession.Token
    }

    private fun untrack(token: MediaSession.Token) {
        open[token]?.let { endSegment(it, nowAt(it)) }
        tracked.remove(token)?.let { it.controller.unregisterCallback(it.callback) }
        cancelGrace(token)
        cancelNearEnd(token)
        cancelOrphanWatch(token)
    }

    // ---- state machine (handler thread) ----

    private fun handleState(token: MediaSession.Token, state: PlaybackState?) {
        val t = tracked[token] ?: return
        val cur = open[token]
        when (state?.state ?: PlaybackState.STATE_NONE) {
            PlaybackState.STATE_PLAYING -> {
                cancelGrace(token)
                cancelOrphanWatch(token)
                cur?.transientSinceElapsed = -1
                val meta = resolvedMeta(t.controller, t.controller.metadata)
                val mediaId = mediaIdOf(t.controller.metadata)
                val pos = positionOf(state)
                val dur = durationOf(t.controller.metadata)
                if (cur == null) {
                    startSegment(t.controller, meta, mediaId, pos, dur)
                } else if (shouldSplit(cur, meta, mediaId, pos)) {
                    cancelNearEnd(token)
                    endSegment(cur, nowAt(cur))
                    startSegment(t.controller, meta, mediaId, pos, dur)
                } else {
                    rememberProgress(cur, mediaId, pos, dur)
                    scheduleNearEnd(token, cur)
                }
            }
            in TRANSIENT_STATES -> {
                // Briefly still "playing" (buffering / seeking / skipping). If it lasts
                // longer than the grace period we close the segment at the moment it began.
                if (cur != null) {
                    val pos = positionOf(state)
                    if (pos > 0) cur.lastPositionMs = maxOf(cur.lastPositionMs, pos)
                    if (cur.transientSinceElapsed < 0) {
                        cur.transientSinceElapsed = SystemClock.elapsedRealtime()
                        scheduleGrace(token)
                    }
                }
            }
            else -> if (cur != null) {
                cancelGrace(token)
                cancelNearEnd(token)
                endSegment(cur, nowAt(cur))
            }
        }
        maybeCheckpoint()
        publishLive()
    }

    private fun handleMetadata(token: MediaSession.Token, metadata: MediaMetadata?) {
        val t = tracked[token] ?: return
        val meta = resolvedMeta(t.controller, metadata)
        val mediaId = mediaIdOf(metadata)
        val pos = positionOf(t.controller.playbackState)
        val dur = durationOf(metadata)
        val state = t.controller.playbackState?.state ?: PlaybackState.STATE_NONE
        val playing = state == PlaybackState.STATE_PLAYING || state in TRANSIENT_STATES
        val cur = open[token]
        if (cur == null) {
            // Near-end / grace may have closed the segment while the session stayed
            // PLAYING. The next item often arrives as metadata-only (no state change),
            // which used to be ignored — recording then stayed dead until reopen.
            if (playing) {
                cancelOrphanWatch(token)
                startSegment(t.controller, meta, mediaId, pos, dur)
                publishLive()
            }
            return
        }
        if (!shouldSplit(cur, meta, mediaId, pos)) {
            // Artwork-only / duration refresh — keep the same segment, refresh progress.
            rememberProgress(cur, mediaId, pos, dur)
            scheduleNearEnd(token, cur)
            return
        }
        val wasTransient = cur.transientSinceElapsed >= 0
        cancelGrace(token)
        cancelNearEnd(token)
        cancelOrphanWatch(token)
        endSegment(cur, nowAt(cur))
        val next = startSegment(t.controller, meta, mediaId, pos, dur)
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
                cancelNearEnd(token)
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

    /**
     * Split when the player advances to another item without going through pause:
     * different mediaId, different title/artist, large position rewind, or
     * restart near 0 after having been near the natural end.
     */
    private fun shouldSplit(
        cur: Segment,
        meta: Pair<String, String>,
        mediaId: String,
        pos: Long
    ): Boolean {
        if (cur.title != meta.first || cur.artist != meta.second) return true
        if (mediaId.isNotBlank() && cur.mediaId.isNotBlank() && mediaId != cur.mediaId) return true
        if (cur.lastPositionMs >= REWIND_ANCHOR_MS && pos + REWIND_DROP_MS < cur.lastPositionMs) return true
        if (cur.durationMs >= MIN_DURATION_FOR_END_MS &&
            cur.lastPositionMs >= cur.durationMs - NEAR_END_MS &&
            pos <= RESTART_POS_MS
        ) {
            return true
        }
        return false
    }

    private fun rememberProgress(cur: Segment, mediaId: String, pos: Long, dur: Long) {
        if (mediaId.isNotBlank()) cur.mediaId = mediaId
        if (dur > 0) cur.durationMs = dur
        if (pos > cur.lastPositionMs) cur.lastPositionMs = pos
        else if (pos > 0 && cur.lastPositionMs == 0L) cur.lastPositionMs = pos
    }

    /**
     * One-shot timer near the expected track end. Closes the segment when the player
     * stays "playing" at the end or has already jumped to the next item — without a
     * standing poll loop while idle mid-track.
     */
    private fun scheduleNearEnd(token: MediaSession.Token, seg: Segment) {
        if (seg.durationMs < MIN_DURATION_FOR_END_MS) return
        val remain = seg.durationMs - seg.lastPositionMs
        if (remain !in 1..NEAR_END_SCHEDULE_MAX_MS) return
        cancelNearEnd(token)
        val expectedId = seg.id
        val r = Runnable {
            nearEndTimers.remove(token)
            val cur = open[token] ?: return@Runnable
            if (cur.id != expectedId) return@Runnable
            val t = tracked[token] ?: return@Runnable
            val st = t.controller.playbackState
            val pos = positionOf(st)
            val meta = resolvedMeta(t.controller, t.controller.metadata)
            val mediaId = mediaIdOf(t.controller.metadata)
            val playing = st?.state == PlaybackState.STATE_PLAYING ||
                (st?.state ?: PlaybackState.STATE_NONE) in TRANSIENT_STATES
            when {
                !playing -> {
                    cancelNearEnd(token)
                    endSegment(cur, nowAt(cur))
                }
                shouldSplit(cur, meta, mediaId, pos) || pos >= cur.durationMs - NEAR_END_MS -> {
                    val prevId = cur.mediaId
                    val prevTitle = cur.title
                    val prevArtist = cur.artist
                    cancelNearEnd(token)
                    endSegment(cur, nowAt(cur))
                    // Only open the next segment once the player actually moved on
                    // (new id/title or position back near the start). Sitting at the
                    // natural end while still "playing" must not loop forever.
                    val movedOn = (mediaId.isNotBlank() && prevId.isNotBlank() && mediaId != prevId) ||
                        meta.first != prevTitle || meta.second != prevArtist ||
                        pos <= RESTART_POS_MS
                    if (playing && movedOn) {
                        val next = startSegment(
                            t.controller, meta, mediaId, pos,
                            durationOf(t.controller.metadata)
                        )
                        if ((st?.state ?: PlaybackState.STATE_NONE) in TRANSIENT_STATES) {
                            next.transientSinceElapsed = SystemClock.elapsedRealtime()
                            scheduleGrace(token)
                        }
                    } else if (playing) {
                        // Still "playing" at the natural end with no next item yet.
                        // Watch briefly so a quiet restart (same session, no pause)
                        // reopens a segment without requiring the user to reopen the app.
                        scheduleOrphanWatch(token, attempts = 0)
                    }
                }
                else -> {
                    rememberProgress(cur, mediaId, pos, durationOf(t.controller.metadata))
                    scheduleNearEnd(token, cur)
                }
            }
            publishLive()
        }
        nearEndTimers[token] = r
        handler.postDelayed(r, remain + NEAR_END_SLACK_MS)
    }

    private fun cancelNearEnd(token: MediaSession.Token) {
        nearEndTimers.remove(token)?.let { handler.removeCallbacks(it) }
    }

    /**
     * After a near-end close left no open segment while the session is still
     * PLAYING, poll briefly for a position restart (common when the player
     * auto-advances without a metadata callback). Bounded — not a standing loop.
     */
    private fun scheduleOrphanWatch(token: MediaSession.Token, attempts: Int) {
        cancelOrphanWatch(token)
        if (attempts > ORPHAN_WATCH_MAX_ATTEMPTS) return
        val r = Runnable {
            orphanWatches.remove(token)
            if (open.containsKey(token)) return@Runnable
            val t = tracked[token] ?: return@Runnable
            val st = t.controller.playbackState
            val state = st?.state ?: PlaybackState.STATE_NONE
            if (state != PlaybackState.STATE_PLAYING && state !in TRANSIENT_STATES) return@Runnable
            val pos = positionOf(st)
            val meta = resolvedMeta(t.controller, t.controller.metadata)
            val mediaId = mediaIdOf(t.controller.metadata)
            if (pos <= RESTART_POS_MS) {
                startSegment(
                    t.controller, meta, mediaId, pos,
                    durationOf(t.controller.metadata)
                )
                if (state in TRANSIENT_STATES) {
                    open[token]?.transientSinceElapsed = SystemClock.elapsedRealtime()
                    scheduleGrace(token)
                }
                publishLive()
            } else {
                scheduleOrphanWatch(token, attempts + 1)
            }
        }
        orphanWatches[token] = r
        handler.postDelayed(r, ORPHAN_WATCH_INTERVAL_MS)
    }

    private fun cancelOrphanWatch(token: MediaSession.Token) {
        orphanWatches.remove(token)?.let { handler.removeCallbacks(it) }
    }

    private fun startSegment(
        controller: MediaController,
        meta: Pair<String, String>,
        mediaId: String = mediaIdOf(controller.metadata),
        positionMs: Long = positionOf(controller.playbackState),
        durationMs: Long = durationOf(controller.metadata)
    ): Segment {
        val label = appLabel(controller.packageName)
        val seg = Segment(
            id = UUID.randomUUID().toString(),
            token = controller.sessionToken,
            packageName = controller.packageName,
            appLabel = label,
            title = meta.first,
            artist = meta.second,
            startedAtMs = System.currentTimeMillis(),
            startedElapsed = SystemClock.elapsedRealtime(),
            mediaId = mediaId,
            lastPositionMs = positionMs.coerceAtLeast(0),
            durationMs = durationMs.coerceAtLeast(0)
        )
        open[seg.token] = seg
        cancelOrphanWatch(seg.token)
        // The only "in progress" disk write: one small async prefs entry per segment.
        prefs.edit().putString(KEY_SEG_PREFIX + seg.id, seg.toJson().toString()).apply()
        scheduleNearEnd(seg.token, seg)
        return seg
    }

    /**
     * Resolve display title/artist. Blank MediaMetadata title falls back to the
     * app label (artist cleared) so the segment still persists when duration >= MIN.
     */
    private fun resolvedMeta(controller: MediaController, metadata: MediaMetadata?): Pair<String, String> {
        val raw = readMeta(metadata)
        if (raw.first.isNotBlank()) return raw
        return appLabel(controller.packageName) to ""
    }

    private fun endSegment(seg: Segment, endedAtMs: Long) {
        cancelNearEnd(seg.token)
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

    private fun mediaIdOf(meta: MediaMetadata?): String =
        meta?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID).orEmpty()

    private fun durationOf(meta: MediaMetadata?): Long =
        meta?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.coerceAtLeast(0) ?: 0L

    /** Playback position, extrapolated while PLAYING using lastPositionUpdateTime. */
    private fun positionOf(state: PlaybackState?): Long {
        if (state == null) return 0L
        var pos = state.position
        if (state.state == PlaybackState.STATE_PLAYING && state.lastPositionUpdateTime > 0L) {
            pos += SystemClock.elapsedRealtime() - state.lastPositionUpdateTime
        }
        return pos.coerceAtLeast(0L)
    }

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
        /** Need this much listened before a large rewind counts as "next track". */
        const val REWIND_ANCHOR_MS = 15_000L
        /** Rewind at least this far to treat as a new item (avoids tiny seek jitter). */
        const val REWIND_DROP_MS = 8_000L
        /** Within this many ms of duration ⇒ "near the natural end". */
        const val NEAR_END_MS = 2_000L
        /** After ending, treat positions at/under this as the next item start. */
        const val RESTART_POS_MS = 3_000L
        /** Ignore tiny/unknown durations for end-of-track scheduling. */
        const val MIN_DURATION_FOR_END_MS = 10_000L
        const val NEAR_END_SCHEDULE_MAX_MS = 30_000L
        const val NEAR_END_SLACK_MS = 800L
        const val ORPHAN_WATCH_INTERVAL_MS = 2_000L
        const val ORPHAN_WATCH_MAX_ATTEMPTS = 15
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
