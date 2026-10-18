package com.dongzhongcen.listenstat.ui

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.Intent
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.dongzhongcen.listenstat.R
import com.dongzhongcen.listenstat.data.AppDatabase
import com.dongzhongcen.listenstat.databinding.ItemRankBinding
import com.dongzhongcen.listenstat.databinding.PageStatsBinding
import com.dongzhongcen.listenstat.tracking.LiveSegment
import com.dongzhongcen.listenstat.tracking.LiveTracker
import com.google.android.material.datepicker.CalendarConstraints
import com.google.android.material.datepicker.DateValidatorPointBackward
import com.google.android.material.datepicker.MaterialDatePicker
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Statistics page: hero ("正在听" / today), period chips + navigator + strip, totals, chart, Top 20.
 * Re-queries only on Room invalidation (service writes) or user navigation — no polling.
 * The 1 s hero tick runs only while visible AND something is playing.
 */
class StatsPage(
    private val activity: MainActivity,
    private val b: PageStatsBinding,
    private val onMenu: () -> Unit
) {
    private class Stats(
        val period: Period,
        val dbTotal: Long,
        val buckets: LongArray,
        val labels: Map<Int, String>,
        val highlight: Int,
        val apps: List<Pair<String, Long>>,
        val titles: List<Pair<String, Long>>,
        val appCount: Int,
        val titleCount: Int
    )

    private val dao by lazy { AppDatabase.get(activity).playDao() }
    private var period = Period(Range.DAY, LocalDate.now())
    private var dbTodayMs = 0L
    private var todaySessions = 0
    private var stats: Stats? = null
    private var firstYear = LocalDate.now().year
    private var liveJob: Job? = null
    private var loadJob: Job? = null
    private var breathing: ObjectAnimator? = null
    private var isBreathing = false

    fun init() {
        b.txtGreeting.text = Ui.greeting(activity)
        b.btnMenu.setOnClickListener { onMenu() }
        b.btnPermission.setOnClickListener {
            activity.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            Toast.makeText(activity, R.string.enable_listenstat, Toast.LENGTH_LONG).show()
        }
        b.chipDay.setOnClickListener { setRange(Range.DAY) }
        b.chipMonth.setOnClickListener { setRange(Range.MONTH) }
        b.chipYear.setOnClickListener { setRange(Range.YEAR) }
        b.btnPrev.setOnClickListener { select(period.shift(-1)) }
        b.btnNext.setOnClickListener { if (!period.isLatest(LocalDate.now())) select(period.shift(1)) }
        b.txtPeriod.setOnClickListener { openPicker() }
        b.btnHeroGo.setOnClickListener {
            select(Period(Range.DAY, LocalDate.now()))
            b.scroll.post { b.scroll.smoothScrollTo(0, b.txtTotalLabel.top) }
        }
        renderChips()
        renderNavigator()

        // Re-query only when the service actually writes a row (Room invalidation), no polling.
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                val todayKey = Period(Range.DAY, LocalDate.now()).prefix
                dao.totalMsFlow(todayKey).collect { total ->
                    dbTodayMs = total ?: 0L
                    todaySessions = withContext(Dispatchers.IO) { dao.countSessions(todayKey) }
                    firstYear = withContext(Dispatchers.IO) {
                        dao.firstDayKey()?.take(4)?.toIntOrNull() ?: LocalDate.now().year
                    }
                    renderLive(LiveTracker.segments.value)
                    loadStats(animate = false)
                }
            }
        }
    }

    fun onStart() {
        b.txtGreeting.text = Ui.greeting(activity)
        liveJob = activity.lifecycleScope.launch {
            LiveTracker.segments.collectLatest { list ->
                renderLive(list)
                setBreathing(list.isNotEmpty())
                while (list.isNotEmpty() && isActive) {
                    delay(1_000L - System.currentTimeMillis() % 1_000L)
                    renderLive(list)
                }
            }
        }
    }

    fun onStop() {
        liveJob?.cancel()
        liveJob = null
        setBreathing(false)
    }

    fun setPermissionMissing(missing: Boolean) {
        b.cardPermission.isVisible = missing
    }

    fun reload() = loadStats(animate = false)

    // ---- period selection ----

    private fun setRange(r: Range) {
        if (r == period.range) return
        val today = LocalDate.now()
        // keep the anchor but never point into the future
        select(Period(r, if (period.anchor.isAfter(today)) today else period.anchor))
    }

    private fun select(p: Period) {
        val today = LocalDate.now()
        val clamped = if (p.start.isAfter(today)) p.copy(anchor = today) else p
        if (clamped == period) return
        period = clamped
        renderChips()
        renderNavigator()
        loadStats(animate = true)
    }

    private fun openPicker() {
        val today = LocalDate.now()
        when (period.range) {
            Range.DAY -> {
                val utc = period.anchor.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                val picker = MaterialDatePicker.Builder.datePicker()
                    .setTitleText(R.string.pick_period)
                    .setSelection(utc)
                    .setCalendarConstraints(
                        CalendarConstraints.Builder()
                            .setEnd(today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
                            .setValidator(DateValidatorPointBackward.now())
                            .build()
                    )
                    .build()
                picker.addOnPositiveButtonClickListener { sel ->
                    val day = Instant.ofEpochMilli(sel).atZone(ZoneOffset.UTC).toLocalDate()
                    select(Period(Range.DAY, day))
                }
                picker.show(activity.supportFragmentManager, "day")
            }
            Range.MONTH -> Sheets.month(activity, period.anchor, today, minOf(firstYear, period.anchor.year)) {
                select(Period(Range.MONTH, it))
            }
            Range.YEAR -> Sheets.year(
                activity, period.anchor.year, minOf(firstYear, period.anchor.year)..today.year, today
            ) { y -> select(Period(Range.YEAR, LocalDate.of(y, 1, 1))) }
        }
    }

    private fun renderChips() {
        listOf(b.chipDay to Range.DAY, b.chipMonth to Range.MONTH, b.chipYear to Range.YEAR).forEach { (v, r) ->
            val on = r == period.range
            v.setBackgroundResource(if (on) R.drawable.bg_pill_mint else R.drawable.bg_pill_outline_peach)
            v.isSelected = on
        }
    }

    private fun renderNavigator() {
        val today = LocalDate.now()
        b.txtPeriod.text = periodLabel(period, today)
        val canNext = !period.isLatest(today)
        b.btnNext.isEnabled = canNext
        b.btnNext.alpha = if (canNext) 1f else 0.3f
        renderStrip(today)
    }

    private fun periodLabel(p: Period, today: LocalDate): String {
        val a = p.anchor
        return when (p.range) {
            Range.DAY -> {
                val wd = a.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.CHINA)
                val rel = when (a) {
                    today -> " · " + activity.getString(R.string.today)
                    today.minusDays(1) -> " · " + activity.getString(R.string.yesterday)
                    else -> ""
                }
                val y = if (a.year != today.year) "${a.year}年" else ""
                "$y${a.monthValue}月${a.dayOfMonth}日 $wd$rel"
            }
            Range.MONTH -> "${a.year}年${a.monthValue}月"
            Range.YEAR -> "${a.year}年"
        }
    }

    private fun totalLabel(p: Period, today: LocalDate): String {
        val inNow = today in p
        return when (p.range) {
            Range.DAY -> if (inNow) activity.getString(R.string.total_label_today)
            else activity.getString(R.string.total_label_fmt, periodLabel(p, today).substringBefore(' '))
            Range.MONTH -> if (inNow) activity.getString(R.string.total_label_this_month)
            else activity.getString(R.string.total_label_fmt, periodLabel(p, today))
            Range.YEAR -> if (inNow) activity.getString(R.string.total_label_this_year)
            else activity.getString(R.string.total_label_fmt, periodLabel(p, today))
        }
    }

    /** Horizontal strip: days (45 around the anchor), months of the year, or years with data. */
    private fun renderStrip(today: LocalDate) {
        val strip = b.strip
        strip.removeAllViews()
        var selectedView: View? = null
        when (period.range) {
            Range.DAY -> {
                var end = period.anchor.plusDays(10)
                if (end.isAfter(today)) end = today
                var d = end.minusDays(44)
                while (!d.isAfter(end)) {
                    val day = d
                    val top = if (day.dayOfMonth == 1) "${day.monthValue}月"
                    else day.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.CHINA)
                    val v = stripChip(top, day.dayOfMonth.toString(), day == period.anchor) {
                        select(Period(Range.DAY, day))
                    }
                    if (day == period.anchor) selectedView = v
                    strip.addView(v)
                    d = d.plusDays(1)
                }
            }
            Range.MONTH -> {
                val y = period.anchor.year
                for (m in 1..12) {
                    val date = LocalDate.of(y, m, 1)
                    if (date.isAfter(today)) break
                    val sel = m == period.anchor.monthValue
                    val v = stripChip(y.toString(), "${m}月", sel) { select(Period(Range.MONTH, date)) }
                    if (sel) selectedView = v
                    strip.addView(v)
                }
            }
            Range.YEAR -> {
                for (y in minOf(firstYear, period.anchor.year)..today.year) {
                    val sel = y == period.anchor.year
                    val v = stripChip("", y.toString(), sel) { select(Period(Range.YEAR, LocalDate.of(y, 1, 1))) }
                    if (sel) selectedView = v
                    strip.addView(v)
                }
            }
        }
        Ui.applyGlass(strip)
        val target = selectedView ?: return
        b.stripScroll.post {
            val x = target.left - (b.stripScroll.width - target.width) / 2
            b.stripScroll.smoothScrollTo(x.coerceAtLeast(0), 0)
        }
    }

    private fun stripChip(top: String, main: String, selected: Boolean, onClick: () -> Unit): View {
        val ctx = activity
        val ink = ContextCompat.getColor(ctx, R.color.ls_ink)
        val muted = ContextCompat.getColor(ctx, R.color.ls_muted)
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundResource(if (selected) R.drawable.bg_day_chip_mint else R.drawable.bg_day_chip_white)
            tag = if (selected) null else "pill"
            minimumWidth = Ui.dp(ctx, 52f)
            val padH = Ui.dp(ctx, 12f)
            setPadding(padH, Ui.dp(ctx, 9f), padH, Ui.dp(ctx, 9f))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            if (top.isNotEmpty()) addView(TextView(ctx).apply {
                text = top
                textSize = 11f
                setTextColor(if (selected) ink else muted)
                typeface = ResourcesCompat.getFont(ctx, R.font.poppins_regular)
                gravity = Gravity.CENTER
            })
            addView(TextView(ctx).apply {
                text = main
                textSize = 16f
                setTextColor(ink)
                typeface = ResourcesCompat.getFont(ctx, R.font.poppins_semibold)
                gravity = Gravity.CENTER
                fontFeatureSettings = "tnum"
            })
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = Ui.dp(ctx, 8f) }
        }
    }

    // ---- hero ----

    private fun liveWithin(list: List<LiveSegment>, fromMs: Long, now: Long): Long =
        list.sumOf { (now - maxOf(it.startedAtMs, fromMs)).coerceAtLeast(0) }

    private fun renderLive(list: List<LiveSegment>) {
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val todayDate = LocalDate.now()
        val midnight = todayDate.atStartOfDay(zone).toInstant().toEpochMilli()
        val today = dbTodayMs + liveWithin(list, midnight, now)
        val top = list.firstOrNull()
        with(b) {
            if (top == null) {
                txtLiveCaption.setText(R.string.live_idle_hint)
                txtLiveTimer.text = Ui.formatClock(today)
                txtLiveTitle.setText(R.string.live_idle)
                txtLiveApp.isVisible = false
            } else {
                txtLiveCaption.setText(R.string.live_elapsed_label)
                txtLiveTimer.text = Ui.formatClock(now - top.startedAtMs)
                txtLiveTitle.text = top.title.ifBlank { activity.getString(R.string.live_unknown_title) }
                txtLiveApp.isVisible = true
                txtLiveApp.text = if (list.size > 1) {
                    activity.getString(R.string.live_more_fmt, top.appLabel, list.size)
                } else {
                    top.appLabel
                }
            }
            pillToday.text = activity.getString(R.string.pill_today_fmt, Ui.formatDuration(today))
            pillSessions.text = activity.getString(R.string.pill_sessions_fmt, todaySessions + list.size)
        }
        // Live in-progress time is added only when the selected period includes now.
        stats?.let { s ->
            if (todayDate in s.period) {
                val from = s.period.start.atStartOfDay(zone).toInstant().toEpochMilli()
                renderTotal(s.dbTotal + liveWithin(list, from, now))
            }
        }
    }

    /** Slow 3.6 s pulse behind the timer; only while playing, visible, and animations enabled. */
    private fun setBreathing(on: Boolean) {
        val v = b.breath
        val enabled = on && ValueAnimator.areAnimatorsEnabled() &&
            activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        if (enabled == isBreathing && (enabled == (breathing != null))) return
        isBreathing = enabled
        breathing?.cancel()
        breathing = null
        v.animate().cancel()
        if (enabled) {
            v.alpha = 0f
            v.scaleX = 0.9f
            v.scaleY = 0.9f
            breathing = ObjectAnimator.ofPropertyValuesHolder(
                v,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 0.9f, 1.08f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.9f, 1.08f),
                PropertyValuesHolder.ofFloat(View.ALPHA, 0.35f, 0.75f)
            ).apply {
                duration = 3_600L
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                interpolator = AccelerateDecelerateInterpolator()
                start()
            }
        } else if (ValueAnimator.areAnimatorsEnabled() && v.alpha > 0f) {
            v.animate().alpha(0f).scaleX(0.9f).scaleY(0.9f).setDuration(700L).start()
        } else {
            v.alpha = 0f
        }
    }

    // ---- stats ----

    private fun loadStats(animate: Boolean) {
        val p = period
        loadJob?.cancel()
        loadJob = activity.lifecycleScope.launch {
            val s = withContext(Dispatchers.IO) { queryStats(p) }
            if (animate && ValueAnimator.areAnimatorsEnabled()) {
                b.stats.animate().cancel()
                b.stats.animate().alpha(0f).setDuration(110L).withEndAction {
                    bindStats(s)
                    b.stats.animate().alpha(1f).setDuration(220L).start()
                }.start()
            } else {
                b.stats.alpha = 1f
                bindStats(s)
            }
        }
    }

    private suspend fun queryStats(p: Period): Stats {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now()
        val prefix = p.prefix
        val total = dao.totalMs(prefix) ?: 0L
        val isNow = today in p
        val buckets: LongArray
        val labels: Map<Int, String>
        val highlight: Int
        when (p.range) {
            Range.DAY -> {
                buckets = LongArray(24)
                val day0 = p.anchor.atStartOfDay(zone).toInstant().toEpochMilli()
                dao.spansForDay(prefix).forEach { span ->
                    for (h in 0 until 24) {
                        val hs = day0 + h * 3_600_000L
                        val he = hs + 3_600_000L
                        val overlap = minOf(he, span.endedAtMs) - maxOf(hs, span.startedAtMs)
                        if (overlap > 0) buckets[h] += overlap
                    }
                }
                labels = mapOf(0 to "0时", 6 to "6时", 12 to "12时", 18 to "18时", 23 to "23时")
                highlight = if (isNow) LocalTime.now().hour else -1
            }
            Range.MONTH -> {
                val n = p.anchor.lengthOfMonth()
                buckets = LongArray(n)
                dao.totalsByDay(prefix).forEach { row ->
                    val d = row.name.takeLast(2).toIntOrNull() ?: return@forEach
                    if (d in 1..n) buckets[d - 1] = row.totalMs
                }
                labels = listOf(1, 8, 15, 22, n).associate { (it - 1) to "${it}日" }
                highlight = if (isNow) today.dayOfMonth - 1 else -1
            }
            Range.YEAR -> {
                buckets = LongArray(12)
                dao.totalsByMonth(prefix).forEach { row ->
                    val m = row.name.takeLast(2).toIntOrNull() ?: return@forEach
                    if (m in 1..12) buckets[m - 1] = row.totalMs
                }
                labels = listOf(1, 4, 7, 10, 12).associate { (it - 1) to "${it}月" }
                highlight = if (isNow) today.monthValue - 1 else -1
            }
        }
        val apps = dao.topApps(prefix, 20).map { labelForApp(it.name) to it.totalMs }
        val titles = dao.topTitles(prefix, 20).map { it.name to it.totalMs }
        return Stats(
            p, total, buckets, labels, highlight, apps, titles,
            dao.countApps(prefix), dao.countTitles(prefix)
        )
    }

    private fun renderTotal(ms: Long) {
        val totalMin = ms / 60_000L
        b.numHours.text = (totalMin / 60).toString()
        b.numMinutes.text = (totalMin % 60).toString()
    }

    private fun bindStats(s: Stats) {
        stats = s
        val today = LocalDate.now()
        with(b) {
            txtTotalLabel.text = totalLabel(s.period, today)
            renderTotal(s.dbTotal)
            numApps.text = s.appCount.toString()
            numTitles.text = s.titleCount.toString()
            chart.setData(s.buckets, s.labels, s.highlight)
            val empty = s.dbTotal <= 0L
            cardEmpty.isVisible = empty
            cardApps.isVisible = !empty
            cardTitles.isVisible = !empty
            fillRanks(listApps, s.apps)
            fillRanks(listTitles, s.titles)
        }
        renderLive(LiveTracker.segments.value) // adds in-progress time if the period includes now
        Ui.applyGlass(b.root)
    }

    private fun fillRanks(container: LinearLayout, rows: List<Pair<String, Long>>) {
        container.removeAllViews()
        val max = rows.firstOrNull()?.second?.coerceAtLeast(1L) ?: 1L
        rows.forEachIndexed { i, (name, ms) ->
            val row = ItemRankBinding.inflate(activity.layoutInflater, container, false)
            row.rank.text = (i + 1).toString()
            row.name.text = name
            row.duration.text = Ui.formatDuration(ms)
            row.bar.setProgressCompat((ms * 1000 / max).toInt().coerceIn(0, 1000), false)
            container.addView(row.root)
        }
    }

    private fun labelForApp(packageName: String): String =
        try {
            val pm = activity.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        } catch (_: Exception) {
            packageName
        }
}
