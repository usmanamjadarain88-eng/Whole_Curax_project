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
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

class AdminEmailSignupVerifyActivity : AppCompatActivity() {

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** True once we hand off to [AdminRegistrationHelper] (password already cleared from session). */
    private var handedOffToProvisioning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_admin_email_signup_verify)

        val email = intent.getStringExtra(EXTRA_EMAIL)?.trim().orEmpty()
        if (email.isBlank()) {
            finish()
            return
        }

        val prefs = Prefs(this)
        val store = LocalUserStore(this)
        val base = prefs.centralApiUrl.trim().removeSuffix("/")

        findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbarAdminEmailSignupVerify).setNavigationOnClickListener {
            finish()
        }

        findViewById<android.widget.TextView>(R.id.tvAdminEmailSignupVerifyBody).text =
            getString(R.string.admin_sign_up_verify_body, email)

        val etOtp = findViewById<TextInputEditText>(R.id.etAdminEmailSignupOtp)
        val btn = findViewById<MaterialButton>(R.id.btnAdminEmailSignupVerify)

        btn.setOnClickListener {
            val otp = etOtp.text?.toString()?.trim()?.replace(" ", "").orEmpty()
            if (otp.length < 6) {
                CuraxFeedback.warn(this, getString(R.string.forgot_password_otp_hint), long = true)
                return@setOnClickListener
            }
            btn.isEnabled = false
            val progress = ProgressDialog(this).apply {
                setMessage(getString(R.string.admin_progress_verifying_account))
                setCancelable(false)
                show()
            }
            Thread {
                try {
                    val json = JSONObject().apply {
                        put("email", email)
                        put("otp", otp)
                    }
                    val req = Request.Builder()
                        .url("$base/admin/email-signup/verify")
                        .post(json.toString().toRequestBody("application/json".toMediaType()))
                        .build()
                    val res = http.newCall(req).execute()
                    val body = res.body?.string().orEmpty()
                    val jo = if (body.isNotBlank()) JSONObject(body) else JSONObject()
                    if (!res.isSuccessful) {
                        runOnUiThread {
                            progress.dismiss()
                            btn.isEnabled = true
                            val key = jo.optString("message", "").trim().lowercase(Locale.US)
                            val detail = jo.optString("detail", "").trim()
                            val msg = when (key) {
                                "session_not_found" -> getString(R.string.admin_error_session_not_found)
                                "invalid_otp" -> detail.ifEmpty { getString(R.string.admin_error_otp_invalid) }
                                "otp_expired" -> getString(R.string.admin_error_otp_expired)
                                "email_already_registered" -> getString(R.string.admin_email_already_in_use)
                                else -> listOf(jo.optString("message", "").trim(), detail)
                                    .filter { it.isNotEmpty() }
                                    .joinToString("\n")
                                    .ifEmpty { getString(R.string.request_failed) }
                            }
                            CuraxFeedback.warn(this@AdminEmailSignupVerifyActivity, msg, long = true)
                        }
                        return@Thread
                    }

                    val accessCode = jo.optString("admin_access_code", "").trim().uppercase(Locale.US)
                    val connectionCode = jo.optString("connection_code", "").trim()
                    val adminName = jo.optString("name", "").trim()
                    val emailRow = jo.optString("email", "").trim().ifEmpty { email }

                    val password = AdminMobileAuthSession.passwordPlain
                    if (accessCode.isBlank()) {
                        runOnUiThread {
                            progress.dismiss()
                            btn.isEnabled = true
                            CuraxFeedback.warn(this@AdminEmailSignupVerifyActivity, getString(R.string.request_failed), long = true)
                        }
                        return@Thread
                    }

                    if (password.isNullOrBlank()) {
                        runOnUiThread {
                            progress.dismiss()
                            btn.isEnabled = true
                            CuraxFeedback.success(this@AdminEmailSignupVerifyActivity, getString(R.string.admin_complete_sign_in_after_verify))
                            startActivity(
                                Intent(this, AdminRegistrationActivity::class.java)
                                    .putExtra(AdminRegistrationActivity.EXTRA_PREFILL_EMAIL, emailRow)
                                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
                            )
                            finish()
                        }
                        return@Thread
                    }

                    handedOffToProvisioning = true
                    val passwordCopy = password
                    AdminMobileAuthSession.clear()
                    AdminRegistrationHelper.provisionAdminMobileSession(
                        activity = this@AdminEmailSignupVerifyActivity,
                        prefs = prefs,
                        store = store,
                        baseRaw = base,
                        email = emailRow,
                        password = passwordCopy,
                        accessCode = accessCode,
                        adminDisplayName = adminName,
                        connectionCode = connectionCode,
                        progress = progress,
                        onErrorEnableUi = Runnable { btn.isEnabled = true },
                    )
                } catch (_: Exception) {
                    runOnUiThread {
                        progress.dismiss()
                        btn.isEnabled = true
                        CuraxFeedback.warn(this@AdminEmailSignupVerifyActivity, getString(R.string.error_network_unreachable), long = true)
                    }
                }
            }.start()
        }
    }

    override fun onDestroy() {
        if (!handedOffToProvisioning) {
            AdminMobileAuthSession.clear()
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_EMAIL = "email"
    }
}
