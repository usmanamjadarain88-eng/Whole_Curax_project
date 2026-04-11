package com.curax.app

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText

class UnlockActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TARGET = "unlock_target"
        const val TARGET_MAIN = "main"
        const val TARGET_ALERT_DETAIL = "alert_detail"
    }

    private lateinit var prefs: Prefs
    private lateinit var etUnlockPin: TextInputEditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // When opened from alert (full-screen intent), wake screen so user sees PIN entry
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
        setContentView(R.layout.activity_unlock)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)

        prefs = Prefs(this)
        etUnlockPin = findViewById(R.id.etUnlockPin)
        etUnlockPin.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        etUnlockPin.clearFocus()

        etUnlockPin.setOnEditorActionListener { _, _, event ->
            if (event == null || event.keyCode == KeyEvent.KEYCODE_ENTER) {
                unlockWithPin()
                true
            } else {
                false
            }
        }

        findViewById<MaterialButton>(R.id.btnUnlock).setOnClickListener { unlockWithPin() }
        findViewById<MaterialButton>(R.id.btnFingerprint).setOnClickListener { unlockWithBiometric() }
    }

    override fun onBackPressed() {
        finishAffinity()
    }

    private fun unlockWithPin() {
        val enteredPin = etUnlockPin.text?.toString()?.trim().orEmpty()
        if (enteredPin == prefs.appPin && enteredPin.isNotEmpty()) {
            completeUnlock()
        } else {
            Toast.makeText(this, getString(R.string.pin_incorrect), Toast.LENGTH_SHORT).show()
            etUnlockPin.setText("")
        }
    }

    private fun unlockWithBiometric() {
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
                            completeUnlock()
                        }

                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                            super.onAuthenticationError(errorCode, errString)
                            if (errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                                Toast.makeText(this@UnlockActivity, errString, Toast.LENGTH_SHORT).show()
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
            else -> Toast.makeText(this, getString(R.string.biometric_not_available), Toast.LENGTH_SHORT).show()
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

    private fun completeUnlock() {
        AppLockState.grantUnlock(20_000L)
        AppLockState.clearBackgroundTimestamp()
        AppLockState.markProcessEntryHandled()
        Prefs(this).lastBackgroundAtMs = 0L
        Prefs(this).lastExitWasClose = false

        val store = LocalUserStore(this)
        val homeIntent = if (store.role == LocalUserStore.ROLE_ADMIN) {
            Intent(this, AdminDashboardActivity::class.java)
        } else {
            Intent(this, MainActivity::class.java)
        }.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        val target = intent.getStringExtra(EXTRA_TARGET)
        if (target == TARGET_ALERT_DETAIL) {
            // Same main PIN screen only; after unlock go straight to alert (no extra home so no second PIN)
            val detailIntent = Intent(this, AlertDetailActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(NotificationHelper.EXTRA_INTERNAL_NAV, true)
                putExtra(NotificationHelper.EXTRA_ALERT_ID, intent.getLongExtra(NotificationHelper.EXTRA_ALERT_ID, -1L))
                putExtra(NotificationHelper.EXTRA_ALERT_TYPE, intent.getStringExtra(NotificationHelper.EXTRA_ALERT_TYPE))
                putExtra(NotificationHelper.EXTRA_ALERT_MESSAGE, intent.getStringExtra(NotificationHelper.EXTRA_ALERT_MESSAGE))
                putExtra(NotificationHelper.EXTRA_ALERT_TIME, intent.getLongExtra(NotificationHelper.EXTRA_ALERT_TIME, System.currentTimeMillis()))
            }
            startActivity(detailIntent)
        } else {
            startActivity(homeIntent)
        }
        finish()
    }
}
