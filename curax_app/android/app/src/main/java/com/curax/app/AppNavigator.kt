package com.curax.app

import android.content.Context
import android.content.Intent

/**
 * Single place for cold-start routing (matches previous [LaunchActivity] branching).
 */
object AppNavigator {

    fun resolveLaunchTarget(context: Context, fromLauncher: Boolean): Intent {
        val store = LocalUserStore(context)
        val prefs = Prefs(context)
        val hasPin = prefs.appPin.isNotEmpty() ||
            (store.pinEnabled && store.pinCode.isNotEmpty())
        return when {
            !store.hasUser() -> Intent(context, SignInActivity::class.java)
            hasPin && fromLauncher -> Intent(context, PinEntryActivity::class.java)
            AppLockPolicy.shouldRequireLockOnEntry(context) -> Intent(context, PinEntryActivity::class.java)
            store.role == LocalUserStore.ROLE_USER -> UserHomeIntent.forSignedInUser(context)
            store.role == LocalUserStore.ROLE_ADMIN -> Intent(context, AdminDashboardActivity::class.java)
            else -> Intent(context, MainActivity::class.java)
        }
    }
}
