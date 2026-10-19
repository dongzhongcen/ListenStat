package com.dongzhongcen.listenstat.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/** A locally unlocked achievement (added in DB version 2). */
@Entity(tableName = "achievements")
data class UnlockedAchievement(
    @PrimaryKey val id: String,
    val unlockedAtMs: Long
)

@Dao
interface AchievementDao {
    @Query("SELECT * FROM achievements")
    suspend fun all(): List<UnlockedAchievement>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(items: List<UnlockedAchievement>)

    /** Keeps the earliest unlock time (used by backup import). */
    @Query("UPDATE achievements SET unlockedAtMs = :atMs WHERE id = :id AND unlockedAtMs > :atMs")
    suspend fun keepEarliest(id: String, atMs: Long)
}
