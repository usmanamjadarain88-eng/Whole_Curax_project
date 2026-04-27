package com.curax.app

import android.content.Context
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

data class UserPlanRow(
    val id: String,
    val title: String,
    val notes: String,
    val planDate: String,
    val planTime: String,
    val activityType: String,
    val createdAt: String,
    /** "pending" or "done" */
    val status: String = "pending",
    val healthType: String = "general",
    val completedAt: String = "",
)

val UserPlanRow.isDone: Boolean
    get() = status.equals("done", ignoreCase = true)

object UserPlansApi {

    private val JSON = "application/json; charset=utf-8".toMediaType()
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    private fun completedAtIsoLocal(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getDefault()
        }.format(Date())

    private fun parsePlanObject(o: JSONObject): UserPlanRow =
        UserPlanRow(
            id = o.optString("id", ""),
            title = o.optString("title", ""),
            notes = o.optString("notes", ""),
            planDate = o.optString("plan_date", ""),
            planTime = o.optString("plan_time", ""),
            activityType = o.optString("activity_type", "other"),
            createdAt = o.optString("created_at", ""),
            status = o.optString("status", "pending").ifEmpty { "pending" },
            healthType = o.optString("health_type", "general").ifEmpty { "general" },
            completedAt = o.optString("completed_at", ""),
        )

    fun fetchPlans(context: Context): Pair<List<UserPlanRow>, String?> {
        val p = Prefs(context.applicationContext)
        val base = p.centralApiUrl.trim().removeSuffix("/")
        val botId = p.id.trim()
        val apiKey = p.apiKey.trim()
        if (base.isEmpty() || botId.isEmpty() || apiKey.isEmpty()) {
            return emptyList<UserPlanRow>() to "missing_credentials"
        }
        val url = "$base/user/plans?bot_id=${URLEncoder.encode(botId, "UTF-8")}&api_key=${URLEncoder.encode(apiKey, "UTF-8")}"
        return try {
            val res = http.newCall(Request.Builder().url(url).get().build()).execute()
            val body = res.body?.string() ?: "{}"
            if (!res.isSuccessful) return emptyList<UserPlanRow>() to "http_${res.code}"
            val j = JSONObject(body)
            val arr = j.optJSONArray("plans") ?: JSONArray()
            val list = mutableListOf<UserPlanRow>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                list.add(parsePlanObject(o))
            }
            UserPlansLocalStore.saveCache(context.applicationContext, arr)
            list to null
        } catch (e: Exception) {
            val cached = UserPlansLocalStore.readCache(context.applicationContext)
            if (cached.isNotEmpty()) {
                cached to "offline_cache"
            } else {
                emptyList<UserPlanRow>() to (e.message ?: "error")
            }
        }
    }

    fun createPlan(
        context: Context,
        title: String,
        notes: String,
        planDate: String,
        planTime: String,
        activityType: String,
        healthType: String = "general",
    ): Pair<Boolean, String?> {
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
            put("title", title)
            put("notes", notes)
            put("plan_date", planDate)
            put("plan_time", planTime)
            put("activity_type", activityType)
            put("health_type", healthType)
        }
        return try {
            val url = "$base/user/plans".toHttpUrlOrNull() ?: return false to "bad_url"
            val res = http.newCall(
                Request.Builder().url(url).post(body.toString().toRequestBody(JSON)).build(),
            ).execute()
            val text = res.body?.string() ?: "{}"
            if (!res.isSuccessful) return false to "http_${res.code}: $text"
            val ok = JSONObject(text).optBoolean("ok", false)
            if (!ok) return false to text
            true to null
        } catch (e: Exception) {
            false to (e.message ?: "error")
        }
    }

    fun patchPlanStatus(context: Context, planId: String, status: String): Pair<Boolean, String?> {
        val p = Prefs(context.applicationContext)
        val base = p.centralApiUrl.trim().removeSuffix("/")
        val botId = p.id.trim()
        val apiKey = p.apiKey.trim()
        if (base.isEmpty() || botId.isEmpty() || apiKey.isEmpty() || planId.isEmpty()) {
            return false to "missing_credentials"
        }
        val body = JSONObject().apply {
            put("bot_id", botId)
            put("api_key", apiKey)
            put("plan_id", planId)
            put("status", status)
        }
        return try {
            val url = "$base/user/plans".toHttpUrlOrNull() ?: return false to "bad_url"
            val res = http.newCall(
                Request.Builder()
                    .url(url)
                    .patch(body.toString().toRequestBody(JSON))
                    .build(),
            ).execute()
            val text = res.body?.string() ?: "{}"
            if (!res.isSuccessful) return false to "http_${res.code}: $text"
            val ok = JSONObject(text).optBoolean("ok", false)
            if (!ok) return false to text
            val completedAt = if (status.equals("done", ignoreCase = true)) completedAtIsoLocal() else ""
            UserPlansLocalStore.updatePlanStatusInCache(context.applicationContext, planId, status, completedAt)
            true to null
        } catch (e: Exception) {
            false to (e.message ?: "error")
        }
    }

    fun deletePlan(context: Context, planId: String): Pair<Boolean, String?> {
        val p = Prefs(context.applicationContext)
        val base = p.centralApiUrl.trim().removeSuffix("/")
        val botId = p.id.trim()
        val apiKey = p.apiKey.trim()
        if (base.isEmpty() || botId.isEmpty() || apiKey.isEmpty() || planId.isEmpty()) {
            return false to "missing_credentials"
        }
        val body = JSONObject().apply {
            put("bot_id", botId)
            put("api_key", apiKey)
            put("plan_id", planId)
        }
        return try {
            val url = "$base/user/plans".toHttpUrlOrNull() ?: return false to "bad_url"
            val res = http.newCall(
                Request.Builder()
                    .url(url)
                    .method("DELETE", body.toString().toRequestBody(JSON))
                    .build(),
            ).execute()
            val text = res.body?.string() ?: "{}"
            if (!res.isSuccessful) return false to "http_${res.code}: $text"
            val ok = JSONObject(text).optBoolean("ok", false)
            if (!ok) return false to text
            UserPlansLocalStore.removePlanFromCache(context.applicationContext, planId)
            true to null
        } catch (e: Exception) {
            false to (e.message ?: "error")
        }
    }
}

