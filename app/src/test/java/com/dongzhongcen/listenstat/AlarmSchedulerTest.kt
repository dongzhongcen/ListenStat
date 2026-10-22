package com.dongzhongcen.listenstat

import com.dongzhongcen.listenstat.alarm.AlarmScheduler
import com.dongzhongcen.listenstat.alarm.UserAlarm
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmSchedulerTest {

    @Test
    fun nextTrigger_sameDayLater() {
        val alarm = UserAlarm(id = 1, hour = 21, minute = 30, weekdays = 0)
        val from = LocalDateTime.of(2026, 10, 22, 10, 0) // Thursday
        val trigger = AlarmScheduler.nextTriggerMillis(alarm, from)
        assertNotNull(trigger)
        val expected = LocalDateTime.of(2026, 10, 22, 21, 30)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals(expected, trigger)
    }

    @Test
    fun nextTrigger_rollsToTomorrowWhenPast() {
        val alarm = UserAlarm(id = 1, hour = 8, minute = 0, weekdays = 0)
        val from = LocalDateTime.of(2026, 10, 22, 9, 0)
        val trigger = AlarmScheduler.nextTriggerMillis(alarm, from)
        assertNotNull(trigger)
        val expected = LocalDateTime.of(2026, 10, 23, 8, 0)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals(expected, trigger)
    }

    @Test
    fun nextTrigger_respectsWeekdayMask() {
        // bit0=Mon … bit6=Sun. Only Friday (bit 4).
        val alarm = UserAlarm(id = 1, hour = 7, minute = 0, weekdays = 1 shl 4)
        val from = LocalDateTime.of(2026, 10, 22, 10, 0) // Thursday
        val trigger = AlarmScheduler.nextTriggerMillis(alarm, from)
        assertNotNull(trigger)
        val expected = LocalDateTime.of(2026, 10, 23, 7, 0) // Friday
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals(expected, trigger)
    }

    @Test
    fun matchesWeekday_zeroMeansEveryday() {
        val alarm = UserAlarm(id = 1, hour = 9, minute = 0, weekdays = 0)
        assertTrue(alarm.matchesWeekday(1))
        assertTrue(alarm.matchesWeekday(7))
    }

    @Test
    fun nextTrigger_exactNowGoesToTomorrow() {
        val alarm = UserAlarm(id = 2, hour = 9, minute = 0, weekdays = 0)
        val atExact = LocalDateTime.of(2026, 10, 22, 9, 0)
        val t = AlarmScheduler.nextTriggerMillis(alarm, atExact)
        assertNotNull(t)
        val expected = LocalDateTime.of(2026, 10, 23, 9, 0)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals(expected, t)
    }
}
