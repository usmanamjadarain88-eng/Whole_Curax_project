package com.curax.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Default-mode linked users: schedule silent +15 / +30 alarms that POST admin escalation to the server.
 * Standalone users use [LocalAlertsController] for on-device user alerts + the same API from [LocalAlertReceiver].
 */
object MissedDoseEscalationController {

    private const val PREFS = "curax_default_escalation_alarms"
    private const val KEY_IDS = "alarm_ids_json"
    private const val DAY_WINDOW = 7
    private const val HORIZON_EXTRA_DAYS = 1
    private const val MAX_ALARMS = 400

    private fun statePrefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun alarmIntent(ctx: Context, alarmId: String): Intent =
        Intent(ctx, LocalAlertReceiver::class.java).apply {
            action = LocalAlertsController.ACTION_LOCAL_STANDALONE_ALERT
            data = Uri.parse("curax://escalation_alarm").buildUpon()
                .appendQueryParameter("id", alarmId)
                .build()
        }

    fun cancelAll(context: Context) {
        val app = context.applicationContext
        val am = app.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val sp = statePrefs(app)
        try {
            val arr = JSONArray(sp.getString(KEY_IDS, "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val id = arr.optString(i, "").trim()
                if (id.isEmpty()) continue
                val pi = PendingIntent.getBroadcast(
                    app, 0, alarmIntent(app, id),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                try {
                    am.cancel(pi)
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
        sp.edit().clear().apply()
    }

    fun getPayload(context: Context, alarmId: String): JSONObject? {
        val raw = statePrefs(context.applicationContext).getString("p_$alarmId", null) ?: return null
        return try {
            JSONObject(raw)
        } catch (_: Exception) {
            null
        }
    }

    fun clearPayload(context: Context, alarmId: String) {
        statePrefs(context.applicationContext).edit().remove("p_$alarmId").apply()
    }

    /** Legacy +15/+30-only scheduler; superseded by [LocalAlertsController] when [LocalAlertsUi] applies. */
    fun reschedule(context: Context) {
        val app = context.applicationContext
        if (!AppRole.isUser(app)) return
        if (LocalAlertsUi.usesOnDeviceMedicineAlarms(app)) return
        val prefs = Prefs(app)
        if (prefs.id.trim().isEmpty() || prefs.apiKey.trim().isEmpty()) return

        cancelAll(app)
        val am = app.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val settings = AdminDemoData.getAlertSettings()
        val esc = sectionMap(settings, "missed_dose_escalation")
        val missed15 = boolOrDefault(esc["15_min_urgent"], true)
        val missed30 = boolOrDefault(esc["30_min_family"], true)
        if (!missed15 && !missed30) return

        val ids = JSONArray()
        val now = System.currentTimeMillis()
        val dayMs = 24L * 60L * 60L * 1000L
        val horizonMs = now + (DAY_WINDOW + HORIZON_EXTRA_DAYS) * dayMs
        var count = 0

        fun scheduleIfOk(alarmId: String, trigger: Long, payload: JSONObject) {
            if (count >= MAX_ALARMS) return
            if (trigger < now + 15_000L || trigger > horizonMs) return
            statePrefs(app).edit().putString("p_$alarmId", payload.toString()).apply()
            val pi = PendingIntent.getBroadcast(
                app, 0, alarmIntent(app, alarmId),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val show = PendingIntent.getActivity(
                        app,
                        alarmId.hashCode() and 0xffff,
                        Intent(app, UserStandaloneActivity::class.java),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )
                    am.setAlarmClock(AlarmManager.AlarmClockInfo(trigger, show), pi)
                } else {
                    @Suppress("DEPRECATION")
                    am.setExact(AlarmManager.RTC_WAKEUP, trigger, pi)
                }
                ids.put(alarmId)
                count++
            } catch (_: Exception) {
            }
        }

        fun escalationPayload(
            type: String,
            box: String,
            name: String,
            hms: String,
            dayKey: String,
        ): JSONObject = JSONObject().apply {
            put("type", type)
            put("box_id", box)
            put("medicine_name", name)
            put("schedule_time", hms.take(5))
            put("dose_date", dayKey)
        }

        for (m in AdminDemoData.medicines) {
            if (m.stock <= 0) continue
            for (slot in m.effectiveScheduleTimes()) {
                val nh = normalizeTimeHms(MedicineSchedule.toHhMmSs(MedicineSchedule.normalizeToHhMm(slot))) ?: continue
                val slotDisp = MedicineSchedule.normalizeToHhMm(slot)
                val tag = nh.replace(":", "")
                for (dayOff in 0 until DAY_WINDOW) {
                    val dayKey = dayKeyFromOffset(dayOff)
                    if (DoseTrackingLocalStore.isTakenForSlot(app, m.box, dayKey, slotDisp)) continue
                    val base = millisForLocalTimeOnDayOffset(nh, dayOff) ?: continue
                    if (missed15) {
                        scheduleIfOk(
                            "esc_${m.box}_${dayKey}_${tag}_post15",
                            base + 15L * 60_000L,
                            escalationPayload("missed_dose_15_admin", m.box, m.name, slotDisp, dayKey),
                        )
                    }
                    if (missed30) {
                        scheduleIfOk(
                            "esc_${m.box}_${dayKey}_${tag}_post30",
                            base + 30L * 60_000L,
                            escalationPayload("missed_dose_30_admin", m.box, m.name, slotDisp, dayKey),
                        )
                    }
                }
            }
        }
        statePrefs(app).edit().putString(KEY_IDS, ids.toString()).apply()
    }

    private fun sectionMap(root: Map<String, Any?>, key: String): Map<String, Any?> {
        val v = root[key]
        @Suppress("UNCHECKED_CAST")
        return if (v is Map<*, *>) v as Map<String, Any?> else emptyMap()
    }

    private fun boolOrDefault(v: Any?, default: Boolean = true): Boolean =
        when (v) {
            is Boolean -> v
            is String -> v.equals("true", ignoreCase = true) || v == "1"
            is Number -> v.toInt() != 0
            else -> default
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

    private fun millisForLocalTimeOnDayOffset(hms: String, dayOffset: Int): Long? {
        val parts = hms.split(":").mapNotNull { it.toIntOrNull() }
        if (parts.size < 2) return null
        val cal = Calendar.getInstance(TimeZone.getDefault()).apply {
            add(Calendar.DAY_OF_YEAR, dayOffset)
            set(Calendar.SECOND, parts.getOrNull(2)?.coerceIn(0, 59) ?: 0)
            set(Calendar.MILLISECOND, 0)
            set(Calendar.HOUR_OF_DAY, parts[0].coerceIn(0, 23))
            set(Calendar.MINUTE, parts[1].coerceIn(0, 59))
        }
        return cal.timeInMillis
    }

    private fun dayKeyFromOffset(dayOffset: Int): String {
        val cal = Calendar.getInstance(TimeZone.getDefault()).apply {
            add(Calendar.DAY_OF_YEAR, dayOffset)
        }
        val y = cal.get(Calendar.YEAR)
        val mo = cal.get(Calendar.MONTH) + 1
        val d = cal.get(Calendar.DAY_OF_MONTH)
        return String.format(Locale.US, "%04d%02d%02d", y, mo, d)
    }
}
