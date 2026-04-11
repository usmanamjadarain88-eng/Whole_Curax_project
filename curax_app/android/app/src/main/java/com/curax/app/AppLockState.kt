package com.curax.app

object AppLockState {
    private const val DEFAULT_UNLOCK_GRACE_MS = 8_000L

    private var unlockUntilMs: Long = 0L
    private var backgroundAtMs: Long = 0L
    private var processEntryPending: Boolean = true

    fun grantUnlock(durationMs: Long = DEFAULT_UNLOCK_GRACE_MS) {
        unlockUntilMs = System.currentTimeMillis() + durationMs
    }

    fun isUnlockValid(): Boolean = System.currentTimeMillis() < unlockUntilMs

    fun clearUnlock() {
        unlockUntilMs = 0L
    }

    fun markBackgroundNow() {
        backgroundAtMs = System.currentTimeMillis()
    }

    fun clearBackgroundTimestamp() {
        backgroundAtMs = 0L
    }

    fun getBackgroundTimestamp(): Long = backgroundAtMs

    fun isProcessEntryPending(): Boolean = processEntryPending

    fun markProcessEntryHandled() {
        processEntryPending = false
    }
}
