package com.curax.app

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/** Legacy entry: forwards to [PinSetupActivity] so Settings / Main use the same PIN UI as Alerts. */
class SetPinActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = LocalUserStore(this)
        startActivity(
            Intent(this, PinSetupActivity::class.java).apply {
                putExtra(PinSetupActivity.EXTRA_NEXT_ROLE, store.role)
                putExtra(PinSetupActivity.EXTRA_FROM_SETTINGS, true)
            },
        )
        finish()
    }
}
