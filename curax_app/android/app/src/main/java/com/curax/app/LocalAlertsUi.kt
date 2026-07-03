package com.curax.app

import android.content.Context

/**
 * On-device medicine / plan / reminder alarms ([LocalAlertsController]).
 *
 * **Personal Health (standalone)** and **Smart System (default, linked)** schedule dose times on this phone
 * via [AlarmManager] so 30 / 15 min before and exact-time alerts work when the app is closed.
 * Custom alert tones remain standalone-only; default mode uses the system notification sound.
 */
object LocalAlertsUi {

    /**
     * Schedule medicine/plan/reminder alarms on-device. Not used while awaiting admin approval.
     */
    fun usesOnDeviceMedicineAlarms(context: Context): Boolean {
        if (!AppRole.isUser(context)) return false
        val p = Prefs(context)
        if (p.awaitingAdminLinkApproval) return false
        if (StandaloneUi.isUserStandalone(context)) return true
        // Default mode: need signed-in user credentials (medicines + settings sync from admin/server).
        return p.id.trim().isNotEmpty() && p.apiKey.trim().isNotEmpty()
    }

    /** Custom ringtone picker is standalone-only; default mode uses system notification sound. */
    fun usesCustomAlertToneSettings(context: Context): Boolean =
        StandaloneUi.isUserStandalone(context)

    /** Linked user → notify admin via relay (+ DB alert row) for escalations, stock, expiry. */
    fun shouldNotifyAdminViaRelay(context: Context): Boolean {
        if (!AppRole.isUser(context)) return false
        val p = Prefs(context)
        if (p.id.trim().isEmpty() || p.apiKey.trim().isEmpty() || p.centralApiUrl.trim().isEmpty()) {
            return false
        }
        return p.linkedAdminId.trim().isNotEmpty()
    }
}
