package com.curax.app

import android.content.Context

/**
 * Keeps alert paths alive when the UI is closed:
 * - **User:** local [AlarmManager] schedules (+ escalation watchdog) from cached medicines.
 * - **User + Admin:** relay WebSocket via [AlertConnectionService] when auto-connect is enabled.
 *
 * Default and standalone linked users use on-device [LocalAlertsController] for dose/stock/expiry.
 * Alert relay (WebSocket) carries user→admin escalations (+15/+30), stock, and expiry to the admin app.
 */
object MobileReliabilityCoordinator {

    fun onAppStart(context: Context) {
        val app = context.applicationContext
        if (AppRole.isUser(app)) {
            UserAlarmScheduler.rescheduleAll(app)
            DoseAutoMissedMarker.run(app)
            RelayAutoConnect.enableForLinkedUser(app)
        }
        ConnectionManager.ensureRelayLiveOnAppOpen(app)
    }

    /** After reboot or APK update. */
    fun onDeviceWake(context: Context) = onAppStart(context)
}
