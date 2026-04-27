package com.curax.app

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.view.ViewTreeObserver
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.app.ActivityCompat
import androidx.core.app.ActivityOptionsCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.NestedScrollView
import androidx.core.widget.doOnTextChanged
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.snackbar.Snackbar
import androidx.appcompat.widget.AppCompatButton
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.messaging.FirebaseMessaging
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

class SignInActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var store: LocalUserStore
    private lateinit var etEmail: TextInputEditText
    private lateinit var etPassword: TextInputEditText
    private lateinit var btnSignIn: AppCompatButton

    private var imeInsetListener: ViewTreeObserver.OnGlobalLayoutListener? = null

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sign_in)

        prefs = Prefs(this)
        store = LocalUserStore(this)

        etEmail = findViewById(R.id.etEmail)
        etPassword = findViewById(R.id.etPassword)
        btnSignIn = findViewById(R.id.btnSignIn)
        applyStableAuthHints()
        val hintEmail = ContextCompat.getColorStateList(this, R.color.auth_hint_email_muted)
        val hintPassword = ContextCompat.getColorStateList(this, R.color.auth_hint_password_muted)
        if (hintEmail != null) {
            findViewById<TextInputLayout>(R.id.tilEmail).defaultHintTextColor = hintEmail
        }
        if (hintPassword != null) {
            findViewById<TextInputLayout>(R.id.tilPassword).defaultHintTextColor = hintPassword
        }
        AuthPasswordToggle.bind(findViewById(R.id.tilPassword), this)
        findViewById<TextView>(R.id.tvSignUpHere).setOnClickListener {
            startActivity(Intent(this, SignUpRegistrationActivity::class.java))
        }
        findViewById<TextView>(R.id.tvResetPassword).setOnClickListener {
            showResetPasswordSheet()
        }

        fun syncBtn() {
            val ok = etEmail.text?.toString()?.trim().orEmpty().isNotEmpty() &&
                etPassword.text?.toString()?.trim().orEmpty().isNotEmpty()
            btnSignIn.isEnabled = ok
        }
        etEmail.doOnTextChanged { _, _, _, _ -> syncBtn() }
        etPassword.doOnTextChanged { _, _, _, _ -> syncBtn() }
        syncBtn()

        val scrollSignIn = findViewById<NestedScrollView>(R.id.scrollSignIn)
        val scrollContent = scrollSignIn.getChildAt(0)
        bindImeOverlayBottomPadding(scrollSignIn)
        bindScrollOnFieldFocus(scrollSignIn, scrollContent, etEmail, etPassword)

        btnSignIn.setOnClickListener { onSignInClicked() }
    }

    override fun onDestroy() {
        imeInsetListener?.let { window.decorView.viewTreeObserver.removeOnGlobalLayoutListener(it) }
        imeInsetListener = null
        super.onDestroy()
    }

    /**
     * [android:windowSoftInputMode] is adjustNothing: window does not shrink, so the sign-up footer
     * stays laid out at the bottom while the IME draws on top. We only grow the scroll view's
     * bottom padding by the keyboard height so the form can scroll above the overlay.
     */
    private fun bindImeOverlayBottomPadding(scroll: NestedScrollView) {
        val decor = window.decorView
        val baseBottomPad = scroll.paddingBottom
        imeInsetListener = ViewTreeObserver.OnGlobalLayoutListener {
            val wi = ViewCompat.getRootWindowInsets(decor)
            val imeBottom = if (wi != null && wi.isVisible(WindowInsetsCompat.Type.ime())) {
                var b = wi.getInsets(WindowInsetsCompat.Type.ime()).bottom
                if (b == 0) {
                    val r = Rect()
                    decor.getWindowVisibleDisplayFrame(r)
                    b = (decor.height - r.bottom).coerceAtLeast(0)
                }
                b
            } else {
                0
            }
            scroll.updatePadding(bottom = baseBottomPad + imeBottom)
        }
        decor.viewTreeObserver.addOnGlobalLayoutListener(imeInsetListener)
    }

    /** Keeps scroll offset from re-laying out fields; scrolls so focused field stays visible above IME. */
    private fun bindScrollOnFieldFocus(scroll: NestedScrollView, content: View, vararg fields: View) {
        for (f in fields) {
            f.setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus) scrollToShowDescendant(scroll, content, v)
            }
        }
    }

    private fun scrollToShowDescendant(scroll: NestedScrollView, content: View, descendant: View) {
        scroll.post {
            var top = 0
            var v: View? = descendant
            while (v != null && v !== content) {
                top += v.top
                v = v.parent as? View
            }
            val pad = (scroll.height * 0.04f).toInt().coerceIn(20, 40)
            val targetY = (top - pad).coerceAtLeast(0)
            val maxY = (content.height - scroll.height).coerceAtLeast(0)
            scroll.scrollTo(0, targetY.coerceAtMost(maxY))
        }
    }

    private fun applyStableAuthHints() {
        ContextCompat.getColorStateList(this, R.color.auth_hint_email_muted)?.let { etEmail.setHintTextColor(it) }
        ContextCompat.getColorStateList(this, R.color.auth_hint_password_muted)?.let { etPassword.setHintTextColor(it) }
    }

    private fun showResetPasswordSheet() {
        val sheet = BottomSheetDialog(this)
        sheet.setContentView(R.layout.dialog_auth_reset_sheet)
        sheet.findViewById<AppCompatButton>(R.id.btnSheetSignInAdmin)?.setOnClickListener {
            sheet.dismiss()
            startActivity(Intent(this, AdminRegistrationActivity::class.java))
        }
        sheet.show()
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

    private fun onSignInClicked() {
        val email = etEmail.text?.toString()?.trim().orEmpty()
        val password = etPassword.text?.toString()?.trim().orEmpty()
        if (email.isBlank() || password.isBlank()) {
            CuraxFeedback.warn(this, getString(R.string.sign_in_all_fields_required))
            return
        }
        if (password.length < 6) {
            CuraxFeedback.warn(this, getString(R.string.password_min_length))
            return
        }
        val base = apiBase()
        if (base.isEmpty()) {
            CuraxFeedback.warn(this, getString(R.string.set_api_url_for_codes), long = true)
            return
        }
        hideKeyboard()
        val label = getString(R.string.sign_in_button)
        btnSignIn.isEnabled = false
        btnSignIn.text = getString(R.string.please_wait)
        Thread {
            try {
                val json = JSONObject().apply {
                    put("email", email)
                    put("password", password)
                }
                val (code, jo) = postJson("/signup/sign-in", json)
                runOnUiThread {
                    btnSignIn.isEnabled = true
                    btnSignIn.text = label
                    if (code == 200 && jo != null) {
                        handleSignInSuccess(email, password, base, jo)
                    } else {
                        CuraxFeedback.warn(
                            this@SignInActivity,
                            ApiErrorMessages.userMessage(this@SignInActivity, code, jo),
                            long = true,
                        )
                    }
                }
            } catch (_: Exception) {
                runOnUiThread {
                    btnSignIn.isEnabled = true
                    btnSignIn.text = label
                    CuraxFeedback.warn(
                        this@SignInActivity,
                        getString(R.string.error_network_unreachable),
                        long = true,
                    )
                }
            }
        }.start()
    }

    private fun handleSignInSuccess(email: String, password: String, base: String, jo: JSONObject) {
        AutofillHelper.commit(this)
        val phase = jo.optString("account_phase", "").trim().lowercase()
        when (phase) {
            "pending_email" -> {
                startActivity(
                    Intent(this, SignUpActivity::class.java)
                        .putExtra(SignUpActivity.EXTRA_START_AT_OTP, true)
                        .putExtra(SignUpActivity.EXTRA_FROM_SIGNIN_PENDING_EMAIL, true)
                        .putExtra(SignUpActivity.EXTRA_EMAIL, email)
                        .putExtra(SignUpActivity.EXTRA_PASSWORD, password)
                        .putExtra(SignUpActivity.EXTRA_DISPLAY_NAME, email)
                )
            }
            "pending_admin" -> {
                val botId = UUID.randomUUID().toString().take(8)
                val apiKey = UUID.randomUUID().toString().replace("-", "").take(16)
                SignUpFlowState.set(email, password, botId, apiKey, nameForLink = email)
                startActivity(Intent(this, SignUpLinkAdminActivity::class.java))
            }
            "active" -> {
                val botId = jo.optString("bot_id", "").trim()
                val apiKey = jo.optString("api_key", "").trim()
                if (botId.isEmpty() || apiKey.isEmpty()) {
                    CuraxFeedback.warn(this, getString(R.string.request_failed), long = true)
                    return
                }
                applyActiveUserPrefs(jo, email, password, botId, apiKey, base)
            }
            else -> CuraxFeedback.warn(this, getString(R.string.request_failed), long = true)
        }
    }

    private fun applyActiveUserPrefs(
        jo: JSONObject,
        email: String,
        password: String,
        botId: String,
        apiKey: String,
        base: String,
    ) {
        prefs.id = botId
        prefs.apiKey = apiKey
        prefs.connectionCode = jo.optString("connection_code", "").trim()
        prefs.databusAccessCode = jo.optString("databus_access_code", "").trim()
        prefs.linkedAdminId = jo.optString("admin_id", "").trim()
        prefs.linkedAdminName = jo.optString("admin_name", "").trim()
        prefs.hasEverConnected = true
        store.saveUser(email, password, LocalUserStore.ROLE_USER)
        SignUpFlowState.clear()

        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            val fcmToken = if (task.isSuccessful) task.result?.trim().orEmpty() else ""
            if (fcmToken.isNotEmpty()) prefs.fcmToken = fcmToken
            val adminId = prefs.linkedAdminId.trim()
            if (fcmToken.isNotEmpty() && adminId.isNotEmpty()) {
                Thread {
                    try {
                        val body = JSONObject().apply {
                            put("bot_id", botId)
                            put("api_key", apiKey)
                            put("role", "user")
                            put("admin_id", adminId)
                            put("fcm_token", fcmToken)
                        }
                        val req = Request.Builder()
                            .url("$base/save-credentials")
                            .post(body.toString().toRequestBody(JSON_MEDIA))
                            .build()
                        http.newCall(req).execute().close()
                    } catch (_: Exception) {
                    }
                }.start()
            }
            runOnUiThread {
                UserDataBusClient.fetchAndApplyUserData(this@SignInActivity, base, botId, apiKey) {
                    runOnUiThread {
                        CuraxFeedback.successThen(
                            this@SignInActivity,
                            R.string.sign_in_success,
                            delayMs = 220L,
                            snackbarDuration = Snackbar.LENGTH_SHORT,
                        ) {
                            val home = UserHomeIntent.forSignedInUser(this@SignInActivity).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                            }
                            val opts = ActivityOptionsCompat.makeCustomAnimation(
                                this@SignInActivity,
                                android.R.anim.fade_in,
                                android.R.anim.fade_out,
                            )
                            ActivityCompat.startActivity(this@SignInActivity, home, opts.toBundle())
                            finish()
                        }
                    }
                }
            }
        }
    }

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
