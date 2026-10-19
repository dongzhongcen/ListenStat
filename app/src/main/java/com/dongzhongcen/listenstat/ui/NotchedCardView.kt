package com.dongzhongcen.listenstat.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.CornerPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.core.content.ContextCompat
import com.dongzhongcen.listenstat.R

/**
 * Flat hero-card background: mint gradient (lighter toward the top), large radius, and a
 * square notch cut out of the bottom-right corner (all corners rounded by one CornerPathEffect),
 * leaving room for the round action button that sits in it.
 */
class NotchedCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val radius = dp(28f)
    /** Side of the notch; button (62dp) + 2 x 4dp margin. */
    var notch = dp(70f)
        set(v) { field = v; invalidate() }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        pathEffect = CornerPathEffect(radius)
    }
    private val path = Path()

    /** 0..255; lowered over a wallpaper. */
    var fillAlpha: Int = 255
        set(v) { field = v; paint.alpha = v; invalidate() }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        paint.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            ContextCompat.getColor(context, R.color.ls_mint_light),
            ContextCompat.getColor(context, R.color.ls_mint),
            Shader.TileMode.CLAMP
        )
        paint.alpha = fillAlpha
        val W = w.toFloat()
        val H = h.toFloat()
        val n = notch
        path.reset()
        path.moveTo(0f, H / 2f) // start mid-edge so the first corner is rounded too
        path.lineTo(0f, 0f)
        path.lineTo(W, 0f)
        path.lineTo(W, H - n)
        path.lineTo(W - n, H - n)
        path.lineTo(W - n, H)
        path.lineTo(0f, H)
        path.close()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawPath(path, paint)
    }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
}
