package com.curax.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * When a linked user sets or clears their app unlock PIN on-device, mirror that value to the hub
 * ([care_app_unlock_pin] in server alert_settings) so an admin in Care mode can see it next to
 * email alerts after a sync.
 */
object CareUnlockPinSync {

    fun postIfUserLinked(context: Context, pinPlain: String) {
        val app = context.applicationContext
        if (!AppRole.isUser(app)) return
        val prefs = Prefs(app)
        val base = prefs.centralApiUrl.trim().removeSuffix("/")
        val botId = prefs.id.trim()
        val apiKey = prefs.apiKey.trim()
        if (base.isEmpty() || botId.isEmpty() || apiKey.isEmpty()) return
        Thread {
            try {
                val body = JSONObject().apply {
                    put("bot_id", botId)
                    put("api_key", apiKey)
                    put("client_ms", System.currentTimeMillis())
                    put("medicines", JSONArray())
                    put("dose_append", JSONArray())
                    StandaloneOfflineMirror.appendSystemSettingsForSync(
                        this,
                        mapOf("care_app_unlock_pin" to pinPlain),
                    )
                }
                UserStandaloneSyncApi.postSync(base, body)
            } catch (_: Exception) {
            }
        }.start()
    }
}
