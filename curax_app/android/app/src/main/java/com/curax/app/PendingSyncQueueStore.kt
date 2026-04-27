package com.curax.app

import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Outbound action queue for standalone user mutations (dose, medicine, reminders).
 * [PendingSyncCoordinator] POSTs /user/standalone-sync and clears pending rows on success.
 */
object PendingSyncQueueStore {

    const val TYPE_DOSE = "dose"
    const val TYPE_MEDICINE = "medicine"
    const val TYPE_REMINDER = "reminder"

    private const val PREFS = "curax_pending_sync_v1"
    private const val KEY_QUEUE = "queue_json"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_QUEUE).apply()
        broadcast(context)
    }

    fun enqueue(context: Context, type: String, title: String, subtitle: String) {
        val app = context.applicationContext
        val arr = readArray(app)
        val o = JSONObject().apply {
            put("id", UUID.randomUUID().toString())
            put("type", type)
            put("title", title)
            put("subtitle", subtitle)
            put("created_ms", System.currentTimeMillis())
            put("attempts", 0)
        }
        arr.put(o)
        prefs(app).edit().putString(KEY_QUEUE, arr.toString()).apply()
        broadcast(app)
    }

    fun pendingCount(context: Context): Int = readArray(context.applicationContext).length()

    fun readPending(context: Context): List<JSONObject> {
        val arr = readArray(context.applicationContext)
        val out = ArrayList<JSONObject>(arr.length())
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.let { out.add(it) }
        }
        return out
    }

    fun bumpAllAttempts(context: Context) {
        val app = context.applicationContext
        val arr = readArray(app)
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            o.put("attempts", o.optInt("attempts", 0) + 1)
        }
        prefs(app).edit().putString(KEY_QUEUE, arr.toString()).apply()
        broadcast(app)
    }

    fun removeExhausted(context: Context, maxAttempts: Int) {
        val app = context.applicationContext
        val arr = readArray(app)
        val next = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optInt("attempts", 0) < maxAttempts) next.put(o)
        }
        prefs(app).edit().putString(KEY_QUEUE, next.toString()).apply()
        broadcast(app)
    }

    private fun readArray(ctx: Context): JSONArray {
        try {
            return JSONArray(prefs(ctx).getString(KEY_QUEUE, "[]") ?: "[]")
        } catch (_: Exception) {
            return JSONArray()
        }
    }

    private fun broadcast(ctx: Context) {
        ctx.sendBroadcast(Intent(AlertEvents.ACTION_PENDING_SYNC_QUEUE_UPDATED))
    }
}
