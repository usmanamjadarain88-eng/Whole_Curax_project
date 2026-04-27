package com.curax.app

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** Rule-based nudges: multiple recent misses; three consecutive calendar days under 60% weekly adherence. */
object DoseNudgeController {

    private const val PREFS = "curax_dose_nudge_v1"
    private const val KEY_LAST_MISS_NUDGE_DAY = "last_miss_nudge_yyyymmdd"
    private const val KEY_LAST_ADH_NUDGE_DAY = "last_adh_nudge_yyyymmdd"
    private const val KEY_ADH_STREAK = "low_adh_streak"
    private const val KEY_LAST_ADH_EVAL_DAY = "last_adh_eval_yyyymmdd"

    private fun yyyymmddToday(): String {
        val c = Calendar.getInstance(TimeZone.getDefault())
        return String.format(
            Locale.US,
            "%04d%02d%02d",
            c.get(Calendar.YEAR),
            c.get(Calendar.MONTH) + 1,
            c.get(Calendar.DAY_OF_MONTH),
        )
    }

    private val tsFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
        timeZone = TimeZone.getDefault()
    }

    fun recentMissedLikeCount(context: Context, withinHours: Int = 48): Int {
        val app = context.applicationContext
        val cutoff = System.currentTimeMillis() - withinHours * 3_600_000L
        var n = 0
        for (row in DoseTrackingLocalStore.readLog(app)) {
            val k = row["kind"]?.toString().orEmpty()
            if (k != "missed_auto" && k != "missed") continue
            val raw = row["timestamp"]?.toString() ?: continue
            try {
                val t = tsFmt.parse(raw)?.time ?: continue
                if (t >= cutoff) n++
            } catch (_: Exception) {
            }
        }
        return n
    }

    fun shouldShowMissedDosesNudge(context: Context): Boolean {
        if (recentMissedLikeCount(context) < 2) return false
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val today = yyyymmddToday()
        if (p.getString(KEY_LAST_MISS_NUDGE_DAY, "") == today) return false
        return true
    }

    fun markMissedNudgeShown(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_LAST_MISS_NUDGE_DAY, yyyymmddToday()).apply()
    }

    /** Call once per day (e.g. from home [onResume]) to advance the low-adherence streak. */
    fun tickDailyAdherenceIfNeeded(context: Context) {
        val app = context.applicationContext
        val today = yyyymmddToday()
        val p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (p.getString(KEY_LAST_ADH_EVAL_DAY, "") == today) return
        val pct = DoseAdherenceCalculator.weeklyAdherencePercent(app)
        val prev = p.getInt(KEY_ADH_STREAK, 0)
        val next = if (pct < 60) prev + 1 else 0
        p.edit()
            .putString(KEY_LAST_ADH_EVAL_DAY, today)
            .putInt(KEY_ADH_STREAK, next)
            .apply()
    }

    fun shouldShowLowAdherenceDashboardWarning(context: Context): Boolean {
        tickDailyAdherenceIfNeeded(context)
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (p.getInt(KEY_ADH_STREAK, 0) < 3) return false
        val today = yyyymmddToday()
        if (p.getString(KEY_LAST_ADH_NUDGE_DAY, "") == today) return false
        return true
    }

    fun markAdherenceNudgeShown(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_LAST_ADH_NUDGE_DAY, yyyymmddToday()).apply()
    }
}
