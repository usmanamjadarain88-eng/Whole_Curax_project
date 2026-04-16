package com.curax.app

import android.app.ProgressDialog
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

class AdminRegistrationActivity : AppCompatActivity() {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_admin_registration)

        val store = LocalUserStore(this)
        val prefs = Prefs(this)
        val etEmail = findViewById<TextInputEditText>(R.id.etAdminEmail)
        val etPassword = findViewById<TextInputEditText>(R.id.etAdminPassword)
        val etConfirmPassword = findViewById<TextInputEditText>(R.id.etAdminConfirmPassword)
        val etAccessCode = findViewById<TextInputEditText>(R.id.etAdminAccessCode)
        val btnRegister = findViewById<MaterialButton>(R.id.btnRegisterAdmin)

        btnRegister.setOnClickListener {
            val email = etEmail.text?.toString()?.trim().orEmpty()
            val password = etPassword.text?.toString()?.trim().orEmpty()
            val confirmPassword = etConfirmPassword.text?.toString()?.trim().orEmpty()
            val accessCode = etAccessCode.text?.toString()?.trim().orEmpty()

            when {
                email.isBlank() || password.isBlank() || confirmPassword.isBlank() || accessCode.isBlank() -> {
                    CuraxFeedback.warn(this, "All fields are required")
                }
                password != confirmPassword -> {
                    CuraxFeedback.warn(this, "Password and confirm password must match")
                }
                else -> {
                    val base = prefs.centralApiUrl.trim().removeSuffix("/")
                    btnRegister.isEnabled = false
                    val progress = ProgressDialog(this).apply {
                        setMessage("Importing data, please wait...")
                        setCancelable(false)
                        show()
                    }
                    Thread {
                        try {
                            // 1) Verify access code and get admin identity
                            val roleUrl = "$base/get-role?access_code=${URLEncoder.encode(accessCode, "UTF-8")}"
                            val roleReq = Request.Builder().url(roleUrl).get().build()
                            val roleRes = http.newCall(roleReq).execute()
                            if (!roleRes.isSuccessful) {
                                runOnUiThread {
                                    progress.dismiss()
                                    btnRegister.isEnabled = true
                                    CuraxFeedback.warn(this, "Invalid admin access code")
                                }
                                return@Thread
                            }
                            val roleBody = roleRes.body?.string() ?: ""
                            val roleJson = JSONObject(roleBody)
                            if (roleJson.optString("role") != "admin") {
                                runOnUiThread {
                                    progress.dismiss()
                                    btnRegister.isEnabled = true
                                    CuraxFeedback.warn(this, "Invalid admin access code")
                                }
                                return@Thread
                            }
                            // Save URL and access code now so dashboard can fetch data even if this request fails
                            prefs.centralApiUrl = base
                            prefs.adminAccessCode = accessCode
                            // 2) Fetch that admin's dashboard data (medicines, dose_logs, etc.)
                            val dataUrl = "$base/admin/data?access_code=${URLEncoder.encode(accessCode, "UTF-8")}"
                            val dataReq = Request.Builder().url(dataUrl).get().build()
                            val dataRes = http.newCall(dataReq).execute()
                            var medicinesCount = 0
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
                                            m["times"] = if (times != null) (0 until times.length()).map { times.optString(it) } else emptyList<String>()
                                            list.add(m)
                                        }
                                        AdminDemoData.replaceMedicines(AdminDemoData.fromApiMedicines(list))
                                        val alertsArray = dataJson.optJSONArray("alerts")
                                        AdminDemoData.replaceApiAlerts(AdminDemoData.fromApiAlerts(alertsArray))
                                        val medicalRemindersObj = dataJson.optJSONObject("medical_reminders")
                                        AdminDemoData.replaceMedicalReminders(AdminDemoData.fromApiMedicalReminders(medicalRemindersObj))
                                        val alertSettingsObj = dataJson.optJSONObject("alert_settings")
                                        AdminDemoData.replaceAlertSettings(AdminDemoData.fromApiAlertSettings(alertSettingsObj))
                                        medicinesCount = AdminDemoData.medicines.size
                                    }
                                } catch (_: Exception) { }
                            }
                            // 3) Save prefs for this admin app instance
                            val botId = UUID.randomUUID().toString().take(8)
                            val apiKey = UUID.randomUUID().toString().replace("-", "").take(16)
                            prefs.id = botId
                            prefs.apiKey = apiKey
                            prefs.connectionCode = roleJson.optString("connection_code")
                            // Register admin with backend: bot_id, api_key, etc. DB already knows connection_code for this admin.
                            try {
                                val saveBody = JSONObject().apply {
                                    put("bot_id", botId)
                                    put("api_key", apiKey)
                                    put("role", "admin")
                                    put("access_code", accessCode)
                                    put("name", roleJson.optString("name"))
                                    put("email", email)
                                    if (prefs.fcmToken.isNotEmpty()) put("fcm_token", prefs.fcmToken)
                                }
                                val saveReq = Request.Builder()
                                    .url("$base/save-credentials")
                                    .post(saveBody.toString().toRequestBody("application/json".toMediaType()))
                                    .build()
                                http.newCall(saveReq).execute()
                            } catch (_: Exception) {}
                            runOnUiThread {
                                progress.dismiss()
                                store.saveUser(email, password, LocalUserStore.ROLE_ADMIN)
                                val msg = if (medicinesCount > 0) {
                                    "Imported $medicinesCount medicines, Reminders, Settings from desktop."
                                } else {
                                    "Imported Reminders, Settings from desktop. Sync medicines from desktop if needed."
                                }
                                CuraxFeedback.successThen(this, msg) {
                                    startActivity(
                                        Intent(this, PinSetupActivity::class.java)
                                            .putExtra(PinSetupActivity.EXTRA_NEXT_ROLE, LocalUserStore.ROLE_ADMIN),
                                    )
                                    finish()
                                }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                            runOnUiThread {
                                progress.dismiss()
                                btnRegister.isEnabled = true
                                CuraxFeedback.warn(this, "Error: ${e.message}", long = true)
                            }
                        }
                    }.start()
                }
            }
        }
    }
}

