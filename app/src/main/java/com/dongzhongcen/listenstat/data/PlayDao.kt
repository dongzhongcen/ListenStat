package com.dongzhongcen.listenstat.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

data class NamedDuration(
    val name: String,
    val totalMs: Long
)

@Dao
interface PlayDao {
    @Insert
    suspend fun insert(session: PlaySession): Long

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
}
