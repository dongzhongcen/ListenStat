package com.dongzhongcen.listenstat.tracking

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A segment that is currently being played and not yet written to the database. */
data class LiveSegment(
    val packageName: String,
    val appLabel: String,
    val title: String,
    val startedAtMs: Long
)

/**
 * In-memory, process-wide view of what is playing right now.
 * Written only by [com.dongzhongcen.listenstat.service.MediaTrackService] when a
 * segment starts/ends; read by the UI. No disk I/O, no timers.
 */
object LiveTracker {
    private val _segments = MutableStateFlow<List<LiveSegment>>(emptyList())
    val segments: StateFlow<List<LiveSegment>> = _segments.asStateFlow()

    internal fun publish(list: List<LiveSegment>) {
        _segments.value = list.sortedByDescending { it.startedAtMs }
    }
}
