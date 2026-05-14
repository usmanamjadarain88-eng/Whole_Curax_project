package com.curax.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Hub connection readiness: headline % + twin pill segments (linked vs pending) + legend rows.
 */
class AdminConnectionReadinessView @JvmOverloads constructor(
    ctx: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(ctx, attrs, defStyleAttr) {

    private val tvPercent = TextView(ctx)
    private val tvHint = TextView(ctx)
    private val bar = SegmentedBarView(ctx)
    private val tvLinked = TextView(ctx)
    private val tvPending = TextView(ctx)
    private var displayedPct = -1
    private var pctAnimator: ValueAnimator? = null

    init {
        orientation = VERTICAL
        val pad = dp(2)
        setPadding(pad, 0, pad, 0)

        tvPercent.apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setTextColor(ContextCompat.getColor(ctx, R.color.admin_insights_chart_muted))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
            typeface = android.graphics.Typeface.create(typeface, android.graphics.Typeface.BOLD)
            includeFontPadding = false
        }
        tvHint.apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            includeFontPadding = false
            visibility = GONE
        }
        val barLp = LayoutParams(LayoutParams.MATCH_PARENT, dp(26)).apply {
            topMargin = dp(14)
        }
        tvLinked.apply {
            setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            includeFontPadding = false
            typeface = android.graphics.Typeface.create(typeface, android.graphics.Typeface.BOLD)
        }
        tvPending.apply {
            setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            includeFontPadding = false
            typeface = android.graphics.Typeface.create(typeface, android.graphics.Typeface.BOLD)
        }

        addView(tvPercent, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(tvHint, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(4)
        })
        addView(bar, barLp)
        addView(tvLinked, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(18)
        })
        addView(tvPending, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(10)
        })

        attachLegendDot(tvLinked, R.drawable.bg_dot_chart_linked)
        attachLegendDot(tvPending, R.drawable.bg_dot_chart_pending)
    }

    private fun attachLegendDot(tv: TextView, drawableRes: Int) {
        val d = ContextCompat.getDrawable(context, drawableRes)?.mutate() ?: return
        val px = dp(10)
        d.setBounds(0, 0, px, px)
        tv.setCompoundDrawablesRelative(d, null, null, null)
        tv.compoundDrawablePadding = dp(8)
    }

    fun bind(linked: Int, pending: Int, placeholder: Boolean) {
        val total = max(1, linked + pending)
        val pct = when {
            placeholder -> 0
            linked + pending == 0 -> 0
            else -> (linked * 100 / total).coerceIn(0, 100)
        }

        tvHint.visibility = if (linked + pending > 0 && !placeholder) VISIBLE else GONE
        tvHint.text = context.getString(R.string.admin_hub_readiness_accounts_fmt, linked + pending)

        tvLinked.text = context.getString(R.string.admin_hub_legend_linked_fmt, linked)
        tvPending.text = context.getString(R.string.admin_hub_legend_pending_fmt, pending)

        bar.setState(linked, pending, placeholder)

        if (placeholder) {
            pctAnimator?.cancel()
            displayedPct = -1
            tvPercent.setTextColor(ContextCompat.getColor(context, R.color.subtitle_grey))
            tvPercent.text = context.getString(R.string.admin_hub_readiness_pct_placeholder)
            tvPercent.alpha = 0.72f
            return
        }
        tvPercent.setTextColor(ContextCompat.getColor(context, R.color.admin_insights_chart_muted))
        tvPercent.alpha = 1f
        val from = if (displayedPct >= 0) displayedPct else 0
        animatePercent(from, pct)
    }

    private fun animatePercent(from: Int, to: Int) {
        pctAnimator?.cancel()
        if (from == to) {
            displayedPct = to
            tvPercent.text = context.getString(R.string.admin_hub_readiness_pct_fmt, to)
            return
        }
        pctAnimator = ValueAnimator.ofInt(from.coerceIn(0, 100), to).apply {
            duration = 420L
            addUpdateListener { a ->
                val v = a.animatedValue as Int
                tvPercent.text = context.getString(R.string.admin_hub_readiness_pct_fmt, v)
            }
            start()
        }
        displayedPct = to
    }

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        v.toFloat(),
        resources.displayMetrics,
    ).roundToInt()

    private class SegmentedBarView(context: Context) : View(context) {

        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val linkedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val pendingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
        }
        private val rect = RectF()
        private val path = Path()
        private var linked = 0
        private var pending = 0
        private var placeholder = true

        fun setState(linked: Int, pending: Int, placeholder: Boolean) {
            this.linked = linked
            this.pending = pending
            this.placeholder = placeholder
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val density = resources.displayMetrics.density
            val h = height.toFloat()
            val w = width.toFloat()
            if (w <= 0f || h <= 0f) return

            val capR = min(h / 2f, 13f * density)
            val gap = 5f * density
            val innerR = 3.5f * density

            trackPaint.color = ContextCompat.getColor(context, R.color.hub_readiness_bar_track)
            rect.set(0f, 0f, w, h)
            canvas.drawRoundRect(rect, capR, capR, trackPaint)

            val linkedRatio = when {
                placeholder -> 0.72f
                linked == 0 && pending == 0 -> 0.72f
                pending == 0 -> 1f
                linked == 0 -> 0f
                else -> linked.toFloat() / (linked + pending).toFloat()
            }

            val useGap = linkedRatio > 0.04f && linkedRatio < 0.96f
            val g = if (useGap) gap else 0f
            val avail = (w - g).coerceAtLeast(0f)
            val leftW = avail * linkedRatio

            val linkedTop = ContextCompat.getColor(
                context,
                if (placeholder) R.color.hub_readiness_placeholder_linked else R.color.hub_readiness_linked,
            )
            val linkedBot = adjustBrightness(linkedTop, 0.82f)
            val pendingTop = ContextCompat.getColor(
                context,
                if (placeholder) R.color.hub_readiness_placeholder_pending else R.color.hub_readiness_pending,
            )
            val pendingBot = adjustBrightness(pendingTop, 0.85f)

            when {
                linkedRatio <= 0.001f -> {
                    fillVerticalGradient(pendingPaint, pendingTop, pendingBot)
                    drawFullPill(canvas, 0f, w, h, capR, pendingPaint)
                }
                linkedRatio >= 0.999f -> {
                    fillVerticalGradient(linkedPaint, linkedTop, linkedBot)
                    drawFullPill(canvas, 0f, w, h, capR, linkedPaint)
                }
                else -> {
                    fillVerticalGradient(linkedPaint, linkedTop, linkedBot)
                    rect.set(0f, 0f, leftW, h)
                    path.reset()
                    path.addRoundRect(
                        rect,
                        floatArrayOf(capR, capR, innerR, innerR, innerR, innerR, capR, capR),
                        Path.Direction.CW,
                    )
                    canvas.drawPath(path, linkedPaint)

                    fillVerticalGradient(pendingPaint, pendingTop, pendingBot)
                    val x0 = leftW + g
                    rect.set(x0, 0f, w, h)
                    path.reset()
                    path.addRoundRect(
                        rect,
                        floatArrayOf(innerR, innerR, capR, capR, capR, capR, innerR, innerR),
                        Path.Direction.CW,
                    )
                    canvas.drawPath(path, pendingPaint)
                }
            }

            linkedPaint.shader = null
            pendingPaint.shader = null

            rimPaint.strokeWidth = 1f * density
            rimPaint.color = ContextCompat.getColor(context, R.color.hub_readiness_bar_gloss)
            rect.set(0.5f * density, 0.5f * density, w - 0.5f * density, h - 0.5f * density)
            canvas.drawRoundRect(rect, (capR - 0.5f * density).coerceAtLeast(0f), (capR - 0.5f * density).coerceAtLeast(0f), rimPaint)
        }

        private fun drawFullPill(canvas: Canvas, x0: Float, x1: Float, h: Float, capR: Float, paint: Paint) {
            rect.set(x0, 0f, x1, h)
            canvas.drawRoundRect(rect, capR, capR, paint)
        }

        private fun fillVerticalGradient(p: Paint, top: Int, bottom: Int) {
            p.shader = LinearGradient(
                0f,
                0f,
                0f,
                height.toFloat(),
                top,
                bottom,
                Shader.TileMode.CLAMP,
            )
        }

        private fun adjustBrightness(rgb: Int, factor: Float): Int {
            val r = ((rgb shr 16) and 0xff) * factor
            val g = ((rgb shr 8) and 0xff) * factor
            val b = (rgb and 0xff) * factor
            return (0xff shl 24) or
                (r.coerceIn(0f, 255f).toInt() shl 16) or
                (g.coerceIn(0f, 255f).toInt() shl 8) or
                b.coerceIn(0f, 255f).toInt()
        }
    }
}
