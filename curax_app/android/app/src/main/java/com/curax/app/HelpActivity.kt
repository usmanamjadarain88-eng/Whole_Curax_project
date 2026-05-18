package com.curax.app

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar

class HelpActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(
            when {
                AppRole.isAdmin(this) -> R.layout.activity_help_admin
                StandaloneUi.isUserStandalone(this) -> R.layout.activity_help_user_standalone
                else -> R.layout.activity_help_user_default
            },
        )

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.title = getString(R.string.help)
        toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
    }
}
