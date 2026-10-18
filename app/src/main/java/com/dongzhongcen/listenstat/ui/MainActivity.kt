package com.dongzhongcen.listenstat.ui

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.ComponentName
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.dongzhongcen.listenstat.R
import com.dongzhongcen.listenstat.data.AppDatabase
import com.dongzhongcen.listenstat.data.NamedDuration
import com.dongzhongcen.listenstat.databinding.ActivityMainBinding
import com.dongzhongcen.listenstat.databinding.ItemRankBinding
import com.dongzhongcen.listenstat.service.MediaTrackService
import com.dongzhongcen.listenstat.tracking.LiveSegment
import com.dongzhongcen.listenstat.tracking.LiveTracker
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private enum class Range { DAY, MONTH, YEAR }

    private class Stats(
        val range: Range,
        val total: Long,
        val buckets: LongArray,
        val labels: Map<Int, String>,
        val highlight: Int,
        val apps: List<Pair<String, Long>>,
        val titles: List<NamedDuration>
    )

    private lateinit var binding: ActivityMainBinding
    private val dayFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val monthFmt = DateTimeFormatter.ofPattern("yyyy-MM")
    private val yearFmt = DateTimeFormatter.ofPattern("yyyy")

    private var range = Range.DAY
    /** Today's total already written to the DB (Room re-emits only on writes; UI never writes). */
    private var dbTodayMs = 0L
    private var liveJob: Job? = null
    private var loadJob: Job? = null
    private var breathing: ObjectAnimator? = null
    private var isPlaying = false

    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this) // Android 12+; teal/blue fallback otherwise
        super.onCreate(savedInstanceState)
        setupEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsets()

        binding.breath.backgroundTintList = ColorStateList.valueOf(
            MaterialColors.getColor(binding.breath, com.google.android.material.R.attr.colorPrimary)
        )

        binding.btnPermission.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            Toast.makeText(this, R.string.enable_listenstat, Toast.LENGTH_LONG).show()
        }

        binding.rangeGroup.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            val next = when (id) {
                R.id.btnMonth -> Range.MONTH
                R.id.btnYear -> Range.YEAR
                else -> Range.DAY
            }
            if (next != range) {
                range = next
                loadStats(animate = true)
            }
        }

        // Re-query only when the service actually writes a row (Room invalidation), no polling.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AppDatabase.get(this@MainActivity).playDao()
                    .totalMsFlow(LocalDate.now().format(dayFmt))
                    .collect { total ->
                        dbTodayMs = total ?: 0L
                        renderLive(LiveTracker.segments.value)
                        loadStats(animate = false)
                    }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Hero timer ticks once per second only while visible AND something is playing.
        liveJob = lifecycleScope.launch {
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

    override fun onResume() {
        super.onResume()
        binding.cardPermission.isVisible = !isNotificationAccessEnabled()
    }

    override fun onStop() {
        liveJob?.cancel()
        liveJob = null
        setBreathing(false)
        super.onStop()
    }

    // ---- window ----

    private fun setupEdgeToEdge() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !night
            isAppearanceLightNavigationBars = !night
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
    }

    private fun applyInsets() {
        val c = binding.content
        val top = c.paddingTop
        val bottom = c.paddingBottom
        val start = c.paddingStart
        val end = c.paddingEnd
        ViewCompat.setOnApplyWindowInsetsListener(binding.scroll) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            c.updatePadding(
                left = start + bars.left,
                top = top + bars.top,
                right = end + bars.right,
                bottom = bottom + bars.bottom
            )
            insets
        }
    }

    // ---- hero ----

    private fun renderLive(list: List<LiveSegment>) {
        val now = System.currentTimeMillis()
        val midnight = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val liveToday = list.sumOf { (now - maxOf(it.startedAtMs, midnight)).coerceAtLeast(0) }
        val today = dbTodayMs + liveToday
        val top = list.firstOrNull()
        with(binding) {
            if (top == null) {
                txtLiveCaption.setText(R.string.live_idle_hint)
                txtLiveTimer.text = formatClock(today)
                txtLiveTitle.setText(R.string.live_idle)
                txtLiveApp.isVisible = false
                txtLiveToday.isVisible = false
            } else {
                txtLiveCaption.setText(R.string.live_elapsed_label)
                txtLiveTimer.text = formatClock(now - top.startedAtMs)
                txtLiveTitle.text = top.title.ifBlank { getString(R.string.live_unknown_title) }
                txtLiveApp.isVisible = true
                txtLiveApp.text = if (list.size > 1) {
                    getString(R.string.live_more_fmt, top.appLabel, list.size)
                } else {
                    top.appLabel
                }
                txtLiveToday.isVisible = true
                txtLiveToday.text = getString(R.string.live_today_fmt, formatClock(today))
            }
        }
    }

    /** Slow 3.6s pulse behind the timer; only while playing, visible, and animations enabled. */
    private fun setBreathing(on: Boolean) {
        val v = binding.breath
        val enabled = on && ValueAnimator.areAnimatorsEnabled() &&
            lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        if (enabled == isPlaying && (enabled == (breathing != null))) return
        isPlaying = enabled
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
                PropertyValuesHolder.ofFloat(View.ALPHA, 0.14f, 0.34f)
            ).apply {
                duration = 3_600L
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                interpolator = AccelerateDecelerateInterpolator()
                start()
            }
        } else if (ValueAnimator.areAnimatorsEnabled() && v.alpha > 0f) {
            v.animate().alpha(0f).scaleX(0.9f).scaleY(0.9f).setDuration(700L).start() // gentle fade out
        } else {
            v.alpha = 0f
        }
    }

    // ---- stats ----

    private fun loadStats(animate: Boolean) {
        val r = range
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            val stats = withContext(Dispatchers.IO) { queryStats(r) }
            if (animate && ValueAnimator.areAnimatorsEnabled()) {
                binding.stats.animate().cancel()
                binding.stats.animate().alpha(0f).setDuration(120L).withEndAction {
                    bindStats(stats)
                    binding.stats.animate().alpha(1f).setDuration(240L).start()
                }.start()
            } else {
                binding.stats.alpha = 1f
                bindStats(stats)
            }
        }
    }

    private suspend fun queryStats(r: Range): Stats {
        val dao = AppDatabase.get(this).playDao()
        val today = LocalDate.now()
        val zone = ZoneId.systemDefault()
        val prefix = when (r) {
            Range.DAY -> today.format(dayFmt)
            Range.MONTH -> today.format(monthFmt)
            Range.YEAR -> today.format(yearFmt)
        }
        val total = dao.totalMs(prefix) ?: 0L
        val buckets: LongArray
        val labels: Map<Int, String>
        val highlight: Int
        when (r) {
            Range.DAY -> {
                buckets = LongArray(24)
                val day0 = today.atStartOfDay(zone).toInstant().toEpochMilli()
                dao.spansForDay(prefix).forEach { span ->
                    for (h in 0 until 24) {
                        val hs = day0 + h * 3_600_000L
                        val he = hs + 3_600_000L
                        val overlap = minOf(he, span.endedAtMs) - maxOf(hs, span.startedAtMs)
                        if (overlap > 0) buckets[h] += overlap
                    }
                }
                labels = mapOf(0 to "0时", 6 to "6时", 12 to "12时", 18 to "18时", 23 to "23时")
                highlight = java.time.LocalTime.now().hour
            }
            Range.MONTH -> {
                val n = today.lengthOfMonth()
                buckets = LongArray(n)
                dao.totalsByDay(prefix).forEach { row ->
                    val d = row.name.takeLast(2).toIntOrNull() ?: return@forEach
                    if (d in 1..n) buckets[d - 1] = row.totalMs
                }
                labels = listOf(1, 8, 15, 22, n).associate { (it - 1) to "${it}日" }
                highlight = today.dayOfMonth - 1
            }
            Range.YEAR -> {
                buckets = LongArray(12)
                dao.totalsByMonth(prefix).forEach { row ->
                    val m = row.name.takeLast(2).toIntOrNull() ?: return@forEach
                    if (m in 1..12) buckets[m - 1] = row.totalMs
                }
                labels = listOf(1, 4, 7, 10, 12).associate { (it - 1) to "${it}月" }
                highlight = today.monthValue - 1
            }
        }
        val apps = dao.topApps(prefix, 20).map { labelForApp(it.name) to it.totalMs }
        val titles = dao.topTitles(prefix, 20)
        return Stats(r, total, buckets, labels, highlight, apps, titles)
    }

    private fun bindStats(s: Stats) {
        with(binding) {
            txtTotalLabel.setText(
                when (s.range) {
                    Range.DAY -> R.string.total_label_day
                    Range.MONTH -> R.string.total_label_month
                    Range.YEAR -> R.string.total_label_year
                }
            )
            txtTotal.text = formatDuration(s.total)
            chart.setData(s.buckets, s.labels, s.highlight)
            val empty = s.total <= 0L
            cardEmpty.isVisible = empty
            cardApps.isVisible = !empty
            cardTitles.isVisible = !empty
            fillRanks(listApps, s.apps)
            fillRanks(listTitles, s.titles.map { it.name to it.totalMs })
        }
    }

    private fun fillRanks(container: LinearLayout, rows: List<Pair<String, Long>>) {
        container.removeAllViews()
        val max = rows.firstOrNull()?.second?.coerceAtLeast(1L) ?: 1L
        rows.forEachIndexed { i, (name, ms) ->
            val row = ItemRankBinding.inflate(layoutInflater, container, false)
            row.rank.text = (i + 1).toString()
            row.name.text = name
            row.duration.text = formatDuration(ms)
            row.bar.setProgressCompat((ms * 1000 / max).toInt().coerceIn(0, 1000), false)
            container.addView(row.root)
        }
    }

    // ---- helpers ----

    private fun labelForApp(packageName: String): String =
        try {
            val pm = packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        } catch (_: Exception) {
            packageName
        }

    private fun formatDuration(ms: Long): String {
        val h = TimeUnit.MILLISECONDS.toHours(ms)
        val m = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
        val s = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
        return when {
            h > 0 -> "${h}小时${m}分"
            m > 0 -> "${m}分${s}秒"
            else -> "${s}秒"
        }
    }

    private fun formatClock(ms: Long): String {
        val total = TimeUnit.MILLISECONDS.toSeconds(ms.coerceAtLeast(0))
        return "%d:%02d:%02d".format(total / 3600, (total / 60) % 60, total % 60)
    }

    private fun isNotificationAccessEnabled(): Boolean {
        val cn = ComponentName(this, MediaTrackService::class.java)
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return flat?.contains(cn.flattenToString()) == true
    }
}
