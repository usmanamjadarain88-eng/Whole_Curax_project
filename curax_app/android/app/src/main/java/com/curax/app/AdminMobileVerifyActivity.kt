package com.curax.app

import android.app.ProgressDialog
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

class AdminMobileVerifyActivity : AppCompatActivity() {

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private var verifiedOk = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_admin_mobile_verify)

        val challengeToken = intent.getStringExtra(EXTRA_CHALLENGE_TOKEN)?.trim().orEmpty()
        val emailDisplay = intent.getStringExtra(EXTRA_EMAIL)?.trim().orEmpty()
        if (challengeToken.length < 16 || emailDisplay.isBlank()) {
            CuraxFeedback.warn(this, getString(R.string.request_failed), long = true)
            finish()
            return
        }

        findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbarAdminVerify).setNavigationOnClickListener {
            finish()
        }

        findViewById<android.widget.TextView>(R.id.tvAdminVerifyBody).text =
            getString(R.string.admin_verify_email_body, emailDisplay)

        val etOtp = findViewById<TextInputEditText>(R.id.etAdminVerifyOtp)
        val btn = findViewById<MaterialButton>(R.id.btnAdminVerifyContinue)
        val prefs = Prefs(this)
        val store = LocalUserStore(this)
        val base = prefs.centralApiUrl.trim().removeSuffix("/")

        btn.setOnClickListener {
            val otp = etOtp.text?.toString()?.trim()?.replace(" ", "").orEmpty()
            if (otp.length < 6) {
                CuraxFeedback.warn(this, getString(R.string.forgot_password_otp_hint), long = true)
                return@setOnClickListener
            }
            val password = AdminMobileAuthSession.passwordPlain
            if (password.isNullOrBlank()) {
                CuraxFeedback.warn(this, getString(R.string.request_failed), long = true)
                finish()
                return@setOnClickListener
            }

            btn.isEnabled = false
            val progress = ProgressDialog(this).apply {
                setMessage(getString(R.string.opening_curax))
                setCancelable(false)
                show()
            }

            Thread {
                try {
                    val json = JSONObject().apply {
                        put("challenge_token", challengeToken)
                        put("otp", otp)
                    }
                    val req = Request.Builder()
                        .url("$base/admin/mobile-sign-in-verify")
                        .post(json.toString().toRequestBody("application/json".toMediaType()))
                        .build()
                    val res = http.newCall(req).execute()
                    val body = res.body?.string().orEmpty()
                    val jo = if (body.isNotBlank()) JSONObject(body) else JSONObject()

                    if (!res.isSuccessful) {
                        val msg = jo.optString("message", "").trim().ifEmpty { res.message }
                        val detail = jo.optString("detail", "").trim()
                        val combined = listOf(msg, detail).filter { it.isNotEmpty() }.joinToString("\n")
                        runOnUiThread {
                            progress.dismiss()
                            btn.isEnabled = true
                            CuraxFeedback.warn(this@AdminMobileVerifyActivity, combined.ifEmpty { getString(R.string.request_failed) }, long = true)
                        }
                        return@Thread
                    }

                    val accessCode = jo.optString("admin_access_code", "").trim().uppercase()
                    val connectionCode = jo.optString("connection_code", "").trim()
                    val adminName = jo.optString("name", "").trim()
                    val emailRow = jo.optString("email", "").trim().ifEmpty { emailDisplay }

                    if (accessCode.isBlank()) {
                        runOnUiThread {
                            progress.dismiss()
                            btn.isEnabled = true
                            CuraxFeedback.warn(this@AdminMobileVerifyActivity, getString(R.string.request_failed), long = true)
                        }
                        return@Thread
                    }

                    val passwordCopy = password
                    AdminMobileAuthSession.clear()
                    verifiedOk = true
                    AdminRegistrationHelper.provisionAdminMobileSession(
                        activity = this@AdminMobileVerifyActivity,
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
                } catch (e: Exception) {
                    runOnUiThread {
                        progress.dismiss()
                        btn.isEnabled = true
                        CuraxFeedback.warn(this@AdminMobileVerifyActivity, getString(R.string.error_network_unreachable), long = true)
                    }
                }
            }.start()
        }
    }

    override fun onDestroy() {
        if (!verifiedOk) {
            AdminMobileAuthSession.clear()
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_CHALLENGE_TOKEN = "challenge_token"
        const val EXTRA_EMAIL = "email"
    }
}
