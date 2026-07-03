package com.curax.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

object AdminDemoData {
    /**
     * When a box's schedule was last applied locally (millis). Used so [DoseAutoMissedMarker] does not
     * retroactively mark "missed" for slots that ended before the user defined that schedule.
     */
    private val medicineScheduleTouchEpochMs = ConcurrentHashMap<String, Long>()

    fun medicineScheduleTouchMs(box: String): Long =
        medicineScheduleTouchEpochMs[box.trim().uppercase(Locale.US)] ?: 0L

    private fun scheduleFieldsFingerprint(m: Medicine): String =
        m.effectiveScheduleTimes().joinToString("\u0001") { MedicineSchedule.normalizeToHhMm(it) }

    private const val PREFS_SCHEDULE_TOUCH = "curax_med_schedule_touch_v1"
    private const val KEY_SCHEDULE_META = "box_schedule_meta_json"

    private data class BoxScheduleMeta(val fp: String, val touchMs: Long)

    private fun readScheduleMeta(app: Context): MutableMap<String, BoxScheduleMeta> {
        val raw = app.getSharedPreferences(PREFS_SCHEDULE_TOUCH, Context.MODE_PRIVATE)
            .getString(KEY_SCHEDULE_META, null) ?: return mutableMapOf()
        return try {
            val root = JSONObject(raw)
            val out = mutableMapOf<String, BoxScheduleMeta>()
            for (k in root.keys()) {
                val inner = root.optJSONObject(k) ?: continue
                out[k] = BoxScheduleMeta(
                    inner.optString("fp", ""),
                    inner.optLong("touch", 0L),
                )
            }
            out
        } catch (_: Exception) {
            mutableMapOf()
        }
    }

    private fun writeScheduleMeta(app: Context, meta: Map<String, BoxScheduleMeta>) {
        val root = JSONObject()
        for ((k, v) in meta) {
            root.put(k, JSONObject().apply {
                put("fp", v.fp)
                put("touch", v.touchMs)
            })
        }
        app.getSharedPreferences(PREFS_SCHEDULE_TOUCH, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SCHEDULE_META, root.toString())
            .apply()
    }

    /** Loads persisted schedule-touch epochs (safe to call before [replaceMedicines]). */
    fun warmScheduleTouchCache(context: Context) {
        val app = context.applicationContext
        val meta = readScheduleMeta(app)
        for ((k, v) in meta) {
            medicineScheduleTouchEpochMs[k] = v.touchMs
        }
    }
    /** Call when admin dashboard opens so only current API data is shown (no stale/demo data). */
    fun clearAll() {
        apiAlertsStore.clear()
        medicalRemindersStore.clear()
        alertSettingsStore.clear()
        medicineStore.clear()
        medicineScheduleTouchEpochMs.clear()
    }

    /** Alerts from GET /admin/data (desktop/backend). Shown in Alerts tab when admin signed up with access code. */
    private val apiAlertsStore = mutableListOf<AlertItem>()
    fun getApiAlerts(): List<AlertItem> = apiAlertsStore.toList()

    private const val MAX_STANDALONE_LOCAL_ALERT_ROWS = 400

    /**
     * Inserts a locally fired standalone alarm at the front of the API alert list so the Alerts tab
     * shows the same items as heads-up notifications (Personal Health).
     */
    fun alertDedupeKey(item: AlertItem): String =
        alertDedupeKey(item.type, item.message, item.receivedAt)

    fun alertDedupeKey(type: String, message: String, receivedAt: Long): String =
        "fp:${type.trim().lowercase()}|${message.trim()}|${receivedAt / 60_000L}"

    fun prependStandaloneLocalAlert(type: String, message: String, receivedAt: Long) {
        val key = alertDedupeKey(type, message, receivedAt)
        if (apiAlertsStore.any { alertDedupeKey(it) == key }) return
        val base = (receivedAt * 31L) xor (type.hashCode().toLong() shl 16) xor message.hashCode().toLong()
        val id = if (base >= 0L) -base - 1L else base
        apiAlertsStore.add(
            0,
            AlertItem(
                id = id,
                type = type,
                message = message,
                receivedAt = receivedAt,
                userName = "",
            ),
        )
        while (apiAlertsStore.size > MAX_STANDALONE_LOCAL_ALERT_ROWS) {
            apiAlertsStore.removeAt(apiAlertsStore.lastIndex)
        }
    }

