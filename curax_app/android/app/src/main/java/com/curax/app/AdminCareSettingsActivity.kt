package com.curax.app

import android.graphics.Color
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/** Full-screen alert & Gmail preferences for the signed-in admin (replaces the old bottom-tab slot). */
class AdminCareSettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_admin_care_settings)
        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbarAdminCareSettings)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.navigationIcon?.setTint(Color.WHITE)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragmentAdminCareSettings, AdminSettingsFragment())
                .commit()
        }
    }
}
