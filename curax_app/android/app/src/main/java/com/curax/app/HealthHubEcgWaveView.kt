package com.curax.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import kotlin.math.floor
import kotlin.math.max

/**
 * Subtle scrolling ECG-style line for the standalone Health Hub hero (monitor feel, no flashy effects).
 * Survives ViewPager tab switches: animator pauses on detach and resumes on attach when [startAnimation] was used.
 */
class HealthHubEcgWaveView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val wavePath = Path()
    private var scrollPhase = 0f
    private var animator: ValueAnimator? = null
    private var wantsRunning = false

    /** One full horizontal scroll of the pattern (~1.5–2s typical). */
    var cycleDurationMs: Long = 2000L
        private set

    init {
        paint.color = ContextCompat.getColor(context, R.color.standalone_health_hub_ecg_line)
    }

    fun setCycleDurationMs(ms: Long) {
        cycleDurationMs = ms.coerceIn(900L, 4000L)
    }

    fun startAnimation() {
        wantsRunning = true
        if (animator?.isStarted == true) return
        ensureAnimatorRunning()
    }

    private fun ensureAnimatorRunning() {
        if (!wantsRunning || !isAttachedToWindow) return
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = cycleDurationMs
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                scrollPhase = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun stopAnimation() {
        wantsRunning = false
        animator?.cancel()
        animator = null
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ensureAnimatorRunning()
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        paint.strokeWidth = 1.75f * resources.displayMetrics.density
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 1 || height <= 1) return
        val w = width.toFloat()
        val h = height.toFloat()
        val midY = h * 0.52f
        val amp = h * 0.36f
        val waveLen = w * 1.2f
        val scroll = scrollPhase * waveLen
        val step = max(1.2f, w / 100f)

        wavePath.rewind()
        var x = 0f
        var first = true
        while (x <= w + step) {
            val u = fract((x - scroll) / waveLen)
            val y = midY - ecgSample(u, amp)
            if (first) {
                wavePath.moveTo(x, y)
                first = false
            } else {
                wavePath.lineTo(x, y)
            }
            x += step
        }
        canvas.drawPath(wavePath, paint)
    }

    private fun fract(x: Float): Float = x - floor(x)

    private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /**
     * Two QRS-style beats per pattern period (same scroll speed / cycleDurationMs).
     * u is phase along one repeat length; [fract(u*2)] drives two identical beats.
     */
    private fun ecgSample(u: Float, amp: Float): Float {
        val local = fract(u * 2f)
        return singleBeat(local, amp)
    }

    /** One beat in local ∈ [0,1): baseline → QRS → baseline. */
    private fun singleBeat(local: Float, amp: Float): Float {
        return when {
            local < 0.10f -> 0f
            local < 0.14f -> smoothstep(0.10f, 0.14f, local) * (0.10f * amp)
            local < 0.17f -> 0.10f * amp + smoothstep(0.14f, 0.17f, local) * (-0.17f * amp)
            local < 0.205f -> (0.10f - 0.17f) * amp + smoothstep(0.17f, 0.205f, local) * (1.0f * amp)
            local < 0.245f -> (0.10f - 0.17f + 1.0f) * amp + smoothstep(0.205f, 0.245f, local) * (-0.50f * amp)
            local < 0.30f -> {
                val plateau = (0.10f - 0.17f + 1.0f - 0.50f) * amp
                plateau + smoothstep(0.245f, 0.30f, local) * (-plateau)
            }
            else -> 0f
        }
    }
}
