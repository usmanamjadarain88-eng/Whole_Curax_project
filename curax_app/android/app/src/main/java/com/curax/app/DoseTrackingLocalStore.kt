package com.curax.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.LinkedHashSet
import java.util.Locale

/**
 * Standalone dose history + per-slot "taken" marks to suppress local medicine alarms
 * for that box + calendar day + schedule slot ([LocalAlertsController]).
 */
object DoseTrackingLocalStore {

    private const val PREFS = "curax_dose_tracking_v1"
    private const val KEY_LOG = "dose_log_json"
    private const val KEY_SUPPRESS = "suppress_keys_json"
    private const val SLOT_PREFIX = "SLOTv1|"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun clear(context: Context) {
        prefs(context.applicationContext).edit().clear().apply()
    }

    /** Legacy whole-day suppress (box + yyyymmdd). */
    fun legacySuppressKey(box: String, dayKey: String): String =
        "${box.trim().uppercase(Locale.US)}_${dayKey.trim()}"

    /** Per-slot suppress: box, yyyymmdd, HH:mm. */
    fun suppressSlotKey(box: String, dayKey: String, slotHhMm: String): String {
        val slot = MedicineSchedule.normalizeToHhMm(slotHhMm)
        return "$SLOT_PREFIX${box.trim().uppercase(Locale.US)}|${dayKey.trim()}|$slot"
    }

    fun isTakenForSlot(context: Context, box: String, dayKey: String, slotHhMm: String): Boolean {
        val set = readSuppressArray(context.applicationContext)
        if (set.contains(legacySuppressKey(box, dayKey))) return true
        return set.contains(suppressSlotKey(box, dayKey, slotHhMm))
    }

    /** @deprecated Prefer [isTakenForSlot] for multi-time medicines. */
    fun isTakenForLocalDay(context: Context, box: String, dayKey: String): Boolean {
        val set = readSuppressArray(context.applicationContext)
        if (set.contains(legacySuppressKey(box, dayKey))) return true
        val b = box.trim().uppercase(Locale.US)
        val d = dayKey.trim()
        for (s in set) {
            if (!s.startsWith(SLOT_PREFIX)) continue
            val parts = s.split("|")
            if (parts.size >= 4 && parts[1].equals(b, ignoreCase = true) && parts[2] == d) return true
        }
        return false
    }

    private fun readSuppressArray(ctx: Context): MutableSet<String> {
        val out = LinkedHashSet<String>()
        try {
            val arr = JSONArray(prefs(ctx).getString(KEY_SUPPRESS, "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val s = arr.optString(i, "").trim()
                if (s.isNotEmpty()) out.add(s)
            }
        } catch (_: Exception) {
        }
        return out
    }

    private fun writeSuppress(ctx: Context, set: Set<String>) {
        val arr = JSONArray()
        for (s in set) arr.put(s)
        prefs(ctx).edit().putString(KEY_SUPPRESS, arr.toString()).apply()
    }

    fun markTakenForSlot(context: Context, box: String, dayKey: String, slotHhMm: String) {
        val set = readSuppressArray(context.applicationContext)
        set.add(suppressSlotKey(box, dayKey, slotHhMm))
        pruneOldSuppress(set)
        writeSuppress(context.applicationContext, set)
    }

    /** Whole-day suppress (legacy / single-slot flows). */
    fun markTakenForDay(context: Context, box: String, dayKey: String) {
        val set = readSuppressArray(context.applicationContext)
        set.add(legacySuppressKey(box, dayKey))
        pruneOldSuppress(set)
        writeSuppress(context.applicationContext, set)
    }

    private fun dayKeyIntFromSuppressKey(key: String): Int? =
        when {
            key.startsWith(SLOT_PREFIX) -> {
                val p = key.split("|")
                if (p.size >= 4) p[2].toIntOrNull() else null
            }
            else -> {
                val u = key.lastIndexOf('_')
                if (u <= 0) null else key.substring(u + 1).toIntOrNull()
            }
        }

    private fun pruneOldSuppress(set: MutableSet<String>) {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -45)
        val y = cal.get(Calendar.YEAR)
        val mo = cal.get(Calendar.MONTH) + 1
        val d = cal.get(Calendar.DAY_OF_MONTH)
        val cutoffInt = y * 10_000 + mo * 100 + d
        set.removeAll { key ->
            val dk = dayKeyIntFromSuppressKey(key) ?: return@removeAll true
            dk < cutoffInt
        }
    }

