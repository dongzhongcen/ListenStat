package com.dongzhongcen.listenstat.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.Gravity
import android.view.LayoutInflater
import android.widget.GridLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.isVisible
import com.dongzhongcen.listenstat.R
import com.dongzhongcen.listenstat.databinding.SheetBackupBinding
import com.dongzhongcen.listenstat.databinding.SheetPeriodBinding
import com.dongzhongcen.listenstat.databinding.SheetUpdateBinding
import com.dongzhongcen.listenstat.update.ReleaseInfo
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.time.LocalDate

/** Mint bottom sheets with a black pill toolbar. */
object Sheets {

    fun openUrl(ctx: Context, url: String) {
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(ctx, url, Toast.LENGTH_LONG).show()
        }
    }

    private fun dialog(ctx: Context) = BottomSheetDialog(ctx).apply {
        behavior.state = BottomSheetBehavior.STATE_EXPANDED
        behavior.skipCollapsed = true
    }

    fun update(ctx: Context, release: ReleaseInfo, currentVersion: String) {
        val d = dialog(ctx)
        val b = SheetUpdateBinding.inflate(LayoutInflater.from(ctx))
        b.txtTitle.text = ctx.getString(R.string.update_found_fmt, release.version)
        b.txtSubtitle.text = buildString {
            append(ctx.getString(R.string.update_current_fmt, currentVersion))
            if (release.name.isNotBlank() && release.name != release.version) append(" · ").append(release.name)
        }
        b.txtNotes.text = release.notes.trim().ifBlank { ctx.getString(R.string.update_no_notes) }
        // keep the notes box bounded so the toolbar stays on screen
        b.notesScroll.layoutParams.height = minOf(
            Ui.dp(ctx, 300f),
            (ctx.resources.displayMetrics.heightPixels * 0.42f).toInt()
        )
        b.btnLater.setOnClickListener { d.dismiss() }
        b.btnPage.setOnClickListener { openUrl(ctx, release.pageUrl); d.dismiss() }
        b.btnApk.isVisible = release.apkUrl != null
        b.btnApk.setOnClickListener { release.apkUrl?.let { openUrl(ctx, it) }; d.dismiss() }
        d.setContentView(b.root)
        d.show()
    }

    fun backup(ctx: Context, onExport: () -> Unit, onImport: () -> Unit) {
        val d = dialog(ctx)
        val b = SheetBackupBinding.inflate(LayoutInflater.from(ctx))
        b.btnExport.setOnClickListener { d.dismiss(); onExport() }
        b.btnImport.setOnClickListener { d.dismiss(); onImport() }
        b.btnClose.setOnClickListener { d.dismiss() }
        d.setContentView(b.root)
        d.show()
    }

    /** Month picker: year arrows + 12 month pills; future months disabled. */
    fun month(ctx: Context, selected: LocalDate, today: LocalDate, minYear: Int, onPick: (LocalDate) -> Unit) {
        val d = dialog(ctx)
        val b = SheetPeriodBinding.inflate(LayoutInflater.from(ctx))
        b.txtTitle.setText(R.string.pick_month)
        var year = selected.year
        fun render() {
            b.txtYear.text = "${year}年"
            b.btnYearPrev.isEnabled = year > minYear
            b.btnYearPrev.alpha = if (year > minYear) 1f else 0.3f
            b.btnYearNext.isEnabled = year < today.year
            b.btnYearNext.alpha = if (year < today.year) 1f else 0.3f
            b.grid.removeAllViews()
            for (m in 1..12) {
                val date = LocalDate.of(year, m, 1)
                val future = date.isAfter(today)
                val isSel = year == selected.year && m == selected.monthValue
                b.grid.addView(cell(ctx, "${m}月", isSel, !future) {
                    d.dismiss(); onPick(date)
                })
            }
        }
        b.btnYearPrev.setOnClickListener { year--; render() }
        b.btnYearNext.setOnClickListener { year++; render() }
        b.btnCurrent.setText(R.string.this_month)
        b.btnCurrent.setOnClickListener { d.dismiss(); onPick(today) }
        b.btnClose.setOnClickListener { d.dismiss() }
        render()
        d.setContentView(b.root)
        d.show()
    }

    fun year(ctx: Context, selected: Int, years: IntRange, today: LocalDate, onPick: (Int) -> Unit) {
        val d = dialog(ctx)
        val b = SheetPeriodBinding.inflate(LayoutInflater.from(ctx))
        b.txtTitle.setText(R.string.pick_year)
        b.yearRow.isVisible = false
        for (y in years.reversed()) {
            b.grid.addView(cell(ctx, y.toString(), y == selected, true) { d.dismiss(); onPick(y) })
        }
        b.btnCurrent.setText(R.string.this_year)
        b.btnCurrent.setOnClickListener { d.dismiss(); onPick(today.year) }
        b.btnClose.setOnClickListener { d.dismiss() }
        d.setContentView(b.root)
        d.show()
    }

    private fun cell(ctx: Context, text: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit): TextView {
        val m = Ui.dp(ctx, 4f)
        return TextView(ctx).apply {
            this.text = text
            gravity = Gravity.CENTER
            textSize = 15f
            typeface = ResourcesCompat.getFont(ctx, R.font.poppins_medium)
            setTextColor(ContextCompat.getColor(ctx, if (selected) R.color.ls_white else R.color.ls_ink))
            setBackgroundResource(if (selected) R.drawable.bg_pill_ink else R.drawable.bg_pill_white)
            isEnabled = enabled
            alpha = if (enabled) 1f else 0.35f
            if (enabled) setOnClickListener { onClick() }
            layoutParams = GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED),
                GridLayout.spec(GridLayout.UNDEFINED, 1f)
            ).apply {
                width = 0
                height = Ui.dp(ctx, 46f)
                setMargins(m, m, m, m)
            }
        }
    }
}