    fun totalAdminAlertsVisibleCount(isAdmin: Boolean, localDbAlertCount: Int): Int {
        return getApiAlerts().size + localDbAlertCount
    }

    fun replaceApiAlerts(items: List<AlertItem>) {
        apiAlertsStore.clear()
        apiAlertsStore.addAll(items)
    }

    /** On-device medicine / stock / plan reminders fired by [LocalAlertsController]. */
    fun isDeviceLocalStandaloneAlertType(type: String): Boolean {
        val t = type.trim().lowercase()
        return t.startsWith("medicine_") ||
            t.startsWith("missed_dose") ||
            t.startsWith("stock_") ||
            t.startsWith("expiry_") ||
            t == "reminder" ||
            t.startsWith("plan_")
    }

    /**
     * Standalone: keep device-local alert rows when cloud GET /user/data has none or only server rows.
     * User deletes only via Alerts tab multi-select delete.
     */
    fun mergeApiAlertsForStandalone(incoming: List<AlertItem>) {
        if (incoming.isEmpty()) return
        val preserved = apiAlertsStore.filter { isDeviceLocalStandaloneAlertType(it.type) }
        val merged = (incoming + preserved)
            .distinctBy { alertDedupeKey(it) }
            .sortedByDescending { it.receivedAt }
            .take(MAX_STANDALONE_LOCAL_ALERT_ROWS)
        replaceApiAlerts(merged)
    }

    fun applyApiAlertsFromSync(context: Context, alertsJson: org.json.JSONArray?) {
        val incoming = DeletedAlertsStore.filter(context, fromApiAlerts(alertsJson))
        val app = context.applicationContext
        val before = getApiAlerts()
        if (AppRole.isUser(app) && LocalAlertsUi.usesOnDeviceMedicineAlarms(app)) {
            if (incoming.isEmpty()) {
                replaceApiAlerts(apiAlertsStore.filter { isDeviceLocalStandaloneAlertType(it.type) })
                return
            }
            mergeApiAlertsForStandalone(incoming)
            SyncAlertNotifier.notifyNewFromSync(app, before, getApiAlerts())
            UserAlertsSnapshot.persistAlerts(app)
        } else {
            replaceApiAlerts(incoming)
        }
    }

    fun removeApiAlertsByIds(ids: Set<Long>): Int {
        if (ids.isEmpty()) return 0
        val before = apiAlertsStore.size
        apiAlertsStore.removeAll { it.id in ids }
        return before - apiAlertsStore.size
    }

