package com.curax.app

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText

class SetPinActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var localUserStore: LocalUserStore
    private lateinit var etPin: TextInputEditText
    private lateinit var etPinConfirm: TextInputEditText
    private lateinit var btnRemovePin: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_set_pin)

        prefs = Prefs(this)
        localUserStore = LocalUserStore(this)

        val currentPin = prefs.appPin.ifEmpty {
            if (localUserStore.pinEnabled) localUserStore.pinCode else ""
        }

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.title = if (currentPin.isEmpty()) getString(R.string.set_pin) else getString(R.string.change_pin)
        toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        etPin = findViewById(R.id.etPin)
        etPinConfirm = findViewById(R.id.etPinConfirm)
        btnRemovePin = findViewById(R.id.btnRemovePin)

        etPin.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        etPinConfirm.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD

        btnRemovePin.visibility = if (currentPin.isEmpty()) View.GONE else View.VISIBLE
        btnRemovePin.setOnClickListener {
            prefs.appPin = ""
            localUserStore.disablePin()
            Toast.makeText(this, getString(R.string.pin_removed), Toast.LENGTH_SHORT).show()
            finish()
        }

        findViewById<MaterialButton>(R.id.btnSavePin).setOnClickListener {
            val pin = etPin.text?.toString()?.trim().orEmpty()
            val confirm = etPinConfirm.text?.toString()?.trim().orEmpty()

            if (pin.length !in 4..8 || !pin.all { it.isDigit() }) {
                Toast.makeText(this, getString(R.string.pin_validation), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (pin != confirm) {
                Toast.makeText(this, getString(R.string.pin_mismatch), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            prefs.appPin = pin
            localUserStore.savePin(pin)
            Toast.makeText(this, getString(R.string.pin_saved), Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
