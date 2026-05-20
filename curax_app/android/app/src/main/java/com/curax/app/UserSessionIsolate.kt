package com.curax.app

import android.content.Context

/**
 * Wipes in-memory and on-device user payload so account B never sees account A's medicines,
 * reminders, alerts, logs, or delete tombstones. Call on logout, sign-in identity change, and
 * new "awaiting admin" home sessions.
 */
object UserSessionIsolate {

    private const val PREFS_SCHEDULE_TOUCH = "curax_med_schedule_touch_v1"

    fun emptyMedicalReminders(): Map<String, List<Map<String, Any?>>> =
        AdminDemoData.fromApiMedicalReminders(null)

    fun clearUserPresentationPrefs(context: Context) {
        val prefs = Prefs(context.applicationContext)
        prefs.userHubFirstName = ""
        prefs.userHubFullName = ""
        prefs.userHubUsername = ""
        prefs.userProfilePictureDataUrl = ""
    }

    fun clearUserScopedData(context: Context) {
        val app = context.applicationContext
        AdminDemoData.clearAll()
        AdminDemoData.replaceMedicines(app, emptyList())
        AdminDemoData.replaceMedicalReminders(emptyMedicalReminders())
        AdminDemoData.replaceAlertSettings(emptyMap())
        DeletedAlertsStore.clearAll(app)
        AlertDb(app).clearAllAlerts()
        HealthHubHistoryStore.clear(app)
        DoseTrackingLocalStore.clear(app)
        UserPlansLocalStore.clear(app)
        PendingSyncQueueStore.clear(app)
        LocalAlertsController.cancelAll(app)
        MissedDoseEscalationWatchdog.cancel(app)
        clearUserPresentationPrefs(app)
        try {
            app.getSharedPreferences(PREFS_SCHEDULE_TOUCH, Context.MODE_PRIVATE).edit().clear().commit()
        } catch (_: Exception) {
        }
    }

    /**
     * Clears stale payload when the signed-in [botId] changes or on first assignment (wipes static demo residue).
     */
    fun ensureSessionForBotId(context: Context, botId: String) {
        val app = context.applicationContext
        val prefs = Prefs(app)
        val next = botId.trim()
        val prev = prefs.lastActiveSessionBotId.trim()
        if (next.isNotEmpty() && (prev.isEmpty() || prev != next)) {
            clearUserScopedData(app)
        }
        if (next.isNotEmpty()) {
            prefs.lastActiveSessionBotId = next
        }
    }

    fun clearSessionIdentity(context: Context) {
        Prefs(context.applicationContext).lastActiveSessionBotId = ""
    }
}
