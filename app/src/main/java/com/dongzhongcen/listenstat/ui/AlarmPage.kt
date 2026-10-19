package com.dongzhongcen.listenstat.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.view.Gravity
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.isVisible
import com.dongzhongcen.listenstat.R
import com.dongzhongcen.listenstat.alarm.AlarmScheduler
import com.dongzhongcen.listenstat.alarm.AlarmStore
import com.dongzhongcen.listenstat.alarm.UserAlarm
import com.dongzhongcen.listenstat.alarm.AlarmReceiver
import com.dongzhongcen.listenstat.databinding.ItemAlarmBinding
import com.dongzhongcen.listenstat.databinding.PageAlarmBinding
import com.dongzhongcen.listenstat.databinding.SheetAlarmBinding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import android.view.LayoutInflater
import android.widget.LinearLayout

/** Local listening reminders only — no system calendar account integration. */
class AlarmPage(
    private val activity: MainActivity,
    private val b: PageAlarmBinding
) {
    private val store by lazy { AlarmStore(activity) }

    private val dayLabels by lazy {
        listOf(
            R.string.alarm_day_mon, R.string.alarm_day_tue, R.string.alarm_day_wed,
            R.string.alarm_day_thu, R.string.alarm_day_fri, R.string.alarm_day_sat,
            R.string.alarm_day_sun
        )
    }

    fun init() {
        b.btnAddAlarm.setOnClickListener { openSheet(null) }
        b.txtExactHint.setOnClickListener { AlarmScheduler.openExactAlarmSettings(activity) }
        AlarmReceiver.ensureChannel(activity)
        maybeRequestPostNotifications()
        refresh()
    }

    fun onShow() = refresh()

    fun refresh() {
        val exactOk = AlarmScheduler.canScheduleExact(activity)
        b.txtExactHint.isVisible = !exactOk
        val alarms = store.list()
        b.txtAlarmEmpty.isVisible = alarms.isEmpty()
        b.listAlarms.isVisible = alarms.isNotEmpty()
        b.listAlarms.removeAllViews()
        alarms.forEach { alarm ->
            val row = ItemAlarmBinding.inflate(activity.layoutInflater, b.listAlarms, false)
            row.txtTime.text = alarm.timeLabel()
            row.txtLabel.text = alarm.label.ifBlank { activity.getString(R.string.alarm_notif_default) }
            row.txtDays.text = formatWeekdays(alarm.weekdays)
            row.switchEnabled.setOnCheckedChangeListener(null)
            row.switchEnabled.isChecked = alarm.enabled
            row.switchEnabled.setOnCheckedChangeListener { _, checked ->
                val updated = alarm.copy(enabled = checked)
                store.upsert(updated)
                AlarmScheduler.schedule(activity, updated)
                refresh()
            }
            row.root.setOnClickListener { openSheet(alarm) }
            b.listAlarms.addView(row.root)
        }
        Ui.applyGlass(b.root)
    }

    private fun formatWeekdays(mask: Int): String {
        if (mask == 0 || mask == 0x7F) return activity.getString(R.string.alarm_everyday)
        if (mask == 0x1F) return activity.getString(R.string.alarm_weekdays)
        if (mask == 0x60) return activity.getString(R.string.alarm_weekend)
        return dayLabels.mapIndexedNotNull { i, res ->
            if ((mask and (1 shl i)) != 0) activity.getString(res) else null
        }.joinToString(" ")
    }

    private fun openSheet(existing: UserAlarm?) {
        val d = BottomSheetDialog(activity).apply {
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
            behavior.skipCollapsed = true
        }
        val sb = SheetAlarmBinding.inflate(LayoutInflater.from(activity))
        sb.txtTitle.setText(if (existing == null) R.string.alarm_sheet_add else R.string.alarm_sheet_edit)
        sb.timePicker.setIs24HourView(true)
        val hour = existing?.hour ?: 21
        val minute = existing?.minute ?: 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            sb.timePicker.hour = hour
            sb.timePicker.minute = minute
        } else {
            @Suppress("DEPRECATION")
            sb.timePicker.currentHour = hour
            @Suppress("DEPRECATION")
            sb.timePicker.currentMinute = minute
        }
        sb.editLabel.setText(existing?.label.orEmpty())
        var weekdays = existing?.weekdays ?: 0
        val chips = mutableListOf<TextView>()
        fun renderChips() {
            chips.forEachIndexed { i, tv ->
                // When mask==0 (everyday), show all selected; tapping one exits everyday into that single day.
                val selected = if (weekdays == 0) true else (weekdays and (1 shl i)) != 0
                tv.setBackgroundResource(if (selected) R.drawable.bg_circle_mint else R.drawable.bg_circle_outline)
                tv.setTextColor(ContextCompat.getColor(activity, R.color.ls_ink))
                tv.alpha = if (selected) 1f else 0.55f
            }
        }
        sb.weekdayRow.removeAllViews()
        dayLabels.forEachIndexed { i, res ->
            val tv = TextView(activity).apply {
                text = activity.getString(res)
                gravity = Gravity.CENTER
                textSize = 13f
                typeface = ResourcesCompat.getFont(activity, R.font.poppins_medium)
                layoutParams = LinearLayout.LayoutParams(Ui.dp(activity, 36f), Ui.dp(activity, 36f)).apply {
                    marginStart = if (i == 0) 0 else Ui.dp(activity, 4f)
                }
                setOnClickListener {
                    if (weekdays == 0) {
                        // Leave "everyday": keep only this day.
                        weekdays = 1 shl i
                    } else {
                        weekdays = weekdays xor (1 shl i)
                        if (weekdays == 0) weekdays = 0 // empty → everyday
                    }
                    renderChips()
                }
            }
            chips.add(tv)
            sb.weekdayRow.addView(tv)
        }
        renderChips()

        sb.btnDelete.isVisible = existing != null
        sb.btnDelete.setOnClickListener {
            if (existing != null) {
                AlarmScheduler.cancel(activity, existing.id)
                store.delete(existing.id)
                Toast.makeText(activity, R.string.alarm_deleted, Toast.LENGTH_SHORT).show()
                refresh()
            }
            d.dismiss()
        }
        sb.btnClose.setOnClickListener { d.dismiss() }
        sb.btnSave.setOnClickListener {
            val h = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) sb.timePicker.hour
            else @Suppress("DEPRECATION") sb.timePicker.currentHour
            val m = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) sb.timePicker.minute
            else @Suppress("DEPRECATION") sb.timePicker.currentMinute
            val label = sb.editLabel.text?.toString()?.trim().orEmpty()
            val alarm = UserAlarm(
                id = existing?.id ?: store.nextId(),
                hour = h,
                minute = m,
                label = label,
                enabled = existing?.enabled ?: true,
                weekdays = weekdays
            )
            store.upsert(alarm)
            AlarmScheduler.schedule(activity, alarm)
            Toast.makeText(activity, R.string.alarm_saved, Toast.LENGTH_SHORT).show()
            refresh()
            d.dismiss()
        }
        d.setContentView(sb.root)
        d.show()
    }

    private fun maybeRequestPostNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_POST_NOTIF)
        }
    }

    companion object {
        const val REQ_POST_NOTIF = 0xA11
    }
}
