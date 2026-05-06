package com.curax.app

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText

class PinSetupActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pin_setup)

        val store = LocalUserStore(this)
        val prefs = Prefs(this)
        val nextRole = intent.getStringExtra(EXTRA_NEXT_ROLE) ?: store.role

        val etPin = findViewById<TextInputEditText>(R.id.etPin)
        val etConfirmPin = findViewById<TextInputEditText>(R.id.etConfirmPin)
        val btnSavePin = findViewById<AppCompatButton>(R.id.btnSavePin)
        val btnSkip = findViewById<MaterialButton>(R.id.btnSkipPin)

        btnSavePin.setOnClickListener {
            val pin = etPin.text?.toString()?.trim().orEmpty()
            val confirmPin = etConfirmPin.text?.toString()?.trim().orEmpty()

            when {
                pin.length != 4 || !pin.all { it.isDigit() } -> {
                    CuraxFeedback.warn(this, "PIN must be 4 digits")
                }
                pin != confirmPin -> {
                    CuraxFeedback.warn(this, "PIN and confirm PIN must match")
                }
                else -> {
                    store.savePin(pin)
                    prefs.appPin = pin
                    // First-time setup should continue directly without immediate re-lock.
                    AppLockState.grantUnlock(60_000L)
                    AppLockState.markProcessEntryHandled()
                    AppLockState.clearBackgroundTimestamp()
                    prefs.lastBackgroundAtMs = 0L
                    prefs.lastExitWasClose = false
                    openDashboard(nextRole)
                }
            }
        }

        btnSkip.setOnClickListener {
            store.disablePin()
            prefs.appPin = ""
            openDashboard(nextRole)
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
    }
}
