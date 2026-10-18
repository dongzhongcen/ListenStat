package com.dongzhongcen.listenstat.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "play_sessions",
    indices = [
        Index("packageName"),
        Index("title"),
        Index("dayKey"),
        // v2: speeds up backup-import de-duplication (startedAtMs + packageName + title)
        Index("startedAtMs", "packageName", "title")
    ]
)
data class PlaySession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val appLabel: String,
    val title: String,
    val artist: String,
    /** yyyy-MM-dd in device local time */
    val dayKey: String,
    val startedAtMs: Long,
    var endedAtMs: Long,
    var durationMs: Long
)
