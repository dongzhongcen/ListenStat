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

data class SessionKey(
    val startedAtMs: Long,
    val packageName: String,
    val title: String
)

/** Per-day aggregate used by achievements. firstStartMs ignores segments shorter than 1 min. */
data class DayAggregate(
    val dayKey: String,
    val totalMs: Long,
    val firstStartMs: Long?
)

data class TitleFirstDay(
    val title: String,
    val firstDay: String
)

data class TimeSpan(
    val startedAtMs: Long,
    val endedAtMs: Long
)


data class PackageTitleDayTotal(
    val packageName: String,
    val title: String,
    val dayKey: String,
    val totalMs: Long
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

    /** Titles under one package for the selected period (Top N). */
    @Query(
        """
        SELECT CASE WHEN title = '' THEN '(未知标题)' ELSE title END AS name,
               SUM(durationMs) AS totalMs
        FROM play_sessions
        WHERE dayKey LIKE :prefix || '%' AND packageName = :packageName
        GROUP BY name
        ORDER BY totalMs DESC
        LIMIT :limit
        """
    )
    suspend fun topTitlesForApp(prefix: String, packageName: String, limit: Int): List<NamedDuration>

    @Query(
        """
        SELECT SUM(durationMs) FROM play_sessions
        WHERE dayKey LIKE :prefix || '%' AND packageName = :packageName
        """
    )
    suspend fun totalMsForApp(prefix: String, packageName: String): Long?

    @Query(
        """
        SELECT COUNT(DISTINCT CASE WHEN title = '' THEN '(未知标题)' ELSE title END)
        FROM play_sessions
        WHERE dayKey LIKE :prefix || '%' AND packageName = :packageName
        """
    )
    suspend fun countTitlesForApp(prefix: String, packageName: String): Int

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

    /** Full sessions for calendar day detail. */
    @Query("SELECT * FROM play_sessions WHERE dayKey = :day ORDER BY startedAtMs")
    suspend fun sessionsForDay(day: String): List<PlaySession>

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

    @Query("SELECT COUNT(DISTINCT packageName) FROM play_sessions WHERE dayKey LIKE :prefix || '%'")
    suspend fun countApps(prefix: String): Int

    @Query("SELECT COUNT(DISTINCT title) FROM play_sessions WHERE title != '' AND dayKey LIKE :prefix || '%'")
    suspend fun countTitles(prefix: String): Int

    @Query("SELECT COUNT(*) FROM play_sessions WHERE dayKey LIKE :prefix || '%'")
    suspend fun countSessions(prefix: String): Int

    @Query("SELECT MIN(dayKey) FROM play_sessions")
    suspend fun firstDayKey(): String?

    // ---- achievements ----

    @Query(
        """
        SELECT dayKey, SUM(durationMs) AS totalMs,
               MIN(CASE WHEN durationMs >= 60000 THEN startedAtMs END) AS firstStartMs
        FROM play_sessions
        GROUP BY dayKey
        ORDER BY dayKey
        """
    )
    suspend fun dayAggregates(): List<DayAggregate>

    @Query(
        """
        SELECT title, MIN(dayKey) AS firstDay FROM play_sessions
        WHERE title != '' GROUP BY title ORDER BY firstDay
        """
    )
    suspend fun titleFirstDays(): List<TitleFirstDay>

    // ---- backup ----

    @Query("SELECT * FROM play_sessions WHERE id > :afterId ORDER BY id LIMIT :limit")
    suspend fun page(afterId: Long, limit: Int): List<PlaySession>

    @Query("SELECT startedAtMs, packageName, title FROM play_sessions")
    suspend fun allKeys(): List<SessionKey>
    // ---- cloud sync (day aggregates) ----

    @Query(
        """
        SELECT packageName, title, dayKey, SUM(durationMs) AS totalMs
        FROM play_sessions
        GROUP BY packageName, title, dayKey
        """
    )
    suspend fun dayAggregatesForSync(): List<PackageTitleDayTotal>

    @Query(
        """
        SELECT SUM(durationMs) FROM play_sessions
        WHERE packageName = :packageName AND title = :title AND dayKey = :dayKey
        """
    )
    suspend fun sumDuration(packageName: String, title: String, dayKey: String): Long?

    @Query(
        """
        SELECT * FROM play_sessions
        WHERE startedAtMs = :startedAtMs AND packageName = :packageName AND title = :title
        LIMIT 1
        """
    )
    suspend fun findByKey(startedAtMs: Long, packageName: String, title: String): PlaySession?

}
