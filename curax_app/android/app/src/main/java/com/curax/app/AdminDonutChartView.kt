package com.curax.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat

/** Ring chart for linked vs pending accounts on admin dashboard. */
class AdminDonutChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    data class Slice(val fraction: Float, val color: Int)

    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = ContextCompat.getColor(context, R.color.text_primary)
    }
    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = ContextCompat.getColor(context, R.color.text_secondary)
    }

    private val oval = RectF()
    private var slices: List<Slice> = emptyList()
    private var centerTitle: String = ""
    private var centerSubtitle: String = ""
    private var placeholderAlpha = 255

    fun setSlices(
        data: List<Slice>,
        title: String,
        subtitle: String,
        placeholder: Boolean,
    ) {
        slices = data
        centerTitle = title
        centerSubtitle = subtitle
        placeholderAlpha = if (placeholder) 160 else 255
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val density = resources.displayMetrics.density
        val stroke = 26f * density
        arcPaint.strokeWidth = stroke

        val pad = stroke * 0.55f
        oval.set(pad, pad, width - pad, height - pad)

        val total = slices.sumOf { it.fraction.toDouble() }.toFloat().coerceAtLeast(0.0001f)
        var angle = -90f
        for (sl in slices) {
            val sweep = 360f * (sl.fraction / total)
            if (sweep <= 0.05f) continue
            arcPaint.color = sl.color
            arcPaint.alpha = placeholderAlpha
            canvas.drawArc(oval, angle, sweep - 0.8f, false, arcPaint)
            angle += sweep
        }
        arcPaint.alpha = 255

        val cx = width / 2f
        val cy = height / 2f - 4f * density
        centerPaint.textSize = 26f * density
        centerPaint.alpha = placeholderAlpha
        subPaint.textSize = 11f * density
        subPaint.alpha = placeholderAlpha

        if (centerTitle.isNotEmpty()) {
            canvas.drawText(centerTitle, cx, cy, centerPaint)
        }
        if (centerSubtitle.isNotEmpty()) {
            canvas.drawText(centerSubtitle, cx, cy + 18f * density, subPaint)
        }
        centerPaint.alpha = 255
        subPaint.alpha = 255
    }
}
