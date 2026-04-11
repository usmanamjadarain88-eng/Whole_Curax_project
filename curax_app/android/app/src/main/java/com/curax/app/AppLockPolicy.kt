package com.curax.app

import android.content.Context
import kotlin.math.max

object AppLockPolicy {
    fun shouldRequireLockOnEntry(context: Context): Boolean {
        val prefs = Prefs(context)
        val localStore = LocalUserStore(context)
        val hasPin = prefs.appPin.isNotEmpty() || (localStore.pinEnabled && localStore.pinCode.isNotEmpty())
        if (!hasPin) return false

        // Fresh process launch must always ask for PIN.
        if (AppLockState.isUnlockValid()) return false
        if (AppLockState.isProcessEntryPending()) return true

        // Keep close marker as a fallback guard for edge lifecycle cases.
        if (prefs.lastExitWasClose) return true

        val backgroundAt = max(AppLockState.getBackgroundTimestamp(), prefs.lastBackgroundAtMs)
        if (backgroundAt <= 0L) return false

        val elapsedMs = System.currentTimeMillis() - backgroundAt
        val thresholdMs = prefs.autoLockSeconds * 1_000L
        return elapsedMs >= thresholdMs
    }
}
