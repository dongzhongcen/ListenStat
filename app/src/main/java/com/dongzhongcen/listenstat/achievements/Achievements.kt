package com.dongzhongcen.listenstat.achievements

import java.time.LocalDate

/** Local-only achievements, computed from the database on app open/resume. */
enum class Kind { FIRST, TOTAL_HOURS, STREAK_DAYS, DAY_HOURS, NIGHT_OWL, TITLES }

data class AchievementDef(
    val id: String,
    val name: String,
    val desc: String,
    val kind: Kind,
    val target: Long
)

data class AchievementState(
    val def: AchievementDef,
    /** Day on which the condition was first met, or null if still locked. */
    val unlockedOn: LocalDate?,
    /** Progress toward target (same unit as target). */
    val progress: Long
)

/** One listening day: local date, total ms that day, earliest start (segments >= 1 min) as local hour. */
data class DayInput(val date: LocalDate, val totalMs: Long, val firstStartHour: Int?)

object Achievements {
    private const val HOUR = 3_600_000L
    /** A day counts toward streaks once at least this much was listened. */
    const val STREAK_MIN_MS = 60_000L

    val ALL: List<AchievementDef> = listOf(
        AchievementDef("first_listen", "初次收听", "记录到第一段收听", Kind.FIRST, 1),
        AchievementDef("total_1h", "入门听众", "累计收听 1 小时", Kind.TOTAL_HOURS, 1),
        AchievementDef("total_10h", "渐入佳境", "累计收听 10 小时", Kind.TOTAL_HOURS, 10),
        AchievementDef("total_100h", "百小时", "累计收听 100 小时", Kind.TOTAL_HOURS, 100),
        AchievementDef("total_500h", "耳朵长茧", "累计收听 500 小时", Kind.TOTAL_HOURS, 500),
        AchievementDef("streak_3", "三天不断", "连续 3 天收听", Kind.STREAK_DAYS, 3),
        AchievementDef("streak_7", "一周坚持", "连续 7 天收听", Kind.STREAK_DAYS, 7),
        AchievementDef("streak_30", "月度习惯", "连续 30 天收听", Kind.STREAK_DAYS, 30),
        AchievementDef("day_3h", "沉浸一天", "单日收听 3 小时", Kind.DAY_HOURS, 3),
        AchievementDef("night_owl", "夜猫子", "在 0–5 点之间收听", Kind.NIGHT_OWL, 1),
        AchievementDef("titles_50", "博览群书", "收听 50 个不同标题", Kind.TITLES, 50)
    )

    /**
     * Pure evaluation (unit-tested). [days] in any order; [titleFirstDays] = first day each distinct
     * non-empty title appeared.
     */
    fun evaluate(days: List<DayInput>, titleFirstDays: List<LocalDate>): List<AchievementState> {
        val sorted = days.filter { it.totalMs > 0 }.sortedBy { it.date }
        val titles = titleFirstDays.sorted()

        // cumulative hours: day on which the running sum crossed each target
        val totalMs = sorted.sumOf { it.totalMs }
        fun dayCumulativeReaches(ms: Long): LocalDate? {
            var acc = 0L
            for (d in sorted) { acc += d.totalMs; if (acc >= ms) return d.date }
            return null
        }

        // streaks: day on which a run first reached N, and the best run length
        var best = 0
        var run = 0
        var prev: LocalDate? = null
        val streakReached = HashMap<Int, LocalDate>()
        for (d in sorted.filter { it.totalMs >= STREAK_MIN_MS }) {
            run = if (prev != null && prev.plusDays(1) == d.date) run + 1 else 1
            prev = d.date
            if (run > best) best = run
            for (n in 1..run) streakReached.putIfAbsent(n, d.date)
        }
        // progress for streak achievements = best run so far

        val maxDay = sorted.maxOfOrNull { it.totalMs } ?: 0L
        val night = sorted.firstOrNull { (it.firstStartHour ?: 99) < 5 }

        return ALL.map { def ->
            when (def.kind) {
                Kind.FIRST -> AchievementState(def, sorted.firstOrNull()?.date, if (sorted.isEmpty()) 0 else 1)
                Kind.TOTAL_HOURS -> AchievementState(def, dayCumulativeReaches(def.target * HOUR), totalMs / HOUR)
                Kind.STREAK_DAYS -> AchievementState(def, streakReached[def.target.toInt()], best.toLong())
                Kind.DAY_HOURS -> AchievementState(
                    def, sorted.firstOrNull { it.totalMs >= def.target * HOUR }?.date, maxDay / HOUR
                )
                Kind.NIGHT_OWL -> AchievementState(def, night?.date, if (night != null) 1 else 0)
                Kind.TITLES -> AchievementState(
                    def, titles.getOrNull(def.target.toInt() - 1), titles.size.toLong()
                )
            }
        }
    }
}
