package com.curax.app

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import androidx.core.content.ContextCompat
import androidx.core.widget.doOnTextChanged
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Email → OTP (proves inbox access) → new password + confirm → POST /password-reset/complete.
 */
class ForgotPasswordActivity : AppCompatActivity() {

    private enum class Step { EMAIL, OTP, PASSWORD }

    private lateinit var prefs: Prefs
    private lateinit var toolbar: MaterialToolbar
    private lateinit var tvHeadline: TextView
    private lateinit var tvSubtitle: TextView
    private lateinit var groupEmail: LinearLayout
    private lateinit var groupOtp: LinearLayout
    private lateinit var groupPw: LinearLayout
    private lateinit var etEmail: TextInputEditText
    private lateinit var etOtp: TextInputEditText
    private lateinit var etNew: TextInputEditText
    private lateinit var etConfirm: TextInputEditText
    private lateinit var btnSendCode: AppCompatButton
    private lateinit var btnOtpNext: AppCompatButton
    private lateinit var btnSubmit: AppCompatButton
    private lateinit var tvAdminRegister: TextView
    private lateinit var tvResend: TextView

    private var step = Step.EMAIL
    private var pendingEmail = ""

    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(22, TimeUnit.SECONDS)
        .writeTimeout(22, TimeUnit.SECONDS)
        .build()