    /** Parse alerts array from API into List<AlertItem>. Includes user_name when present (admin view). */
    fun fromApiAlerts(arr: JSONArray?): List<AlertItem> {
        if (arr == null) return emptyList()
        val list = mutableListOf<AlertItem>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val type = o.optString("type", "")
            val message = o.optString("message", "")
            val createdAt = o.optString("created_at", "")
            val receivedAt = parseIsoToMillis(createdAt)
            val userName = o.optString("user_name", "").trim().ifEmpty { "User" }
            val serverId = o.optString("id", "").trim().takeIf { it.isNotEmpty() }
            val id = when {
                o.has("local_id") && !o.isNull("local_id") -> o.getLong("local_id")
                serverId != null -> {
                    val idStr = serverId + type + message + createdAt
                    (-idStr.hashCode().toLong()).let { if (it >= 0) -it - 1 else it }
                }
                else -> {
                    val idStr = type + message + createdAt
                    (-idStr.hashCode().toLong()).let { if (it >= 0) -it - 1 else it }
                }
            }
            list.add(
                AlertItem(
                    id = id,
                    type = type,
                    message = message,
                    receivedAt = receivedAt,
                    userName = userName,
                    serverId = serverId,
                ),
            )
        }
        return list
    }

    private fun parseIsoToMillis(iso: String): Long = IsoTimeParse.toMillis(iso)

    /** Medical reminders from GET /admin/data. Keys: appointments, prescriptions, lab_tests, custom. */
    private val medicalRemindersStore = mutableMapOf<String, List<Map<String, Any?>>>()
    fun getMedicalReminders(): Map<String, List<Map<String, Any?>>> = medicalRemindersStore.toMap()
    fun replaceMedicalReminders(map: Map<String, List<Map<String, Any?>>>) {
        medicalRemindersStore.clear()
        medicalRemindersStore.putAll(map)
    }

    /** Parse medical_reminders JSON from API. */
    fun fromApiMedicalReminders(json: JSONObject?): Map<String, List<Map<String, Any?>>> {
        val out = mutableMapOf<String, List<Map<String, Any?>>>()
        if (json == null) {
            for (key in listOf("appointments", "prescriptions", "lab_tests", "custom")) {
                out[key] = emptyList()
            }
            return out
        }
        for (key in listOf("appointments", "prescriptions", "lab_tests", "custom")) {
            val arr = json.optJSONArray(key) ?: JSONArray()
            val list = mutableListOf<Map<String, Any?>>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                list.add(jsonObjectToMap(o))
            }
            out[key] = list
        }
        return out
    }

    private fun jsonObjectToMap(o: JSONObject): Map<String, Any?> {
        val m = mutableMapOf<String, Any?>()
        val keys = o.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            m[k] = jsonValueToAny(o.opt(k))
        }
        return m
    }

    private fun jsonValueToAny(v: Any?): Any? {
        if (v == null || v == JSONObject.NULL) return null
        return when (v) {
            is JSONObject -> jsonObjectToMap(v)
            is JSONArray -> (0 until v.length()).map { jsonValueToAny(v.opt(it)) }
            else -> v
        }
    }

    /** Alert settings + Gmail from GET /admin/data (for Settings tab). */
    private val alertSettingsStore = mutableMapOf<String, Any?>()
    fun getAlertSettings(): Map<String, Any?> = alertSettingsStore.toMap()
    fun replaceAlertSettings(map: Map<String, Any?>) {
        alertSettingsStore.clear()
        alertSettingsStore.putAll(map)
    }

    /** True when at least one medicine has stock and a schedule time (from server or local). */
    fun hasSchedulableMedicines(): Boolean =
        medicines.any { it.stock > 0 && it.effectiveScheduleTimes().isNotEmpty() }

    /** Dose alerts require saved alert_settings with medicine or plan phase toggles from server/UI. */
    fun hasSavedDoseAlertSettings(): Boolean {
        val settings = getAlertSettings()
        if (settings.isEmpty()) return false
        fun section(key: String): Map<*, *>? {
            val nested = settings["alert_settings"] as? Map<*, *>
            return (nested?.get(key) as? Map<*, *>) ?: (settings[key] as? Map<*, *>)
        }
        val ma = section("medicine_alerts")
        val pa = section("plan_alerts")
        return (ma != null && ma.isNotEmpty()) || (pa != null && pa.isNotEmpty())
    }

    /** Rebuild on-device dose alarms after medicines or settings change (both app modes). */
    fun notifyUserScheduleDataChanged(context: Context) {
        val app = context.applicationContext
        if (!AppRole.isUser(app)) return
        UserAlarmScheduler.rescheduleAlarmsOnly(app)
        CuraxNextDoseWidgetProvider.updateAll(app)
    }

    /** Parse alert_settings JSON from API (may contain alert_settings, gmail_config, etc.). */
    fun fromApiAlertSettings(json: JSONObject?): Map<String, Any?> {
        if (json == null) return emptyMap()
        return jsonObjectToMap(json)
    }

    /** Low stock threshold from Settings (stock_alerts.low_stock_threshold). Used for Low vs Normal status and KPI counts. */
    fun getLowStockThreshold(): Int {
        val nested = alertSettingsStore["alert_settings"] as? Map<*, *>
        val sa = (nested?.get("stock_alerts") as? Map<*, *>)
            ?: (alertSettingsStore["stock_alerts"] as? Map<*, *>)
        val v = sa?.get("low_stock_threshold")
        return when (v) {
            is Number -> v.toInt().coerceIn(1, 100)
            is String -> v.trim().toIntOrNull()?.coerceIn(1, 100) ?: 5
            else -> 5
        }
    }

    data class Medicine(
        val name: String,
        val stock: Int,
        val expiry: String,
        val status: String,
        val box: String,
        val dosePerDay: Int,
        val exactTime: String = "08:00",
        /** When non-empty with more than one entry, medicine has multiple daily times from the server. */
        val scheduleTimes: List<String> = emptyList(),
    ) {
        fun effectiveScheduleTimes(): List<String> {
            val cleaned = MedicineSchedule.dedupeSorted(scheduleTimes)
            if (cleaned.size > 1) return cleaned.take(MedicineSchedule.MAX_SCHEDULE_SLOTS)
            if (cleaned.size == 1) return cleaned
            val one = MedicineSchedule.normalizeToHhMm(exactTime)
            return listOf(if (one.isNotEmpty()) one else "08:00")
        }

        fun usesMultipleTimesPerDay(): Boolean = effectiveScheduleTimes().size > 1

        fun displayScheduleLabel(): String =
            effectiveScheduleTimes().joinToString(" · ") { MedicineSchedule.formatDisplay12h(it) }

        /** Units (tablets) taken on one successful mark for the active scheduled time. */
        fun dosePerAdministration(): Int = dosePerDay.coerceAtLeast(1)

        /** Total units for one calendar day (per-time × slot count when multi-schedule; else same as [dosePerAdministration]). */
        fun totalDoseUnitsPerDay(): Int =
            if (usesMultipleTimesPerDay()) dosePerAdministration() * effectiveScheduleTimes().size
            else dosePerAdministration()
    }

    /** Empty until API/sync; admin care uses server fetch only. */
    private val medicineStore = mutableListOf<Medicine>()

    val medicines: List<Medicine>
        get() = medicineStore.toList()

    fun replaceMedicines(context: Context, items: List<Medicine>) {
        val app = context.applicationContext
        val disk = readScheduleMeta(app)
        val nowMs = System.currentTimeMillis()
        val next = LinkedHashMap<String, BoxScheduleMeta>()
        for (m in items) {
            val boxU = m.box.trim().uppercase(Locale.US)
            val fp = scheduleFieldsFingerprint(m)
            val prev = disk[boxU]
            val touch = if (prev?.fp == fp) prev.touchMs else nowMs
            next[boxU] = BoxScheduleMeta(fp, touch)
            medicineScheduleTouchEpochMs[boxU] = touch
        }
        writeScheduleMeta(app, next)
        medicineScheduleTouchEpochMs.keys.retainAll(next.keys)
        medicineStore.clear()
        medicineStore.addAll(items)
        notifyUserScheduleDataChanged(app)
    }

    /** Merge updated medicines from incremental API response (by box_id: update or add). */
    fun mergeMedicines(context: Context, updates: List<Medicine>) {
        val app = context.applicationContext
        val disk = readScheduleMeta(app)
        val nowMs = System.currentTimeMillis()
        for (m in updates) {
            val boxU = m.box.trim().uppercase(Locale.US)
            val old = medicineStore.find { it.box.equals(m.box, ignoreCase = true) }
            medicineStore.removeAll { it.box.equals(m.box, ignoreCase = true) }
            medicineStore.add(m)
            val fpAfter = scheduleFieldsFingerprint(m)
            val prev = disk[boxU]
            val newTouch =
                if (old != null && scheduleFieldsFingerprint(old) == fpAfter) {
                    if (prev?.fp == fpAfter) prev.touchMs else 0L
                } else {
                    nowMs
                }
            disk[boxU] = BoxScheduleMeta(fpAfter, newTouch)
            medicineScheduleTouchEpochMs[boxU] = newTouch
        }
        writeScheduleMeta(app, disk)
    }

    /** When incremental sync sends current box_ids from backend, remove local medicines not in that set (so deletes on desktop appear in app). */
    fun removeMedicinesExceptBoxIds(allowBoxIds: Set<String>) {
        val allowed = allowBoxIds.map { it.uppercase() }.toSet()
        medicineStore.removeAll { !allowed.contains(it.box.uppercase()) }
    }

    /** Append new alerts from incremental API response. */
    fun appendApiAlerts(newItems: List<AlertItem>) {
        val existingIds = apiAlertsStore.map { it.id }.toMutableSet()
        for (item in newItems) {
            if (item.id !in existingIds) {
                apiAlertsStore.add(item)
                existingIds.add(item.id)
            }
        }
    }

    /**
     * When a standalone user has no linked admin and no stored/API alerts yet, seed in-memory demo [AlertItem]s.
     * Same store feeds **Alerts** and **Logs** tabs (API alert pipeline).
     */
    fun seedStandaloneDemoLogsIfNeeded(context: Context) {
        val app = context.applicationContext
        if (!AppRole.isUser(app)) return
        if (!StandaloneUi.isUserStandalone(app)) return
        val p = Prefs(app)
        if (p.awaitingAdminLinkApproval) return
        if (!p.userStandaloneDataReady) return
        if (p.linkedAdminId.trim().isNotEmpty()) return
        if (getApiAlerts().isNotEmpty()) return
        if (AlertDb(app).getAllAlerts().isNotEmpty()) return

        val now = System.currentTimeMillis()
        val day = 86_400_000L
        val hour = 3_600_000L
        appendApiAlerts(
            listOf(
                AlertItem(-9_000_000_000_001L, "dose_taken", "Dose marked taken: Panadol from box B1", now - hour * 2, "You"),
                AlertItem(-9_000_000_000_002L, "dose_missed", "Missed dose reminder: Amoxil from box B2", now - day / 2, "You"),
                AlertItem(-9_000_000_000_003L, "refill_reminder", "Refill soon: Vitamin D from box B3", now - day, "You"),
                AlertItem(-9_000_000_000_004L, "dose_taken", "Dose marked taken: Metformin from box A1", now - day - hour * 3, "You"),
                AlertItem(-9_000_000_000_005L, "sync", "Sync completed with desktop CuraX", now - day * 2, "System"),
                AlertItem(-9_000_000_000_006L, "dose_missed", "Missed dose reminder: Ibuprofen from box B4", now - day * 2 - hour * 5, "You"),
            ),
        )
    }

    /** Build Medicine list from backend API response (medicines array). Used after admin sign-up and polling. */
    fun fromApiMedicines(medicines: List<Map<String, Any?>>): List<Medicine> {
        return medicines.map { m ->
            val name = (m["name"] as? String).orEmpty().ifEmpty { "Medicine" }
            val boxId = (m["box_id"] as? String).orEmpty().ifEmpty { "B1" }
            val lowStockThreshold = (m["low_stock"] as? Number)?.toInt() ?: 5
            val stock = (m["quantity"] as? Number)?.toInt() ?: (m["stock"] as? Number)?.toInt() ?: 0
            @Suppress("UNCHECKED_CAST")
            val times = m["times"] as? List<Any?> ?: emptyList()
            val timesStrings = times.mapNotNull { (it as? String)?.trim()?.takeIf { s -> s.isNotEmpty() } }
            val sortedApiTimes = MedicineSchedule.dedupeSorted(timesStrings)
            val scheduleTimesStored =
                if (sortedApiTimes.size > 1) sortedApiTimes.take(MedicineSchedule.MAX_SCHEDULE_SLOTS) else emptyList()
            val exactRaw = (m["exact_time"] as? String)?.trim().orEmpty().ifBlank {
                sortedApiTimes.firstOrNull() ?: "08:00"
            }
            val exactTime = MedicineSchedule.normalizeToHhMm(exactRaw).ifEmpty {
                sortedApiTimes.firstOrNull()?.let { MedicineSchedule.normalizeToHhMm(it) } ?: "08:00"
            }
            val rawDose = (m["dose_per_day"] as? Number)?.toInt() ?: 0
            val nSlots = when {
                scheduleTimesStored.size > 1 -> scheduleTimesStored.size
                sortedApiTimes.size > 1 -> sortedApiTimes.size
                else -> 1
            }
            val dosePerDay = when {
                nSlots > 1 && rawDose > 0 && rawDose % nSlots == 0 -> (rawDose / nSlots).coerceAtLeast(1)
                nSlots > 1 && rawDose > 0 -> rawDose.coerceAtLeast(1)
                rawDose > 0 -> rawDose
                else -> nSlots.coerceAtLeast(1)
            }
            val expiry = (m["expiry"] as? String).orEmpty()
            val status = when {
                stock == 0 -> "Refill"
                stock in 1..lowStockThreshold -> "Low"
                else -> "Normal"
            }
            Medicine(name, stock, expiry, status, boxId, dosePerDay, exactTime, scheduleTimesStored)
        }
    }

    fun totalMedicines(): Int = medicineStore.size
    /** Low = quantity between 1 and threshold (inclusive), using Settings low_stock_threshold. */
    fun lowStockCount(): Int {
        val threshold = getLowStockThreshold()
        return medicineStore.count { it.stock in 1..threshold }
    }
    /** Expiring = expiry date within next 30 days (from expiry field YYYY-MM-DD). */
    fun expiringSoonCount(): Int = medicineStore.count { isExpiringSoon(it.expiry) }
    /** Refill = quantity is zero (box empty). */
    fun refillNeeded(): Int = medicineStore.count { it.stock == 0 }

    private fun isExpiringSoon(expiry: String): Boolean {
        if (expiry.isBlank()) return false
        return try {
            val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val expiryCal = Calendar.getInstance().apply { time = fmt.parse(expiry)!! }
            val now = Calendar.getInstance()
            val limit = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 30) }
            !expiryCal.before(now) && !expiryCal.after(limit)
        } catch (_: Exception) {
            false
        }
    }
    fun normalCount(): Int = medicineStore.count { it.status == "Normal" }

    fun averageDailyConsumption(): Int {
        if (medicineStore.isEmpty()) return 0
        return medicineStore.sumOf { it.totalDoseUnitsPerDay() }
    }

    fun weeklyAdherence(): Int = 89
    /** Dummy adherence % per day (7 days) for chart before first connect. */
    fun adherencePercentByDay(): List<Float> = listOf(85f, 90f, 88f, 92f, 85f, 90f, 89f)
    /** Expected doses per day for dummy adherence. */
    fun expectedDosesPerDayDemo(): Int = medicineStore.sumOf { it.totalDoseUnitsPerDay() }

    fun mostUsedMedicine(): String = medicineStore.maxByOrNull { it.stock }?.name ?: "N/A"

    fun morningPercent(): Int = 36
    fun afternoonPercent(): Int = 41
    fun nightPercent(): Int = 23

    fun logs(): List<String> = listOf(
        "[08:00] Taken: Panadol from Box B1",
        "[09:15] Taken: Amoxil from Box B2",
        "[11:40] Missed: Vitamin D from Box B5",
        "[13:10] Taken: Insulin from Box B3",
        "[16:05] Missed: Metformin from Box B6",
        "[18:30] Taken: Aspirin from Box B4",
        "[20:00] Relay sync complete"
    )

    /** Log entries in table format (Date, Time, Medicine, Status, Source) for dummy display before first connect. */
    fun logEntries(): List<LogEntry> {
        val dateStr = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(java.util.Date())
        return listOf(
            LogEntry(dateStr, "08:00", "Panadol", "Taken", "User"),
            LogEntry(dateStr, "09:15", "Amoxil", "Taken", "User"),
            LogEntry(dateStr, "11:40", "Vitamin D", "Missed", "System"),
            LogEntry(dateStr, "13:10", "Insulin", "Taken", "User"),
            LogEntry(dateStr, "16:05", "Metformin", "Missed", "System"),
            LogEntry(dateStr, "18:30", "Aspirin", "Taken", "User"),
            LogEntry(dateStr, "20:00", "-", "Sync", "System")
        )
    }

    fun takenCount(): Int = logs().count { it.contains("Taken:") }
    fun missedCount(): Int = logs().count { it.contains("Missed:") }
    fun boxesCovered(): Int = medicineStore.map { it.box }.distinct().count()

    fun reportsCsv(): String {
        val header = "Medicine,Stock,DailyTotalUnits,Times,Expiry,Status,Box"
        val rows = medicineStore.joinToString("\n") { m ->
            "${m.name},${m.stock},${m.totalDoseUnitsPerDay()},${m.displayScheduleLabel()},${m.expiry},${m.status},${m.box}"
        }
        return "$header\n$rows"
    }

    fun reportText(): String {
        val medicinesText = medicineStore.joinToString("\n") {
            "- ${it.name} | stock ${it.stock} | daily total ${it.totalDoseUnitsPerDay()} units | each time ${it.dosePerAdministration()} | times ${it.displayScheduleLabel()} | ${it.status} | ${it.box}"
        }
        return """
            CuraX Admin Report

            KPI
            - Total Medicines: ${totalMedicines()}
            - Low Stock: ${lowStockCount()}
            - Expiring Soon: ${expiringSoonCount()}
            - Refill Needed: ${refillNeeded()}

            Inventory
            $medicinesText

            Activity Logs
            ${logs().joinToString("\n")}
        """.trimIndent()
    }
}
