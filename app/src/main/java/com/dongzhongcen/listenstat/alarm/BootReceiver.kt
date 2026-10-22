package com.dongzhongcen.listenstat.alarm

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Re-arms local listening reminders after reboot, package update, timezone/time
 * changes, and when the user grants exact-alarm permission again.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val reschedule = when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_TIME_CHANGED -> true
            else -> {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    action == AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED
            }
        }
        if (reschedule) AlarmScheduler.rescheduleAll(context)
    }
}
