package com.curax.app

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.os.CountDownTimer
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doOnTextChanged
import androidx.appcompat.widget.AppCompatButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
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
    private lateinit var tvTitleLine1: TextView
    private lateinit var tvTitleLine2: TextView
    private lateinit var tvAuthSubtitle: TextView
    private lateinit var tvSignUpAdmin: TextView
    private lateinit var group1: LinearLayout
    private lateinit var group2: LinearLayout
    private lateinit var etEmail: TextInputEditText
    private lateinit var etPassword: TextInputEditText
    private lateinit var btnSignUp: AppCompatButton
    private lateinit var tvVerifySubtitle: TextView
    private lateinit var tvEmailVerifiedBanner: TextView
    private lateinit var otpInputBlock: LinearLayout
    private lateinit var etOtp: EditText
    private lateinit var otpDigitViews: List<TextView>
    private lateinit var tvResendCountdown: TextView
    private lateinit var tvResend: TextView
    private lateinit var btnVerifyOtp: AppCompatButton
    private var authBottomSvg: WebView? = null

    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(22, TimeUnit.SECONDS)
        .writeTimeout(22, TimeUnit.SECONDS)
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
    private var otpEntryMode = false
    private var pendingDisplayName = ""

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
                    if (otpEntryMode) {
                        SignUpFlowState.clear()
                        resetStep2UiForOtpEntry()
                        finish()
                    } else {
                        resetStep2UiForOtpEntry()
                        SignUpFlowState.clear()
                        showStep(Step.ONE)
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_signup)
        authBottomSvg = findViewById(R.id.authBottomSvg)
        setupAuthBottomSvg()
        onBackPressedDispatcher.addCallback(this, backCallback)

        store = LocalUserStore(this)
        prefs = Prefs(this)

        tvTitleLine1 = findViewById(R.id.tvTitleLine1)
        tvTitleLine2 = findViewById(R.id.tvTitleLine2)
        tvAuthSubtitle = findViewById(R.id.tvAuthSubtitle)
        tvSignUpAdmin = findViewById(R.id.tvSignUpAdmin)
        group1 = findViewById(R.id.groupSignUpStep1)
        group2 = findViewById(R.id.groupSignUpStep2)
        etEmail = findViewById(R.id.etEmail)
        etPassword = findViewById(R.id.etPassword)
        etEmail.setHintTextColor(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.auth_subtitle)))
        etPassword.setHintTextColor(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.auth_password_hint)))
        AuthPasswordToggle.bind(findViewById<TextInputLayout>(R.id.tilPasswordStep1), this)
        btnSignUp = findViewById(R.id.btnSignUp)
        tvVerifySubtitle = findViewById(R.id.tvVerifySubtitle)
        tvEmailVerifiedBanner = findViewById(R.id.tvEmailVerifiedBanner)
        otpInputBlock = findViewById(R.id.otpInputBlock)
        etOtp = findViewById(R.id.etOtp)
        otpDigitViews = listOf(
            findViewById(R.id.tvOtpDigit0),
            findViewById(R.id.tvOtpDigit1),
            findViewById(R.id.tvOtpDigit2),
            findViewById(R.id.tvOtpDigit3),
            findViewById(R.id.tvOtpDigit4),
            findViewById(R.id.tvOtpDigit5),
        )
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
        tvSignUpAdmin.setOnClickListener {
            startActivity(Intent(this, AdminRegistrationActivity::class.java))
        }

        otpEntryMode = intent.getBooleanExtra(EXTRA_START_AT_OTP, false)
        pendingDisplayName = intent.getStringExtra(EXTRA_DISPLAY_NAME).orEmpty()

        updateOtpDashDisplay("")
        if (otpEntryMode) {
            pendingEmail = intent.getStringExtra(EXTRA_EMAIL).orEmpty()
            pendingPassword = intent.getStringExtra(EXTRA_PASSWORD).orEmpty()
            tvSignUpAdmin.visibility = View.GONE
            resetStep2UiForOtpEntry()
            tvVerifySubtitle.text = getString(R.string.verify_pin_subtitle, pendingEmail)
            showStep(Step.TWO)
            // Sign-in + pending_email: /signup/sign-in does not send OTP; must call /signup/start here.
            // Registration path already called /signup/start before this screen.
            if (intent.getBooleanExtra(EXTRA_FROM_SIGNIN_PENDING_EMAIL, false)) {
                requestOtpEmailAfterSignInPending()
            } else {
                startResendCooldown()
                etOtp.post { focusOtpField() }
            }
        } else {
            showStep(Step.ONE)
        }
    }

    private fun setupAuthBottomSvg() {
        val wv = findViewById<WebView>(R.id.authBottomSvg) ?: return
        wv.isNestedScrollingEnabled = false
        wv.overScrollMode = View.OVER_SCROLL_NEVER
        wv.setBackgroundColor(Color.TRANSPARENT)
        wv.isClickable = false
        wv.isFocusable = false
        wv.isFocusableInTouchMode = false
        wv.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        wv.settings.apply {
            @Suppress("DEPRECATION")
            allowFileAccess = true
            cacheMode = WebSettings.LOAD_NO_CACHE
        }
        val html =
            "<!DOCTYPE html><html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"/>" +
                "<style>html,body{margin:0;padding:0;background:transparent}</style></head><body>" +
                "<img src=\"bottom.svg\" width=\"100%\" style=\"display:block;vertical-align:bottom\"/>" +
                "</body></html>"
        wv.loadDataWithBaseURL("file:///android_asset/", html, "text/html", "UTF-8", null)
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

    /** Dash row looks like the input; forward taps to the real OTP field and open the keypad. */
    private fun focusOtpField() {
        if (!etOtp.isEnabled || otpInputBlock.visibility != View.VISIBLE) return
        etOtp.requestFocus()
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        etOtp.post {
            imm.showSoftInput(etOtp, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    /** After sign-in with pending_email: issue OTP + trigger signup email (same as resend). */
    private fun requestOtpEmailAfterSignInPending() {
        val base = apiBase()
        if (base.isEmpty()) {
            CuraxFeedback.warn(this, getString(R.string.set_api_url_for_codes), long = true)
            startResendCooldown()
            etOtp.post { focusOtpField() }
            return
        }
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
                        val emailOk = jo?.optBoolean("email_sent", true) != false
                        if (emailOk) {
                            CuraxFeedback.success(this, getString(R.string.otp_resend_email_success))
                        } else {
                            CuraxFeedback.warn(this, getString(R.string.otp_resend_email_saved_smtp_failed), long = true)
                        }
                    } else {
                        CuraxFeedback.warn(this, ApiErrorMessages.userMessage(this, code, jo), long = true)
                    }
                    etOtp.post { focusOtpField() }
                }
            } catch (_: Exception) {
                runOnUiThread {
                    CuraxFeedback.warn(this, getString(R.string.error_network_unreachable), long = true)
                    etOtp.post { focusOtpField() }
                }
            }
        }.start()
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
        group1.visibility = if (s == Step.ONE) View.VISIBLE else View.GONE
        group2.visibility = if (s == Step.TWO) View.VISIBLE else View.GONE
        tvSignUpAdmin.visibility = if (s == Step.ONE) View.VISIBLE else View.GONE
        authBottomSvg?.visibility = if (s == Step.TWO) View.GONE else View.VISIBLE
        when (s) {
            Step.ONE -> {
                tvTitleLine1.text = getString(R.string.sign_up_screen_title)
                tvTitleLine2.visibility = View.GONE
                tvAuthSubtitle.visibility = View.VISIBLE
            }
            Step.TWO -> {
                tvTitleLine1.text = getString(R.string.verify_pin_heading_line1)
                tvTitleLine2.text = getString(R.string.verify_pin_heading_line2)
                tvTitleLine2.visibility = View.VISIBLE
                tvAuthSubtitle.visibility = View.GONE
            }
        }
        backCallback.isEnabled = true
    }

    /** Digits in upper row only; em dashes below stay fixed in XML. */
    private fun updateOtpDashDisplay(code: String) {
        for (i in otpDigitViews.indices) {
            otpDigitViews[i].text =
                if (i < code.length) code[i].toString() else ""
        }
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
        resendTimer = object : CountDownTimer(8_000L, 1_000L) {
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
        tvTitleLine1.text = getString(R.string.verify_pin_heading_line1)
        tvTitleLine2.text = getString(R.string.verify_pin_heading_line2)
        tvTitleLine2.visibility = View.VISIBLE
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
        tvTitleLine1.text = getString(R.string.auth_continue_heading_line1)
        tvTitleLine2.visibility = View.GONE
        tvAuthSubtitle.visibility = View.GONE
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
                CuraxFeedback.warn(this, getString(R.string.sign_in_all_fields_required))
            }
            password.length < 6 -> {
                CuraxFeedback.warn(this, getString(R.string.password_min_length))
            }
            else -> {
                val base = apiBase()
                if (base.isEmpty()) {
                    CuraxFeedback.warn(this, getString(R.string.set_api_url_for_codes), long = true)
                    return
                }
                pendingEmail = email
                pendingPassword = password
                pendingDisplayName = email
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
                                AutofillHelper.commit(this@SignUpActivity)
                                resetStep2UiForOtpEntry()
                                tvVerifySubtitle.text = getString(R.string.verify_pin_subtitle, email)
                                showStep(Step.TWO)
                                startResendCooldown()
                                etOtp.post { focusOtpField() }
                            } else {
                                CuraxFeedback.warn(
                                    this@SignUpActivity,
                                    ApiErrorMessages.userMessage(this@SignUpActivity, code, jo),
                                    long = true,
                                )
                            }
                        }
                    } catch (_: Exception) {
                        runOnUiThread {
                            btnSignUp.isEnabled = true
                            CuraxFeedback.warn(this@SignUpActivity, getString(R.string.error_network_unreachable), long = true)
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
            CuraxFeedback.warn(this, getString(R.string.set_api_url_for_codes), long = true)
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
                        val emailOk = jo?.optBoolean("email_sent", true) != false
                        if (emailOk) {
                            CuraxFeedback.success(this, getString(R.string.otp_resend_email_success))
                        } else {
                            CuraxFeedback.warn(this, getString(R.string.otp_resend_email_saved_smtp_failed), long = true)
                        }
                    } else {
                        CuraxFeedback.warn(this, ApiErrorMessages.userMessage(this, code, jo), long = true)
                        tvResend.isEnabled = true
                        tvResend.alpha = 1f
                    }
                }
            } catch (_: Exception) {
                runOnUiThread {
                    CuraxFeedback.warn(this, getString(R.string.error_network_unreachable), long = true)
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
            CuraxFeedback.warn(this, getString(R.string.otp_enter_all_digits))
            return
        }
        val base = apiBase()
        if (base.isEmpty()) {
            CuraxFeedback.warn(this, getString(R.string.set_api_url_for_codes), long = true)
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
                        val linkName = pendingDisplayName.trim().ifBlank { pendingEmail }
                        SignUpFlowState.set(
                            pendingEmail,
                            pendingPassword,
                            botIdForLink,
                            apiKeyForLink,
                            nameForLink = linkName,
                        )
                        linkAdminLauncher.launch(Intent(this, SignUpLinkAdminActivity::class.java))
                    } else {
                        CuraxFeedback.warn(this, ApiErrorMessages.userMessage(this, code, jo), long = true)
                    }
                    btnVerifyOtp.text = verifyLabel
                    btnVerifyOtp.isEnabled = etOtp.text?.length == 6
                }
            } catch (_: Exception) {
                runOnUiThread {
                    btnVerifyOtp.text = verifyLabel
                    btnVerifyOtp.isEnabled = etOtp.text?.length == 6
                    CuraxFeedback.warn(this, getString(R.string.error_network_unreachable), long = true)
                }
            }
        }.start()
    }

    companion object {
        const val EXTRA_START_AT_OTP = "start_at_otp"
        /** True when opening verify after /signup/sign-in returned pending_email (needs /signup/start for mail). */
        const val EXTRA_FROM_SIGNIN_PENDING_EMAIL = "from_signin_pending_email"
        const val EXTRA_EMAIL = "pending_email"
        const val EXTRA_PASSWORD = "pending_password"
        const val EXTRA_DISPLAY_NAME = "display_name"

        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
