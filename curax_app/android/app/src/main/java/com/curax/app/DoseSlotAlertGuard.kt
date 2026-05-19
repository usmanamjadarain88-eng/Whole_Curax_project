package com.curax.app

import org.json.JSONObject

/** Skip dose-timed local alerts when this box/slot/day is already marked taken. */
object DoseSlotAlertGuard {

    private val doseTypes = setOf(
        "medicine_pre_30",
        "medicine_pre_15",
        "medicine_time",
        "missed_dose_5",
        "missed_dose_15",
        "missed_dose_30",
        "missed_dose_60",
    )

    fun shouldSkip(context: android.content.Context, payload: JSONObject): Boolean {
        val type = payload.optString("type", "")
        if (type !in doseTypes) return false
        val box = payload.optString("box_id").trim()
        val slot = payload.optString("schedule_time").trim()
        val dayKey = payload.optString("dose_date").trim()
        if (box.isEmpty() || slot.isEmpty() || dayKey.isEmpty()) return false
        return DoseTrackingLocalStore.isTakenForSlot(context, box, dayKey, slot)
    }
}
