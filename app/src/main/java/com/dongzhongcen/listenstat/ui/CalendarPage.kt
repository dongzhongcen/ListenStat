package com.dongzhongcen.listenstat.ui

import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.dongzhongcen.listenstat.R
import com.dongzhongcen.listenstat.data.AppDatabase
import com.dongzhongcen.listenstat.data.PlaySession
import com.dongzhongcen.listenstat.databinding.ItemRankBinding
import com.dongzhongcen.listenstat.databinding.PageCalendarBinding
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Month grid + day detail. Queries [PlayDao.totalsByDay] / [PlayDao.sessionsForDay]
 * with dayKey = yyyy-MM-dd (same as StatsPage).
 */
class CalendarPage(
    private val activity: MainActivity,
    private val b: PageCalendarBinding
) {
    private val dao by lazy { AppDatabase.get(activity).playDao() }
    private var month: YearMonth = YearMonth.now()
    private var selected: LocalDate = LocalDate.now()
    private var daysWithListen: Set<String> = emptySet()
    private var loadJob: Job? = null
    private val monthFmt = DateTimeFormatter.ofPattern("yyyy年M月")
    private val dayFmt = DateTimeFormatter.ofPattern("M月d日 EEEE")

    fun init() {
        b.btnPrevMonth.setOnClickListener {
            month = month.minusMonths(1)
            loadMonth()
        }
        b.btnNextMonth.setOnClickListener {
            month = month.plusMonths(1)
            loadMonth()
        }
        setupWeekdayHeaders()
        loadMonth()
    }

    fun onShow() {
        loadMonth()
    }

    private fun setupWeekdayHeaders() {
        b.weekdayRow.removeAllViews()
        val labels = listOf(
            R.string.calendar_weekday_mon, R.string.calendar_weekday_tue, R.string.calendar_weekday_wed,
            R.string.calendar_weekday_thu, R.string.calendar_weekday_fri, R.string.calendar_weekday_sat,
            R.string.calendar_weekday_sun
        )
        labels.forEach { res ->
            b.weekdayRow.addView(TextView(activity).apply {
                text = activity.getString(res)
                gravity = Gravity.CENTER
                setTextColor(ContextCompat.getColor(activity, R.color.ls_muted))
                textSize = 12f
                typeface = ResourcesCompat.getFont(activity, R.font.poppins_medium)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
        }
    }

    private fun loadMonth() {
        b.txtMonth.text = month.format(monthFmt)
        val today = LocalDate.now()
        // Clamp next: allow browsing future months but keep selection in range.
        loadJob?.cancel()
        loadJob = activity.lifecycleScope.launch {
            val prefix = "%04d-%02d".format(month.year, month.monthValue)
            val totals = withContext(Dispatchers.IO) { dao.totalsByDay(prefix) }
            daysWithListen = totals.filter { it.totalMs > 0L }.map { it.name }.toSet()
            if (selected.year != month.year || selected.month != month.month) {
                selected = if (month == YearMonth.from(today)) today else month.atDay(1)
            }
            renderGrid()
            loadDay(selected)
        }
    }

    private fun renderGrid() {
        b.dayGrid.removeAllViews()
        val first = month.atDay(1)
        // Monday-first: DayOfWeek.MONDAY=1 → offset 0
        val offset = first.dayOfWeek.value - 1
        val daysInMonth = month.lengthOfMonth()
        val cellH = Ui.dp(activity, 44f)
        val today = LocalDate.now()

        fun emptyCell() {
            b.dayGrid.addView(View(activity).apply {
                layoutParams = GridLayout.LayoutParams().apply {
                    width = 0
                    height = cellH
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    setMargins(2, 2, 2, 2)
                }
            })
        }

        repeat(offset) { emptyCell() }

        for (d in 1..daysInMonth) {
            val date = month.atDay(d)
            val dayKey = "%04d-%02d-%02d".format(date.year, date.monthValue, date.dayOfMonth)
            val has = dayKey in daysWithListen
            val isSel = date == selected
            val isToday = date == today

            val cell = FrameLayout(activity).apply {
                layoutParams = GridLayout.LayoutParams().apply {
                    width = 0
                    height = cellH
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    setMargins(2, 2, 2, 2)
                }
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    selected = date
                    renderGrid()
                    loadDay(date)
                }
            }

            val bg = View(activity).apply {
                layoutParams = FrameLayout.LayoutParams(
                    Ui.dp(activity, 36f), Ui.dp(activity, 36f), Gravity.CENTER
                )
                when {
                    isSel -> setBackgroundResource(R.drawable.bg_day_selected)
                    isToday -> setBackgroundResource(R.drawable.bg_day_today_ring)
                }
            }
            cell.addView(bg)

            val num = TextView(activity).apply {
                text = d.toString()
                gravity = Gravity.CENTER
                textSize = 14f
                typeface = ResourcesCompat.getFont(activity, R.font.poppins_medium)
                setTextColor(ContextCompat.getColor(activity, R.color.ls_ink))
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }
            cell.addView(num)

            if (has) {
                cell.addView(View(activity).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        Ui.dp(activity, 6f), Ui.dp(activity, 6f), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                    ).apply { bottomMargin = Ui.dp(activity, 4f) }
                    setBackgroundResource(R.drawable.bg_day_dot)
                })
            }
            b.dayGrid.addView(cell)
        }
    }

    private fun loadDay(day: LocalDate) {
        activity.lifecycleScope.launch {
            val key = "%04d-%02d-%02d".format(day.year, day.monthValue, day.dayOfMonth)
            val sessions = withContext(Dispatchers.IO) { dao.sessionsForDay(key) }
            val total = sessions.sumOf { it.durationMs }
            bindDay(day, total, sessions)
        }
    }

    private fun bindDay(day: LocalDate, totalMs: Long, sessions: List<PlaySession>) {
        b.txtDayLabel.text = activity.getString(R.string.calendar_day_fmt, day.format(dayFmt))
        val totalMin = totalMs / 60_000L
        b.numDayHours.text = (totalMin / 60).toString()
        b.numDayMinutes.text = (totalMin % 60).toString()
        b.txtDayEmpty.isVisible = sessions.isEmpty()
        b.listSegments.removeAllViews()
        val max = sessions.maxOfOrNull { it.durationMs }?.coerceAtLeast(1L) ?: 1L
        sessions.forEachIndexed { i, s ->
            val row = ItemRankBinding.inflate(activity.layoutInflater, b.listSegments, false)
            row.rank.text = (i + 1).toString()
            val title = s.title.ifBlank { activity.getString(R.string.live_unknown_title) }
            val app = s.appLabel.ifBlank { s.packageName }
            row.name.text = activity.getString(R.string.calendar_segment_fmt, title, app)
            row.duration.text = Ui.formatDuration(s.durationMs)
            row.bar.setProgressCompat((s.durationMs * 1000 / max).toInt().coerceIn(0, 1000), false)
            b.listSegments.addView(row.root)
        }
        Ui.applyGlass(b.root)
    }
}
