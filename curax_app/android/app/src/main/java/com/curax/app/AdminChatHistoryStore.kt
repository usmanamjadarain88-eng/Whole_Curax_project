package com.curax.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Local admin-side chat log (outgoing messages only). */
object AdminChatHistoryStore {

    data class Line(
        val at: String,
        val text: String,
        val delivery: String,
    )

    private const val PREFS = "curax_admin_chat_history"
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

    private fun key(userId: String) = "u_${userId.trim()}"

    fun append(context: Context, userId: String, text: String, delivery: String) {
        val k = key(userId)
        val sp = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val arr = try {
            JSONArray(sp.getString(k, "[]") ?: "[]")
        } catch (_: Exception) {
            JSONArray()
        }
        val row = JSONObject().apply {
            put("at", fmt.format(Date()))
            put("text", text.trim())
            put("delivery", delivery.trim())
        }
        val next = JSONArray()
        next.put(row)
        val keep = minOf(arr.length(), 99)
        for (i in 0 until keep) {
            next.put(arr.get(i))
        }
        sp.edit().putString(k, next.toString()).apply()
    }

    fun lines(context: Context, userId: String): List<Line> {
        val k = key(userId)
        val sp = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val arr = try {
            JSONArray(sp.getString(k, "[]") ?: "[]")
        } catch (_: Exception) {
            return emptyList()
        }
        val out = mutableListOf<Line>()
        for (i in arr.length() - 1 downTo 0) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(
                Line(
                    at = o.optString("at", ""),
                    text = o.optString("text", ""),
                    delivery = o.optString("delivery", ""),
                ),
            )
        }
        return out
    }
}
