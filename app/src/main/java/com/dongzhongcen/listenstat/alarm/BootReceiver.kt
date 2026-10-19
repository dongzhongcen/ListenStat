package com.dongzhongcen.listenstat.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Re-arms local listening reminders after reboot. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        AlarmScheduler.rescheduleAll(context)
    }
}
