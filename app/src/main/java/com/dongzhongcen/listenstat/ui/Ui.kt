package com.dongzhongcen.listenstat.ui

import android.content.Context
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import java.time.LocalTime
import java.util.concurrent.TimeUnit
import com.dongzhongcen.listenstat.R

/** Small shared UI helpers. */
object Ui {
    const val GLASS_CARD_ALPHA = 200
    const val GLASS_PILL_ALPHA = 228

    /** Whether a wallpaper is shown; cards/pills go semi-transparent. */
    var glass = false

    /** Applies the current [glass] state to every tagged view below [root]. */
    fun applyGlass(root: View) {
        when (root) {
            is NotchedCardView -> root.fillAlpha = if (glass) GLASS_CARD_ALPHA else 255
        }
        when (root.tag) {
            "glass" -> root.background?.mutate()?.alpha = if (glass) GLASS_CARD_ALPHA else 255
            "pill" -> root.background?.mutate()?.alpha = if (glass) GLASS_PILL_ALPHA else 255
        }
        if (root is ViewGroup) for (i in 0 until root.childCount) applyGlass(root.getChildAt(i))
    }

    fun greeting(ctx: Context): String = ctx.getString(
        when (LocalTime.now().hour) {
            in 5..10 -> R.string.greeting_morning
            in 11..12 -> R.string.greeting_noon
            in 13..17 -> R.string.greeting_afternoon
            in 18..22 -> R.string.greeting_evening
            else -> R.string.greeting_night
        }
    )

    fun formatDuration(ms: Long): String {
        val h = TimeUnit.MILLISECONDS.toHours(ms)
        val m = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
        val s = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
        return when {
            h > 0 -> "${h}小时${m}分"
            m > 0 -> "${m}分${s}秒"
            else -> "${s}秒"
        }
    }

    fun formatClock(ms: Long): String {
        val total = TimeUnit.MILLISECONDS.toSeconds(ms.coerceAtLeast(0))
        return "%d:%02d:%02d".format(total / 3600, (total / 60) % 60, total % 60)
    }

    fun dp(ctx: Context, v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics).toInt()
}
