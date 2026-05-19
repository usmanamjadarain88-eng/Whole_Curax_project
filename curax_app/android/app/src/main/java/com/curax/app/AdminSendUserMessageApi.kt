package com.curax.app

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** POST /admin/send-user-message — admin chat → user relay popup. */
object AdminSendUserMessageApi {

    data class Result(val ok: Boolean, val detail: String)

    private val JSON = "application/json; charset=utf-8".toMediaType()
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun sendSync(accessCode: String, userId: String, message: String, baseUrl: String): Result {
        val base = baseUrl.trim().removeSuffix("/")
        val code = accessCode.trim()
        val uid = userId.trim()
        val msg = message.trim()
        if (base.isEmpty() || code.isEmpty() || uid.isEmpty() || msg.isEmpty()) {
            return Result(false, "missing_fields")
        }
        return try {
            val body = JSONObject().apply {
                put("access_code", code)
                put("user_id", uid)
                put("message", msg)
            }
            val req = Request.Builder()
                .url("$base/admin/send-user-message")
                .post(body.toString().toRequestBody(JSON))
                .build()
            http.newCall(req).execute().use { res ->
                val raw = res.body?.string().orEmpty()
                if (res.isSuccessful) {
                    Result(true, "sent")
                } else {
                    val err = try {
                        JSONObject(raw).optString("message", raw)
                    } catch (_: Exception) {
                        raw.ifEmpty { "HTTP ${res.code}" }
                    }
                    Result(false, err)
                }
            }
        } catch (e: Exception) {
            Result(false, e.message ?: "network_error")
        }
    }
}
