package com.curax.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Mirrors the user's medicine-box (ESP32) PIN to the hub for admin Care mode (default linked users). */
object CareDevicePinSync {

    fun postIfUserLinked(context: Context, devicePinPlain: String) {
        val app = context.applicationContext
        if (!AppRole.isUser(app)) return
        val prefs = Prefs(app)
        val base = prefs.centralApiUrl.trim().removeSuffix("/")
        val botId = prefs.id.trim()
        val apiKey = prefs.apiKey.trim()
        if (base.isEmpty() || botId.isEmpty() || apiKey.isEmpty()) return
        val pin = devicePinPlain.filter { it.isDigit() }
        if (pin.isEmpty()) return
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
                        mapOf("care_device_password" to pin),
                    )
                }
                UserStandaloneSyncApi.postSync(base, body)
            } catch (_: Exception) {
            }
        }.start()
    }
}
