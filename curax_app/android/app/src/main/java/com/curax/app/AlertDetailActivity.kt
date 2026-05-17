package com.curax.app

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import android.app.KeyguardManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AlertDetailActivity : AppCompatActivity() {

    private lateinit var alertDb: AlertDb
    private lateinit var tvType: TextView
    private lateinit var tvMessage: TextView
    private lateinit var tvUser: TextView
    private lateinit var tvTime: TextView

    private var alertId: Long = -1L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Turn screen on first so full-screen intent wakes device (like WhatsApp)
        if (intent.getBooleanExtra(NotificationHelper.EXTRA_WAKE_SCREEN, false)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                setShowWhenLocked(true)
                setTurnScreenOn(true)
            } else {
                @Suppress("DEPRECATION")
                window.addFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                )
            }
            // Best-effort: request keyguard dismiss so full-screen alert is visible
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val km = getSystemService(KeyguardManager::class.java)
                if (km.isKeyguardLocked) {
                    km.requestDismissKeyguard(this, null)
                }
            }
        }

        val fromInternalNav = intent.getBooleanExtra(NotificationHelper.EXTRA_INTERNAL_NAV, false)
        val alreadyUnlocked = AppLockState.isUnlockValid()
        if (!fromInternalNav && !alreadyUnlocked && AppLockPolicy.shouldRequireLockOnEntry(this)) {
            AlertNavigation.routeFromNotificationTap(this, intent)
            finish()
            return
        }

        if (!fromInternalNav && isTaskRoot) {
            AlertNavigation.launchDetailFromNotification(
                this,
                alertId = intent.getLongExtra(NotificationHelper.EXTRA_ALERT_ID, -1L),
                type = intent.getStringExtra(NotificationHelper.EXTRA_ALERT_TYPE).orEmpty(),
                message = intent.getStringExtra(NotificationHelper.EXTRA_ALERT_MESSAGE).orEmpty(),
                receivedAt = intent.getLongExtra(NotificationHelper.EXTRA_ALERT_TIME, System.currentTimeMillis()),
                userName = intent.getStringExtra(NotificationHelper.EXTRA_ALERT_USER_NAME).orEmpty(),
            )
            finish()
            return
        }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = navigateBackFromDetail()
            },
        )

        AppLockState.clearBackgroundTimestamp()
        AppLockState.markProcessEntryHandled()
        Prefs(this).lastBackgroundAtMs = 0L
        Prefs(this).lastExitWasClose = false

        setContentView(R.layout.activity_alert_detail)

        alertDb = AlertDb(this)

        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.alert_detail)

        tvType = findViewById(R.id.tvDetailType)
        tvMessage = findViewById(R.id.tvDetailMessage)
        tvUser = findViewById(R.id.tvDetailUser)
        tvTime = findViewById(R.id.tvDetailTime)

        val fallbackType =
            intent.getStringExtra(NotificationHelper.EXTRA_ALERT_TYPE)
                ?: intent.getStringExtra("type")
                ?: intent.getStringExtra("gcm.notification.title")
                ?: "alert"
        val fallbackMessage =
            intent.getStringExtra(NotificationHelper.EXTRA_ALERT_MESSAGE)
                ?: intent.getStringExtra("message")
                ?: intent.getStringExtra("gcm.notification.body")
                ?: ""
        val fallbackTime = intent.getLongExtra(NotificationHelper.EXTRA_ALERT_TIME, System.currentTimeMillis())
        val fallbackUser =
            intent.getStringExtra(NotificationHelper.EXTRA_ALERT_USER_NAME)?.trim().orEmpty()
        alertId = intent.getLongExtra(NotificationHelper.EXTRA_ALERT_ID, -1L)

        // Demo/API alerts use negative ids; only persist when opened from a real notification
        // (positive id already in DB, or missing id with extras). Never insert for in-app list navigation.
        if (!fromInternalNav && alertId <= 0L && fallbackMessage.isNotEmpty()) {
            val storedUser = AlertDisplayRules.linkedUserLabelForAlert(fallbackType, fallbackUser)
            alertId = alertDb.insertAlert(fallbackType, fallbackMessage, userName = storedUser)
        }

        val fromDb = if (alertId > 0L) alertDb.getAlertById(alertId) else null
        val type = fromDb?.type ?: fallbackType
        val message = fromDb?.message ?: fallbackMessage
        val time = fromDb?.receivedAt ?: fallbackTime
        val userLabel = AlertDisplayRules.linkedUserLabelForAlert(
            type,
            fromDb?.userName?.trim().orEmpty().ifEmpty { fallbackUser },
        )

        tvType.text = type
        tvMessage.text = message
        if (userLabel.isNotEmpty()) {
            tvUser.visibility = android.view.View.VISIBLE
            tvUser.text = getString(R.string.alert_detail_linked_user_fmt, userLabel)
        } else {
            tvUser.visibility = android.view.View.GONE
        }
        tvTime.text = SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault()).format(Date(time))
    }

    override fun onSupportNavigateUp(): Boolean {
        navigateBackFromDetail()
        return true
    }

    /** Back returns to Alerts list (or previous screen if opened from in-app list). */
    private fun navigateBackFromDetail() {
        if (isTaskRoot) {
            startActivity(AlertNavigation.homeOnAlertsTabIntent(this))
        }
        finish()
    }
}
