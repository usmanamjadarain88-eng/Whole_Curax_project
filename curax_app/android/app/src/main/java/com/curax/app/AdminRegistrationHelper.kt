package com.curax.app

import android.app.ProgressDialog
import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.TimeUnit

object AdminRegistrationHelper {

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * After [accessCode] is known (desktop code or email-verify flow), register this device,
     * pull admin data, save local admin session, open admin dashboard (no PIN setup step).
     */
    fun provisionAdminMobileSession(
        activity: AppCompatActivity,
        prefs: Prefs,
        store: LocalUserStore,
        baseRaw: String,
        email: String,
        password: String,
        accessCode: String,
        adminDisplayName: String,
        connectionCode: String,
        progress: ProgressDialog,
        onErrorEnableUi: Runnable?,
    ) {
        val base = baseRaw.trim().removeSuffix("/")
        Thread {
            try {
                val roleUrl = "$base/get-role?access_code=${URLEncoder.encode(accessCode, "UTF-8")}"
                val roleReq = Request.Builder().url(roleUrl).get().build()
                val roleRes = http.newCall(roleReq).execute()
                if (!roleRes.isSuccessful) {
                    activity.runOnUiThread {
                        progress.dismiss()
                        onErrorEnableUi?.run()
                        CuraxFeedback.warn(activity, activity.getString(R.string.admin_invalid_access_code))
                    }
                    return@Thread
                }
                val roleBody = roleRes.body?.string() ?: ""
                val roleJson = JSONObject(roleBody)
                if (roleJson.optString("role") != "admin") {
                    activity.runOnUiThread {
                        progress.dismiss()
                        onErrorEnableUi?.run()
                        CuraxFeedback.warn(activity, activity.getString(R.string.admin_invalid_access_code))
                    }
                    return@Thread
                }

                prefs.centralApiUrl = base
                prefs.adminAccessCode = accessCode

                val dataUrl = "$base/admin/data?access_code=${URLEncoder.encode(accessCode, "UTF-8")}"
                val dataReq = Request.Builder().url(dataUrl).get().build()
                val dataRes = http.newCall(dataReq).execute()
                if (dataRes.isSuccessful) {
                    val dataBody = dataRes.body?.string() ?: "{}"
                    try {
                        val dataJson = JSONObject(dataBody)
                        val serverTime = dataJson.optString("server_time", "").trim()
                        if (serverTime.isNotEmpty()) {
                            prefs.lastSyncTime = serverTime
                            val medicinesArray = dataJson.optJSONArray("medicines") ?: JSONArray()
                            val list = mutableListOf<Map<String, Any?>>()
                            for (i in 0 until medicinesArray.length()) {
                                val o = medicinesArray.optJSONObject(i) ?: continue
                                val m = mutableMapOf<String, Any?>()
                                m["name"] = o.optString("name")
                                m["box_id"] = o.optString("box_id")
                                m["dosage"] = o.optString("dosage")
                                m["low_stock"] = o.optInt("low_stock", 5)
                                m["quantity"] = o.optInt("quantity", 0)
                                m["expiry"] = o.optString("expiry")
                                m["exact_time"] = o.optString("exact_time")
                                m["dose_per_day"] = o.optInt("dose_per_day", 0)
                                m["instructions"] = o.optString("instructions")
                                val times = o.optJSONArray("times")
                                m["times"] = if (times != null) {
                                    (0 until times.length()).map { times.optString(it) }
                                } else {
                                    emptyList()
                                }
                                list.add(m)
                            }
                            AdminDemoData.replaceMedicines(AdminDemoData.fromApiMedicines(list))
                            val alertsArray = dataJson.optJSONArray("alerts")
                            AdminDemoData.replaceApiAlerts(AdminDemoData.fromApiAlerts(alertsArray))
                            val medicalRemindersObj = dataJson.optJSONObject("medical_reminders")
                            AdminDemoData.replaceMedicalReminders(
                                AdminDemoData.fromApiMedicalReminders(medicalRemindersObj),
                            )
                            val alertSettingsObj = dataJson.optJSONObject("alert_settings")
                            AdminDemoData.replaceAlertSettings(
                                AdminDemoData.fromApiAlertSettings(alertSettingsObj),
                            )
                        }
                    } catch (_: Exception) {
                    }
                }

                val botId = UUID.randomUUID().toString().take(8)
                val apiKey = UUID.randomUUID().toString().replace("-", "").take(16)
                prefs.id = botId
                prefs.apiKey = apiKey
                val cc = connectionCode.ifBlank { roleJson.optString("connection_code") }
                prefs.connectionCode = cc

                val saveName = adminDisplayName.ifBlank { roleJson.optString("name") }
                try {
                    val saveBody = JSONObject().apply {
                        put("bot_id", botId)
                        put("api_key", apiKey)
                        put("role", "admin")
                        put("access_code", accessCode)
                        put("name", saveName)
                        put("email", email)
                        put("desktop_password", password)
                        if (prefs.fcmToken.isNotEmpty()) put("fcm_token", prefs.fcmToken)
                    }
                    val saveReq = Request.Builder()
                        .url("$base/save-credentials")
                        .post(saveBody.toString().toRequestBody("application/json".toMediaType()))
                        .build()
                    http.newCall(saveReq).execute()
                } catch (_: Exception) {
                }

                activity.runOnUiThread {
                    progress.dismiss()
                    store.saveUser(email, password, LocalUserStore.ROLE_ADMIN)
                    val msg = activity.getString(R.string.admin_welcome_signed_in)
                    CuraxFeedback.successThen(activity, msg) {
                        store.disablePin()
                        prefs.appPin = ""
                        AppLockState.grantUnlock(60_000L)
                        AppLockState.markProcessEntryHandled()
                        AppLockState.clearBackgroundTimestamp()
                        prefs.lastBackgroundAtMs = 0L
                        prefs.lastExitWasClose = false
                        val dash = Intent(activity, AdminDashboardActivity::class.java)
                        dash.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        activity.startActivity(dash)
                        activity.finish()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                activity.runOnUiThread {
                    progress.dismiss()
                    onErrorEnableUi?.run()
                    CuraxFeedback.warn(activity, activity.getString(R.string.error_generic_with_reason, e.message ?: ""), long = true)
                }
            }
        }.start()
    }
}
