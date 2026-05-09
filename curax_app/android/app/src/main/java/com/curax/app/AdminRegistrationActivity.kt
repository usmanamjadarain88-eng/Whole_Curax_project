package com.curax.app

import android.app.ProgressDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

class AdminRegistrationActivity : AppCompatActivity() {

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private var desktopCodePathVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_admin_registration)

        val store = LocalUserStore(this)
        val prefs = Prefs(this)
        val etEmail = findViewById<TextInputEditText>(R.id.etAdminEmail)
        val etPassword = findViewById<TextInputEditText>(R.id.etAdminPassword)
        val etConfirmPassword = findViewById<TextInputEditText>(R.id.etAdminConfirmPassword)
        val etAccessCode = findViewById<TextInputEditText>(R.id.etAdminAccessCode)
        val btnContinueEmail = findViewById<MaterialButton>(R.id.btnAdminContinueEmail)
        val btnRegisterWithCode = findViewById<MaterialButton>(R.id.btnAdminRegisterWithCode)
        val layoutDesktop = findViewById<LinearLayout>(R.id.layoutDesktopCodePath)
        val tvToggle = findViewById<TextView>(R.id.tvToggleDesktopCodePath)

        findViewById<TextView>(R.id.tvAdminSignIn).setOnClickListener {
            startActivity(Intent(this, SignInActivity::class.java))
        }

        tvToggle.setOnClickListener {
            desktopCodePathVisible = !desktopCodePathVisible
            layoutDesktop.visibility = if (desktopCodePathVisible) View.VISIBLE else View.GONE
            btnContinueEmail.visibility = if (desktopCodePathVisible) View.GONE else View.VISIBLE
            tvToggle.text = getString(
                if (desktopCodePathVisible) {
                    R.string.admin_sign_in_toggle_email_path
                } else {
                    R.string.admin_sign_in_toggle_desktop_code
                },
            )
        }

        btnContinueEmail.setOnClickListener {
            val email = etEmail.text?.toString()?.trim().orEmpty()
            val password = etPassword.text?.toString()?.trim().orEmpty()
            val confirmPassword = etConfirmPassword.text?.toString()?.trim().orEmpty()
            when {
                email.isBlank() || password.isBlank() || confirmPassword.isBlank() -> {
                    CuraxFeedback.warn(this, getString(R.string.sign_up_validation_all_required))
                }
                password != confirmPassword -> {
                    CuraxFeedback.warn(this, getString(R.string.sign_up_password_mismatch))
                }
                password.length < 6 -> {
                    CuraxFeedback.warn(this, getString(R.string.sign_up_password_short))
                }
                else -> {
                    val base = prefs.centralApiUrl.trim().removeSuffix("/")
                    btnContinueEmail.isEnabled = false
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
                                .url("$base/admin/mobile-sign-in-start")
                                .post(json.toString().toRequestBody("application/json".toMediaType()))
                                .build()
                            val res = http.newCall(req).execute()
                            val body = res.body?.string().orEmpty()
                            val jo = if (body.isNotBlank()) JSONObject(body) else JSONObject()
                            runOnUiThread {
                                progress.dismiss()
                                btnContinueEmail.isEnabled = true
                            }
                            if (!res.isSuccessful) {
                                val codeKey = jo.optString("message", "").trim().lowercase(Locale.US)
                                val detail = jo.optString("detail", "").trim()
                                val msg = when (codeKey) {
                                    "unknown_admin_email" -> getString(R.string.admin_unknown_email)
                                    "invalid_credentials" -> getString(R.string.sign_in_error_invalid_credentials)
                                    "password_not_synced" -> detail.ifEmpty { getString(R.string.admin_password_not_synced_detail) }
                                    "password_too_short" -> getString(R.string.sign_up_password_short)
                                    "admin_mobile_login_not_configured" -> getString(R.string.request_failed)
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
                                    CuraxFeedback.warn(this@AdminRegistrationActivity, getString(R.string.request_failed), long = true)
                                }
                                return@Thread
                            }
                            AdminMobileAuthSession.passwordPlain = password
                            runOnUiThread {
                                startActivity(
                                    Intent(this, AdminMobileVerifyActivity::class.java)
                                        .putExtra(AdminMobileVerifyActivity.EXTRA_CHALLENGE_TOKEN, challenge)
                                        .putExtra(AdminMobileVerifyActivity.EXTRA_EMAIL, email),
                                )
                            }
                        } catch (_: Exception) {
                            runOnUiThread {
                                progress.dismiss()
                                btnContinueEmail.isEnabled = true
                                CuraxFeedback.warn(this@AdminRegistrationActivity, getString(R.string.error_network_unreachable), long = true)
                            }
                        }
                    }.start()
                }
            }
        }

        btnRegisterWithCode.setOnClickListener {
            val email = etEmail.text?.toString()?.trim().orEmpty()
            val password = etPassword.text?.toString()?.trim().orEmpty()
            val confirmPassword = etConfirmPassword.text?.toString()?.trim().orEmpty()
            val accessCode = etAccessCode.text?.toString()?.trim().orEmpty().uppercase(Locale.US)
            when {
                email.isBlank() || password.isBlank() || confirmPassword.isBlank() || accessCode.isBlank() -> {
                    CuraxFeedback.warn(this, getString(R.string.sign_up_validation_all_required))
                }
                password != confirmPassword -> {
                    CuraxFeedback.warn(this, getString(R.string.sign_up_password_mismatch))
                }
                else -> {
                    val base = prefs.centralApiUrl.trim().removeSuffix("/")
                    btnRegisterWithCode.isEnabled = false
                    val progress = ProgressDialog(this).apply {
                        setMessage(getString(R.string.opening_curax))
                        setCancelable(false)
                        show()
                    }
                    AdminRegistrationHelper.provisionAdminMobileSession(
                        activity = this,
                        prefs = prefs,
                        store = store,
                        baseRaw = base,
                        email = email,
                        password = password,
                        accessCode = accessCode,
                        adminDisplayName = "",
                        connectionCode = "",
                        progress = progress,
                        onErrorEnableUi = Runnable { btnRegisterWithCode.isEnabled = true },
                    )
                }
            }
        }
    }
}
