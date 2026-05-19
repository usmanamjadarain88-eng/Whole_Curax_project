package com.curax.app

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** POST /user/delete-alerts — remove this user's alert rows on the server (so sync does not restore them). */
object UserAlertsApi {

    private val JSON = "application/json; charset=utf-8".toMediaType()
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(25, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    fun deleteAlerts(baseUrl: String, botId: String, apiKey: String, serverAlertIds: List<String>): Boolean {
        if (baseUrl.isBlank() || botId.isBlank() || apiKey.isBlank() || serverAlertIds.isEmpty()) {
            return false
        }
        val base = baseUrl.trim().removeSuffix("/")
        val body = JSONObject().apply {
            put("bot_id", botId.trim())
            put("api_key", apiKey.trim())
            put("alert_ids", JSONArray(serverAlertIds.distinct()))
        }
        val req = Request.Builder()
            .url("$base/user/delete-alerts")
            .post(body.toString().toRequestBody(JSON))
            .build()
        return try {
            http.newCall(req).execute().use { it.isSuccessful }
        } catch (_: Exception) {
            false
        }
    }
}
