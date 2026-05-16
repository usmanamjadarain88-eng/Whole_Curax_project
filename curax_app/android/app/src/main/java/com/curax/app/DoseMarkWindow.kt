package com.curax.app

import java.util.Calendar
import java.util.TimeZone

/**
 * Today's scheduled dose instant and the fixed mark window (30 min before → 30 min after),
 * aligned with [DoseIntakeClassifier] (standalone product rule).
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

    /** Today's millis for a schedule label (HH:mm) in local timezone. */
    fun todayMillisForHms(hmsDisplay: String): Long? {
        val hms = normalizeTimeHms(MedicineSchedule.toHhMmSs(MedicineSchedule.normalizeToHhMm(hmsDisplay))) ?: return null
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

    /** First scheduled instant today (uses [AdminDemoData.Medicine.effectiveScheduleTimes] first entry). */
    fun todayScheduledBaseMillis(m: AdminDemoData.Medicine): Long? {
        val label = m.effectiveScheduleTimes().firstOrNull() ?: return null
        return todayMillisForHms(label)
    }

    /** Inclusive window [start, end] in millis: always 30 min before and 30 min after scheduled dose. */
    fun windowBoundsMillis(m: AdminDemoData.Medicine): Pair<Long, Long>? {
        val base = todayScheduledBaseMillis(m) ?: return null
        val start = base - DoseIntakeClassifier.MARK_WINDOW_BEFORE_MS
        val end = base + DoseIntakeClassifier.MARK_WINDOW_AFTER_MS
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