    private val backCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            when (step) {
                Step.EMAIL -> {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
                Step.OTP -> showStep(Step.EMAIL)
                Step.PASSWORD -> showStep(Step.OTP)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setContentView(R.layout.activity_forgot_password)
        onBackPressedDispatcher.addCallback(this, backCallback)

        toolbar = findViewById(R.id.toolbarForgotPassword)
        toolbar.setNavigationOnClickListener { backCallback.handleOnBackPressed() }

        tvHeadline = findViewById(R.id.tvForgotHeadline)
        tvSubtitle = findViewById(R.id.tvForgotSubtitle)
        groupEmail = findViewById(R.id.groupForgotEmail)
        groupOtp = findViewById(R.id.groupForgotOtp)
        groupPw = findViewById(R.id.groupForgotNewPassword)
        etEmail = findViewById(R.id.etForgotEmail)
        etOtp = findViewById(R.id.etForgotOtp)
        etNew = findViewById(R.id.etForgotNewPassword)
        etConfirm = findViewById(R.id.etForgotConfirmPassword)
        btnSendCode = findViewById(R.id.btnForgotSendCode)
        btnOtpNext = findViewById(R.id.btnForgotOtpNext)
        btnSubmit = findViewById(R.id.btnForgotSubmit)
        tvAdminRegister = findViewById(R.id.tvForgotAdminRegister)
        tvResend = findViewById(R.id.tvForgotResend)

        etEmail.setHintTextColor(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.auth_hint_email_muted)))
        etNew.setHintTextColor(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.auth_hint_password_muted)))
        etConfirm.setHintTextColor(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.auth_hint_password_muted)))

        AuthPasswordToggle.bind(findViewById(R.id.tilForgotNewPassword), this)
        AuthPasswordToggle.bind(findViewById(R.id.tilForgotConfirmPassword), this)

        fun syncOtpNext() {
            btnOtpNext.isEnabled = etOtp.text?.length == 6
        }
        fun syncSubmit() {
            val a = etNew.text?.toString()?.trim().orEmpty()
            val b = etConfirm.text?.toString()?.trim().orEmpty()
            btnSubmit.isEnabled = a.length >= 6 && a == b
        }
        etOtp.doOnTextChanged { _, _, _, _ -> syncOtpNext() }
        etNew.doOnTextChanged { _, _, _, _ -> syncSubmit() }
        etConfirm.doOnTextChanged { _, _, _, _ -> syncSubmit() }
        syncOtpNext()
        syncSubmit()

        btnSendCode.setOnClickListener { onSendCodeClicked() }
        btnOtpNext.setOnClickListener { showStep(Step.PASSWORD) }
        btnSubmit.setOnClickListener { onSubmitClicked() }
        tvResend.setOnClickListener { onSendCodeClicked() }

        tvAdminRegister.setOnClickListener {
            startActivity(Intent(this, AdminRegistrationActivity::class.java))
        }

        showStep(Step.EMAIL)
    }

    private fun apiBase(): String = prefs.centralApiUrl.trim().removeSuffix("/")

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        imm.hideSoftInputFromWindow(currentFocus?.windowToken ?: window.decorView.windowToken, 0)
    }

    private fun postJson(path: String, json: JSONObject): Pair<Int, JSONObject?> {
        val base = apiBase()
        if (base.isEmpty()) return Pair(-1, null)
        val req = Request.Builder()
            .url("$base$path")
            .post(json.toString().toRequestBody(JSON_MEDIA))
            .build()
        http.newCall(req).execute().use { res ->
            val raw = res.body?.string().orEmpty()
            val jo = ApiErrorMessages.parseResponseBody(raw, res.code)
            return Pair(res.code, jo)
        }
    }

    private fun showStep(s: Step) {
        step = s
        groupEmail.visibility = if (s == Step.EMAIL) View.VISIBLE else View.GONE
        groupOtp.visibility = if (s == Step.OTP) View.VISIBLE else View.GONE
        groupPw.visibility = if (s == Step.PASSWORD) View.VISIBLE else View.GONE
        when (s) {
            Step.EMAIL -> {
                tvHeadline.setText(R.string.forgot_password_step_email_title)
                tvSubtitle.setText(R.string.forgot_password_step_email_body)
            }
            Step.OTP -> {
                tvHeadline.setText(R.string.forgot_password_step_otp_title)
                tvSubtitle.text = getString(R.string.forgot_password_step_otp_body, pendingEmail)
            }
            Step.PASSWORD -> {
                tvHeadline.setText(R.string.forgot_password_step_pw_title)
                tvSubtitle.setText(R.string.forgot_password_step_pw_body)
            }
        }
    }

    private fun onSendCodeClicked() {
        val email = etEmail.text?.toString()?.trim().orEmpty()
        if (email.isBlank()) {
            CuraxFeedback.warn(this, getString(R.string.sign_in_all_fields_required))
            return
        }
        val base = apiBase()
        if (base.isEmpty()) {
            CuraxFeedback.warn(this, getString(R.string.set_api_url_for_codes), long = true)
            return
        }
        hideKeyboard()
        pendingEmail = email
        val label = getString(R.string.forgot_password_send_code)
        btnSendCode.isEnabled = false
        btnSendCode.text = getString(R.string.please_wait)
        Thread {
            try {
                val json = JSONObject().put("email", email)
                val (code, jo) = postJson("/password-reset/start", json)
                runOnUiThread {
                    btnSendCode.isEnabled = true
                    btnSendCode.text = label
                    if (code == 200 && jo != null) {
                        val devOtp = jo.optString("dev_otp", "").trim()
                        if (devOtp.isNotEmpty()) {
                            CuraxFeedback.success(this, "Dev OTP: $devOtp")
                        }
                        val sent = jo.optBoolean("email_sent", true)
                        if (!sent && devOtp.isEmpty()) {
                            CuraxFeedback.warn(
                                this,
                                getString(R.string.forgot_password_email_maybe_sent),
                                long = true,
                            )
                        }
                        etOtp.text = null
                        btnOtpNext.isEnabled = false
                        showStep(Step.OTP)
                    } else {
                        CuraxFeedback.warn(this, ApiErrorMessages.userMessage(this, code, jo), long = true)
                    }
                }
            } catch (_: Exception) {
                runOnUiThread {
                    btnSendCode.isEnabled = true
                    btnSendCode.text = label
                    CuraxFeedback.warn(this, getString(R.string.error_network_unreachable), long = true)
                }
            }
        }.start()
    }

    private fun onSubmitClicked() {
        val otp = etOtp.text?.toString()?.trim().orEmpty()
        val pw = etNew.text?.toString()?.trim().orEmpty()
        val pw2 = etConfirm.text?.toString()?.trim().orEmpty()
        if (otp.length < 6) {
            CuraxFeedback.warn(this, getString(R.string.otp_enter_all_digits))
            return
        }
        if (pw.length < 6) {
            CuraxFeedback.warn(this, getString(R.string.password_min_length))
            return
        }
        if (pw != pw2) {
            CuraxFeedback.warn(this, getString(R.string.forgot_password_password_mismatch))
            return
        }
        val base = apiBase()
        if (base.isEmpty()) {
            CuraxFeedback.warn(this, getString(R.string.set_api_url_for_codes), long = true)
            return
        }
        hideKeyboard()
        val label = getString(R.string.forgot_password_save)
        btnSubmit.isEnabled = false
        btnSubmit.text = getString(R.string.please_wait)
        Thread {
            try {
                val json = JSONObject().apply {
                    put("email", pendingEmail)
                    put("otp", otp)
                    put("new_password", pw)
                }
                val (code, jo) = postJson("/password-reset/complete", json)
                runOnUiThread {
                    btnSubmit.isEnabled = true
                    btnSubmit.text = label
                    if (code == 200) {
                        CuraxFeedback.success(this, getString(R.string.forgot_password_success))
                        finish()
                    } else {
                        CuraxFeedback.warn(this, ApiErrorMessages.userMessage(this, code, jo), long = true)
                    }
                }
            } catch (_: Exception) {
                runOnUiThread {
                    btnSubmit.isEnabled = true
                    btnSubmit.text = label
                    CuraxFeedback.warn(this, getString(R.string.error_network_unreachable), long = true)
                }
            }
        }.start()
    }

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
