package com.dongzhongcen.listenstat.alarm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.dongzhongcen.listenstat.R
import com.dongzhongcen.listenstat.ui.MainActivity

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != AlarmScheduler.ACTION_FIRE) return
        val id = intent.getLongExtra(AlarmScheduler.EXTRA_ALARM_ID, -1L)
        if (id < 0) return
        val store = AlarmStore(context)
        val alarm = store.get(id) ?: return
        if (!alarm.enabled) return

        ensureChannel(context)
        val open = PendingIntent.getActivity(
            context,
            id.toInt(),
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val body = alarm.label.ifBlank { context.getString(R.string.alarm_notif_default) }
        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_headphones)
            .setContentTitle(context.getString(R.string.alarm_notif_title))
            .setContentText(body)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIF_BASE + id.toInt(), notif)
        }

        // Reschedule next occurrence for repeating alarms.
        AlarmScheduler.schedule(context, alarm)
    }

    companion object {
        const val CHANNEL_ID = "listen_reminders"
        private const val NOTIF_BASE = 41000

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            val ch = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.alarm_notif_channel),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.alarm_notif_channel_desc)
            }
            nm.createNotificationChannel(ch)
        }
    }
}
