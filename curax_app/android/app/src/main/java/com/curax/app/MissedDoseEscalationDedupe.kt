package com.curax.app

import android.content.Context

/** On-device dedupe so +15 / +30 admin emails are not POSTed twice (alarm + watchdog). */
object MissedDoseEscalationDedupe {

    private const val PREFS = "curax_escalation_sent"

    private fun key(box: String, dayKey: String, slot: String, phase: Int): String {
        val b = box.trim().uppercase()
        val d = dayKey.trim()
        val s = slot.trim()
        return "${b}|${d}|${s}|$phase"
    }

    fun markSent(context: Context, box: String, dayKey: String, slot: String, phase: Int) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(key(box, dayKey, slot, phase), true)
            .apply()
    }

    /** @return true if this phase should fire now (not sent yet today for this slot). */
    fun tryClaim(context: Context, box: String, dayKey: String, slot: String, phase: Int): Boolean {
        val k = key(box, dayKey, slot, phase)
        val sp = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (sp.getBoolean(k, false)) return false
        sp.edit().putBoolean(k, true).apply()
        return true
    }
}
