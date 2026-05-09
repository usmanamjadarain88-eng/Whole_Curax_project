package com.curax.app

import android.app.ProgressDialog
import android.content.Intent
import android.os.Bundle
import android.widget.TextView
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

/**
 * Administrator sign in: email + password → email OTP → [AdminMobileVerifyActivity].
 */
class AdminRegistrationActivity : AppCompatActivity() {

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private lateinit var etEmail: TextInputEditText
    private lateinit var etPassword: TextInputEditText
    private lateinit var btnSignIn: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_admin_registration)

        etEmail = findViewById(R.id.etAdminEmail)
        etPassword = findViewById(R.id.etAdminPassword)
        btnSignIn = findViewById(R.id.btnAdminSignIn)

        applyFloatingHintColors()
        applyPrefillFromIntent(intent)

        findViewById<TextView>(R.id.tvAdminSignUp).setOnClickListener {
            startActivity(Intent(this, AdminSignUpActivity::class.java))
        }

        btnSignIn.setOnClickListener {
            val email = etEmail.text?.toString()?.trim().orEmpty()
            val password = etPassword.text?.toString()?.trim().orEmpty()
            when {
                email.isBlank() || password.isBlank() -> {
                    CuraxFeedback.warn(this, getString(R.string.sign_in_all_fields_required))
                }
                password.length < 6 -> {
                    CuraxFeedback.warn(this, getString(R.string.sign_up_password_short))
                }
                else -> {
                    val prefs = Prefs(this)
                    val base = prefs.centralApiUrl.trim().removeSuffix("/")
                    btnSignIn.isEnabled = false
                    val progress = ProgressDialog(this).apply {
                        setMessage(getString(R.string.admin_progress_signing_in))
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
                                .url("$base/admin/mobile-sign-in-start")
                                .post(json.toString().toRequestBody("application/json".toMediaType()))
                                .build()
                            val res = http.newCall(req).execute()
                            val body = res.body?.string().orEmpty()
                            val jo = if (body.isNotBlank()) JSONObject(body) else JSONObject()
                            if (!res.isSuccessful) {
                                runOnUiThread {
                                    progress.dismiss()
                                    btnSignIn.isEnabled = true
                                }
                                val codeKey = jo.optString("message", "").trim().lowercase(Locale.US)
                                val detail = jo.optString("detail", "").trim()
                                val msg = when (codeKey) {
                                    "unknown_admin_email" -> getString(R.string.admin_unknown_email)
                                    "invalid_credentials" -> getString(R.string.sign_in_error_invalid_credentials)
                                    "password_not_synced" -> detail.ifEmpty { getString(R.string.admin_password_not_synced_detail) }
                                    "password_too_short" -> getString(R.string.sign_up_password_short)
                                    "invalid_email" -> getString(R.string.admin_error_invalid_email_server)
                                    "admin_mobile_login_not_configured" -> getString(R.string.admin_error_mobile_login_not_configured)
                                    else -> listOf(jo.optString("message", "").trim(), detail)
                                        .filter { it.isNotEmpty() }
                                        .joinToString("\n")
                                        .ifEmpty { getString(R.string.request_failed) }
                                }
                                runOnUiThread {
                                    CuraxFeedback.warn(this@AdminRegistrationActivity, msg, long = true)
                                }
                                return@Thread
                            }
                            val challenge = jo.optString("challenge_token", "").trim()
                            if (challenge.length < 16) {
                                runOnUiThread {
                                    progress.dismiss()
                                    btnSignIn.isEnabled = true
                                    CuraxFeedback.warn(this@AdminRegistrationActivity, getString(R.string.request_failed), long = true)
                                }
                                return@Thread
                            }
                            AdminMobileAuthSession.passwordPlain = password
                            runOnUiThread {
                                progress.dismiss()
                                btnSignIn.isEnabled = true
                                CuraxFeedback.success(this@AdminRegistrationActivity, getString(R.string.admin_otp_sent_short))
                                window.decorView.post {
                                    if (!isFinishing) {
                                        startActivity(
                                            Intent(this, AdminMobileVerifyActivity::class.java)
                                                .putExtra(AdminMobileVerifyActivity.EXTRA_CHALLENGE_TOKEN, challenge)
                                                .putExtra(AdminMobileVerifyActivity.EXTRA_EMAIL, email),
                                        )
                                    }
                                }
                            }
                        } catch (_: Exception) {
                            runOnUiThread {
                                progress.dismiss()
                                btnSignIn.isEnabled = true
                                CuraxFeedback.warn(this@AdminRegistrationActivity, getString(R.string.error_network_unreachable), long = true)
                            }
                        }
                    }.start()
                }
            }
        }
    }

    private fun applyFloatingHintColors() {
        val hintEmail = ContextCompat.getColorStateList(this, R.color.auth_hint_email_muted)
        val hintPassword = ContextCompat.getColorStateList(this, R.color.auth_hint_password_muted)
        hintEmail?.let { findViewById<TextInputLayout>(R.id.tilAdminEmail).defaultHintTextColor = it }
        hintPassword?.let { findViewById<TextInputLayout>(R.id.tilAdminPassword).defaultHintTextColor = it }
    }

    private fun applyPrefillFromIntent(incoming: Intent) {
        val email = incoming.getStringExtra(EXTRA_PREFILL_EMAIL)?.trim().orEmpty()
        if (email.isNotEmpty()) {
            etEmail.setText(email)
        }
        incoming.removeExtra(EXTRA_PREFILL_EMAIL)
    }

    companion object {
        const val EXTRA_PREFILL_EMAIL = "prefill_email"
    }
}
