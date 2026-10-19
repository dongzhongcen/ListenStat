package com.dongzhongcen.listenstat.ui

import android.animation.ValueAnimator
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.dongzhongcen.listenstat.R
import com.dongzhongcen.listenstat.data.AppDatabase
import com.dongzhongcen.listenstat.databinding.ItemRankBinding
import com.dongzhongcen.listenstat.databinding.PageAppDetailBinding
import com.dongzhongcen.listenstat.tracking.LiveSegment
import com.dongzhongcen.listenstat.tracking.LiveTracker
import com.google.android.material.datepicker.CalendarConstraints
import com.google.android.material.datepicker.DateValidatorPointBackward
import com.google.android.material.datepicker.MaterialDatePicker
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Local drill-down for one app: period total + Top titles under that package.
 * Period chips / navigator / strip mirror [StatsPage]; no network.
 */
class AppDetailPage(
    private val activity: MainActivity,
    private val b: PageAppDetailBinding,
    private val onClose: () -> Unit
) {
    private data class Detail(
        val period: Period,
        val dbTotal: Long,
        val titles: List<Pair<String, Long>>,
        val titleCount: Int
    )

    private val dao by lazy { AppDatabase.get(activity).playDao() }
    private var packageName: String = ""
    private var appLabel: String = ""
    private var period = Period(Range.DAY, LocalDate.now())
    private var firstYear = LocalDate.now().year
    private var detail: Detail? = null
    private var loadJob: Job? = null
    private var liveJob: Job? = null
    private var open = false

    val isOpen: Boolean get() = open

    fun init() {
        b.btnBack.setOnClickListener { close() }
        b.chipDay.setOnClickListener { setRange(Range.DAY) }
        b.chipMonth.setOnClickListener { setRange(Range.MONTH) }
        b.chipYear.setOnClickListener { setRange(Range.YEAR) }
        b.btnPrev.setOnClickListener { select(period.shift(-1)) }
        b.btnNext.setOnClickListener { if (!period.isLatest(LocalDate.now())) select(period.shift(1)) }
        b.txtPeriod.setOnClickListener { openPicker() }
    }

    fun open(packageName: String, appLabel: String, initial: Period) {
        this.packageName = packageName
        this.appLabel = appLabel
        this.period = initial
        open = true
        b.txtAppName.text = appLabel
        b.root.isVisible = true
        b.root.alpha = 0f
        b.root.animate().alpha(1f).setDuration(180L).start()
        renderChips()
        renderNavigator()
        load(animate = false)
        liveJob?.cancel()
        liveJob = activity.lifecycleScope.launch {
            LiveTracker.segments.collectLatest { list ->
                applyLive(list)
            }
        }
        activity.lifecycleScope.launch {
            firstYear = withContext(Dispatchers.IO) {
                dao.firstDayKey()?.take(4)?.toIntOrNull() ?: LocalDate.now().year
            }
        }
    }

    fun close() {
        if (!open) return
        open = false
        liveJob?.cancel()
        liveJob = null
        loadJob?.cancel()
        b.root.animate().cancel()
        b.root.isVisible = false
        onClose()
    }

    fun applyGlass() = Ui.applyGlass(b.root)

    // ---- period ----

    private fun setRange(r: Range) {
        if (r == period.range) return
        val today = LocalDate.now()
        select(Period(r, if (period.anchor.isAfter(today)) today else period.anchor))
    }

    private fun select(p: Period) {
        val today = LocalDate.now()
        val clamped = if (p.start.isAfter(today)) p.copy(anchor = today) else p
        if (clamped == period) return
        period = clamped
        renderChips()
        renderNavigator()
        load(animate = true)
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
                picker.show(activity.supportFragmentManager, "app_day")
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
                    val v = stripChip("", y.toString(), sel) {
                        select(Period(Range.YEAR, LocalDate.of(y, 1, 1)))
                    }
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

    // ---- data ----

    private fun load(animate: Boolean) {
        val p = period
        val pkg = packageName
        loadJob?.cancel()
        loadJob = activity.lifecycleScope.launch {
            val d = withContext(Dispatchers.IO) {
                Detail(
                    p,
                    dao.totalMsForApp(p.prefix, pkg) ?: 0L,
                    dao.topTitlesForApp(p.prefix, pkg, 20).map { it.name to it.totalMs },
                    dao.countTitlesForApp(p.prefix, pkg)
                )
            }
            if (animate && ValueAnimator.areAnimatorsEnabled()) {
                b.stats.animate().cancel()
                b.stats.animate().alpha(0f).setDuration(110L).withEndAction {
                    bind(d)
                    b.stats.animate().alpha(1f).setDuration(220L).start()
                }.start()
            } else {
                b.stats.alpha = 1f
                bind(d)
            }
        }
    }

    private fun bind(d: Detail) {
        detail = d
        val today = LocalDate.now()
        with(b) {
            txtTotalLabel.text = totalLabel(d.period, today)
            numTitles.text = d.titleCount.toString()
            val empty = d.dbTotal <= 0L && LiveTracker.segments.value.none {
                it.packageName == packageName && today in d.period
            }
            cardEmpty.isVisible = empty
            cardTitles.isVisible = !empty
            fillRanks(listTitles, d.titles)
        }
        applyLive(LiveTracker.segments.value)
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

    private fun applyLive(list: List<LiveSegment>) {
        val d = detail ?: return
        val today = LocalDate.now()
        if (today !in d.period) {
            renderTotal(d.dbTotal)
            return
        }
        val zone = ZoneId.systemDefault()
        val from = d.period.start.atStartOfDay(zone).toInstant().toEpochMilli()
        val now = System.currentTimeMillis()
        val live = list.filter { it.packageName == packageName }
            .sumOf { (now - maxOf(it.startedAtMs, from)).coerceAtLeast(0) }
        renderTotal(d.dbTotal + live)
        if (d.dbTotal + live > 0L) {
            b.cardEmpty.isVisible = false
            b.cardTitles.isVisible = true
        }
    }

    private fun renderTotal(ms: Long) {
        val totalMin = ms / 60_000L
        b.numHours.text = (totalMin / 60).toString()
        b.numMinutes.text = (totalMin % 60).toString()
    }
}
