package com.curax.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.max

/**
 * Lightweight 7-point sparkline; values are 0…1. Used on admin dashboard with live alert buckets or demo curve.
 */
class AdminSparklineChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * resources.displayMetrics.density
        color = ContextCompat.getColor(context, R.color.summary_stroke)
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val fillPath = Path()

    /** Normalized heights (length typically 7). */
    var series: FloatArray = floatArrayOf()
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
        val n = series.size
        if (n < 2) return

        val density = resources.displayMetrics.density
        val padL = 4f * density
        val padR = 4f * density
        val padT = 4f * density
        val padB = 6f * density
        val w = width.toFloat()
        val h = height.toFloat()
        val innerW = (w - padL - padR).coerceAtLeast(1f)
        val innerH = (h - padT - padB).coerceAtLeast(1f)

        val gridY = padT + innerH * 0.5f
        canvas.drawLine(padL, gridY, w - padR, gridY, gridPaint)

        path.reset()
        fillPath.reset()
        val step = innerW / (n - 1).coerceAtLeast(1)
        val lineTop = ContextCompat.getColor(context, R.color.chart_status_normal)
        val lineMid = ContextCompat.getColor(context, R.color.chart_status_monitor)
        linePaint.shader = LinearGradient(
            padL, padT, padL, padT + innerH,
            intArrayOf(lineTop, lineMid),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP,
        )
        if (placeholderMode) {
            linePaint.alpha = 150
        } else {
            linePaint.alpha = 255
        }

        for (i in 0 until n) {
            val x = padL + step * i
            val v = series[i].coerceIn(0f, 1f)
            val y = padT + innerH * (1f - v)
            if (i == 0) {
                path.moveTo(x, y)
                fillPath.moveTo(x, y)
            } else {
                path.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
        }

        val baseY = padT + innerH
        fillPath.lineTo(padL + step * (n - 1), baseY)
        fillPath.lineTo(padL, baseY)
        fillPath.close()

        val fillTop = ContextCompat.getColor(context, R.color.chart_status_normal)
        fillPaint.shader = LinearGradient(
            0f, padT, 0f, baseY,
            intArrayOf(
                (fillTop and 0x00FFFFFF) or (0x55 shl 24),
                (fillTop and 0x00FFFFFF) or (0x08 shl 24),
            ),
            null,
            Shader.TileMode.CLAMP,
        )
        if (placeholderMode) fillPaint.alpha = 120 else fillPaint.alpha = 200
        canvas.drawPath(fillPath, fillPaint)
        fillPaint.shader = null
        fillPaint.alpha = 255

        canvas.drawPath(path, linePaint)
        linePaint.shader = null
    }

    companion object {
        fun normalizeBuckets(buckets: IntArray): FloatArray {
            if (buckets.isEmpty()) return floatArrayOf()
            val mx = max(buckets.maxOrNull() ?: 0, 1)
            return FloatArray(buckets.size) { buckets[it].toFloat() / mx.toFloat() }
        }

        fun demoCurve(): FloatArray = floatArrayOf(0.28f, 0.46f, 0.40f, 0.58f, 0.50f, 0.68f, 0.54f)
    }
}
