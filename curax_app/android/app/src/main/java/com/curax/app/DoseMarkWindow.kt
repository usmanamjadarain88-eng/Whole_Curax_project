package com.curax.app

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Allowed "mark dose taken" window: from first enabled pre-alert before today's scheduled dose
 * until 30 minutes after that dose time (standalone product rule).
 */
object DoseMarkWindow {

    enum class Gate {
        OK,
        NO_TIME,
        TOO_EARLY,
        TOO_LATE,
    }

    private fun normalizeTimeHms(raw: String): String? {
        val t = raw.trim()
        if (t.isEmpty()) return null
        return when {
            t.length >= 8 -> t.take(8)
            t.length == 5 -> "$t:00"
            else -> null
        }
    }

    /** Today's scheduled dose instant for this medicine (single daily [AdminDemoData.Medicine.exactTime]). */
    fun todayScheduledBaseMillis(m: AdminDemoData.Medicine): Long? {
        val hms = normalizeTimeHms(m.exactTime) ?: return null
        val parts = hms.split(":").mapNotNull { it.toIntOrNull() }
        if (parts.size < 2) return null
        val h = parts[0].coerceIn(0, 23)
        val min = parts[1].coerceIn(0, 59)
        val sec = parts.getOrNull(2)?.coerceIn(0, 59) ?: 0
        val cal = Calendar.getInstance(TimeZone.getDefault()).apply {
            set(Calendar.SECOND, sec)
            set(Calendar.MILLISECOND, 0)
            set(Calendar.HOUR_OF_DAY, h)
            set(Calendar.MINUTE, min)
        }
        return cal.timeInMillis
    }

    private fun sectionMap(settings: Map<String, Any?>, key: String): Map<String, Any?> {
        val nested = settings["alert_settings"] as? Map<*, *>
        val fromNested = nested?.get(key) as? Map<*, *>
        if (fromNested != null) {
            @Suppress("UNCHECKED_CAST")
            return fromNested as Map<String, Any?>
        }
        @Suppress("UNCHECKED_CAST")
        return (settings[key] as? Map<*, *>)?.let { it as Map<String, Any?> } ?: emptyMap()
    }

    private fun boolOrDefault(v: Any?, default: Boolean = true): Boolean =
        if (v == null) default else when (v) {
            is Boolean -> v
            is Number -> v.toInt() != 0
            else -> v.toString().trim().lowercase(Locale.US) in listOf("true", "1", "yes", "on")
        }

    /**
     * Inclusive window [start, end] in millis. End is 30 minutes after scheduled dose.
     */
    fun windowBoundsMillis(m: AdminDemoData.Medicine): Pair<Long, Long>? {
        val base = todayScheduledBaseMillis(m) ?: return null
        val settings = AdminDemoData.getAlertSettings()
        val ma = sectionMap(settings, "medicine_alerts")
        val med30 = boolOrDefault(ma["30_min_before"], true)
        val med15 = boolOrDefault(ma["15_min_before"], true)
        val start = when {
            med30 -> base - 30L * 60_000L
            med15 -> base - 15L * 60_000L
            else -> base
        }
        val end = base + 30L * 60_000L
        return start to end
    }

    fun gateForNow(m: AdminDemoData.Medicine, now: Long = System.currentTimeMillis()): Gate {
        val bounds = windowBoundsMillis(m) ?: return Gate.NO_TIME
        val (start, end) = bounds
        return when {
            now < start -> Gate.TOO_EARLY
            now > end -> Gate.TOO_LATE
            else -> Gate.OK
        }
    }
}
