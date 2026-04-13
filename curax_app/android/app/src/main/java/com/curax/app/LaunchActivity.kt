package com.curax.app

import android.animation.Animator
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.appcompat.app.AppCompatActivity

class LaunchActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private val dotAnimators = mutableListOf<Animator>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = LocalUserStore(this)
        val prefs = Prefs(this)
        val fromLauncher = intent.action == android.content.Intent.ACTION_MAIN &&
            (intent.hasCategory(android.content.Intent.CATEGORY_LAUNCHER) ||
                intent.hasCategory(android.content.Intent.CATEGORY_LEANBACK_LAUNCHER))
        val hasPin = prefs.appPin.isNotEmpty() ||
            (store.pinEnabled && store.pinCode.isNotEmpty())

        val target = when {
            !store.hasUser() -> Intent(this, SignUpActivity::class.java)
            hasPin && fromLauncher -> Intent(this, PinEntryActivity::class.java)
            AppLockPolicy.shouldRequireLockOnEntry(this) -> Intent(this, PinEntryActivity::class.java)
            store.role == LocalUserStore.ROLE_USER && prefs.userStandaloneMode -> Intent(this, UserStandaloneActivity::class.java)
            store.role == LocalUserStore.ROLE_ADMIN -> Intent(this, AdminDashboardActivity::class.java)
            else -> Intent(this, MainActivity::class.java)
        }

        if (!store.hasUser()) {
            setContentView(R.layout.activity_launch)
            startLoadingDotAnimation()
            handler.postDelayed({
                if (isFinishing) return@postDelayed
                startActivity(target)
                overridePendingTransition(R.anim.splash_fade_in, R.anim.splash_fade_out)
                finish()
            }, SPLASH_MS)
            return
        }

        startActivity(target)
        finish()
    }

    private fun startLoadingDotAnimation() {
        dotAnimators.clear()
        val dots = listOf(
            findViewById<View>(R.id.dot1),
            findViewById<View>(R.id.dot2),
            findViewById<View>(R.id.dot3)
        )
        val staggerMs = 140L
        val duration = 480L
        dots.forEachIndexed { index, v ->
            val alpha = PropertyValuesHolder.ofFloat(View.ALPHA, 0.28f, 1f)
            val scaleX = PropertyValuesHolder.ofFloat(View.SCALE_X, 0.55f, 1f)
            val scaleY = PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.55f, 1f)
            val anim = ObjectAnimator.ofPropertyValuesHolder(v, alpha, scaleX, scaleY).apply {
                this.duration = duration
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                startDelay = index * staggerMs
                interpolator = AccelerateDecelerateInterpolator()
            }
            dotAnimators.add(anim)
            anim.start()
        }
    }

    override fun onDestroy() {
        dotAnimators.forEach { it.cancel() }
        dotAnimators.clear()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        private const val SPLASH_MS = 2400L
    }
}
