package com.curax.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Single line chart: Medication Adherence Trend (Last 7 Days).
 * Y: 0-100%, X: 7 days. Green line, light grid, tooltip on touch.
 */
class AdherenceLineChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    data class DayPoint(
        val dateMillis: Long,
        val label: String,
        val expectedDoses: Int,
        val takenDoses: Int,
        val adherencePercent: Float? // null = no dose scheduled (gap)
    )

    var data: List<DayPoint> = emptyList()
        set(value) {
            if (field == value) {
                return
            }
            val shouldAnimate = field.isEmpty()
            field = value
            animator?.cancel()
            if (shouldAnimate) {
                animator = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 400
                    addUpdateListener { invalidate() }
                    start()
                }
            } else {
                animator = null
            }
            invalidate()
        }

    private var animator: ValueAnimator? = null
    private val dateFormat = SimpleDateFormat("d MMM", Locale.getDefault())

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.summary_stroke)
        style = Paint.Style.STROKE
        strokeWidth = 1f
        alpha = 120
    }

    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_secondary)
        textSize = 11f * resources.displayMetrics.density
    }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.chart_status_normal)
        style = Paint.Style.STROKE
        strokeWidth = 3f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.chart_status_normal)
        style = Paint.Style.FILL
    }

    private val tooltipBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.summary_card)
        style = Paint.Style.FILL
    }

    private val tooltipStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.summary_stroke)
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }

    private val tooltipTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_primary)
        textSize = 12f * resources.displayMetrics.density
    }

    private var touchX: Float = -1f
    private var touchY: Float = -1f
    private var highlightedIndex: Int = -1

    private val fullPath = Path()
    private val drawPath = Path()
    private val pathMeasure = android.graphics.PathMeasure()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (data.isEmpty()) return

        val w = width.toFloat()
        val h = height.toFloat()
        val padLeft = 40f
        val padRight = 24f
        val padTop = 20f
        val padBottom = 36f
        val chartW = w - padLeft - padRight
        val chartH = h - padTop - padBottom

        val animProgress = (animator?.animatedValue as? Float) ?: 1f
        val stepX = if (data.size > 1) chartW / (data.size - 1) else chartW

        // Y-axis labels and horizontal grid (0, 25, 50, 75, 100)
        for (i in 0..4) {
            val pct = i * 25
            val y = padTop + chartH * (1 - pct / 100f)
            canvas.drawLine(padLeft, y, padLeft + chartW, y, gridPaint)
            canvas.drawText("${pct}%", 6f, y + axisPaint.textSize / 3, axisPaint)
        }

        // X-axis labels and vertical grid
        data.forEachIndexed { i, point ->
            val x = padLeft + i * stepX
            canvas.drawLine(x, padTop, x, padTop + chartH, gridPaint)
            canvas.drawText(
                point.label,
                x - axisPaint.measureText(point.label) / 2,
                h - 10f,
                axisPaint
            )
        }

        // Build path (smooth line through points; gap where adherence is null)
        fullPath.reset()
        val points = data.mapIndexed { i, p ->
            val x = padLeft + i * stepX
            val y = when (p.adherencePercent) {
                null -> Float.NaN
                else -> padTop + chartH * (1 - (p.adherencePercent!!.coerceIn(0f, 100f) / 100f))
            }
            Triple(x, y, p)
        }

        var first = true
        points.forEachIndexed { i, (x, y, _) ->
            if (!y.isNaN()) {
                if (first) {
                    fullPath.moveTo(x, y)
                    first = false
                } else {
                    val prev = points[i - 1]
                    if (!prev.second.isNaN()) {
                        val midX = (prev.first + x) / 2
                        fullPath.cubicTo(prev.first, prev.second, midX, prev.second, midX, (prev.second + y) / 2)
                        fullPath.cubicTo(midX, (prev.second + y) / 2, x, y, x, y)
                    } else {
                        fullPath.moveTo(x, y)
                    }
                }
            }
        }

        // Animated segment
        pathMeasure.setPath(fullPath, false)
        val totalLen = pathMeasure.length
        drawPath.reset()
        if (totalLen > 0) {
            pathMeasure.getSegment(0f, totalLen * animProgress, drawPath, true)
            drawPath.rLineTo(0f, 0f)
        }
        canvas.drawPath(drawPath, linePaint)

        // Points
        val pointRadius = 5f
        points.forEachIndexed { i, (x, y, _) ->
            if (!y.isNaN()) {
                canvas.drawCircle(x, y, pointRadius, pointPaint)
                if (highlightedIndex == i) {
                    val r = pointRadius * 2.5f
                    canvas.drawCircle(x, y, r, tooltipStrokePaint)
                }
            }
        }

        // Tooltip
        if (highlightedIndex in data.indices) {
            val p = data[highlightedIndex]
            val pct = p.adherencePercent?.let { "%.0f%%".format(it) } ?: "-"
            val dateStr = dateFormat.format(p.dateMillis)
            val lines = listOf(
                dateStr,
                "Taken: ${p.takenDoses}",
                "Expected: ${p.expectedDoses}",
                "Adherence: $pct"
            )
            val lineH = tooltipTextPaint.textSize * 1.3f
            val boxW = lines.maxOf { tooltipTextPaint.measureText(it) } + 24
            val boxH = lineH * lines.size + 16
            val tx = touchX.coerceIn(padLeft, (w - padRight - boxW).coerceAtLeast(padLeft))
            val ty = (touchY - boxH - 16).coerceIn(padTop, (h - boxH - 8).coerceAtLeast(padTop))
            val rect = RectF(tx, ty, tx + boxW, ty + boxH)
            canvas.drawRoundRect(rect, 8f, 8f, tooltipBgPaint)
            canvas.drawRoundRect(rect, 8f, 8f, tooltipStrokePaint)
            lines.forEachIndexed { idx, line ->
                canvas.drawText(line, tx + 12, ty + 12 + (idx + 1) * lineH, tooltipTextPaint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                touchX = event.x
                touchY = event.y
                if (data.isEmpty()) return true
                val chartW = width - 40f - 24f
                val stepX = if (data.size > 1) chartW / (data.size - 1) else chartW
                val padLeft = 40f
                val idx = ((event.x - padLeft + stepX / 2) / stepX).toInt().coerceIn(0, data.size - 1)
                highlightedIndex = idx
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                highlightedIndex = -1
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.cancel()
    }
}

