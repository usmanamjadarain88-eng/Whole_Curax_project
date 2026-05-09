package com.curax.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils

/**
 * Lightweight animated footer for admin auth / PIN screens: soft blobs, a drifting wave line, and orbit dots.
 * Does not intercept touches; meant at the bottom of scroll content.
 */
class AdminAuthBottomDecorationView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density

    private val blobPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 14_000L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { invalidate() }
    }

    private val wavePath = Path()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 1f || h <= 1f) return

        val t = animator.animatedFraction
        val bob = kotlin.math.sin(t * Math.PI * 2).toFloat()
        val bob2 = kotlin.math.sin(t * Math.PI * 2 + 1.4).toFloat()

        val green = ContextCompat.getColor(context, R.color.chart_status_normal)
        val mint = ContextCompat.getColor(context, R.color.splash_orbit_ring)
        val hub = ContextCompat.getColor(context, R.color.splash_orbit_hub)

        drawBlob(canvas, w * (0.06f + bob * 0.04f), h * (0.48f + bob2 * 0.12f), w * 0.44f, mint, 0.42f)
        drawBlob(canvas, w * (0.82f - bob * 0.035f), h * (0.42f - bob2 * 0.1f), w * 0.34f, green, 0.32f)
        drawBlob(canvas, w * 0.48f + bob2 * w * 0.06f, h * 0.88f + bob * h * 0.06f, w * 0.4f, hub, 0.15f)

        val waveBase = h * 0.68f + bob * 10f * density
        wavePath.reset()
        val steps = 28
        for (i in 0..steps) {
            val xf = i / steps.toFloat()
            val x = w * xf
            val phase = t * Math.PI * 2.0 + xf * Math.PI * 3.0
            val y = waveBase + kotlin.math.sin(phase).toFloat() * 9f * density
            if (i == 0) wavePath.moveTo(x, y) else wavePath.lineTo(x, y)
        }
        wavePaint.shader = null
        wavePaint.color = ColorUtils.setAlphaComponent(green, 48)
        canvas.drawPath(wavePath, wavePaint)

        val cx = w * 0.52f + bob * w * 0.1f
        val cy = h * 0.36f + bob2 * h * 0.12f
        val orbitR = kotlin.math.min(w, h) * 0.15f
        for (i in 0 until 5) {
            val ang = (t * Math.PI * 2f + i * 1.28f).toDouble()
            val px = cx + kotlin.math.cos(ang).toFloat() * orbitR
            val py = cy + kotlin.math.sin(ang).toFloat() * orbitR * 0.52f
            dotPaint.color = ColorUtils.setAlphaComponent(hub, (70 + i * 22).coerceAtMost(165))
            canvas.drawCircle(px, py, (2.2f + i * 0.55f) * density, dotPaint)
        }
    }

    private fun drawBlob(canvas: Canvas, cx: Float, cy: Float, radius: Float, color: Int, alphaMul: Float) {
        val inner = ColorUtils.setAlphaComponent(color, (alphaMul * 210).toInt().coerceIn(28, 195))
        val outer = ColorUtils.setAlphaComponent(color, 0)
        blobPaint.shader = RadialGradient(
            cx,
            cy,
            radius,
            intArrayOf(inner, outer),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, radius, blobPaint)
        blobPaint.shader = null
    }
}
