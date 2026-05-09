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
import java.util.concurrent.TimeUnit

class AdminEmailSignupVerifyActivity : AppCompatActivity() {

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_admin_email_signup_verify)

        val email = intent.getStringExtra(EXTRA_EMAIL)?.trim().orEmpty()
        if (email.isBlank()) {
            finish()
            return
        }

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
                        put("otp", otp)
                    }
                    val req = Request.Builder()
                        .url("$base/admin/email-signup/verify")
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
                        val msg = jo.optString("message", "").trim().ifEmpty { getString(R.string.request_failed) }
                        runOnUiThread {
                            CuraxFeedback.warn(this@AdminEmailSignupVerifyActivity, msg, long = true)
                        }
                        return@Thread
                    }
                    runOnUiThread {
                        CuraxFeedback.successThen(this@AdminEmailSignupVerifyActivity, getString(R.string.admin_sign_up_success)) {
                            startActivity(
                                Intent(this, AdminRegistrationActivity::class.java)
                                    .putExtra(AdminRegistrationActivity.EXTRA_PREFILL_EMAIL, email)
                                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
                            )
                            finish()
                        }
                    }
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

    companion object {
        const val EXTRA_EMAIL = "email"
    }
}
