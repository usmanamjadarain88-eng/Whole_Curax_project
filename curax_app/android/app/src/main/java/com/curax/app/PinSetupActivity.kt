package com.curax.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText

/**
 * Optional PIN onboarding (Alerts link / post-sign-up) and the same UI when opened from Settings
 * ([EXTRA_FROM_SETTINGS]): save then finish, cancel without clearing PIN, optional remove PIN.
 */
class PinSetupActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pin_setup)

        val fromSettings = intent.getBooleanExtra(EXTRA_FROM_SETTINGS, false)

        findViewById<MaterialToolbar>(R.id.toolbarPinSetup).apply {
            title = ""
            subtitle = ""
            setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        }

        val store = LocalUserStore(this)
        val prefs = Prefs(this)
        val nextRole = intent.getStringExtra(EXTRA_NEXT_ROLE) ?: store.role

        val etPin = findViewById<TextInputEditText>(R.id.etPin)
        val etConfirmPin = findViewById<TextInputEditText>(R.id.etConfirmPin)
        val btnSavePin = findViewById<MaterialButton>(R.id.btnSavePin)
        val btnSkip = findViewById<MaterialButton>(R.id.btnSkipPin)
        val btnRemovePin = findViewById<MaterialButton>(R.id.btnRemovePin)
        val tvTitle1 = findViewById<TextView>(R.id.tvPinSetupTitle1)
        val tvTitle2 = findViewById<TextView>(R.id.tvPinSetupTitle2)
        val tvSubtitle = findViewById<TextView>(R.id.tvPinSetupSubtitle)

        val currentPin = prefs.appPin.ifEmpty {
            if (store.pinEnabled) store.pinCode else ""
        }

        if (fromSettings) {
            btnSkip.text = getString(R.string.cancel)
            if (currentPin.isNotEmpty()) {
                tvTitle1.setText(R.string.pin_setup_change_heading_line1)
                tvTitle2.setText(R.string.pin_setup_change_heading_line2)
                tvSubtitle.setText(R.string.set_pin_screen_subtitle)
                btnRemovePin.visibility = View.VISIBLE
            } else {
                tvTitle1.setText(R.string.pin_setup_heading_line1)
                tvTitle2.setText(R.string.pin_setup_heading_line2)
                tvSubtitle.setText(R.string.pin_setup_subtitle)
                btnRemovePin.visibility = View.GONE
            }
        } else {
            btnSkip.text = getString(R.string.pin_setup_skip_cta)
            btnRemovePin.visibility = View.GONE
        }

        btnRemovePin.setOnClickListener {
            prefs.appPin = ""
            store.disablePin()
            CuraxFeedback.successThen(this, R.string.pin_removed) { finish() }
        }

        btnSavePin.setOnClickListener {
            val pin = etPin.text?.toString()?.trim().orEmpty()
            val confirmPin = etConfirmPin.text?.toString()?.trim().orEmpty()

            val validLen = if (fromSettings) {
                pin.length in 4..8 && pin.all { it.isDigit() }
            } else {
                pin.length == 4 && pin.all { it.isDigit() }
            }

            when {
                !validLen -> {
                    CuraxFeedback.warn(
                        this,
                        if (fromSettings) getString(R.string.pin_validation) else getString(R.string.pin_must_be_four_digits),
                    )
                }
                pin != confirmPin -> {
                    CuraxFeedback.warn(this, getString(R.string.pin_mismatch))
                }
                else -> {
                    store.savePin(pin)
                    prefs.appPin = pin
                    if (fromSettings) {
                        CuraxFeedback.successThen(this, R.string.pin_saved) { finish() }
                    } else {
                        AppLockState.grantUnlock(60_000L)
                        AppLockState.markProcessEntryHandled()
                        AppLockState.clearBackgroundTimestamp()
                        prefs.lastBackgroundAtMs = 0L
                        prefs.lastExitWasClose = false
                        openDashboard(nextRole)
                    }
                }
            }
        }

        btnSkip.setOnClickListener {
            if (fromSettings) {
                finish()
            } else {
                store.disablePin()
                prefs.appPin = ""
                openDashboard(nextRole)
            }
        }
    }

    private fun openDashboard(role: String) {
        val target = if (role == LocalUserStore.ROLE_ADMIN) {
            Intent(this, AdminDashboardActivity::class.java)
        } else {
            UserHomeIntent.forSignedInUser(this)
        }
        target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(target)
        finish()
    }

    companion object {
        const val EXTRA_NEXT_ROLE = "next_role"
        /** When true: opened from Settings / legacy Set PIN — finish() instead of clearing task to home. */
        const val EXTRA_FROM_SETTINGS = "pin_setup_from_settings"
    }
}
