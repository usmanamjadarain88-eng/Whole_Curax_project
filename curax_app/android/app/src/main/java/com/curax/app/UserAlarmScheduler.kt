package com.curax.app

import android.content.Context

/**
 * Keeps on-device medicine alarms scheduled for linked/standalone users after reboot,
 * app close, or process death. Does not use the server scheduler — local [AlarmManager] only.
 */
object UserAlarmScheduler {

    fun restoreCacheIfNeeded(context: Context): Boolean {
        val app = context.applicationContext
        if (!AppRole.isUser(app)) return false
        return UserDataBusClient.restoreCachedUserData(app)
    }

    /** Rebuild all local schedules from cached/API-hydrated [AdminDemoData]. */
    fun rescheduleAll(context: Context) {
        val app = context.applicationContext
        if (!AppRole.isUser(app)) {
            MissedDoseEscalationWatchdog.cancel(app)
            return
        }
        restoreCacheIfNeeded(app)
        if (StandaloneUi.isUserStandalone(app)) {
            LocalAlertsController.reschedule(app)
        } else if (hasLinkedCredentials(app)) {
            MissedDoseEscalationController.reschedule(app)
        } else {
            LocalAlertsController.cancelAll(app)
            MissedDoseEscalationController.cancelAll(app)
            MissedDoseEscalationWatchdog.cancel(app)
            return
        }
        if (hasEscalationApi(app)) {
            MissedDoseEscalationWatchdog.scheduleNext(app)
        } else {
            MissedDoseEscalationWatchdog.cancel(app)
        }
    }

    private fun hasLinkedCredentials(app: Context): Boolean {
        val p = Prefs(app)
        return p.id.trim().isNotEmpty() && p.apiKey.trim().isNotEmpty()
    }

    private fun hasEscalationApi(app: Context): Boolean {
        val p = Prefs(app)
        return hasLinkedCredentials(app) && p.centralApiUrl.trim().isNotEmpty()
    }
}
