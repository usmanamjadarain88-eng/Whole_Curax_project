package com.curax.app

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText

class PinEntryActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TARGET = "unlock_target"
        const val TARGET_MAIN = "main"
        const val TARGET_ALERT_DETAIL = "alert_detail"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pin_entry)

        val store = LocalUserStore(this)
        val prefs = Prefs(this)
        val etPin = findViewById<TextInputEditText>(R.id.etPinEntry)
        val btnUnlock = findViewById<AppCompatButton>(R.id.btnUnlockWithPin)
        val btnFingerprint = findViewById<MaterialButton>(R.id.btnFingerprint)
        val tvPinRole = findViewById<TextView>(R.id.tvPinRole)

        tvPinRole.text = if (store.role == LocalUserStore.ROLE_ADMIN) {
            "Admin Secure Access"
        } else {
            "User Secure Access"
        }

        btnUnlock.setOnClickListener {
            val enteredPin = etPin.text?.toString()?.trim().orEmpty()
            val expectedPin = prefs.appPin.ifEmpty { store.pinCode }
            if (enteredPin == expectedPin && enteredPin.isNotBlank()) {
                openTarget(store.role)
            } else {
                CuraxFeedback.warn(this, "Invalid PIN")
            }
        }

        btnFingerprint.setOnClickListener { unlockWithBiometric(store.role) }
    }

    private fun unlockWithBiometric(role: String) {
        val biometricManager = BiometricManager.from(this)
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG

        when (biometricManager.canAuthenticate(authenticators)) {
            BiometricManager.BIOMETRIC_SUCCESS -> {
                val prompt = BiometricPrompt(
                    this,
                    ContextCompat.getMainExecutor(this),
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                            super.onAuthenticationSucceeded(result)
                            openTarget(role)
                        }

                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                            super.onAuthenticationError(errorCode, errString)
                            if (errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                                CuraxFeedback.warn(this@PinEntryActivity, errString)
                            }
                        }
                    }
                )
                val promptInfo = BiometricPrompt.PromptInfo.Builder()
                    .setTitle(getString(R.string.biometric_title))
                    .setSubtitle(getString(R.string.biometric_subtitle))
                    .setNegativeButtonText(getString(android.R.string.cancel))
                    .build()
                prompt.authenticate(promptInfo)
            }

            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> openBiometricEnroll()
            else -> CuraxFeedback.warn(this, getString(R.string.biometric_not_available))
        }
    }

    private fun openBiometricEnroll() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startActivity(Intent(Settings.ACTION_BIOMETRIC_ENROLL).apply {
                putExtra(
                    Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED,
                    BiometricManager.Authenticators.BIOMETRIC_STRONG
                )
            })
        } else {
            startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
        }
    }

    private fun openTarget(role: String) {
        // Mark app as unlocked so MainActivity/AdminDashboardActivity don't show UnlockActivity again.
        AppLockState.grantUnlock(20_000L)
        AppLockState.markProcessEntryHandled()
        AppLockState.clearBackgroundTimestamp()
        Prefs(this).lastBackgroundAtMs = 0L
        Prefs(this).lastExitWasClose = false

        val target = intent.getStringExtra(EXTRA_TARGET)
        if (target == TARGET_ALERT_DETAIL) {
            AlertNavigation.launchDetailFromNotification(
                this,
                alertId = intent.getLongExtra(NotificationHelper.EXTRA_ALERT_ID, -1L),
                type = intent.getStringExtra(NotificationHelper.EXTRA_ALERT_TYPE).orEmpty(),
                message = intent.getStringExtra(NotificationHelper.EXTRA_ALERT_MESSAGE).orEmpty(),
                receivedAt = intent.getLongExtra(NotificationHelper.EXTRA_ALERT_TIME, System.currentTimeMillis()),
                userName = intent.getStringExtra(NotificationHelper.EXTRA_ALERT_USER_NAME).orEmpty(),
            )
        } else {
            val homeIntent = if (role == LocalUserStore.ROLE_ADMIN) {
                Intent(this, AdminDashboardActivity::class.java)
            } else {
                UserHomeIntent.forSignedInUser(this)
            }
            homeIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            startActivity(homeIntent)
        }
        finish()
    }
}
