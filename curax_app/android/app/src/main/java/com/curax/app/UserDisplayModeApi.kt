package com.curax.app

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** POST [Prefs.centralApiUrl]/user/display-mode — best-effort; failures are ignored. */
object UserDisplayModeApi {

    private val JSON = "application/json; charset=utf-8".toMediaType()
    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(18, TimeUnit.SECONDS)
        .writeTimeout(18, TimeUnit.SECONDS)
        .build()

    fun postDisplayModeAsync(context: Context, standalone: Boolean) {
        val app = context.applicationContext
        Thread {
            try {
                val p = Prefs(app)
                val base = p.centralApiUrl.trim().removeSuffix("/")
                val botId = p.id.trim()
                val apiKey = p.apiKey.trim()
                if (base.isEmpty() || botId.isEmpty() || apiKey.isEmpty()) return@Thread
                val body = JSONObject().apply {
                    put("bot_id", botId)
                    put("api_key", apiKey)
                    put("display_mode", if (standalone) "standalone" else "default")
                }
                val req = Request.Builder()
                    .url("$base/user/display-mode")
                    .post(body.toString().toRequestBody(JSON))
                    .build()
                http.newCall(req).execute().close()
            } catch (_: Exception) {
            }
        }.start()
    }
}
