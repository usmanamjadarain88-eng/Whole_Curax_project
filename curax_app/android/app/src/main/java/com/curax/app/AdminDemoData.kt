package com.curax.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

object AdminDemoData {
    /** Call when admin dashboard opens so only current API data is shown (no stale/demo data). */
    fun clearAll() {
        apiAlertsStore.clear()
        medicalRemindersStore.clear()
        alertSettingsStore.clear()
        medicineStore.clear()
    }

    /** Alerts from GET /admin/data (desktop/backend). Shown in Alerts tab when admin signed up with access code. */
    private val apiAlertsStore = mutableListOf<AlertItem>()
    fun getApiAlerts(): List<AlertItem> = apiAlertsStore.toList()
    fun replaceApiAlerts(items: List<AlertItem>) {
        apiAlertsStore.clear()
        apiAlertsStore.addAll(items)
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
            val idStr = o.optString("id", "") + type + message + createdAt
            val id = (-idStr.hashCode().toLong()).let { if (it >= 0) -it - 1 else it }
            list.add(AlertItem(id = id, type = type, message = message, receivedAt = receivedAt, userName = userName))
        }
        return list
    }

    private fun parseIsoToMillis(iso: String): Long {
        if (iso.isBlank()) return System.currentTimeMillis()
        return try {
            val withZ = iso.replace("Z", "+00:00").replace(Regex("([+-]\\d{2}):(\\d{2})"), "$1$2")
            val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US)
            f.parse(withZ)?.time ?: System.currentTimeMillis()
        } catch (_: Exception) {
            try {
                SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(iso)?.time ?: System.currentTimeMillis()
            } catch (_: Exception) {
                System.currentTimeMillis()
            }
        }
    }

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
        if (json == null) return out
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
        val exactTime: String = "08:00"
    )

    private val medicineStore = mutableListOf(
        Medicine("Panadol", 56, "2026-10-10", "Normal", "B1", 2, "08:00"),
        Medicine("Amoxil", 67, "2026-11-01", "Normal", "B2", 3, "09:15"),
        Medicine("Insulin", 78, "2026-09-20", "Normal", "B3", 2, "13:10"),
        Medicine("Aspirin", 40, "2026-07-15", "Normal", "B4", 1, "18:30"),
        Medicine("Vitamin D", 32, "2026-06-30", "Low", "B5", 1, "11:40"),
        Medicine("Metformin", 25, "2026-03-02", "Expiring", "B6", 2, "16:05")
    )

    val medicines: List<Medicine>
        get() = medicineStore.toList()

    fun replaceMedicines(items: List<Medicine>) {
        medicineStore.clear()
        medicineStore.addAll(items)
    }

    /** Merge updated medicines from incremental API response (by box_id: update or add). */
    fun mergeMedicines(updates: List<Medicine>) {
        for (m in updates) {
            medicineStore.removeAll { it.box.equals(m.box, ignoreCase = true) }
            medicineStore.add(m)
        }
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
        if (Prefs(app).linkedAdminId.trim().isNotEmpty()) return
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
                AlertItem(-9_000_000_000_005L, "sync", "Sync completed with desktop Curax", now - day * 2, "System"),
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
            val exactTime = ((m["exact_time"] as? String)?.trim().orEmpty().ifBlank {
                (times.firstOrNull() as? String) ?: "08:00"
            })
            val rawDose = (m["dose_per_day"] as? Number)?.toInt() ?: 0
            val dosePerDay = rawDose.takeIf { it > 0 } ?: times.size.coerceAtLeast(1)
            val expiry = (m["expiry"] as? String).orEmpty()
            val status = when {
                stock == 0 -> "Refill"
                stock in 1..lowStockThreshold -> "Low"
                else -> "Normal"
            }
            Medicine(name, stock, expiry, status, boxId, dosePerDay, exactTime)
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
        val totalDose = medicineStore.sumOf { it.dosePerDay }
        return totalDose
    }

    fun weeklyAdherence(): Int = 89
    /** Dummy adherence % per day (7 days) for chart before first connect. */
    fun adherencePercentByDay(): List<Float> = listOf(85f, 90f, 88f, 92f, 85f, 90f, 89f)
    /** Expected doses per day for dummy adherence. */
    fun expectedDosesPerDayDemo(): Int = medicineStore.sumOf { it.dosePerDay }

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
        val header = "Medicine,Stock,DosePerDay,Time,Expiry,Status,Box"
        val rows = medicineStore.joinToString("\n") { m ->
            "${m.name},${m.stock},${m.dosePerDay},${m.exactTime},${m.expiry},${m.status},${m.box}"
        }
        return "$header\n$rows"
    }

    fun reportText(): String {
        val medicinesText = medicineStore.joinToString("\n") {
            "- ${it.name} | stock ${it.stock} | dose/day ${it.dosePerDay} | time ${it.exactTime} | ${it.status} | ${it.box}"
        }
        return """
            Curax Admin Report

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

    /** Sample alerts for admin Alerts tab + dashboard until real relay/API alerts exist (not persisted in apiAlertsStore). */
    fun adminPreviewAlerts(now: Long = System.currentTimeMillis()): List<AlertItem> {
        val hour = 3_600_000L
        val day = 86_400_000L
        return listOf(
            AlertItem(-8_010_000_000_001L, "dose_taken", "Dose marked taken: Panadol from box B1", now - hour, "Usman"),
            AlertItem(-8_010_000_000_002L, "dose_missed", "Missed dose reminder: Metformin from box B2", now - hour * 3, "Hamad"),
            AlertItem(-8_010_000_000_003L, "dose_taken", "Dose marked taken: Amoxil from box A1", now - day / 2, "Abdullah"),
            AlertItem(-8_010_000_000_004L, "refill_reminder", "Refill soon: Vitamin D from box B4", now - day, "Zara"),
            AlertItem(-8_010_000_000_005L, "dose_taken", "Dose marked taken: Insulin from box B3", now - day - hour, "Usman"),
            AlertItem(-8_010_000_000_006L, "sync", "Handoff sync completed for linked device", now - day - hour * 2, "System"),
        )
    }

    fun adminPreviewMedicinesForReport(seed: Int): List<Medicine> {
        val base = medicineStore.toList()
        if (base.isEmpty()) return emptyList()
        return base.mapIndexed { i, m ->
            val shift = (seed + i * 3) % 7
            m.copy(stock = (m.stock + shift - 3).coerceIn(0, 120))
        }
    }

    fun adminPreviewAlertsForReport(userLabel: String, now: Long = System.currentTimeMillis()): List<AlertItem> {
        val hour = 3_600_000L
        val day = 86_400_000L
        return listOf(
            AlertItem(-8_020_000_000_001L, "dose_taken", "Dose marked taken: Panadol from box B1", now - hour, userLabel),
            AlertItem(-8_020_000_000_002L, "dose_missed", "Missed dose: evening Metformin", now - day / 3, userLabel),
            AlertItem(-8_020_000_000_003L, "dose_taken", "Dose marked taken: Vitamin D from box B4", now - day, userLabel),
        )
    }
}
