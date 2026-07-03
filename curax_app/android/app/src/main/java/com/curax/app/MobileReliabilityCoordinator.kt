package com.curax.app

import android.content.Context

/**
 * Keeps alert paths alive when the UI is closed:
 * - **User:** local [AlarmManager] schedules (+ escalation watchdog) from cached medicines.
 *
 * Relay WebSocket auto-connect runs only when a home [Activity] is in the foreground
 * ([RelayAutoConnect.restoreOnAppOpen]) — never from [Application.onCreate] (Android 12+ FGS crash).
 *
 * Personal Health (standalone) and Smart System (default) users use on-device [LocalAlertsController]
 * for dose/stock/expiry when medicines and saved alert settings are present.
 * Alert relay (WebSocket) carries user→admin escalations (+15/+30), stock, and expiry to the admin app.
 */
object MobileReliabilityCoordinator {

    fun onAppStart(context: Context) {
        val app = context.applicationContext
        try {
            if (AppRole.isUser(app)) {
                UserAlarmScheduler.restoreCacheIfNeeded(app)
                UserAlarmScheduler.rescheduleAlarmsOnly(app)
                DoseAutoMissedMarker.run(app)
            }
        } catch (t: Throwable) {
            android.util.Log.e("MobileReliability", "onAppStart failed", t)
        }
    }

    /** After reboot or APK update. */
    fun onDeviceWake(context: Context) = onAppStart(context)
}
