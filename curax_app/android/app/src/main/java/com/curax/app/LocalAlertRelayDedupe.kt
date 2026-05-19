package com.curax.app

import android.content.Context
/** Once-per-day dedupe for user → admin relay notifications (stock / expiry scan). */
object LocalAlertRelayDedupe {

    private const val PREFS = "curax_local_relay_admin_sent"

    fun tryClaim(context: Context, key: String): Boolean {
        val k = key.trim()
        if (k.isEmpty()) return false
        val day = LocalAlertsController.localDayKeyToday()
        val full = "$day|$k"
        val sp = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        synchronized(this) {
            if (sp.getString(full, null) != null) return false
            sp.edit().putString(full, "1").apply()
            return true
        }
    }

    fun pruneOldDays(context: Context) {
        val sp = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val today = LocalAlertsController.localDayKeyToday()
        val ed = sp.edit()
        var changed = false
        for (entry in sp.all.keys) {
            val day = entry.substringBefore('|', "")
            if (day.length == 8 && day != today && day < today) {
                ed.remove(entry)
                changed = true
            }
        }
        if (changed) ed.apply()
    }
}
