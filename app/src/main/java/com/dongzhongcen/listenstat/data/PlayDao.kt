package com.dongzhongcen.listenstat.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

data class NamedDuration(
    val name: String,
    val totalMs: Long
)

data class TimeSpan(
    val startedAtMs: Long,
    val endedAtMs: Long
)

@Dao
interface PlayDao {
    @Insert
    suspend fun insert(session: PlaySession): Long

    @Insert
    suspend fun insertAll(sessions: List<PlaySession>)

    @Update
    suspend fun update(session: PlaySession)

    @Query("SELECT * FROM play_sessions WHERE id = :id")
    suspend fun get(id: Long): PlaySession?

    @Query(
        """
        SELECT packageName AS name, SUM(durationMs) AS totalMs
        FROM play_sessions
        WHERE dayKey LIKE :prefix || '%'
        GROUP BY packageName
        ORDER BY totalMs DESC
        LIMIT :limit
        """
    )
    suspend fun topApps(prefix: String, limit: Int): List<NamedDuration>

    @Query(
        """
        SELECT CASE WHEN title = '' THEN '(未知标题)' ELSE title END AS name,
               SUM(durationMs) AS totalMs
        FROM play_sessions
        WHERE dayKey LIKE :prefix || '%'
        GROUP BY name
        ORDER BY totalMs DESC
        LIMIT :limit
        """
    )
    suspend fun topTitles(prefix: String, limit: Int): List<NamedDuration>

    @Query(
        """
        SELECT SUM(durationMs) FROM play_sessions
        WHERE dayKey LIKE :prefix || '%'
        """
    )
    suspend fun totalMs(prefix: String): Long?

    /** Observed by the UI; emits again only when rows are written (no polling). */
    @Query(
        """
        SELECT SUM(durationMs) FROM play_sessions
        WHERE dayKey LIKE :prefix || '%'
        """
    )
    fun totalMsFlow(prefix: String): Flow<Long?>

    /** Raw spans of one day, bucketed by hour in Kotlin for the day chart. */
    @Query("SELECT startedAtMs, endedAtMs FROM play_sessions WHERE dayKey = :day")
    suspend fun spansForDay(day: String): List<TimeSpan>

    @Query(
        """
        SELECT dayKey AS name, SUM(durationMs) AS totalMs
        FROM play_sessions
        WHERE dayKey LIKE :prefix || '%'
        GROUP BY dayKey
        """
    )
    suspend fun totalsByDay(prefix: String): List<NamedDuration>

    @Query(
        """
        SELECT substr(dayKey, 1, 7) AS name, SUM(durationMs) AS totalMs
        FROM play_sessions
        WHERE dayKey LIKE :prefix || '%'
        GROUP BY substr(dayKey, 1, 7)
        """
    )
    suspend fun totalsByMonth(prefix: String): List<NamedDuration>
}
