package com.curax.app

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
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
                finish()
                @Suppress("DEPRECATION")
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            }
            findViewById<AppCompatButton>(R.id.btnContinueAdmin).setOnClickListener {
                startActivity(Intent(this, AdminRegistrationActivity::class.java))
                finish()
                @Suppress("DEPRECATION")
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            }
            return
        }

        findViewById<View>(R.id.splashRoleButtons).visibility = View.GONE
        findViewById<View>(R.id.splashReturningBlock).visibility = View.VISIBLE
        findViewById<TextView>(R.id.tvSplashSubtitle).text = getString(R.string.splash_subtitle_returning)

        handler.postDelayed({
            if (isFinishing) return@postDelayed
            startActivity(target)
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.splash_fade_in, R.anim.splash_fade_out)
            finish()
        }, RETURNING_SPLASH_MS)
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
