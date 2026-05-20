package com.curax.app

import android.content.Context

/**
 * On-device medicine / plan / reminder alarms ([LocalAlertsController]).
 *
 * **Personal Health (standalone) only** — AlarmManager + optional custom tone.
 * **Default (Smart System)** — popup via relay / server; system notification sound only (no device dose alarm schedule).
 */
object LocalAlertsUi {

    /**
     * Personal Health shell only. Not used while awaiting admin approval (no stale dose alarms).
     */
    fun usesOnDeviceMedicineAlarms(context: Context): Boolean {
        if (!AppRole.isUser(context)) return false
        if (!StandaloneUi.isUserStandalone(context)) return false
        if (Prefs(context).awaitingAdminLinkApproval) return false
        return true
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