object UserPlansLocalStore {

    private const val PREFS = "curax_user_plans_local"
    private const val KEY = "plans_json"

    fun saveCache(context: Context, plans: JSONArray) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, plans.toString())
            .apply()
    }

    private fun readJsonArray(context: Context): JSONArray {
        val raw = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "[]") ?: "[]"
        return try {
            JSONArray(raw)
        } catch (_: Exception) {
            JSONArray()
        }
    }

    fun updatePlanStatusInCache(context: Context, planId: String, status: String, completedAt: String) {
        val arr = readJsonArray(context)
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("id", "") != planId) continue
            o.put("status", status)
            if (status.equals("done", ignoreCase = true) && completedAt.isNotEmpty()) {
                o.put("completed_at", completedAt)
            } else {
                o.remove("completed_at")
            }
            saveCache(context, arr)
            return
        }
    }

    fun removePlanFromCache(context: Context, planId: String) {
        val arr = readJsonArray(context)
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("id", "") != planId) out.put(o)
        }
        saveCache(context, out)
    }

    fun readCache(context: Context): List<UserPlanRow> {
        val arr = readJsonArray(context)
        val list = mutableListOf<UserPlanRow>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            list.add(
                UserPlanRow(
                    id = o.optString("id", ""),
                    title = o.optString("title", ""),
                    notes = o.optString("notes", ""),
                    planDate = o.optString("plan_date", ""),
                    planTime = o.optString("plan_time", ""),
                    activityType = o.optString("activity_type", "other"),
                    createdAt = o.optString("created_at", ""),
                    status = o.optString("status", "pending").ifEmpty { "pending" },
                    healthType = o.optString("health_type", "general").ifEmpty { "general" },
                    completedAt = o.optString("completed_at", ""),
                ),
            )
        }
        return list
    }

    fun clear(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY)
            .apply()
    }
}
