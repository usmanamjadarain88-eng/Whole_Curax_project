package com.curax.app

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** POST /user/missed-dose-escalate — instant admin escalation (no cron delay). */
object MissedDoseEscalationApi {

    private val JSON = "application/json; charset=utf-8".toMediaType()
    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun postEscalation(
        context: Context,
        phase: Int,
        boxId: String,
        medicineName: String,
        scheduleTime: String,
        doseDate: String,
    ) {
        val app = context.applicationContext
        if (!AppRole.isUser(app)) return
        val prefs = Prefs(app)
        val base = prefs.centralApiUrl.trim().removeSuffix("/")
        val botId = prefs.id.trim()
        val apiKey = prefs.apiKey.trim()
        if (base.isEmpty() || botId.isEmpty() || apiKey.isEmpty()) return
        if (phase != 15 && phase != 30) return
        val box = boxId.trim().uppercase()
        if (box.isEmpty()) return
        Thread {
            try {
                val body = JSONObject().apply {
                    put("bot_id", botId)
                    put("api_key", apiKey)
                    put("phase", phase.toString())
                    put("box_id", box)
                    put("medicine_name", medicineName.trim())
                    put("schedule_time", scheduleTime.trim())
                    put("dose_date", doseDateForApi(doseDate))
                }
                val req = Request.Builder()
                    .url("$base/user/missed-dose-escalate")
                    .post(body.toString().toRequestBody(JSON))
                    .build()
                http.newCall(req).execute().close()
            } catch (_: Exception) {
            }
        }.start()
    }

    /** Local day keys use yyyyMMdd; server expects yyyy-MM-dd. */
    private fun doseDateForApi(dayKey: String): String {
        val d = dayKey.trim()
        if (d.length == 8 && d.all { it.isDigit() }) {
            return "${d.take(4)}-${d.substring(4, 6)}-${d.takeLast(2)}"
        }
        return d.take(10)
    }
}
