package com.curax.app

import android.content.Context
import org.json.JSONArray

/**
 * Tombstones for alerts the user removed locally so the next DataBus /user/data sync
 * does not put them back in [AdminDemoData].
 */
object DeletedAlertsStore {

    private const val PREFS = "curax_deleted_alerts_v1"
    private const val KEY_SERVER_IDS = "server_ids"
    private const val KEY_FINGERPRINTS = "fingerprints"
    private const val MAX_ENTRIES = 500

    fun fingerprint(item: AlertItem): String {
        val sid = item.serverId?.trim().orEmpty()
        if (sid.isNotEmpty()) return "id:$sid"
        return "fp:${item.type}|${item.message}|${item.receivedAt / 60_000L}"
    }

    fun isDeleted(context: Context, item: AlertItem): Boolean {
        val sid = item.serverId?.trim().orEmpty()
        if (sid.isNotEmpty() && serverIds(context).contains(sid)) return true
        return fingerprints(context).contains(fingerprint(item))
    }

    fun markDeleted(context: Context, items: Collection<AlertItem>) {
        if (items.isEmpty()) return
        val app = context.applicationContext
        val ids = serverIds(app).toMutableSet()
        val fps = fingerprints(app).toMutableSet()
        for (item in items) {
            val sid = item.serverId?.trim().orEmpty()
            if (sid.isNotEmpty()) ids.add(sid)
            fps.add(fingerprint(item))
        }
        trimAndSave(app, ids, fps)
    }

    fun unmarkDeleted(context: Context, items: Collection<AlertItem>) {
        if (items.isEmpty()) return
        val app = context.applicationContext
        val ids = serverIds(app).toMutableSet()
        val fps = fingerprints(app).toMutableSet()
        for (item in items) {
            val sid = item.serverId?.trim().orEmpty()
            if (sid.isNotEmpty()) ids.remove(sid)
            fps.remove(fingerprint(item))
        }
        trimAndSave(app, ids, fps)
    }

    fun clearAll(context: Context) {
        prefs(context).edit().clear().commit()
    }

    fun filter(context: Context, items: List<AlertItem>): List<AlertItem> {
        if (items.isEmpty()) return items
        val idSet = serverIds(context)
        val fpSet = fingerprints(context)
        if (idSet.isEmpty() && fpSet.isEmpty()) return items
        return items.filter { item ->
            val sid = item.serverId?.trim().orEmpty()
            if (sid.isNotEmpty() && sid in idSet) return@filter false
            fingerprint(item) !in fpSet
        }
    }

    private fun serverIds(context: Context): Set<String> {
        val raw = prefs(context).getString(KEY_SERVER_IDS, null) ?: return emptySet()
        return try {
            JSONArray(raw).let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    arr.optString(i, "").trim().takeIf { it.isNotEmpty() }
                }.toSet()
            }
        } catch (_: Exception) {
            emptySet()
        }
    }

    private fun fingerprints(context: Context): Set<String> {
        val raw = prefs(context).getString(KEY_FINGERPRINTS, null) ?: return emptySet()
        return try {
            JSONArray(raw).let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    arr.optString(i, "").trim().takeIf { it.isNotEmpty() }
                }.toSet()
            }
        } catch (_: Exception) {
            emptySet()
        }
    }

    private fun trimAndSave(context: Context, ids: MutableSet<String>, fps: MutableSet<String>) {
        val idList = ids.toList().takeLast(MAX_ENTRIES)
        val fpList = fps.toList().takeLast(MAX_ENTRIES)
        prefs(context).edit()
            .putString(KEY_SERVER_IDS, JSONArray(idList).toString())
            .putString(KEY_FINGERPRINTS, JSONArray(fpList).toString())
            .commit()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
