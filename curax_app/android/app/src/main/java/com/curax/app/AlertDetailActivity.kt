package com.curax.app

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import android.app.KeyguardManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AlertDetailActivity : AppCompatActivity() {

    private lateinit var alertDb: AlertDb
    private lateinit var tvType: TextView
    private lateinit var tvMessage: TextView
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
            val fallbackTime =
                intent.getLongExtra(
                    NotificationHelper.EXTRA_ALERT_TIME,
                    System.currentTimeMillis()
                )
            startActivity(
                Intent(this, PinEntryActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
                    putExtra(PinEntryActivity.EXTRA_TARGET, PinEntryActivity.TARGET_ALERT_DETAIL)
                    putExtra(
                        NotificationHelper.EXTRA_ALERT_ID,
                        intent.getLongExtra(NotificationHelper.EXTRA_ALERT_ID, -1L)
                    )
                    putExtra(
                        NotificationHelper.EXTRA_ALERT_TYPE,
                        fallbackType
                    )
                    putExtra(
                        NotificationHelper.EXTRA_ALERT_MESSAGE,
                        fallbackMessage
                    )
                    putExtra(
                        NotificationHelper.EXTRA_ALERT_TIME,
                        fallbackTime
                    )
                }
            )
            finish()
            return
        }

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
        alertId = intent.getLongExtra(NotificationHelper.EXTRA_ALERT_ID, -1L)

        if (alertId <= 0L && fallbackMessage.isNotEmpty()) {
            // If launched from system notification, store alert so it appears in list.
            alertId = alertDb.insertAlert(fallbackType, fallbackMessage)
        }

        val fromDb = if (alertId > 0L) alertDb.getAlertById(alertId) else null
        val type = fromDb?.type ?: fallbackType
        val message = fromDb?.message ?: fallbackMessage
        val time = fromDb?.receivedAt ?: fallbackTime

        tvType.text = type
        tvMessage.text = message
        tvTime.text = SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault()).format(Date(time))
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
