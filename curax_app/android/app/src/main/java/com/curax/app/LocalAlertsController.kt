package com.curax.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.LinkedHashSet
import java.util.Locale
import java.util.TimeZone

/**
 * Schedules standalone-only device alarms from cached [AdminDemoData], [UserPlansLocalStore], and
 * medical reminders (per-row Reminders tab options: 24h/2h or 7d/3d/1d + Send alert).
 * Medicines use System View [AdminDemoData.getAlertSettings] medicine + missed toggles.
 * Health Hub plans use optional 30 / 15 min before + exact ([plan_alerts] in Settings → System).
 * Primary on-device scheduler for medicine times, plans, medical reminders, and stock/expiry scans
 * for standalone users. Does not use FCM or the data bus — routine reminders are not duplicated via cloud push.
 */
object LocalAlertsController {

    const val ACTION_LOCAL_STANDALONE_ALERT = "com.curax.app.action.LOCAL_STANDALONE_ALERT"
    const val TYPE_STOCK_EXPIRY_SCAN = "stock_expiry_scan"

    private const val PREFS = "curax_standalone_local_alarm_state"
    private const val KEY_IDS = "alarm_ids_json"

    /** Single rolling alarm: run stock/expiry evaluation, then reschedule. */
    private const val DAILY_SCAN_ID = "daily_stock_expiry"

    /** How many calendar days ahead to place medicine / reminder phase alarms. */
    private const val DAY_WINDOW = 7

    /** Extra slack so e.g. “tomorrow 08:00” daily scan stays inside [horizonMs]. */
    private const val HORIZON_EXTRA_DAYS = 1

    private const val MAX_ALARMS = 2000
    private const val DAILY_SCAN_HOUR = 8
    private const val DAILY_SCAN_MINUTE = 0

    private fun statePrefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun alarmIntent(ctx: Context, alarmId: String): Intent =
        Intent(ctx, LocalAlertReceiver::class.java).apply {
            action = ACTION_LOCAL_STANDALONE_ALERT
            data = Uri.parse("curax://standalone_alarm").buildUpon()
                .appendQueryParameter("id", alarmId)
                .build()
        }

