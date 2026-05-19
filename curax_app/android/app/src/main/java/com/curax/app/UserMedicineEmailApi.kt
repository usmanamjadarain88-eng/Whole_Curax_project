package com.curax.app

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** POST /user/medicine-reminder-email — user Gmail at −30/−15/exact and +5/+15 (from local alarm). */
object UserMedicineEmailApi {

    private val JSON = "application/json; charset=utf-8".toMediaType()
    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun postReminder(
        context: Context,
        kind: String,
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
        val k = kind.trim().lowercase()
        if (k !in listOf("pre30", "pre15", "exact", "post5", "post15")) return
        Thread {
            try {
                val body = JSONObject().apply {
                    put("bot_id", botId)
                    put("api_key", apiKey)
                    put("kind", k)
                    put("box_id", boxId.trim().uppercase())
                    put("medicine_name", medicineName.trim())
                    put("schedule_time", scheduleTime.trim())
                    put("dose_date", doseDateForApi(doseDate))
                }
                val req = Request.Builder()
                    .url("$base/user/medicine-reminder-email")
                    .post(body.toString().toRequestBody(JSON))
                    .build()
                http.newCall(req).execute().close()
            } catch (_: Exception) {
            }
        }.start()
    }

    private fun doseDateForApi(dayKey: String): String {
        val d = dayKey.trim()
        if (d.length == 8 && d.all { it.isDigit() }) {
            return "${d.take(4)}-${d.substring(4, 6)}-${d.takeLast(2)}"
        }
        return d.take(10)
    }
}
