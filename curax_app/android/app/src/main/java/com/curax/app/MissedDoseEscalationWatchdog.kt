package com.curax.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import org.json.JSONObject
import java.util.Calendar
import java.util.TimeZone

/**
 * Safety net when a dose-time alarm was dropped (OEM battery / force-stop): every 15 min,
 * scan today's slots and POST admin escalation if +15 / +30 window passed (standalone + default user).
 */
object MissedDoseEscalationWatchdog {

    const val TYPE = "escalation_watchdog"
    private const val ALARM_ID = "escalation_watchdog"
    private const val INTERVAL_MS = 15L * 60_000L

    fun scheduleNext(context: Context) {
        val app = context.applicationContext
        if (!LocalAlertsUi.usesOnDeviceMedicineAlarms(app)) return
        if (!StandaloneUi.isUserStandalone(app)) {
            val p = Prefs(app)
            if (p.id.trim().isEmpty() || p.apiKey.trim().isEmpty() || p.centralApiUrl.trim().isEmpty()) return
        }
        val am = app.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val trigger = System.currentTimeMillis() + INTERVAL_MS
        val payload = JSONObject().apply { put("type", TYPE) }
        LocalAlertsController.putPayload(app, ALARM_ID, payload)
        val pi = PendingIntent.getBroadcast(
            app,
            0,
            watchdogIntent(app),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
            } else {
                @Suppress("DEPRECATION")
                am.set(AlarmManager.RTC_WAKEUP, trigger, pi)
            }
        } catch (_: Exception) {
        }
    }

    fun cancel(context: Context) {
        val app = context.applicationContext
        val am = app.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pi = PendingIntent.getBroadcast(
            app,
            0,
            watchdogIntent(app),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        try {
            am.cancel(pi)
        } catch (_: Exception) {
        }
        LocalAlertsController.clearPayload(app, ALARM_ID)
    }

    fun runCheck(context: Context) {
        val app = context.applicationContext
        if (!AppRole.isUser(app)) return
        UserAlarmScheduler.restoreCacheIfNeeded(app)
        val settings = AdminDemoData.getAlertSettings()
        val esc = (settings["missed_dose_escalation"] as? Map<*, *>) ?: emptyMap<Any?, Any?>()
        val allow15 = boolOrDefault(esc["15_min_urgent"], true)
        val allow30 = boolOrDefault(esc["30_min_family"], true)
        if (!allow15 && !allow30) return

        val now = System.currentTimeMillis()
        val dayKey = LocalAlertsController.localDayKeyToday()
        for (m in AdminDemoData.medicines) {
            if (m.stock <= 0) continue
            for (slot in m.effectiveScheduleTimes()) {
                val slotDisp = MedicineSchedule.normalizeToHhMm(slot)
                if (DoseTrackingLocalStore.isTakenForSlot(app, m.box, dayKey, slotDisp)) continue
                val base = todayMillisForSlot(slotDisp) ?: continue
                val minutesLate = (now - base) / 60_000L
                if (allow15 && minutesLate in 15..29) {
                    fireIfClaimed(app, m.box, m.name, slotDisp, dayKey, 15)
                }
                if (allow30 && minutesLate in 30..179) {
                    fireIfClaimed(app, m.box, m.name, slotDisp, dayKey, 30)
                }
            }
        }
    }

    private fun fireIfClaimed(
        app: Context,
        box: String,
        name: String,
        slotDisp: String,
        dayKey: String,
        phase: Int,
    ) {
        if (!MissedDoseEscalationDedupe.tryClaim(app, box, dayKey, slotDisp, phase)) return
        MissedDoseEscalationApi.postEscalation(
            app,
            phase = phase,
            boxId = box,
            medicineName = name,
            scheduleTime = slotDisp,
            doseDate = dayKey,
        )
    }

    private fun watchdogIntent(ctx: Context): Intent =
        Intent(ctx, LocalAlertReceiver::class.java).apply {
            action = LocalAlertsController.ACTION_LOCAL_STANDALONE_ALERT
            data = Uri.parse("curax://standalone_watchdog").buildUpon()
                .appendQueryParameter("id", ALARM_ID)
                .build()
        }

    private fun boolOrDefault(v: Any?, default: Boolean): Boolean = when (v) {
        is Boolean -> v
        is Number -> v.toInt() != 0
        else -> v != null && v.toString().lowercase() in listOf("true", "1", "yes")
    }

    private fun todayMillisForSlot(hhMm: String): Long? {
        val hms = MedicineSchedule.toHhMmSs(MedicineSchedule.normalizeToHhMm(hhMm)) ?: return null
        val parts = hms.split(":").mapNotNull { it.toIntOrNull() }
        if (parts.size < 2) return null
        val cal = Calendar.getInstance(TimeZone.getDefault()).apply {
            set(Calendar.SECOND, parts.getOrNull(2)?.coerceIn(0, 59) ?: 0)
            set(Calendar.MILLISECOND, 0)
            set(Calendar.HOUR_OF_DAY, parts[0].coerceIn(0, 23))
            set(Calendar.MINUTE, parts[1].coerceIn(0, 59))
        }
        return cal.timeInMillis
    }
}
