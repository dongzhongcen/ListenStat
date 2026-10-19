package com.dongzhongcen.listenstat

import com.dongzhongcen.listenstat.achievements.Achievements
import com.dongzhongcen.listenstat.achievements.DayInput
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AchievementsTest {
    private val h = 3_600_000L
    private fun d(day: Int) = LocalDate.of(2026, 10, day)
    private fun byId(days: List<DayInput>, titles: List<LocalDate> = emptyList()) =
        Achievements.evaluate(days, titles).associateBy { it.def.id }

    @Test fun empty_allLocked() {
        assertEquals(0, byId(emptyList()).values.count { it.unlockedOn != null })
    }

    @Test fun totals_streaks_dayAndNight() {
        val days = listOf(
            DayInput(d(1), 2 * h, 9),
            DayInput(d(2), 4 * h, 3),   // night owl + single-day 3h
            DayInput(d(3), 5 * h, 20),  // 11h cumulative -> 10h unlocked here, streak 3
            DayInput(d(5), 30_000, 10)  // < 1 min: does not extend streaks
        )
        val a = byId(days)
        assertEquals(d(1), a["first_listen"]!!.unlockedOn)
        assertEquals(d(1), a["total_1h"]!!.unlockedOn)
        assertEquals(d(3), a["total_10h"]!!.unlockedOn)
        assertNull(a["total_100h"]!!.unlockedOn)
        assertEquals(d(3), a["streak_3"]!!.unlockedOn)
        assertNull(a["streak_7"]!!.unlockedOn)
        assertEquals(3L, a["streak_7"]!!.progress)
        assertEquals(d(2), a["day_3h"]!!.unlockedOn)
        assertEquals(d(2), a["night_owl"]!!.unlockedOn)
    }

    @Test fun titles_50th_firstDay() {
        val titles = (1..60).map { d(1).plusDays((it / 10).toLong()) }
        val a = byId(listOf(DayInput(d(1), h, 10)), titles)
        assertEquals(titles.sorted()[49], a["titles_50"]!!.unlockedOn)
    }
}
