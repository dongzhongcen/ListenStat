package com.dongzhongcen.listenstat.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import com.google.android.material.color.MaterialColors

/** Minimal native bar chart: rounded bars + a few axis labels. No library. */
class BarChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var values: LongArray = LongArray(0)
    private var labels: Map<Int, String> = emptyMap()
    private var highlight = -1

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MaterialColors.getColor(this@BarChartView, com.google.android.material.R.attr.colorPrimary)
    }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MaterialColors.getColor(this@BarChartView, com.google.android.material.R.attr.colorSurfaceContainerHigh)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MaterialColors.getColor(this@BarChartView, com.google.android.material.R.attr.colorOnSurfaceVariant)
        textSize = sp(11f)
        textAlign = Paint.Align.CENTER
    }
    private val rect = RectF()

    /**
     * @param values one entry per bucket
     * @param labels bucket index -> label (only a few, to stay airy)
     * @param highlight bucket to draw at full strength (e.g. current hour); others slightly muted
     */
    fun setData(values: LongArray, labels: Map<Int, String>, highlight: Int = -1) {
        this.values = values
        this.labels = labels
        this.highlight = highlight
        contentDescription = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val n = values.size
        if (n == 0) return
        val labelH = sp(11f) + dp(8f)
        val chartH = height - paddingTop - paddingBottom - labelH
        val w = (width - paddingLeft - paddingRight).toFloat()
        val slot = w / n
        val barW = (slot * 0.62f).coerceAtMost(dp(18f))
        val radius = barW / 2f
        val max = values.maxOrNull()?.coerceAtLeast(1L) ?: 1L
        val bottom = paddingTop + chartH

        for (i in 0 until n) {
            val cx = paddingLeft + slot * i + slot / 2f
            // faint track so empty buckets still read as a rhythm
            rect.set(cx - barW / 2f, bottom - dp(3f), cx + barW / 2f, bottom)
            canvas.drawRoundRect(rect, radius, radius, trackPaint)
            val v = values[i]
            if (v > 0) {
                val h = (chartH * v / max.toFloat()).coerceAtLeast(dp(3f))
                rect.set(cx - barW / 2f, bottom - h, cx + barW / 2f, bottom)
                barPaint.alpha = if (highlight < 0 || i == highlight) 255 else 170
                canvas.drawRoundRect(rect, radius, radius, barPaint)
            }
            labels[i]?.let { canvas.drawText(it, cx, height - paddingBottom - dp(2f), textPaint) }
        }
    }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)
}
