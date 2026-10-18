package com.dongzhongcen.listenstat.service

import android.content.ComponentName
import android.content.pm.PackageManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.dongzhongcen.listenstat.data.AppDatabase
import com.dongzhongcen.listenstat.data.PlaySession
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Uses notification-listener permission to attach MediaSessionManager callbacks
 * and accumulate local play time. Nothing is uploaded.
 */
class MediaTrackService : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dayFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    private var sessionManager: MediaSessionManager? = null
    private val active = mutableMapOf<String, Long>() // controller key -> session row id
    private val tickJobs = mutableMapOf<String, Job>()

    private val listener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        syncControllers(controllers ?: emptyList())
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        val mgr = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
        sessionManager = mgr
        val cn = ComponentName(this, MediaTrackService::class.java)
        try {
            mgr.addOnActiveSessionsChangedListener(listener, cn)
            syncControllers(mgr.getActiveSessions(cn))
        } catch (_: SecurityException) {
            // Permission not granted yet
        }
    }

    override fun onListenerDisconnected() {
        sessionManager?.removeOnActiveSessionsChangedListener(listener)
        tickJobs.values.forEach { it.cancel() }
        tickJobs.clear()
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) = Unit
    override fun onNotificationRemoved(sbn: StatusBarNotification?) = Unit

    private fun syncControllers(controllers: List<MediaController>) {
        val keys = controllers.map { keyOf(it) }.toSet()
        // close sessions that disappeared
        active.keys.filter { it !in keys }.forEach { endSession(it) }

        controllers.forEach { controller ->
            val key = keyOf(controller)
            controller.registerCallback(object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) {
                    handleState(controller, state)
                }

                override fun onMetadataChanged(metadata: MediaMetadata?) {
                    handleState(controller, controller.playbackState)
                }
            })
            handleState(controller, controller.playbackState)
        }
    }

    private fun handleState(controller: MediaController, state: PlaybackState?) {
        val key = keyOf(controller)
        val playing = state?.state == PlaybackState.STATE_PLAYING
        if (playing) {
            if (key !in active) startSession(controller)
        } else {
            endSession(key)
        }
    }

    private fun startSession(controller: MediaController) {
        val key = keyOf(controller)
        val meta = controller.metadata
        val title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
            .ifBlank { meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE).orEmpty() }
        val artist = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
            .ifBlank { meta?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST).orEmpty() }
        val pkg = controller.packageName
        val label = appLabel(pkg)
        val now = System.currentTimeMillis()
        val day = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate().format(dayFmt)

        scope.launch {
            val id = AppDatabase.get(this@MediaTrackService).playDao().insert(
                PlaySession(
                    packageName = pkg,
                    appLabel = label,
                    title = title,
                    artist = artist,
                    dayKey = day,
                    startedAtMs = now,
                    endedAtMs = now,
                    durationMs = 0
                )
            )
            active[key] = id
            tickJobs[key]?.cancel()
            tickJobs[key] = scope.launch {
                while (true) {
                    kotlinx.coroutines.delay(15_000)
                    bump(key)
                }
            }
        }
    }

    private fun bump(key: String) {
        val id = active[key] ?: return
        scope.launch {
            val dao = AppDatabase.get(this@MediaTrackService).playDao()
            val row = dao.get(id) ?: return@launch
            val now = System.currentTimeMillis()
            row.endedAtMs = now
            row.durationMs = (now - row.startedAtMs).coerceAtLeast(0)
            dao.update(row)
        }
    }

    private fun endSession(key: String) {
        tickJobs.remove(key)?.cancel()
        val id = active.remove(key) ?: return
        scope.launch {
            val dao = AppDatabase.get(this@MediaTrackService).playDao()
            val row = dao.get(id) ?: return@launch
            val now = System.currentTimeMillis()
            row.endedAtMs = now
            row.durationMs = (now - row.startedAtMs).coerceAtLeast(0)
            dao.update(row)
        }
    }

    private fun keyOf(c: MediaController) = c.packageName + ":" + System.identityHashCode(c.sessionToken)

    private fun appLabel(pkg: String): String =
        try {
            val pm = packageManager
            val ai = pm.getApplicationInfo(pkg, 0)
            pm.getApplicationLabel(ai).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            pkg
        }
}
