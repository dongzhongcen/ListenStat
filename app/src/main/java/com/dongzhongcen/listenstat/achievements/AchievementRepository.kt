package com.dongzhongcen.listenstat.achievements

import android.content.Context
import com.dongzhongcen.listenstat.data.AppDatabase
import com.dongzhongcen.listenstat.data.UnlockedAchievement
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class AchievementView(
    val state: AchievementState,
    /** Persisted unlock time (ms), null if locked. */
    val unlockedAtMs: Long?
)

class AchievementRepository(context: Context) {
    private val db = AppDatabase.get(context)

    /**
     * Recomputes from the DB, persists newly met ones and returns (all, newlyUnlocked).
     * Called on app open / resume only; no background work.
     */
    suspend fun refresh(): Pair<List<AchievementView>, List<AchievementDef>> {
        val zone = ZoneId.systemDefault()
        val play = db.playDao()
        val days = play.dayAggregates().mapNotNull { row ->
            val date = runCatching { LocalDate.parse(row.dayKey) }.getOrNull() ?: return@mapNotNull null
            DayInput(
                date, row.totalMs,
                row.firstStartMs?.let { Instant.ofEpochMilli(it).atZone(zone).hour }
            )
        }
        val titles = play.titleFirstDays().mapNotNull { runCatching { LocalDate.parse(it.firstDay) }.getOrNull() }
        val states = Achievements.evaluate(days, titles)

        val dao = db.achievementDao()
        val stored = dao.all().associateBy { it.id }
        val fresh = states.filter { it.unlockedOn != null && it.def.id !in stored }
        if (fresh.isNotEmpty()) {
            dao.insertAll(fresh.map {
                UnlockedAchievement(it.def.id, it.unlockedOn!!.atStartOfDay(zone).toInstant().toEpochMilli())
            })
        }
        val now = dao.all().associateBy { it.id }
        // An achievement persisted earlier (e.g. via backup import) stays unlocked.
        val views = states.map { AchievementView(it, now[it.def.id]?.unlockedAtMs) }
        return views to fresh.map { it.def }
    }
}
