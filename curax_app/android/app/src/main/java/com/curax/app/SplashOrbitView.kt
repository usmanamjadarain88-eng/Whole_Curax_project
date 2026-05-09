package com.curax.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

private data class MedRingSlot(val ring: Int, val angleDeg: Float, val iconRes: Int)

private data class DoctorSlot(val angleDeg: Float, val drawableRes: Int)

/**
 * Three stroked rings; clinical icons only on middle + outer rings (inner stays clear around hub);
 * PNG doctors sit outside the outer ring; launcher in the center.
 */
class SplashOrbitView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = ContextCompat.getColor(context, R.color.splash_orbit_ring)
    }

    /** Soft plate behind launcher only — not a fourth orbit ring. */
    private val hubPlatePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.splash_hub_plate)
    }

    private val medBlobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.splash_orbit_icon_bg)
    }

    private val hubClipPath = Path()

    /**
     * Inner ring has no clinical icons (clean hub). Former inner-ring icons sit on
     * middle + outer only; 3 per ring, staggered 60°.
     */
    private val medSlots = listOf(
        MedRingSlot(1, -90f, R.drawable.ic_splash_med_pediatrics),
        MedRingSlot(1, 30f, R.drawable.ic_splash_med_medication),
        MedRingSlot(1, 150f, R.drawable.ic_splash_med_stethoscope),
        MedRingSlot(2, -30f, R.drawable.ic_splash_med_clipboard),
        MedRingSlot(2, 90f, R.drawable.ic_splash_med_hospital_cross),
        MedRingSlot(2, 210f, R.drawable.ic_splash_med_cardiology),
    )

    /** ~310° ≈ top-right on screen; new crossed-arms asset there. */
    private val doctorSlots = listOf(
        DoctorSlot(70f, R.drawable.splash_doctor_male_wave),
        DoctorSlot(190f, R.drawable.splash_doctor_female_chibi),
        DoctorSlot(310f, R.drawable.splash_doctor_male_crossed),
    )

    private var orbitPhaseDeg = 0f
    private val orbitAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 34_000L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            orbitPhaseDeg = it.animatedValue as Float
            invalidate()
        }
    }

    private val launcherDrawable = ContextCompat.getDrawable(context, R.drawable.ic_launcher_inset)

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    /** Radii must match the three [canvas.drawCircle] calls in [onDraw]. */
    private fun ringRadius(maxR: Float, ring: Int): Float = when (ring) {
        0 -> maxR * INNER_RING_FRAC
        1 -> maxR * MID_RING_FRAC
        2 -> maxR * OUT_RING_FRAC
        else -> maxR * MID_RING_FRAC
    }

    private fun drawDrawablePreservingAspect(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        maxSide: Float,
        resId: Int,
    ) {
        val d = ContextCompat.getDrawable(context, resId) ?: return
        val iw = d.intrinsicWidth.toFloat().coerceAtLeast(1f)
        val ih = d.intrinsicHeight.toFloat().coerceAtLeast(1f)
        val scale = maxSide / max(iw, ih)
        val w = iw * scale
        val h = ih * scale
        d.setBounds(
            (cx - w / 2f).toInt(),
            (cy - h / 2f).toInt(),
            (cx + w / 2f).toInt(),
            (cy + h / 2f).toInt(),
        )
        d.draw(canvas)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        orbitAnimator.start()
    }

    override fun onDetachedFromWindow() {
        orbitAnimator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val safe = dp(10f)
        val halfShort = min(width, height) / 2f
        val medBlobR = dp(15f)
        val strokeHalf = ringPaint.strokeWidth * 0.5f

        // Upper bound for ring radius: clinical blobs + stroke must stay inside the view.
        val maxRUpper =
            (halfShort - safe - medBlobR - strokeHalf).coerceAtLeast(dp(48f))

        val tBob = SystemClock.uptimeMillis().toDouble() / 820.0
        val bob = (sin(tBob) * dp(1.5f)).toFloat()
        val bobVertExtra = abs(bob) * 0.45f

        fun doctorsFullyVisible(doctorMax: Float, radialCenter: Float): Boolean {
            val half = doctorMax * 0.5f
            for (slot in doctorSlots) {
                val ang = Math.toRadians(slot.angleDeg.toDouble())
                val ix = cx + radialCenter * cos(ang).toFloat()
                val iy = cy + radialCenter * sin(ang).toFloat() + bob * 0.35f
                if (ix - half < safe || ix + half > width - safe) return false
                if (iy - half - bobVertExtra < safe || iy + half + bobVertExtra > height - safe) {
                    return false
                }
            }
            return true
        }

        var fitMaxR = maxRUpper
        var fitDoctorMax = dp(78f)
        var fitRadial = 0f

        for (shrink in 0..45) {
            val maxR = maxRUpper * (1f - shrink * 0.018f).coerceAtLeast(0.55f)
            val rOut = maxR * OUT_RING_FRAC
            var dm = dp(78f)
            var gap = dp(20f)
            var radial = rOut + gap + dm * 0.5f

            while (!doctorsFullyVisible(dm, radial) && dm > dp(46f)) {
                dm *= 0.965f
                gap = max(dp(10f), gap - dp(1.2f))
                radial = rOut + gap + dm * 0.48f
            }

            if (doctorsFullyVisible(dm, radial)) {
                fitMaxR = maxR
                fitDoctorMax = dm
                fitRadial = radial
                break
            }
        }

        if (fitRadial <= 0f) {
            fitMaxR = maxRUpper * 0.72f
            val rOut = fitMaxR * OUT_RING_FRAC
            fitDoctorMax = dp(46f)
            fitRadial = rOut + dp(10f) + fitDoctorMax * 0.45f
        }

        val maxR = fitMaxR
        val rInner = maxR * INNER_RING_FRAC
        val rMid = maxR * MID_RING_FRAC
        val rOut = maxR * OUT_RING_FRAC

        canvas.drawCircle(cx, cy, rOut, ringPaint)
        canvas.drawCircle(cx, cy, rMid, ringPaint)
        canvas.drawCircle(cx, cy, rInner, ringPaint)

        doctorSlots.forEach { slot ->
            val ang = Math.toRadians(slot.angleDeg.toDouble())
            val ix = cx + fitRadial * cos(ang).toFloat()
            val iy = cy + fitRadial * sin(ang).toFloat() + bob * 0.35f
            drawDrawablePreservingAspect(canvas, ix, iy, fitDoctorMax, slot.drawableRes)
        }

        val medIcon = dp(28f)
        medSlots.forEach { slot ->
            val rRing = ringRadius(maxR, slot.ring)
            val angleRad = Math.toRadians((slot.angleDeg + orbitPhaseDeg).toDouble())
            val ix = cx + rRing * cos(angleRad).toFloat()
            val iy = cy + rRing * sin(angleRad).toFloat()
            canvas.drawCircle(ix, iy, medBlobR, medBlobPaint)
            val d = ContextCompat.getDrawable(context, slot.iconRes) ?: return@forEach
            val half = medIcon / 2f
            d.setBounds(
                (ix - half).toInt(),
                (iy - half).toInt(),
                (ix + half).toInt(),
                (iy + half).toInt(),
            )
            d.draw(canvas)
        }

        val hubR = min(rInner - ringPaint.strokeWidth - dp(4f), maxR * 0.28f)
        canvas.drawCircle(cx, cy, hubR + dp(2f), hubPlatePaint)

        val pulse =
            1f + (0.055f * sin(SystemClock.uptimeMillis().toDouble() / 880.0)).toFloat()
        canvas.save()
        canvas.scale(pulse, pulse, cx, cy)

        val insetInner = hubR * 0.72f
        hubClipPath.reset()
        hubClipPath.addCircle(cx, cy, insetInner, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(hubClipPath)
        launcherDrawable?.let { ld ->
            ld.setBounds(
                (cx - insetInner).toInt(),
                (cy - insetInner).toInt(),
                (cx + insetInner).toInt(),
                (cy + insetInner).toInt(),
            )
            ld.draw(canvas)
        }
        canvas.restore()
        canvas.restore()
    }

    companion object {
        private const val INNER_RING_FRAC = 0.34f
        private const val MID_RING_FRAC = 0.62f
        private const val OUT_RING_FRAC = 1.0f
    }
}
