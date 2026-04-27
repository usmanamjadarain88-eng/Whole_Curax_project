package com.curax.app

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** Rolling 7-day adherence from dose log + current medicine schedule. */
object DoseAdherenceCalculator {

    private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
        timeZone = TimeZone.getDefault()
    }

    fun weeklyAdherencePercent(context: Context): Int {
        val app = context.applicationContext
        val expectedPerDay = AdminDemoData.medicines.sumOf { it.dosePerDay.coerceAtLeast(0) }.coerceAtLeast(0)
        if (expectedPerDay <= 0) return 100
        val expected = expectedPerDay * 7
        val cal = Calendar.getInstance(TimeZone.getDefault())
        val end = cal.time
        cal.add(Calendar.DAY_OF_YEAR, -6)
        val start = cal.time
        val startStr = dayFmt.format(start)
        val endStr = dayFmt.format(end)
        var good = 0
        for (row in DoseTrackingLocalStore.readLog(app)) {
            val ts = row["timestamp"]?.toString()?.take(10) ?: continue
            if (ts < startStr || ts > endStr) continue
            val k = row["kind"]?.toString().orEmpty()
            if (k == "taken_on_time" || k == "taken_late" || k == "taken") {
                val dt = row["dose_taken"]
                val n = when (dt) {
                    is Number -> dt.toInt().coerceAtLeast(1)
                    else -> dt.toString().toIntOrNull()?.coerceAtLeast(1) ?: 1
                }
                good += n.coerceAtMost(8)
            }
        }
        return ((good * 100L) / expected.coerceAtLeast(1)).toInt().coerceIn(0, 100)
    }
}
