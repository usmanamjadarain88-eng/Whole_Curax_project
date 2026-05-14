package com.curax.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.max

/** Seven vertical bars for the same 7-day window as the dashboard sparkline. */
class AdminBarChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * resources.displayMetrics.density
        color = ContextCompat.getColor(context, R.color.summary_stroke)
    }

    /** Raw counts per day (length 7). */
    var counts: IntArray = IntArray(7)
        set(value) {
            field = value
            invalidate()
        }

    var placeholderMode: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val n = 7
        val density = resources.displayMetrics.density
        val padL = 6f * density
        val padR = 6f * density
        val padB = 6f * density
        val padT = 8f * density
        val w = width.toFloat()
        val h = height.toFloat()
        val chartW = w - padL - padR
        val chartH = h - padT - padB
        val baseY = padT + chartH
        canvas.drawLine(padL, baseY, w - padR, baseY, axisPaint)

        val mx = max(counts.maxOrNull() ?: 0, 1)
        val gap = 4f * density
        val barSlot = (chartW - gap * (n - 1)) / n

        barPaint.color = ContextCompat.getColor(context, R.color.admin_insights_chart_muted)
        if (placeholderMode) barPaint.alpha = 140 else barPaint.alpha = 255

        for (i in 0 until n) {
            val norm = if (placeholderMode) {
                AdminSparklineChartView.demoCurve().getOrElse(i) { 0.4f }
            } else {
                (counts.getOrElse(i) { 0 }).toFloat() / mx.toFloat()
            }
            val barH = chartH * norm.coerceIn(0f, 1f)
            val left = padL + i * (barSlot + gap)
            val top = baseY - barH
            val r = RectF(left, top, left + barSlot, baseY)
            canvas.drawRoundRect(r, 4f * density, 4f * density, barPaint)
        }
        barPaint.alpha = 255
    }
}
