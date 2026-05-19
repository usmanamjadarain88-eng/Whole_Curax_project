package com.curax.app

import android.content.Context
import org.json.JSONArray

/** Avoid duplicate heads-up notifications for the same alert (relay + sync, or double socket). */
object AlertNotifyDedupe {

    private const val PREFS = "curax_alert_notify_dedupe_v1"
    private const val KEY_FPS = "fps"
    private const val MAX = 400

    fun fingerprint(type: String, message: String, receivedAtMs: Long): String =
        "${type.trim().lowercase()}|${message.trim()}|${receivedAtMs / 60_000L}"

    fun fingerprint(item: AlertItem): String =
        fingerprint(item.type, item.message, item.receivedAt)

    /** @return true if caller should show notification; false if already shown recently. */
    fun shouldNotifyAndMark(context: Context, fingerprint: String): Boolean {
        val fp = fingerprint.trim()
        if (fp.isEmpty()) return true
        val app = context.applicationContext
        val set = load(app)
        if (fp in set) return false
        set.add(fp)
        while (set.size > MAX) {
            val first = set.first()
            set.remove(first)
        }
        save(app, set)
        return true
    }

    private fun load(context: Context): LinkedHashSet<String> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_FPS, null) ?: return linkedSetOf()
        return try {
            JSONArray(raw).let { arr ->
                linkedSetOf<String>().apply {
                    for (i in 0 until arr.length()) {
                        val s = arr.optString(i, "").trim()
                        if (s.isNotEmpty()) add(s)
                    }
                }
            }
        } catch (_: Exception) {
            linkedSetOf()
        }
    }

    private fun save(context: Context, set: LinkedHashSet<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_FPS, JSONArray(set.toList()).toString())
            .apply()
    }
}
