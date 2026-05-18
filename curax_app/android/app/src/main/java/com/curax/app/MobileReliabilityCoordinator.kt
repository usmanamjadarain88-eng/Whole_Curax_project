package com.curax.app

import android.content.Context

/**
 * Keeps alert paths alive when the UI is closed:
 * - **User:** local [AlarmManager] schedules (+ escalation watchdog) from cached medicines.
 * - **User + Admin:** relay WebSocket via [AlertConnectionService] when auto-connect is enabled.
 *
 * Default linked users still receive routine dose reminders from server/relay/FCM; on-device +15/+30
 * POSTs are a backup so admin email is not missed if cron is delayed.
 */
object MobileReliabilityCoordinator {

    fun onAppStart(context: Context) {
        val app = context.applicationContext
        if (AppRole.isUser(app)) {
            UserAlarmScheduler.rescheduleAll(app)
        }
        ConnectionManager.ensureRelayLiveOnAppOpen(app)
    }

    /** After reboot or APK update. */
    fun onDeviceWake(context: Context) = onAppStart(context)
}
