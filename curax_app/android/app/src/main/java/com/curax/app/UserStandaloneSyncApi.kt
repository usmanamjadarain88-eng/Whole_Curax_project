package com.curax.app

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object UserStandaloneSyncApi {

    private val JSON = "application/json; charset=utf-8".toMediaType()
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /** POST /user/standalone-sync; body must include bot_id + api_key (merged in by caller). */
    fun postSync(baseUrl: String, body: JSONObject): Pair<Boolean, String?> {
        val base = baseUrl.trim().removeSuffix("/")
        if (base.isEmpty()) return false to "no_base_url"
        return try {
            val req = Request.Builder()
                .url("$base/user/standalone-sync")
                .post(body.toString().toRequestBody(JSON))
                .build()
            http.newCall(req).execute().use { res ->
                if (res.isSuccessful) true to null
                else false to "http_${res.code}"
            }
        } catch (e: Exception) {
            false to (e.message ?: "error")
        }
    }
}
