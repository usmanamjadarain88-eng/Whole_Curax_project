package com.curax.app

import android.app.ProgressDialog
import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

class SignUpActivity : AppCompatActivity() {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_signup)

        val store = LocalUserStore(this)
        val prefs = Prefs(this)
        val etEmail = findViewById<TextInputEditText>(R.id.etEmail)
        val etPassword = findViewById<TextInputEditText>(R.id.etPassword)
        val etConfirmPassword = findViewById<TextInputEditText>(R.id.etConfirmPassword)
        val etConnectionCode = findViewById<TextInputEditText>(R.id.etConnectionCode)
        val btnSignUp = findViewById<MaterialButton>(R.id.btnSignUp)
        val tvSignUpAdmin = findViewById<TextView>(R.id.tvSignUpAdmin)

        btnSignUp.setOnClickListener {
            val email = etEmail.text?.toString()?.trim().orEmpty()
            val password = etPassword.text?.toString()?.trim().orEmpty()
            val confirmPassword = etConfirmPassword.text?.toString()?.trim().orEmpty()
            val connectionCode = etConnectionCode.text?.toString()?.trim().orEmpty()

            when {
                email.isBlank() || password.isBlank() || confirmPassword.isBlank() || connectionCode.isBlank() -> {
                    Toast.makeText(this, "All fields are required", Toast.LENGTH_SHORT).show()
                }
                password != confirmPassword -> {
                    Toast.makeText(this, "Password and confirm password must match", Toast.LENGTH_SHORT).show()
                }
                password.length < 6 -> {
                    Toast.makeText(this, "Password must be at least 6 characters", Toast.LENGTH_SHORT).show()
                }
                else -> {
                    val base = prefs.centralApiUrl.trim().trimEnd('/')
                    btnSignUp.isEnabled = false
                    val progress = ProgressDialog(this).apply {
                        setMessage("Linking to admin...")
                        setCancelable(false)
                        show()
                    }
                    Thread {
                        try {
                            val botId = UUID.randomUUID().toString().take(8)
                            val apiKey = UUID.randomUUID().toString().replace("-", "").take(16)
                            val json = JSONObject().apply {
                                put("connection_code", connectionCode)
                                put("bot_id", botId)
                                put("api_key", apiKey)
                                put("name", email)
                                put("email", email)
                            }
                            val req = Request.Builder()
                                .url("$base/connect-to-admin")
                                .post(json.toString().toRequestBody("application/json".toMediaType()))
                                .build()
                            val resp = http.newCall(req).execute()
                            val respBody = resp.body?.string().orEmpty()
                            runOnUiThread {
                                progress.dismiss()
                                btnSignUp.isEnabled = true
                                if (resp.isSuccessful) {
                                    val obj = try { JSONObject(respBody) } catch (_: Exception) { null }
                                    val adminId = obj?.optString("admin_id", "") ?: ""
                                    val adminName = obj?.optString("admin_name", "") ?: ""
                                    prefs.id = botId
                                    prefs.apiKey = apiKey
                                    prefs.connectionCode = connectionCode
                                    prefs.databusAccessCode = obj?.optString("databus_access_code", "")?.trim().orEmpty()
                                    prefs.linkedAdminId = adminId
                                    prefs.linkedAdminName = adminName
                                    prefs.hasEverConnected = true
                                    // Save the first standalone snapshot locally so the user sees data immediately on open.
                                    UserDataBusClient.fetchAndApplyUserData(applicationContext, base, botId, apiKey)
                                    store.saveUser(email, password, LocalUserStore.ROLE_USER)
                                    Toast.makeText(this, getString(R.string.linked_to_admin_success), Toast.LENGTH_SHORT).show()
                                    startActivity(
                                        Intent(this, PinSetupActivity::class.java)
                                            .putExtra(PinSetupActivity.EXTRA_NEXT_ROLE, LocalUserStore.ROLE_USER)
                                    )
                                    finish()
                                } else {
                                    Toast.makeText(this, getString(R.string.invalid_connection_code), Toast.LENGTH_SHORT).show()
                                }
                            }
                        } catch (e: Exception) {
                            runOnUiThread {
                                progress.dismiss()
                                btnSignUp.isEnabled = true
                                Toast.makeText(this, getString(R.string.invalid_connection_code), Toast.LENGTH_SHORT).show()
                            }
                        }
                    }.start()
                }
            }
        }

        tvSignUpAdmin.setOnClickListener {
            startActivity(Intent(this, AdminRegistrationActivity::class.java))
        }
    }
}
