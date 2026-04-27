package com.curax.app

import android.content.Context
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** POST /user/profile-picture — stores avatar on server for linked admin list + GET /user/data. */
object UserProfilePictureApi {

    private val JSON = "application/json; charset=utf-8".toMediaType()
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .build()

    fun uploadProfilePicture(context: Context, profilePictureDataUrl: String): Pair<Boolean, String?> {
        val p = Prefs(context.applicationContext)
        val base = p.centralApiUrl.trim().removeSuffix("/")
        val botId = p.id.trim()
        val apiKey = p.apiKey.trim()
        if (base.isEmpty() || botId.isEmpty() || apiKey.isEmpty()) {
            return false to "missing_credentials"
        }
        val body = JSONObject().apply {
            put("bot_id", botId)
            put("api_key", apiKey)
            put("profile_picture", profilePictureDataUrl)
        }
        return try {
            val url = "$base/user/profile-picture".toHttpUrlOrNull() ?: return false to "bad_url"
            val res = http.newCall(
                Request.Builder()
                    .url(url)
                    .post(body.toString().toRequestBody(JSON))
                    .build(),
            ).execute()
            val text = res.body?.string() ?: "{}"
            if (!res.isSuccessful) return false to "http_${res.code}: $text"
            if (!JSONObject(text).optBoolean("ok", false)) return false to text
            true to null
        } catch (e: Exception) {
            false to (e.message ?: "error")
        }
    }
}
