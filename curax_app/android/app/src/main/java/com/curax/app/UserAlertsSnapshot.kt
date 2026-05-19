package com.curax.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Keep cached GET /user/data snapshot alerts in sync after local delete / relay prepend. */
object UserAlertsSnapshot {

    private val apiTimeFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    fun persistAlerts(context: Context) {
        val app = context.applicationContext
        if (!AppRole.isUser(app)) return
        val prefs = Prefs(app)
        val base = try {
            JSONObject(prefs.cachedUserDataSnapshotJson.trim().ifEmpty { "{}" })
        } catch (_: Exception) {
            JSONObject()
        }
        val arr = JSONArray()
        for (item in AdminDemoData.getApiAlerts()) {
            val o = JSONObject()
            o.put("type", item.type)
            o.put("message", item.message)
            o.put("created_at", apiTimeFmt.format(item.receivedAt))
            val sid = item.serverId?.trim().orEmpty()
            if (sid.isNotEmpty()) o.put("id", sid)
            arr.put(o)
        }
        base.put("alerts", arr)
        prefs.cachedUserDataSnapshotJson = base.toString()
        prefs.userStandaloneDataReady = true
    }
}
