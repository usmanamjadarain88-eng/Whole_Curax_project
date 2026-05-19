package com.curax.app

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Last ~48h of dose-alert outcomes (fired / skipped / admin notify) for field testing.
 * Filter logcat: adb logcat -s AlertFlowLog
 */
object AlertFlowLog {

    private const val TAG = "AlertFlowLog"
    private const val PREFS = "curax_alert_flow_log"
    private const val KEY = "entries"
    private const val MAX = 300

    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun record(
        context: Context,
        type: String,
        box: String,
        slot: String,
        dayKey: String,
        outcome: String,
        detail: String = "",
    ) {
        val app = context.applicationContext
        val line = JSONObject().apply {
            put("at", fmt.format(Date()))
            put("type", type.trim())
            put("box", box.trim().uppercase())
            put("slot", slot.trim())
            put("day", dayKey.trim())
            put("outcome", outcome.trim())
            if (detail.isNotBlank()) put("detail", detail.trim())
        }
        val sp = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val arr = try {
            JSONArray(sp.getString(KEY, "[]") ?: "[]")
        } catch (_: Exception) {
            JSONArray()
        }
        val next = JSONArray()
        next.put(line)
        val keep = minOf(arr.length(), MAX - 1)
        for (i in 0 until keep) {
            next.put(arr.get(i))
        }
        sp.edit().putString(KEY, next.toString()).apply()
        val msg = "${line.optString("at")} | ${line.optString("type")} | ${line.optString("box")}" +
            " @${line.optString("slot")} | ${line.optString("outcome")}" +
            if (detail.isNotBlank()) " ($detail)" else ""
        Log.i(TAG, msg)
    }

    fun recentJson(context: Context, limit: Int = 50): String {
        val arr = try {
            JSONArray(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY, "[]") ?: "[]")
        } catch (_: Exception) {
            JSONArray()
        }
        val out = JSONArray()
        val n = minOf(limit.coerceAtLeast(1), arr.length())
        for (i in 0 until n) {
            out.put(arr.get(i))
        }
        return out.toString(2)
    }
}
