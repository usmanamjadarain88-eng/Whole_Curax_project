package com.curax.app

import android.content.Context

/** One-shot dedupe for medical reminder offsets (7d / 3d / 1d / 24h / 2h). */
object MedicalReminderAlertDedupe {

    private const val PREFS = "curax_med_reminder_alert_sent"

    fun tryClaim(context: Context, alarmId: String): Boolean {
        val k = alarmId.trim()
        if (k.isEmpty()) return false
        val sp = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        synchronized(this) {
            if (sp.getBoolean(k, false)) return false
            sp.edit().putBoolean(k, true).apply()
            return true
        }
    }

    fun hasFired(context: Context, alarmId: String): Boolean {
        val k = alarmId.trim()
        if (k.isEmpty()) return false
        return context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(k, false)
    }
}