    fun cancelAll(context: Context) {
        val app = context.applicationContext
        val am = app.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val sp = statePrefs(app)
        val idSet = LinkedHashSet<String>()
        try {
            val arr = JSONArray(sp.getString(KEY_IDS, "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val id = arr.optString(i, "").trim()
                if (id.isNotEmpty()) idSet.add(id)
            }
        } catch (_: Exception) {
        }
        for (key in sp.getAll().keys) {
            if (key.startsWith("p_")) idSet.add(key.removePrefix("p_"))
        }
        for (id in idSet) {
            val pi = PendingIntent.getBroadcast(
                app,
                0,
                alarmIntent(app, id),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
            )
            if (pi != null) {
                try {
                    am.cancel(pi)
                } catch (_: Exception) {
                }
                try {
                    pi.cancel()
                } catch (_: Exception) {
                }
            }
            sp.edit().remove("p_$id").apply()
        }
        sp.edit().putString(KEY_IDS, "[]").apply()
    }

    /** Reschedule from current in-memory + local plan cache. No-op if not standalone user. */
    fun reschedule(context: Context) {
        val app = context.applicationContext
        if (!StandaloneUi.isUserStandalone(app)) return
        cancelAll(app)
        val am = app.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val ids = JSONArray()
        val now = System.currentTimeMillis()
        val dayMs = 24L * 60L * 60L * 1000L
        val horizonMedMs = now + (DAY_WINDOW + HORIZON_EXTRA_DAYS) * dayMs
        /** Plans and medical reminders (one-shot) can be months ahead. */
        val horizonLongMs = now + 400L * dayMs
        var count = 0

        val settings = AdminDemoData.getAlertSettings()
        val ma = sectionMap(settings, "medicine_alerts")
        val esc = sectionMap(settings, "missed_dose_escalation")
        val sa = sectionMap(settings, "stock_alerts")
        val ea = sectionMap(settings, "expiry_alerts")
        val pa = sectionMap(settings, "plan_alerts")

        val med30 = boolOrDefault(ma["30_min_before"], true)
        val med15 = boolOrDefault(ma["15_min_before"], true)
        val medExact = boolOrDefault(ma["exact_time"], true)
        val missed5 = boolOrDefault(esc["5_min_reminder"], true)
        val missed15 = boolOrDefault(esc["15_min_urgent"], true)
        val missed30 = boolOrDefault(esc["30_min_family"], true)
        val missed1h = boolOrDefault(esc["1_hour_log"], true)

        val plan30 = boolOrDefault(pa["30_min_before"], true)
        val plan15 = boolOrDefault(pa["15_min_before"], true)
        val planExact = boolOrDefault(pa["exact_time"], true)

        fun scheduleIfOk(
            alarmId: String,
            trigger: Long,
            payload: JSONObject,
            horizonEnd: Long = horizonMedMs,
        ) {
            if (count >= MAX_ALARMS) return
            if (trigger < now + 15_000L || trigger > horizonEnd) return
            statePrefs(app).edit().putString("p_$alarmId", payload.toString()).apply()
            val pi = PendingIntent.getBroadcast(
                app,
                0,
                alarmIntent(app, alarmId),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val show = PendingIntent.getActivity(
                app,
                alarmId.hashCode() and 0xffff,
                Intent(app, UserStandaloneActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
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

        fun medLabel(phase: String, name: String, box: String, hms: String): String {
            val hm = hms.take(5)
            return "$phase · $name ($box) · $hm"
        }

        fun scheduleMedicinePhases(
            box: String,
            dayKey: String,
            name: String,
            hms: String,
            base: Long,
        ) {
            if (med30) {
                scheduleIfOk(
                    "med_${box}_${dayKey}_pre30",
                    base - 30L * 60_000L,
                    JSONObject().apply {
                        put("type", "medicine_pre_30")
                        put("title", app.getString(R.string.local_alert_med_title))
                        put("message", medLabel(app.getString(R.string.local_alert_phase_30_before), name, box, hms))
                    },
                )
            }
            if (med15) {
                scheduleIfOk(
                    "med_${box}_${dayKey}_pre15",
                    base - 15L * 60_000L,
                    JSONObject().apply {
                        put("type", "medicine_pre_15")
                        put("title", app.getString(R.string.local_alert_med_title))
                        put("message", medLabel(app.getString(R.string.local_alert_phase_15_before), name, box, hms))
                    },
                )
            }
            if (medExact) {
                scheduleIfOk(
                    "med_${box}_${dayKey}_exact",
                    base,
                    JSONObject().apply {
                        put("type", "medicine_time")
                        put("title", app.getString(R.string.local_alert_med_title))
                        put("message", medLabel(app.getString(R.string.local_alert_phase_exact), name, box, hms))
                    },
                )
            }
            if (missed5) {
                scheduleIfOk(
                    "med_${box}_${dayKey}_post5",
                    base + 5L * 60_000L,
                    JSONObject().apply {
                        put("type", "missed_dose_5")
                        put("title", app.getString(R.string.local_alert_missed_title))
                        put("message", medLabel(app.getString(R.string.local_alert_phase_5_after), name, box, hms))
                    },
                )
            }
            if (missed15) {
                scheduleIfOk(
                    "med_${box}_${dayKey}_post15",
                    base + 15L * 60_000L,
                    JSONObject().apply {
                        put("type", "missed_dose_15")
                        put("title", app.getString(R.string.local_alert_missed_title))
                        put("message", medLabel(app.getString(R.string.local_alert_phase_15_after), name, box, hms))
                    },
                )
            }
            if (missed30) {
                scheduleIfOk(
                    "med_${box}_${dayKey}_post30",
                    base + 30L * 60_000L,
                    JSONObject().apply {
                        put("type", "missed_dose_30")
                        put("title", app.getString(R.string.local_alert_missed_title))
                        put("message", medLabel(app.getString(R.string.local_alert_phase_30_after), name, box, hms))
                    },
                )
            }
            if (missed1h) {
                scheduleIfOk(
                    "med_${box}_${dayKey}_post60",
                    base + 60L * 60_000L,
                    JSONObject().apply {
                        put("type", "missed_dose_60")
                        put("title", app.getString(R.string.local_alert_missed_title))
                        put("message", medLabel(app.getString(R.string.local_alert_phase_missed_logged), name, box, hms))
                    },
                )
            }
        }

        // Medicines: per day window, only when stock > 0 and a valid schedule time exists (no default time).
        for (m in AdminDemoData.medicines) {
            if (m.stock <= 0) continue
            val hms = normalizeTimeHms(m.exactTime) ?: continue
            for (dayOff in 0 until DAY_WINDOW) {
                val dayKey = dayKeyFromOffset(dayOff)
                if (DoseTrackingLocalStore.isTakenForLocalDay(app, m.box, dayKey)) continue
                val base = millisForLocalTimeOnDayOffset(hms, dayOff) ?: continue
                scheduleMedicinePhases(m.box, dayKey, m.name, hms, base)
            }
        }

        // Plans: only when time is set — optional 30 / 15 min before + exact (System · Health Hub plans toggles).
        for (p in UserPlansLocalStore.readCache(app)) {
            if (p.isDone) continue
            if (p.planTime.trim().isEmpty()) continue
            val exact = planExactMillis(p) ?: continue
            val stableId = p.id.trim().ifEmpty { "${p.title}_${p.planDate}".hashCode().toString() }
                .replace(Regex("[^a-zA-Z0-9_.-]"), "_").take(48)
            if (plan30) {
                scheduleIfOk(
                    "plan_${stableId}_pre30",
                    exact - 30L * 60_000L,
                    JSONObject().apply {
                        put("type", "plan")
                        put("title", app.getString(R.string.local_alert_plan_title))
                        put(
                            "message",
                            "${p.title} · ${p.planDate.take(10)} · ${app.getString(R.string.local_alert_plan_in_30_min)}",
                        )
                    },
                    horizonLongMs,
                )
            }
            if (plan15) {
                scheduleIfOk(
                    "plan_${stableId}_pre15",
                    exact - 15L * 60_000L,
                    JSONObject().apply {
                        put("type", "plan")
                        put("title", app.getString(R.string.local_alert_plan_title))
                        put(
                            "message",
                            "${p.title} · ${p.planDate.take(10)} · ${app.getString(R.string.local_alert_plan_in_15_min)}",
                        )
                    },
                    horizonLongMs,
                )
            }
            if (planExact) {
                scheduleIfOk(
                    "plan_${stableId}_exact",
                    exact,
                    JSONObject().apply {
                        put("type", "plan")
                        put("title", app.getString(R.string.local_alert_plan_title))
                        put(
                            "message",
                            "${p.title} · ${p.planDate.take(10)} · ${p.planTime.trim().take(5)}",
                        )
                    },
                    horizonLongMs,
                )
            }
        }

        // Medical reminders: offsets from Reminders tab form ([AdminMedicalRemindersFragment] `reminders` map).
        val remKeys = listOf("appointments", "prescriptions", "lab_tests", "custom")
        for (key in remKeys) {
            for ((idx, row) in AdminDemoData.getMedicalReminders()[key].orEmpty().withIndex()) {
                scheduleMedicalReminderAlarms(app, key, idx, row) { id, trigger, payload ->
                    scheduleIfOk(id, trigger, payload, horizonLongMs)
                }
            }
        }

        val stockEnabled = boolOrDefault(sa["enabled"], true)
        val e30 = boolOrDefault(ea["30_days_before"], true)
        val e15 = boolOrDefault(ea["15_days_before"], true)
        val e7 = boolOrDefault(ea["7_days_before"], true)
        val e1 = boolOrDefault(ea["1_day_before"], true)
        val anyExpiry = e30 || e15 || e7 || e1
        if (stockEnabled || anyExpiry) {
            val nextScan = nextDailyScanMillis(DAILY_SCAN_HOUR, DAILY_SCAN_MINUTE, now)
            scheduleIfOk(
                DAILY_SCAN_ID,
                nextScan,
                JSONObject().apply {
                    put("type", TYPE_STOCK_EXPIRY_SCAN)
                    put("title", app.getString(R.string.local_alert_scan_title))
                    put("message", "")
                },
            )
        }

        statePrefs(app).edit().putString(KEY_IDS, ids.toString()).apply()
    }

    /**
     * Evaluates stock (empty / low) and expiry milestone days using cached medicines and alert toggles.
     * Intended to run once when the daily alarm fires.
     */
    fun runDailyStockExpiryScan(context: Context) {
        val app = context.applicationContext
        val settings = AdminDemoData.getAlertSettings()
        val sa = sectionMap(settings, "stock_alerts")
        val ea = sectionMap(settings, "expiry_alerts")
        val stockEnabled = boolOrDefault(sa["enabled"], true)
        val threshold = AdminDemoData.getLowStockThreshold()
        val emptyOn = boolOrDefault(sa["empty_alert"], true)
        val criticalOn = boolOrDefault(sa["critical_alert"], true)
        val e30 = boolOrDefault(ea["30_days_before"], true)
        val e15 = boolOrDefault(ea["15_days_before"], true)
        val e7 = boolOrDefault(ea["7_days_before"], true)
        val e1 = boolOrDefault(ea["1_day_before"], true)

        val todayStart = Calendar.getInstance(TimeZone.getDefault()).apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        if (stockEnabled) {
            for (m in AdminDemoData.medicines) {
                val dayTag = dayKeyFromOffset(0)
                if (emptyOn && m.stock == 0) {
                    val seed = "stock_empty_${m.box}_$dayTag"
                    NotificationHelper.showAlertNotification(
                        app,
                        (seed.hashCode() and 0x7fff_0000) xor 0x1200,
                        -(100L + (seed.hashCode() and 0xfffffff)),
                        "medicine_stock",
                        app.getString(R.string.local_alert_stock_empty, m.name, m.box),
                    )
                }
                if (criticalOn && m.stock > 0 && m.stock <= threshold) {
                    val seed = "stock_low_${m.box}_$dayTag"
                    NotificationHelper.showAlertNotification(
                        app,
                        (seed.hashCode() and 0x7fff_0000) xor 0x1300,
                        -(101L + (seed.hashCode() and 0xfffffff)),
                        "medicine_stock",
                        app.getString(R.string.local_alert_stock_low, m.name, m.box, m.stock),
                    )
                }
            }
        }

        val anyExpiry = e30 || e15 || e7 || e1
        if (anyExpiry) {
            for (m in AdminDemoData.medicines) {
                val expStr = m.expiry.trim()
                if (expStr.isEmpty()) continue
                val expDay = try {
                    ymdFmt.parse(expStr.take(10))
                } catch (_: Exception) {
                    null
                } ?: continue
                val expCal = Calendar.getInstance(TimeZone.getDefault()).apply {
                    time = expDay
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                val daysUntil = ((expCal.timeInMillis - todayStart.timeInMillis) / (24L * 60L * 60L * 1000L)).toInt()
                if (daysUntil < 0) continue
                val hit = (e30 && daysUntil == 30) ||
                    (e15 && daysUntil == 15) ||
                    (e7 && daysUntil == 7) ||
                    (e1 && daysUntil == 1)
                if (!hit) continue
                val seed = "exp_${m.box}_${daysUntil}_${dayKeyFromOffset(0)}"
                NotificationHelper.showAlertNotification(
                    app,
                    (seed.hashCode() and 0x7fff_0000) xor (daysUntil shl 8),
                    -(200L + (seed.hashCode() and 0xfffffff)),
                    "medicine_expiry",
                    app.getString(R.string.local_alert_expiry_days, m.name, m.box, daysUntil, expStr.take(10)),
                )
            }
        }
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
            else -> v.toString().trim().lowercase() in listOf("true", "1", "yes", "on")
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
        val h = parts[0].coerceIn(0, 23)
        val min = parts[1].coerceIn(0, 59)
        val sec = parts.getOrNull(2)?.coerceIn(0, 59) ?: 0
        val cal = Calendar.getInstance(TimeZone.getDefault()).apply {
            add(Calendar.DAY_OF_YEAR, dayOffset)
            set(Calendar.SECOND, sec)
            set(Calendar.MILLISECOND, 0)
            set(Calendar.HOUR_OF_DAY, h)
            set(Calendar.MINUTE, min)
        }
        return cal.timeInMillis
    }

    private fun dayKeyFromOffset(dayOffset: Int): String {
        val cal = Calendar.getInstance(TimeZone.getDefault()).apply { add(Calendar.DAY_OF_YEAR, dayOffset) }
        val y = cal.get(Calendar.YEAR)
        val mo = cal.get(Calendar.MONTH) + 1
        val d = cal.get(Calendar.DAY_OF_MONTH)
        return String.format(Locale.US, "%04d%02d%02d", y, mo, d)
    }

    /** Calendar day key for "today" in the default timezone (matches dose suppress keys). */
    fun localDayKeyToday(): String = dayKeyFromOffset(0)

    private val planFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
        timeZone = TimeZone.getDefault()
    }

    private val ymdFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
        timeZone = TimeZone.getDefault()
    }

    private fun planExactMillis(p: UserPlanRow): Long? {
        val d = p.planDate.trim().ifEmpty { return null }
        val datePrefix = d.take(10)
        val tRaw = p.planTime.trim()
        if (tRaw.isEmpty()) return null
        val t = normalizeTimeHms(tRaw) ?: return null
        return try {
            planFmt.parse("$datePrefix $t")?.time
        } catch (_: Exception) {
            null
        }
    }

    private fun reminderBaseMillis(row: Map<String, Any?>): Long? {
        val dateStr = (row["date"] ?: row["expiry_date"])?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null
        val datePrefix = dateStr.take(10)
        val timeStr = row["time"]?.toString()?.trim().orEmpty()
        return try {
            val day = ymdFmt.parse(datePrefix) ?: return null
            val cal = Calendar.getInstance(TimeZone.getDefault()).apply {
                time = day
                if (timeStr.isEmpty()) {
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                } else {
                    val t = normalizeTimeHms(timeStr) ?: return null
                    val parts = t.split(":").mapNotNull { it.toIntOrNull() }
                    set(Calendar.HOUR_OF_DAY, parts.getOrNull(0)?.coerceIn(0, 23) ?: 0)
                    set(Calendar.MINUTE, parts.getOrNull(1)?.coerceIn(0, 59) ?: 0)
                    set(Calendar.SECOND, parts.getOrNull(2)?.coerceIn(0, 59) ?: 0)
                    set(Calendar.MILLISECOND, 0)
                }
            }
            cal.timeInMillis
        } catch (_: Exception) {
            null
        }
    }

    private fun nextDailyScanMillis(hour: Int, minute: Int, now: Long): Long {
        val cal = Calendar.getInstance(TimeZone.getDefault())
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        cal.set(Calendar.HOUR_OF_DAY, hour)
        cal.set(Calendar.MINUTE, minute)
        if (cal.timeInMillis <= now + 10_000L) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return cal.timeInMillis
    }

    private fun rowReminderOptions(row: Map<String, Any?>): Map<String, Any?> {
        val raw = row["reminders"]
        return if (raw is Map<*, *>) {
            @Suppress("UNCHECKED_CAST")
            raw as Map<String, Any?>
        } else {
            emptyMap()
        }
    }

    private fun reminderSendAlertEnabled(row: Map<String, Any?>): Boolean =
        boolOrDefault(rowReminderOptions(row)["alert"], true)

    /** Same local clock time, shifted by whole days (for prescription 7d / 3d / 1d before expiry). */
    private fun millisDaysOffsetSameClock(baseMs: Long, dayDelta: Int): Long {
        val cal = Calendar.getInstance(TimeZone.getDefault()).apply { timeInMillis = baseMs }
        cal.add(Calendar.DAY_OF_YEAR, dayDelta)
        return cal.timeInMillis
    }

    /**
     * Local alarms for one medical reminder row — matches Reminders tab checkboxes
     * ([AdminMedicalRemindersFragment]: 24h / 2h for appointments & lab & custom; 7d / 3d / 1d for prescriptions).
     */
    private fun scheduleMedicalReminderAlarms(
        app: Context,
        categoryKey: String,
        index: Int,
        row: Map<String, Any?>,
        scheduleOne: (String, Long, JSONObject) -> Unit,
    ) {
        if (!reminderSendAlertEnabled(row)) return
        val base = reminderBaseMillis(row) ?: return
        val rem = rowReminderOptions(row)
        val label = (row["title"] ?: row["doctor"] ?: row["medicine"] ?: row["test_name"] ?: categoryKey).toString()
        val datePart = ((row["date"] ?: row["expiry_date"])?.toString()?.trim()?.take(10)).orEmpty()
        val timePart = row["time"]?.toString()?.trim().orEmpty()
        val whenStr = buildString {
            if (datePart.isNotEmpty()) append(datePart)
            if (timePart.isNotEmpty()) {
                if (isNotEmpty()) append(' ')
                append(timePart.take(5))
            }
        }.trim().ifEmpty { datePart }

        fun remPayload(message: String) = JSONObject().apply {
            put("type", "reminder")
            put("title", app.getString(R.string.local_alert_reminder_title))
            put("message", message)
        }

        val idBase = "rem_${categoryKey}_${index}_${datePart}_${label.hashCode()}"
            .replace(Regex("[^a-zA-Z0-9_.-]"), "_")
            .take(72)

        when (categoryKey) {
            "prescriptions" -> {
                if (boolOrDefault(rem["7d"], true)) {
                    scheduleOne(
                        "${idBase}_rx7d",
                        millisDaysOffsetSameClock(base, -7),
                        remPayload(app.getString(R.string.local_alert_reminder_7d, label, whenStr)),
                    )
                }
                if (boolOrDefault(rem["3d"], true)) {
                    scheduleOne(
                        "${idBase}_rx3d",
                        millisDaysOffsetSameClock(base, -3),
                        remPayload(app.getString(R.string.local_alert_reminder_3d, label, whenStr)),
                    )
                }
                if (boolOrDefault(rem["1d"], true)) {
                    scheduleOne(
                        "${idBase}_rx1d",
                        millisDaysOffsetSameClock(base, -1),
                        remPayload(app.getString(R.string.local_alert_reminder_1d, label, whenStr)),
                    )
                }
            }
            else -> {
                if (boolOrDefault(rem["24h"], true)) {
                    scheduleOne(
                        "${idBase}_24h",
                        base - 24L * 60L * 60L * 1000L,
                        remPayload(app.getString(R.string.local_alert_reminder_24h, label, whenStr)),
                    )
                }
                if (boolOrDefault(rem["2h"], true)) {
                    scheduleOne(
                        "${idBase}_2h",
                        base - 2L * 60L * 60L * 1000L,
                        remPayload(app.getString(R.string.local_alert_reminder_2h, label, whenStr)),
                    )
                }
            }
        }
    }
}