    fun readLog(context: Context): List<Map<String, Any?>> {
        val out = mutableListOf<Map<String, Any?>>()
        try {
            val arr = JSONArray(prefs(context).getString(KEY_LOG, "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val m = mutableMapOf<String, Any?>()
                for (k in o.keys()) {
                    m[k] = when (val v = o.get(k)) {
                        JSONObject.NULL -> null
                        else -> v
                    }
                }
                out.add(m)
            }
        } catch (_: Exception) {
        }
        return out
    }

    private fun writeLog(ctx: Context, list: List<Map<String, Any?>>) {
        val arr = JSONArray()
        for (row in list) {
            val o = JSONObject()
            for ((k, v) in row) {
                when (v) {
                    null -> o.put(k, JSONObject.NULL)
                    is Number -> o.put(k, v)
                    is Boolean -> o.put(k, v)
                    else -> o.put(k, v.toString())
                }
            }
            arr.put(o)
        }
        prefs(ctx).edit().putString(KEY_LOG, arr.toString()).apply()
    }

    fun appendLogEntry(context: Context, entry: Map<String, Any?>) {
        val app = context.applicationContext
        val list = readLog(app).toMutableList()
        list.add(0, entry)
        val trimmed = list.take(500)
        writeLog(app, trimmed)
    }

    @Suppress("UNUSED_PARAMETER")
    fun seedStandaloneDemoHistoryIfNeeded(context: Context) {
    }

    fun doseLogToJsonArray(context: Context): JSONArray {
        val arr = JSONArray()
        for (row in readLog(context.applicationContext)) {
            val o = JSONObject()
            for ((k, v) in row) {
                when (v) {
                    null -> o.put(k, JSONObject.NULL)
                    is Number -> o.put(k, v)
                    is Boolean -> o.put(k, v)
                    else -> o.put(k, v.toString())
                }
            }
            arr.put(o)
        }
        return arr
    }

    fun mergeFromPayloadArray(context: Context, arr: JSONArray?) {
        if (arr == null || arr.length() == 0) return
        val app = context.applicationContext
        val existing = readLog(app).toMutableList()
        val sig = existing.map { sigFor(it) }.toMutableSet()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val m = mutableMapOf<String, Any?>()
            for (k in o.keys()) {
                m[k] = when (val v = o.get(k)) {
                    JSONObject.NULL -> null
                    else -> v
                }
            }
            val s = sigFor(m)
            if (s !in sig) {
                sig.add(s)
                existing.add(0, m)
            }
        }
        writeLog(app, existing.take(500))
    }

    fun replaceLogEntries(context: Context, entries: List<Map<String, Any?>>) {
        writeLog(context.applicationContext, entries.take(500))
    }

    /** Central API dose_logs rows → local history shape (care mode / user sync). */
    fun replaceFromServerDoseLogsArray(context: Context, arr: JSONArray?) {
        if (arr == null) return
        val meds = AdminDemoData.medicines
        val out = mutableListOf<Map<String, Any?>>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val boxRaw = o.optString("box_id", "").trim()
            if (boxRaw.isEmpty()) continue
            val takenRaw = o.optString("taken_at", "").trim()
            val ts = formatServerTakenAt(takenRaw)
            val medName =
                meds.find { it.box.equals(boxRaw, ignoreCase = true) }?.name?.trim().orEmpty()
            val src = o.optString("source", "").trim().ifEmpty { "recorded" }
            out.add(
                mapOf(
                    "timestamp" to ts,
                    "box" to boxRaw,
                    "medicine" to medName,
                    "dose_taken" to 1,
                    "remaining" to "—",
                    "kind" to src,
                ),
            )
        }
        replaceLogEntries(context, out)
    }

    private fun formatServerTakenAt(raw: String): String {
        val t = raw.trim()
        if (t.isEmpty()) return ""
        if (t.length >= 19 && t[10] == 'T') {
            return t.take(10) + " " + t.substring(11, 19)
        }
        return t
    }

    private fun sigFor(m: Map<String, Any?>): String {
        val ts = m["timestamp"]?.toString().orEmpty()
        val box = m["box"]?.toString().orEmpty()
        val kind = m["kind"]?.toString().orEmpty()
        val slot = m["scheduled_slot"]?.toString().orEmpty()
        return "$ts|$box|$kind|$slot"
    }

    fun dayPrefixFromDayKey(dayKey: String): String {
        val dk = dayKey.trim()
        if (dk.length == 8 && dk.all { it.isDigit() }) {
            return "${dk.substring(0, 4)}-${dk.substring(4, 6)}-${dk.substring(6, 8)}"
        }
        return dk
    }

    /** Any taken/missed outcome for this box on this calendar day (legacy / quick check). */
    fun hasSlotOutcomeForBoxDay(context: Context, box: String, dayKey: String): Boolean {
        val prefix = dayPrefixFromDayKey(dayKey)
        val b = box.trim()
        if (b.isEmpty()) return false
        for (row in readLog(context.applicationContext)) {
            if (!row["box"].toString().equals(b, ignoreCase = true)) continue
            val ts = row["timestamp"]?.toString() ?: continue
            if (!ts.startsWith(prefix)) continue
            val k = row["kind"]?.toString().orEmpty()
            if (k.startsWith("taken") || k == "missed_auto" || k == "missed") return true
        }
        return false
    }

    fun hasSlotOutcomeForBoxSlot(
        context: Context,
        box: String,
        dayKey: String,
        slotHhMm: String,
        singleDailySlot: Boolean,
    ): Boolean {
        val prefix = dayPrefixFromDayKey(dayKey)
        val b = box.trim()
        val slotNorm = MedicineSchedule.normalizeToHhMm(slotHhMm)
        if (b.isEmpty() || slotNorm.isEmpty()) return false
        for (row in readLog(context.applicationContext)) {
            if (!row["box"].toString().equals(b, ignoreCase = true)) continue
            val ts = row["timestamp"]?.toString() ?: continue
            if (!ts.startsWith(prefix)) continue
            val k = row["kind"]?.toString().orEmpty()
            if (!(k.startsWith("taken") || k == "missed_auto" || k == "missed")) continue
            val rowSlot = row["scheduled_slot"]?.toString()?.let { MedicineSchedule.normalizeToHhMm(it) }.orEmpty()
            if (singleDailySlot && rowSlot.isEmpty()) return true
            if (rowSlot == slotNorm) return true
        }
        return false
    }
}
