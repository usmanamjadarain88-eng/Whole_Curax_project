package com.curax.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Persists Health Hub "Last sync" events locally (shown in sync history screen). */
object HealthHubHistoryStore {

    private const val PREFS = "curax_health_hub_history"
    private const val KEY_SYNC = "sync_events_json"
    private const val MAX = 100

    fun appendSyncEvent(context: Context, serverTimeIso: String) {
        val t = serverTimeIso.trim()
        if (t.isEmpty()) return
        val sp = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val arr = try {
            JSONArray(sp.getString(KEY_SYNC, "[]"))
        } catch (_: Exception) {
            JSONArray()
        }
        val now = System.currentTimeMillis()
        val o = JSONObject().apply {
            put("at", now)
            put("server_time", t)
        }
        val next = JSONArray()
        next.put(o)
        val take = (MAX - 1).coerceAtLeast(0)
        for (i in 0 until minOf(arr.length(), take)) {
            next.put(arr.getJSONObject(i))
        }
        sp.edit().putString(KEY_SYNC, next.toString()).apply()
    }

    fun clear(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    fun readSyncEvents(context: Context): List<Pair<Long, String>> {
        val sp = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val arr = try {
            JSONArray(sp.getString(KEY_SYNC, "[]"))
        } catch (_: Exception) {
            JSONArray()
        }
        val out = mutableListOf<Pair<Long, String>>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(o.optLong("at", 0L) to o.optString("server_time", ""))
        }
        return out
    }
}
