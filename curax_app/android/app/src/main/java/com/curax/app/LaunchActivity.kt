package com.curax.app

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton

class LaunchActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = LocalUserStore(this)
        val fromLauncher = intent.action == Intent.ACTION_MAIN &&
            (intent.hasCategory(Intent.CATEGORY_LAUNCHER) ||
                intent.hasCategory(Intent.CATEGORY_LEANBACK_LAUNCHER))

        val target = AppNavigator.resolveLaunchTarget(this, fromLauncher)

        setContentView(R.layout.activity_launch)

        if (!store.hasUser()) {
            findViewById<View>(R.id.splashRoleButtons).visibility = View.VISIBLE
            findViewById<View>(R.id.splashReturningBlock).visibility = View.GONE

            findViewById<AppCompatButton>(R.id.btnContinueUser).setOnClickListener {
                startActivity(Intent(this, SignInActivity::class.java))
                @Suppress("DEPRECATION")
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            }
            findViewById<AppCompatButton>(R.id.btnContinueAdmin).setOnClickListener {
                startActivity(Intent(this, AdminRegistrationActivity::class.java))
                @Suppress("DEPRECATION")
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            }

            window.decorView.post { runFirstLaunchEntrance() }
            return
        }

        findViewById<View>(R.id.splashRoleButtons).visibility = View.GONE
        findViewById<View>(R.id.splashReturningBlock).visibility = View.VISIBLE
        findViewById<TextView>(R.id.tvSplashSubtitle).text = getString(R.string.splash_processing)

        handler.postDelayed({
            if (isFinishing) return@postDelayed
            startActivity(target)
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.splash_fade_in, R.anim.splash_fade_out)
            finish()
        }, RETURNING_SPLASH_MS)
    }

    /**
     * Title → subtitle → orbit → User → Admin, left-to-right slide + fade (one after another).
     */
    private fun runFirstLaunchEntrance() {
        if (isFinishing) return
        val slidePx = 48f * resources.displayMetrics.density
        val decel = DecelerateInterpolator()
        val dur = 400L

        fun slideIn(v: View, delay: Long) {
            v.alpha = 0f
            v.translationX = -slidePx
            v.animate()
                .alpha(1f)
                .translationX(0f)
                .setDuration(dur)
                .setStartDelay(delay)
                .setInterpolator(decel)
                .start()
        }

        slideIn(findViewById(R.id.tvSplashTitle), 30L)
        slideIn(findViewById(R.id.tvSplashSubtitle), 130L)
        slideIn(findViewById(R.id.splashOrbit), 210L)
        slideIn(findViewById(R.id.btnContinueUser), 340L)
        slideIn(findViewById(R.id.btnContinueAdmin), 490L)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        /** Returning session: show splash + bottom progress briefly before home / PIN. */
        /** Long enough for the indeterminate bar to animate ~2–3 cycles before transition. */
        private const val RETURNING_SPLASH_MS = 4_500L
    }
}
