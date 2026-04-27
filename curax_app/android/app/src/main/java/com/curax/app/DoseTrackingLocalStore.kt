package com.curax.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.LinkedHashSet
import java.util.Locale

/**
 * Standalone dose history + per-day "taken" marks used to suppress further local medicine alarms
 * for that box on that calendar day ([LocalAlertsController]).
 */
object DoseTrackingLocalStore {

    private const val PREFS = "curax_dose_tracking_v1"
    private const val KEY_LOG = "dose_log_json"
    private const val KEY_SUPPRESS = "suppress_keys_json"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun clear(context: Context) {
        prefs(context.applicationContext).edit().clear().apply()
    }

    fun suppressKey(box: String, dayKey: String): String =
        "${box.trim().uppercase(Locale.US)}_${dayKey.trim()}"

    fun isTakenForLocalDay(context: Context, box: String, dayKey: String): Boolean {
        val key = suppressKey(box, dayKey)
        val arr = readSuppressArray(context)
        return arr.contains(key)
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

    /** After a successful mark for this calendar day, cancel remaining local alarms for that box/day. */
    fun markTakenForDay(context: Context, box: String, dayKey: String) {
        val set = readSuppressArray(context.applicationContext)
        set.add(suppressKey(box, dayKey))
        pruneOldSuppress(set)
        writeSuppress(context.applicationContext, set)
    }

    /** Drop suppress keys older than ~45 days to keep prefs small. */
    private fun pruneOldSuppress(set: MutableSet<String>) {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -45)
        val y = cal.get(Calendar.YEAR)
        val mo = cal.get(Calendar.MONTH) + 1
        val d = cal.get(Calendar.DAY_OF_MONTH)
        val cutoffInt = y * 10_000 + mo * 100 + d
        set.removeAll { key ->
            val idx = key.lastIndexOf('_')
            if (idx <= 0) return@removeAll true
            val dk = key.substring(idx + 1).toIntOrNull() ?: return@removeAll true
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

    /** Merge server/snapshot rows into local log (dedupe by timestamp+box+kind). */
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

    private fun sigFor(m: Map<String, Any?>): String {
        val ts = m["timestamp"]?.toString().orEmpty()
        val box = m["box"]?.toString().orEmpty()
        val kind = m["kind"]?.toString().orEmpty()
        return "$ts|$box|$kind"
    }

    /** yyyyMMdd → yyyy-MM-dd prefix for log timestamps. */
    fun dayPrefixFromDayKey(dayKey: String): String {
        val dk = dayKey.trim()
        if (dk.length == 8 && dk.all { it.isDigit() }) {
            return "${dk.substring(0, 4)}-${dk.substring(4, 6)}-${dk.substring(6, 8)}"
        }
        return dk
    }

    /** True if this box already has a dose outcome logged for that calendar day (taken, missed, or auto-missed). */
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
}
