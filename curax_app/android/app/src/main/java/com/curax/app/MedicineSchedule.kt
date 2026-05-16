package com.curax.app

import java.util.Locale

/** Normalizes medicine schedule strings (API + UI) for multi–time-per-day dosing. */
object MedicineSchedule {

    private val SPLIT = Regex("[:.]")

    /** "9:30", "09:30:00" → "09:30" (24h display / log slot key). */
    fun normalizeToHhMm(raw: String): String {
        val t = raw.trim()
        if (t.isEmpty()) return ""
        val parts = SPLIT.split(t).mapNotNull { it.toIntOrNull() }
        if (parts.isEmpty()) return ""
        val h = parts.getOrElse(0) { 0 }.coerceIn(0, 23)
        val min = parts.getOrElse(1) { 0 }.coerceIn(0, 59)
        return "%02d:%02d".format(Locale.US, h, min)
    }

    /** "HH:mm" → "HH:mm:ss" for alarm / millis helpers. */
    fun toHhMmSs(hhMm: String): String {
        val n = normalizeToHhMm(hhMm)
        return if (n.length == 5) "$n:00" else n.padEnd(8, '0').take(8)
    }

    fun dedupeSorted(times: Collection<String>): List<String> =
        times.map { normalizeToHhMm(it) }.filter { it.isNotEmpty() }.distinct().sorted()

    const val MAX_SCHEDULE_SLOTS: Int = 4
}
