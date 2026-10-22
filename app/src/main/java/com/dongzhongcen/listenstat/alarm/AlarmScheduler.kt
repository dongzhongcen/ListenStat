package com.dongzhongcen.listenstat.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import com.dongzhongcen.listenstat.ui.MainActivity
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Schedules local AlarmManager reminders.
 *
 * Prefer [AlarmManager.setAlarmClock] when exact alarms are allowed — it is the
 * most reliable user-visible alarm path under Doze / OEM battery savers, shows
 * in the status bar, and is what users expect for 「会响」reminders.
 * Falls back to inexact when exact permission is denied.
 */
object AlarmScheduler {

    const val ACTION_FIRE = "com.dongzhongcen.listenstat.ALARM_FIRE"
    const val EXTRA_ALARM_ID = "alarm_id"

    fun canScheduleExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val am = context.getSystemService(AlarmManager::class.java) ?: return false
        return am.canScheduleExactAlarms()
    }

    fun openExactAlarmSettings(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
            data = android.net.Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }

    fun rescheduleAll(context: Context) {
        val store = AlarmStore(context)
        store.list().forEach { schedule(context, it) }
    }

    fun schedule(context: Context, alarm: UserAlarm) {
        cancel(context, alarm.id)
        if (!alarm.enabled) return
        val triggerAt = nextTriggerMillis(alarm) ?: return
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = pending(context, alarm.id)
        val show = showPending(context, alarm.id)
        try {
            if (canScheduleExact(context)) {
                // setAlarmClock is Doze-exempt and surfaces as a system alarm clock.
                am.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, show), pi)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } else {
                @Suppress("DEPRECATION")
                am.set(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
        } catch (_: SecurityException) {
            // Exact denied mid-flight — fall back to inexact.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } else {
                @Suppress("DEPRECATION")
                am.set(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
        }
    }

    fun cancel(context: Context, id: Long) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        am.cancel(pending(context, id))
    }

    fun nextTriggerMillis(alarm: UserAlarm, from: LocalDateTime = LocalDateTime.now()): Long? {
        val zone = ZoneId.systemDefault()
        var day = from.toLocalDate()
        val time = LocalTime.of(alarm.hour, alarm.minute)
        // Look ahead up to 8 days for a matching weekday.
        for (i in 0..7) {
            val candidate = LocalDateTime.of(day, time)
            if (candidate.isAfter(from) && alarm.matchesWeekday(day.dayOfWeek.value)) {
                return candidate.atZone(zone).toInstant().toEpochMilli()
            }
            day = day.plusDays(1)
        }
        return null
    }

    private fun pending(context: Context, id: Long): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = ACTION_FIRE
            putExtra(EXTRA_ALARM_ID, id)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, id.toInt(), intent, flags)
    }

    /** Status-bar / AlarmClock tap target — open the app on the alarm page. */
    private fun showPending(context: Context, id: Long): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_ALARM_ID, id)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        // Distinct requestCode from the fire broadcast so they do not collide.
        return PendingIntent.getActivity(context, (id + 100_000L).toInt(), intent, flags)
    }
}
