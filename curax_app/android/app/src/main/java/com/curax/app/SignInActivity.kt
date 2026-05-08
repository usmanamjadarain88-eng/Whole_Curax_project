package com.curax.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.content.res.ColorStateList
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewTreeObserver
import android.view.inputmethod.InputMethodManager
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
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
import com.google.android.material.snackbar.Snackbar
import androidx.appcompat.widget.AppCompatButton
import com.facebook.CallbackManager
import com.facebook.FacebookCallback
import com.facebook.FacebookException
import com.facebook.login.LoginManager
import com.facebook.login.LoginResult
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.auth.api.signin.GoogleSignInStatusCodes
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.api.ApiException
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.messaging.FirebaseMessaging
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

class SignInActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var store: LocalUserStore
    private lateinit var etEmail: TextInputEditText
    private lateinit var etPassword: TextInputEditText
    private lateinit var btnSignIn: AppCompatButton
    private lateinit var googleSignInClient: GoogleSignInClient
    private lateinit var facebookCallbackManager: CallbackManager
    private lateinit var btnGoogleSignIn: ImageButton
    private lateinit var btnFacebookSignIn: ImageButton

    private var imeInsetListener: ViewTreeObserver.OnGlobalLayoutListener? = null

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val googleSignInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data ?: return@registerForActivityResult
        val task = GoogleSignIn.getSignedInAccountFromIntent(data)
        try {
            val account = task.getResult(ApiException::class.java)
            onGoogleSignInSuccess(account)
        } catch (e: ApiException) {
            if (e.statusCode == GoogleSignInStatusCodes.SIGN_IN_CANCELLED) return@registerForActivityResult
            if (e.statusCode == ConnectionResult.DEVELOPER_ERROR) {
                showGoogleSignInDeveloperSetupDialog()
            } else {
                CuraxFeedback.warn(
                    this,
                    getString(R.string.social_google_failed, googleErrorMessage(e)),
                    long = true,
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sign_in)

        prefs = Prefs(this)
        store = LocalUserStore(this)

        intent.getStringExtra(EXTRA_SESSION_INVALIDATED_MESSAGE)?.trim()?.takeIf { it.isNotEmpty() }?.let { msg ->
            CuraxFeedback.warn(this, msg, long = true)
            intent.removeExtra(EXTRA_SESSION_INVALIDATED_MESSAGE)
        }

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
            startActivity(Intent(this, ForgotPasswordActivity::class.java))
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

        val gsoBuilder = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestProfile()
        val webClientId = resolveGoogleWebClientId()
        if (webClientId.isNotEmpty()) {
            gsoBuilder.requestIdToken(webClientId)
        }
        googleSignInClient = GoogleSignIn.getClient(this, gsoBuilder.build())

        facebookCallbackManager = CallbackManager.Factory.create()
        LoginManager.getInstance().registerCallback(
            facebookCallbackManager,
            object : FacebookCallback<LoginResult> {
                override fun onSuccess(result: LoginResult) {
                    val token = result.accessToken.token.trim().orEmpty()
                    LoginManager.getInstance().logOut()
                    if (token.isEmpty()) {
                        CuraxFeedback.warn(
                            this@SignInActivity,
                            getString(R.string.social_facebook_failed, "No token"),
                            long = true,
                        )
                        return
                    }
                    postFacebookSignIn(token)
                }

                override fun onCancel() = Unit

                override fun onError(error: FacebookException) {
                    CuraxFeedback.warn(
                        this@SignInActivity,
                        getString(R.string.social_facebook_failed, error.message ?: "Login error"),
                        long = true,
                    )
                }
            },
        )

        btnGoogleSignIn = findViewById(R.id.btnGoogleSignIn)
        btnFacebookSignIn = findViewById(R.id.btnFacebookSignIn)
        btnGoogleSignIn.setOnClickListener {
            try {
                googleSignInLauncher.launch(googleSignInClient.signInIntent)
            } catch (e: Exception) {
                CuraxFeedback.warn(this, getString(R.string.social_google_failed, e.message ?: "error"), long = true)
            }
        }
        btnFacebookSignIn.setOnClickListener {
            try {
                LoginManager.getInstance().logInWithReadPermissions(
                    this@SignInActivity,
                    listOf("email", "public_profile"),
                )
            } catch (e: Exception) {
                CuraxFeedback.warn(this, getString(R.string.social_facebook_failed, e.message ?: "error"), long = true)
            }
        }
    }

    @Suppress("DEPRECATION")
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        facebookCallbackManager.onActivityResult(requestCode, resultCode, data)
        super.onActivityResult(requestCode, resultCode, data)
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

    private fun setSocialBusy(busy: Boolean) {
        if (::btnGoogleSignIn.isInitialized) btnGoogleSignIn.isEnabled = !busy
        if (::btnFacebookSignIn.isInitialized) btnFacebookSignIn.isEnabled = !busy
    }

    private fun resolveGoogleWebClientId(): String {
        val overrideId = getString(R.string.google_web_client_id).trim()
        if (overrideId.isNotEmpty()) return overrideId
        val resId = resources.getIdentifier("default_web_client_id", "string", packageName)
        return if (resId != 0) getString(resId).trim() else ""
    }

    private fun installApkSigningSha1ColonUpper(): String? {
        return try {
            val pm = packageManager
            val pkg = packageName
            val pi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(
                    pkg,
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
                )
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
            }
            val sig: Signature = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val si = pi.signingInfo ?: return null
                si.apkContentsSigners?.firstOrNull() ?: return null
            } else {
                @Suppress("DEPRECATION")
                pi.signatures?.firstOrNull() ?: return null
            }
            val digest = MessageDigest.getInstance("SHA-1").digest(sig.toByteArray())
            digest.joinToString(":") { "%02X".format(it) }
        } catch (_: Exception) {
            null
        }
    }

    private fun copySha1ToClipboard(shaColonUpper: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        cm.setPrimaryClip(ClipData.newPlainText("SHA-1", shaColonUpper))
        CuraxFeedback.success(this, getString(R.string.google_signin_copied_sha1))
    }

    private fun showGoogleSignInDeveloperSetupDialog() {
        val sha = installApkSigningSha1ColonUpper()
        val shaBlock = sha ?: getString(R.string.google_signin_fix_dialog_sha_unknown)
        val msg = getString(
            R.string.google_signin_fix_dialog_message,
            packageName,
            shaBlock,
        )
        val firebaseUrl = getString(R.string.firebase_console_project_settings_url)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.google_signin_fix_dialog_title)
            .setMessage(msg)
            .setPositiveButton(R.string.google_signin_copy_sha1) { _, _ ->
                if (sha != null) {
                    copySha1ToClipboard(sha)
                } else {
                    CuraxFeedback.warn(this, getString(R.string.google_signin_fix_dialog_sha_unknown), long = true)
                }
            }
            .setNeutralButton(R.string.google_signin_open_firebase) { _, _ ->
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(firebaseUrl)))
                } catch (_: Exception) {
                    CuraxFeedback.warn(this, getString(R.string.privacy_open_failed))
                }
            }
            .setNegativeButton(android.R.string.ok, null)
            .show()
    }

    private fun googleErrorMessage(e: ApiException): String {
        if (e.statusCode == ConnectionResult.DEVELOPER_ERROR) {
            return getString(R.string.social_google_developer_error)
        }
        val m = e.message?.trim()?.takeIf { it.isNotEmpty() }
        return m ?: getString(R.string.social_google_status_code, e.statusCode)
    }

    private fun onGoogleSignInSuccess(account: GoogleSignInAccount) {
        val idToken = account.idToken?.trim()?.takeIf { it.isNotEmpty() }
        val email = account.email?.trim().orEmpty()
        try {
            if (idToken.isNullOrEmpty()) {
                CuraxFeedback.warn(this, getString(R.string.social_google_failed, "No ID token"), long = true)
                return
            }
            if (email.isEmpty()) {
                CuraxFeedback.warn(this, getString(R.string.social_email_required_for_signup), long = true)
                return
            }
            postGoogleSignIn(idToken, email)
        } finally {
            googleSignInClient.signOut()
        }
    }

    private fun postGoogleSignIn(idToken: String, emailHint: String) {
        val base = apiBase()
        if (base.isEmpty()) {
            CuraxFeedback.warn(this, getString(R.string.set_api_url_for_codes), long = true)
            return
        }
        hideKeyboard()
        setSocialBusy(true)
        Thread {
            try {
                val json = JSONObject().apply { put("google_id_token", idToken) }
                val (code, jo) = postJson("/signup/sign-in", json)
                runOnUiThread {
                    setSocialBusy(false)
                    if (code == 200 && jo != null) {
                        handleSignInSuccess(
                            emailHint,
                            LocalUserStore.OAUTH_LOCAL_PASSWORD_PLACEHOLDER,
                            base,
                            jo,
                            pendingGoogleIdToken = idToken,
                        )
                    } else {
                        CuraxFeedback.warn(
                            this@SignInActivity,
                            oauthSignInUserMessage(code, jo),
                            long = true,
                        )
                    }
                }
            } catch (_: Exception) {
                runOnUiThread {
                    setSocialBusy(false)
                    CuraxFeedback.warn(this@SignInActivity, getString(R.string.error_network_unreachable), long = true)
                }
            }
        }.start()
    }

    private fun postFacebookSignIn(accessToken: String) {
        val base = apiBase()
        if (base.isEmpty()) {
            CuraxFeedback.warn(this, getString(R.string.set_api_url_for_codes), long = true)
            return
        }
        hideKeyboard()
        setSocialBusy(true)
        Thread {
            try {
                val json = JSONObject().apply { put("facebook_access_token", accessToken) }
                val (code, jo) = postJson("/signup/sign-in", json)
                runOnUiThread {
                    setSocialBusy(false)
                    if (code == 200 && jo != null) {
                        handleSignInSuccess(
                            "",
                            LocalUserStore.OAUTH_LOCAL_PASSWORD_PLACEHOLDER,
                            base,
                            jo,
                            pendingFacebookAccessToken = accessToken,
                        )
                    } else {
                        CuraxFeedback.warn(
                            this@SignInActivity,
                            oauthSignInUserMessage(code, jo),
                            long = true,
                        )
                    }
                }
            } catch (_: Exception) {
                runOnUiThread {
                    setSocialBusy(false)
                    CuraxFeedback.warn(this@SignInActivity, getString(R.string.error_network_unreachable), long = true)
                }
            }
        }.start()
    }

    private fun oauthSignInUserMessage(code: Int, jo: JSONObject?): String {
        val key = jo?.optString("message", "").orEmpty().trim()
        if (code == 503 && key == "facebook_oauth_not_configured") {
            return getString(R.string.facebook_sign_in_server_not_configured)
        }
        return ApiErrorMessages.userMessage(this, code, jo)
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

    private fun resumePendingAdminDirectorySession(
        email: String,
        password: String,
        botId: String,
        apiKey: String,
        pendingAdminName: String,
    ) {
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            val fcmToken = if (task.isSuccessful) task.result?.trim().orEmpty() else ""
            runOnUiThread {
                if (!prefs.commitAwaitingAdminHomeSession(
                        chosenAdminDisplayName = pendingAdminName,
                        botId = botId,
                        apiKey = apiKey,
                        emailForWip = email,
                        passwordForWip = password,
                        nameForLinkForWip = email,
                        fcmToken = fcmToken,
                    )
                ) {
                    CuraxFeedback.warn(this@SignInActivity, getString(R.string.request_failed), long = true)
                    return@runOnUiThread
                }
                SignUpFlowState.clear()
                if (!store.saveUserCommitted(email, password, LocalUserStore.ROLE_USER)) {
                    CuraxFeedback.warn(this@SignInActivity, getString(R.string.request_failed), long = true)
                    return@runOnUiThread
                }
                CuraxFeedback.successThen(
                    this@SignInActivity,
                    getString(R.string.sign_in_success),
                    delayMs = 220L,
                    snackbarDuration = Snackbar.LENGTH_SHORT,
                ) {
                    navigateToUserHomeAfterAuth()
                }
            }
        }
    }

    private fun navigateToUserHomeAfterAuth() {
        val home = UserHomeIntent.forSignedInUser(this).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        val opts = ActivityOptionsCompat.makeCustomAnimation(
            this,
            android.R.anim.fade_in,
            android.R.anim.fade_out,
        )
        ActivityCompat.startActivity(this, home, opts.toBundle())
        finish()
    }

    private fun handleSignInSuccess(
        email: String,
        password: String,
        base: String,
        jo: JSONObject,
        pendingGoogleIdToken: String? = null,
        pendingFacebookAccessToken: String? = null,
    ) {
        AutofillHelper.commit(this)
        val resolvedEmail = email.ifBlank { jo.optString("email", "").trim() }.ifBlank { email }
        val phase = jo.optString("account_phase", "").trim().lowercase()
        when (phase) {
            "pending_email" -> {
                val i = Intent(this, SignUpActivity::class.java)
                    .putExtra(SignUpActivity.EXTRA_START_AT_OTP, true)
                    .putExtra(SignUpActivity.EXTRA_FROM_SIGNIN_PENDING_EMAIL, true)
                    .putExtra(SignUpActivity.EXTRA_EMAIL, resolvedEmail)
                    .putExtra(SignUpActivity.EXTRA_PASSWORD, password)
                    .putExtra(SignUpActivity.EXTRA_DISPLAY_NAME, resolvedEmail)
                pendingGoogleIdToken?.takeIf { it.isNotEmpty() }?.let {
                    i.putExtra(SignUpActivity.EXTRA_GOOGLE_ID_TOKEN, it)
                }
                pendingFacebookAccessToken?.takeIf { it.isNotEmpty() }?.let {
                    i.putExtra(SignUpActivity.EXTRA_FACEBOOK_ACCESS_TOKEN, it)
                }
                startActivity(i)
            }
            "pending_admin" -> {
                val botId = jo.optString("bot_id", "").trim()
                val apiKey = jo.optString("api_key", "").trim()
                val pendingAdminName = jo.optString("pending_admin_name", "").trim()
                if (botId.isNotEmpty() && apiKey.isNotEmpty()) {
                    resumePendingAdminDirectorySession(
                        resolvedEmail,
                        password,
                        botId,
                        apiKey,
                        pendingAdminName,
                    )
                    return
                }
                val nb = UUID.randomUUID().toString().take(8)
                val nk = UUID.randomUUID().toString().replace("-", "").take(16)
                SignUpFlowState.set(resolvedEmail, password, nb, nk, nameForLink = resolvedEmail)
                SignUpFlowState.persistWipToPrefs(prefs)
                startActivity(Intent(this, SignUpLinkAdminActivity::class.java))
            }
            "active" -> {
                val botId = jo.optString("bot_id", "").trim()
                val apiKey = jo.optString("api_key", "").trim()
                if (botId.isEmpty() || apiKey.isEmpty()) {
                    CuraxFeedback.warn(this, getString(R.string.request_failed), long = true)
                    return
                }
                applyActiveUserPrefs(jo, resolvedEmail, password, botId, apiKey, base)
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
        prefs.clearSignupWipLink()

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
                UserDataBusClient.fetchAndApplyUserData(
                    this@SignInActivity,
                    base,
                    botId,
                    apiKey,
                    onSuccess = {
                        runOnUiThread {
                            CuraxFeedback.successThen(
                                this@SignInActivity,
                                R.string.sign_in_success,
                                delayMs = 220L,
                                snackbarDuration = Snackbar.LENGTH_SHORT,
                            ) {
                                navigateToUserHomeAfterAuth()
                            }
                        }
                    },
                    onAuthRejected = { msg ->
                        runOnUiThread {
                            UserLogoutHelper.clearLocalSession(this@SignInActivity)
                            CuraxFeedback.warn(
                                this@SignInActivity,
                                msg.ifBlank { getString(R.string.account_removed_by_admin) },
                                long = true,
                            )
                        }
                    },
                )
            }
        }
    }

    companion object {
        /** Set when returning from UserDataBusClient after server rejects bot_id/api_key (deleted user, etc.). */
        const val EXTRA_SESSION_INVALIDATED_MESSAGE = "extra_session_invalidated_message"

        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
