package com.curax.app

import android.content.Context

/**
 * On-device medicine / plan / reminder alarms ([LocalAlertsController]).
 * Used for standalone users and default-mode linked users (no Vercel cron dependency).
 */
object LocalAlertsUi {

    fun usesOnDeviceMedicineAlarms(context: Context): Boolean {
        if (!AppRole.isUser(context)) return false
        if (StandaloneUi.isUserStandalone(context)) return true
        val p = Prefs(context)
        return p.id.trim().isNotEmpty() && p.apiKey.trim().isNotEmpty()
    }

    /** Custom ringtone picker is standalone-only; default mode uses system default alert sound. */
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
