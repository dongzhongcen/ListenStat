package com.dongzhongcen.listenstat.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Explicit, non-destructive schema migrations. Never use fallbackToDestructiveMigration:
 * listening history must survive every upgrade.
 *
 * The SQL lives in plain lists so the JVM unit test (MigrationSqlTest) can replay it on a
 * real SQLite database created from schemas/1.json and compare the result with schemas/2.json.
 */
object Migrations {

    /** v1 (0.1.x) -> v2 (0.2.0): achievements table + de-dup index. play_sessions rows untouched. */
    val SQL_1_2: List<String> = listOf(
        "CREATE TABLE IF NOT EXISTS `achievements` (`id` TEXT NOT NULL, `unlockedAtMs` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        "CREATE INDEX IF NOT EXISTS `index_play_sessions_startedAtMs_packageName_title` ON `play_sessions` (`startedAtMs`, `packageName`, `title`)"
    )

    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            SQL_1_2.forEach { db.execSQL(it) }
        }
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2)
}
