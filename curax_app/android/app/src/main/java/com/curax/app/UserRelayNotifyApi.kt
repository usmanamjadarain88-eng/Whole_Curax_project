package com.curax.app

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** User app → backend → admin relay WebSocket (+15/+30 uses /user/missed-dose-escalate). */
object UserRelayNotifyApi {

    private val JSON = "application/json; charset=utf-8".toMediaType()
    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun notifyAdmin(context: Context, eventType: String, message: String) {
        val app = context.applicationContext
        if (!LocalAlertsUi.shouldNotifyAdminViaRelay(app)) return
        val prefs = Prefs(app)
        val base = prefs.centralApiUrl.trim().removeSuffix("/")
        val botId = prefs.id.trim()
        val apiKey = prefs.apiKey.trim()
        val msg = message.trim()
        if (base.isEmpty() || botId.isEmpty() || apiKey.isEmpty() || msg.isEmpty()) return
        val type = eventType.trim().ifEmpty { "alert" }
        Thread {
            try {
                val body = JSONObject().apply {
                    put("bot_id", botId)
                    put("api_key", apiKey)
                    put("event_type", type)
                    put("message", msg)
                }
                val req = Request.Builder()
                    .url("$base/notify-event-by-user")
                    .post(body.toString().toRequestBody(JSON))
                    .build()
                http.newCall(req).execute().close()
            } catch (_: Exception) {
            }
        }.start()
    }
}
