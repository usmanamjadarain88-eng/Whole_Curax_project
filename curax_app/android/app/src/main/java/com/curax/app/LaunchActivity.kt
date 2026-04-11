package com.curax.app

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class LaunchActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = LocalUserStore(this)
        val prefs = Prefs(this)
        val fromLauncher = intent.action == android.content.Intent.ACTION_MAIN &&
            (intent.hasCategory(android.content.Intent.CATEGORY_LAUNCHER) ||
                intent.hasCategory(android.content.Intent.CATEGORY_LEANBACK_LAUNCHER))
        val hasPin = prefs.appPin.isNotEmpty() ||
            (store.pinEnabled && store.pinCode.isNotEmpty())

        val target = when {
            !store.hasUser() -> Intent(this, SignUpActivity::class.java)
            hasPin && fromLauncher -> Intent(this, PinEntryActivity::class.java)
            AppLockPolicy.shouldRequireLockOnEntry(this) -> Intent(this, PinEntryActivity::class.java)
            store.role == LocalUserStore.ROLE_USER && prefs.userStandaloneMode -> Intent(this, UserStandaloneActivity::class.java)
            store.role == LocalUserStore.ROLE_ADMIN -> Intent(this, AdminDashboardActivity::class.java)
            else -> Intent(this, MainActivity::class.java)
        }

        startActivity(target)
        finish()
    }
}

