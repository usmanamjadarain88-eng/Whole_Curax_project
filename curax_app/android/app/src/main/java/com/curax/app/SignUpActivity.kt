package com.curax.app

import android.content.Intent
import android.os.Bundle
import android.os.CountDownTimer
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doOnTextChanged
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

    private enum class Step { ONE, TWO }

    private lateinit var store: LocalUserStore
    private lateinit var prefs: Prefs
    private lateinit var tvScreenTitle: TextView
    private lateinit var tvSignUpAdmin: TextView
    private lateinit var group1: LinearLayout
    private lateinit var group2: LinearLayout
    private lateinit var etEmail: TextInputEditText
    private lateinit var etPassword: TextInputEditText
    private lateinit var btnSignUp: MaterialButton
    private lateinit var tvVerifySubtitle: TextView
    private lateinit var tvEmailVerifiedBanner: TextView
    private lateinit var otpInputBlock: LinearLayout
    private lateinit var tvOtpHint: TextView
    private lateinit var etOtp: TextInputEditText
    private lateinit var tvResendCountdown: TextView
    private lateinit var tvResend: TextView
    private lateinit var btnVerifyOtp: MaterialButton

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val linkAdminLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == RESULT_OK) {
            finish()
        } else {
            step2ContinueOnly = true
            applyContinueOnlyStep2Ui()
            showStep(Step.TWO)
        }
    }

    private var step = Step.ONE
    private var pendingEmail = ""
    private var pendingPassword = ""
    private var botIdForLink = ""
    private var apiKeyForLink = ""
    private var resendTimer: CountDownTimer? = null
    private var step2ContinueOnly = false

    private val verifyClickListener = View.OnClickListener { onVerifyOtpClicked() }
    private val continueClickListener = View.OnClickListener {
        hideKeyboard()
        if (SignUpFlowState.isReady()) {
            linkAdminLauncher.launch(Intent(this, SignUpLinkAdminActivity::class.java))
        }
    }

    private val backCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            when (step) {
                Step.ONE -> {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
                Step.TWO -> {
                    cancelResendTimer()
                    resetStep2UiForOtpEntry()
                    SignUpFlowState.clear()
                    showStep(Step.ONE)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_signup)
        onBackPressedDispatcher.addCallback(this, backCallback)

        store = LocalUserStore(this)
        prefs = Prefs(this)

        tvScreenTitle = findViewById(R.id.tvScreenTitle)
        tvSignUpAdmin = findViewById(R.id.tvSignUpAdmin)
        group1 = findViewById(R.id.groupSignUpStep1)
        group2 = findViewById(R.id.groupSignUpStep2)
        etEmail = findViewById(R.id.etEmail)
        etPassword = findViewById(R.id.etPassword)
        btnSignUp = findViewById(R.id.btnSignUp)
        tvVerifySubtitle = findViewById(R.id.tvVerifySubtitle)
        tvEmailVerifiedBanner = findViewById(R.id.tvEmailVerifiedBanner)
        otpInputBlock = findViewById(R.id.otpInputBlock)
        tvOtpHint = findViewById(R.id.tvOtpHint)
        etOtp = findViewById(R.id.etOtp)
        tvResendCountdown = findViewById(R.id.tvResendCountdown)
        tvResend = findViewById(R.id.tvResend)
        btnVerifyOtp = findViewById(R.id.btnVerifyOtp)

        fun syncSignUpButtonEnabled() {
            val emailOk = etEmail.text?.toString()?.trim().orEmpty().isNotEmpty()
            val passwordOk = etPassword.text?.toString()?.trim().orEmpty().isNotEmpty()
            btnSignUp.isEnabled = emailOk && passwordOk
        }
        etEmail.doOnTextChanged { _, _, _, _ -> syncSignUpButtonEnabled() }
        etPassword.doOnTextChanged { _, _, _, _ -> syncSignUpButtonEnabled() }
        syncSignUpButtonEnabled()

        etOtp.doOnTextChanged { text, _, _, _ ->
            updateOtpDashDisplay(text?.toString().orEmpty())
            btnVerifyOtp.isEnabled = step2ContinueOnly || text?.length == 6
        }

        btnSignUp.setOnClickListener { onSignUpClicked() }
        btnVerifyOtp.setOnClickListener(verifyClickListener)
        tvResend.setOnClickListener { onResendClicked() }

        updateOtpDashDisplay("")
        showStep(Step.ONE)
    }

    override fun onDestroy() {
        cancelResendTimer()
        super.onDestroy()
    }

    private fun apiBase(): String = prefs.centralApiUrl.trim().removeSuffix("/")

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        val token = currentFocus?.windowToken ?: window.decorView.windowToken
        imm.hideSoftInputFromWindow(token, 0)
    }

    private fun messageFromResponse(jo: JSONObject?): String {
        if (jo == null) return getString(R.string.request_failed)
        val m = jo.optString("message", "").trim()
        if (m.isEmpty()) return getString(R.string.request_failed)
        val hint = jo.optString("hint", "").trim()
        val detail = jo.optString("detail", "").trim()
        val extra = hint.ifEmpty { detail }
        return if (extra.isNotEmpty()) "$m\n\n$extra" else m
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
            val jo = try {
                if (raw.isNotBlank()) JSONObject(raw) else JSONObject()
            } catch (_: Exception) {
                JSONObject()
            }
            return Pair(res.code, jo)
        }
    }

    private fun showStep(s: Step) {
        step = s
        group1.visibility = if (s == Step.ONE) View.VISIBLE else View.GONE
        group2.visibility = if (s == Step.TWO) View.VISIBLE else View.GONE
        tvSignUpAdmin.visibility = if (s == Step.ONE) View.VISIBLE else View.GONE
        tvScreenTitle.text = when (s) {
            Step.ONE -> getString(R.string.auth_heading_sign_up)
            Step.TWO -> getString(R.string.verify_pin_title)
        }
        backCallback.isEnabled = true
    }

    private fun updateOtpDashDisplay(code: String) {
        val sb = StringBuilder()
        for (i in 0 until 6) {
            if (i > 0) sb.append(' ')
            sb.append(if (i < code.length) code[i] else '—')
        }
        tvOtpHint.text = sb.toString()
    }

    private fun cancelResendTimer() {
        resendTimer?.cancel()
        resendTimer = null
    }

    private fun startResendCooldown() {
        cancelResendTimer()
        tvResend.isEnabled = false
        tvResend.alpha = 0.45f
        tvResendCountdown.visibility = View.VISIBLE
        resendTimer = object : CountDownTimer(15_000L, 1_000L) {
            override fun onTick(msUntilFinished: Long) {
                val sec = ((msUntilFinished + 999) / 1000).toInt().coerceAtLeast(0)
                tvResendCountdown.text = getString(R.string.resend_code_wait, sec)
            }

            override fun onFinish() {
                tvResendCountdown.visibility = View.GONE
                tvResend.isEnabled = true
                tvResend.alpha = 1f
                resendTimer = null
            }
        }.start()
    }

    private fun resetStep2UiForOtpEntry() {
        step2ContinueOnly = false
        otpInputBlock.visibility = View.VISIBLE
        tvEmailVerifiedBanner.visibility = View.GONE
        btnVerifyOtp.text = getString(R.string.verify)
        btnVerifyOtp.setOnClickListener(verifyClickListener)
        etOtp.isEnabled = true
        etOtp.text = null
        updateOtpDashDisplay("")
        btnVerifyOtp.isEnabled = false
    }

    private fun applyContinueOnlyStep2Ui() {
        tvScreenTitle.text = getString(R.string.verify_pin_title)
        tvVerifySubtitle.text = getString(R.string.signup_email_verified_continue)
        otpInputBlock.visibility = View.GONE
        tvEmailVerifiedBanner.visibility = View.VISIBLE
        tvResendCountdown.visibility = View.GONE
        cancelResendTimer()
        tvResend.isEnabled = false
        tvResend.alpha = 0.45f
        btnVerifyOtp.text = getString(R.string.continue_action)
        btnVerifyOtp.isEnabled = true
        btnVerifyOtp.setOnClickListener(continueClickListener)
    }

    private fun onSignUpClicked() {
        val email = etEmail.text?.toString()?.trim().orEmpty()
        val password = etPassword.text?.toString()?.trim().orEmpty()
        when {
            email.isBlank() || password.isBlank() -> {
                Toast.makeText(this, getString(R.string.sign_in_all_fields_required), Toast.LENGTH_SHORT).show()
            }
            password.length < 6 -> {
                Toast.makeText(this, "Password must be at least 6 characters", Toast.LENGTH_SHORT).show()
            }
            else -> {
                val base = apiBase()
                if (base.isEmpty()) {
                    Toast.makeText(this, getString(R.string.set_api_url_for_codes), Toast.LENGTH_LONG).show()
                    return
                }
                pendingEmail = email
                pendingPassword = password
                btnSignUp.isEnabled = false
                Thread {
                    try {
                        val json = JSONObject().apply {
                            put("email", email)
                            put("password", password)
                        }
                        val (code, jo) = postJson("/signup/start", json)
                        runOnUiThread {
                            btnSignUp.isEnabled = true
                            if (code == 200) {
                                resetStep2UiForOtpEntry()
                                tvVerifySubtitle.text = getString(R.string.verify_pin_subtitle, email)
                                showStep(Step.TWO)
                                startResendCooldown()
                            } else {
                                Toast.makeText(this@SignUpActivity, messageFromResponse(jo), Toast.LENGTH_LONG).show()
                            }
                        }
                    } catch (_: Exception) {
                        runOnUiThread {
                            btnSignUp.isEnabled = true
                            Toast.makeText(this@SignUpActivity, getString(R.string.request_failed), Toast.LENGTH_LONG).show()
                        }
                    }
                }.start()
            }
        }
    }

    private fun onResendClicked() {
        if (!tvResend.isEnabled) return
        val base = apiBase()
        if (base.isEmpty()) {
            Toast.makeText(this, getString(R.string.set_api_url_for_codes), Toast.LENGTH_LONG).show()
            return
        }
        tvResend.isEnabled = false
        Thread {
            try {
                val json = JSONObject().apply {
                    put("email", pendingEmail)
                    put("password", pendingPassword)
                }
                val (code, jo) = postJson("/signup/start", json)
                runOnUiThread {
                    if (code == 200) {
                        startResendCooldown()
                        Toast.makeText(this, getString(R.string.resend_code), Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, messageFromResponse(jo), Toast.LENGTH_LONG).show()
                        tvResend.isEnabled = true
                        tvResend.alpha = 1f
                    }
                }
            } catch (_: Exception) {
                runOnUiThread {
                    Toast.makeText(this, getString(R.string.request_failed), Toast.LENGTH_LONG).show()
                    tvResend.isEnabled = true
                    tvResend.alpha = 1f
                }
            }
        }.start()
    }

    private fun onVerifyOtpClicked() {
        if (step2ContinueOnly) return
        val otp = etOtp.text?.toString()?.trim().orEmpty()
        if (otp.length != 6) {
            Toast.makeText(this, getString(R.string.otp_enter_all_digits), Toast.LENGTH_SHORT).show()
            return
        }
        val base = apiBase()
        if (base.isEmpty()) {
            Toast.makeText(this, getString(R.string.set_api_url_for_codes), Toast.LENGTH_LONG).show()
            return
        }
        hideKeyboard()
        val verifyLabel = getString(R.string.verify)
        btnVerifyOtp.isEnabled = false
        btnVerifyOtp.text = getString(R.string.please_wait)
        Thread {
            try {
                val json = JSONObject().apply {
                    put("email", pendingEmail)
                    put("otp", otp)
                }
                val (code, jo) = postJson("/signup/verify-email", json)
                runOnUiThread {
                    if (code == 200) {
                        cancelResendTimer()
                        botIdForLink = UUID.randomUUID().toString().take(8)
                        apiKeyForLink = UUID.randomUUID().toString().replace("-", "").take(16)
                        SignUpFlowState.set(pendingEmail, pendingPassword, botIdForLink, apiKeyForLink)
                        linkAdminLauncher.launch(Intent(this, SignUpLinkAdminActivity::class.java))
                    } else {
                        Toast.makeText(this, messageFromResponse(jo), Toast.LENGTH_LONG).show()
                    }
                    btnVerifyOtp.text = verifyLabel
                    btnVerifyOtp.isEnabled = etOtp.text?.length == 6
                }
            } catch (_: Exception) {
                runOnUiThread {
                    btnVerifyOtp.text = verifyLabel
                    btnVerifyOtp.isEnabled = etOtp.text?.length == 6
                    Toast.makeText(this, getString(R.string.request_failed), Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
