package com.dongzhongcen.listenstat.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Schema history (exported JSON under app/schemas/):
 * - 1: v0.1.0 / v0.1.1 — play_sessions
 * - 2: v0.2.0 — + achievements, + index (startedAtMs, packageName, title)
 */
@Database(
    entities = [PlaySession::class, UnlockedAchievement::class],
    version = 2,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun playDao(): PlayDao
    abstract fun achievementDao(): AchievementDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "listenstat.db"
                ).addMigrations(*Migrations.ALL)
                    .build().also { instance = it }
            }
    }
}
