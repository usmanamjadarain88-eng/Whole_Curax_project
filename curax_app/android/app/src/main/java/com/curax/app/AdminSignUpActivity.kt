package com.curax.app

import android.app.ProgressDialog
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Independent administrator registration (email + password → OTP → creates admin on server). */
class AdminSignUpActivity : AppCompatActivity() {

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_admin_sign_up)

        findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbarAdminSignUp).setNavigationOnClickListener {
            finish()
        }

        val hintEmail = ContextCompat.getColorStateList(this, R.color.auth_hint_email_muted)
        val hintPw = ContextCompat.getColorStateList(this, R.color.auth_hint_password_muted)
        hintEmail?.let { findViewById<TextInputLayout>(R.id.tilAdminSignUpEmail).defaultHintTextColor = it }
        hintPw?.let {
            findViewById<TextInputLayout>(R.id.tilAdminSignUpPassword).defaultHintTextColor = it
            findViewById<TextInputLayout>(R.id.tilAdminSignUpConfirmPassword).defaultHintTextColor = it
        }

        val etEmail = findViewById<TextInputEditText>(R.id.etAdminSignUpEmail)
        val etPassword = findViewById<TextInputEditText>(R.id.etAdminSignUpPassword)
        val etConfirm = findViewById<TextInputEditText>(R.id.etAdminSignUpConfirmPassword)
        val btn = findViewById<MaterialButton>(R.id.btnAdminSignUpSendCode)

        btn.setOnClickListener {
            val email = etEmail.text?.toString()?.trim().orEmpty()
            val password = etPassword.text?.toString()?.trim().orEmpty()
            val confirm = etConfirm.text?.toString()?.trim().orEmpty()
            when {
                email.isBlank() || password.isBlank() || confirm.isBlank() -> {
                    CuraxFeedback.warn(this, getString(R.string.sign_up_validation_all_required), long = true)
                }
                password != confirm -> {
                    CuraxFeedback.warn(this, getString(R.string.sign_up_password_mismatch), long = true)
                }
                password.length < 6 -> {
                    CuraxFeedback.warn(this, getString(R.string.sign_up_password_short), long = true)
                }
                else -> {
                    val base = Prefs(this).centralApiUrl.trim().removeSuffix("/")
                    btn.isEnabled = false
                    val progress = ProgressDialog(this).apply {
                        setMessage(getString(R.string.opening_curax))
                        setCancelable(false)
                        show()
                    }
                    Thread {
                        try {
                            val json = JSONObject().apply {
                                put("email", email)
                                put("password", password)
                            }
                            val req = Request.Builder()
                                .url("$base/admin/email-signup/start")
                                .post(json.toString().toRequestBody("application/json".toMediaType()))
                                .build()
                            val res = http.newCall(req).execute()
                            val body = res.body?.string().orEmpty()
                            val jo = if (body.isNotBlank()) JSONObject(body) else JSONObject()
                            runOnUiThread {
                                progress.dismiss()
                                btn.isEnabled = true
                            }
                            if (!res.isSuccessful) {
                                val key = jo.optString("message", "").trim().lowercase(Locale.US)
                                val detail = jo.optString("detail", "").trim()
                                val msg = when (key) {
                                    "email_already_registered" -> detail.ifEmpty { getString(R.string.admin_email_already_in_use) }
                                    else -> listOf(jo.optString("message", "").trim(), detail)
                                        .filter { it.isNotEmpty() }
                                        .joinToString("\n")
                                        .ifEmpty { getString(R.string.request_failed) }
                                }
                                runOnUiThread {
                                    CuraxFeedback.warn(this@AdminSignUpActivity, msg, long = true)
                                }
                                return@Thread
                            }
                            runOnUiThread {
                                startActivity(
                                    Intent(this, AdminEmailSignupVerifyActivity::class.java)
                                        .putExtra(AdminEmailSignupVerifyActivity.EXTRA_EMAIL, email),
                                )
                            }
                        } catch (_: Exception) {
                            runOnUiThread {
                                progress.dismiss()
                                btn.isEnabled = true
                                CuraxFeedback.warn(this@AdminSignUpActivity, getString(R.string.error_network_unreachable), long = true)
                            }
                        }
                    }.start()
                }
            }
        }
    }
}
