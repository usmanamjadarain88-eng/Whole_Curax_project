package com.curax.app

import android.content.Context

/** On-device dedupe for user Gmail POSTs (−30/−15/exact and +5/+15). */
object MedicineEmailDedupe {

    private const val PREFS = "curax_medicine_email_sent"

    private fun key(box: String, dayKey: String, slot: String, kind: String): String =
        "${box.trim().uppercase()}|${dayKey.trim()}|${slot.trim()}|$kind"

    fun tryClaim(context: Context, box: String, dayKey: String, slot: String, kind: String): Boolean {
        val k = key(box, dayKey, slot, kind)
        val sp = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (sp.getBoolean(k, false)) return false
        sp.edit().putBoolean(k, true).apply()
        return true
    }
}
