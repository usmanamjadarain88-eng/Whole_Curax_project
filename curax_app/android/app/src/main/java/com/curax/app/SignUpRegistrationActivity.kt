package com.curax.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.text.method.HideReturnsTransformationMethod
import android.util.Base64
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.View
import android.view.ViewTreeObserver
import android.view.inputmethod.InputMethodManager
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.NestedScrollView
import androidx.core.widget.doOnTextChanged
import com.facebook.CallbackManager
import com.facebook.FacebookCallback
import com.facebook.FacebookException
import com.facebook.GraphRequest
import com.facebook.login.LoginManager
import com.facebook.login.LoginResult
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.auth.api.signin.GoogleSignInStatusCodes
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.api.ApiException
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.Locale
import java.util.concurrent.TimeUnit

class SignUpRegistrationActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var etFirstName: TextInputEditText
    private lateinit var etLastName: TextInputEditText
    private lateinit var etEmail: TextInputEditText
    private lateinit var etPassword: TextInputEditText
    private lateinit var etConfirmPassword: TextInputEditText
    private lateinit var btnCreateAccount: AppCompatButton
    private lateinit var cbTermsAgree: MaterialCheckBox
    private lateinit var cbMarketingEmails: MaterialCheckBox

    private lateinit var googleSignInClient: GoogleSignInClient
    private lateinit var facebookCallbackManager: CallbackManager

    private var imeInsetListener: ViewTreeObserver.OnGlobalLayoutListener? = null

    /** Set after Google Sign-In when using requestIdToken; used to skip email OTP via /signup/verify-email. */
    private var pendingGoogleIdToken: String? = null

    private val googleSignInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data ?: return@registerForActivityResult
        val task = GoogleSignIn.getSignedInAccountFromIntent(data)
        try {
            val account = task.getResult(ApiException::class.java)
            onGoogleSignedIn(account)
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

    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(22, TimeUnit.SECONDS)
        .writeTimeout(22, TimeUnit.SECONDS)
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sign_up_register)

        prefs = Prefs(this)

        // Email + profile only → device account chooser. ID token is optional for signup (OTP path);
        // requesting id token in the same intent often triggers Google’s web-style sign-in UI.
        val gsoBuilder = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestProfile()
        googleSignInClient = GoogleSignIn.getClient(this, gsoBuilder.build())

        facebookCallbackManager = CallbackManager.Factory.create()
        LoginManager.getInstance().registerCallback(
            facebookCallbackManager,
            object : FacebookCallback<LoginResult> {
                override fun onSuccess(result: LoginResult) {
                    val token = result.accessToken
                    val req = GraphRequest.newMeRequest(token) { json, response ->
                        runOnUiThread {
                            LoginManager.getInstance().logOut()
                            val graphError = response?.error
                            if (graphError != null) {
                                CuraxFeedback.warn(
                                    this@SignUpRegistrationActivity,
                                    getString(
                                        R.string.social_facebook_failed,
                                        graphError.errorMessage ?: "Graph error",
                                    ),
                                    long = true,
                                )
                                return@runOnUiThread
                            }
                            if (json == null) {
                                CuraxFeedback.warn(
                                    this@SignUpRegistrationActivity,
                                    getString(R.string.social_facebook_failed, "No profile"),
                                    long = true,
                                )
                                return@runOnUiThread
                            }
                            val email = json.optString("email", "").trim()
                            var first = json.optString("first_name", "").trim()
                            var last = json.optString("last_name", "").trim()
                            if (first.isEmpty() && last.isEmpty()) {
                                val nameStr = json.optString("name", "").trim()
                                if (nameStr.isNotEmpty()) {
                                    val parts = nameStr.split(Regex("\\s+")).filter { it.isNotEmpty() }
                                    if (parts.isNotEmpty()) first = parts[0]
                                    if (parts.size > 1) last = parts.drop(1).joinToString(" ")
                                }
                            }
                            if (email.isEmpty() && first.isEmpty() && last.isEmpty()) {
                                CuraxFeedback.warn(
                                    this@SignUpRegistrationActivity,
                                    getString(R.string.social_facebook_failed, "Email or name not shared"),
                                    long = true,
                                )
                                return@runOnUiThread
                            }
                            if (email.isEmpty()) {
                                CuraxFeedback.warn(
                                    this@SignUpRegistrationActivity,
                                    getString(R.string.social_email_required_for_signup),
                                    long = true,
                                )
                                return@runOnUiThread
                            }
                            completeSignupAfterSocial(email, first, last)
                        }
                    }
                    val params = Bundle()
                    params.putString("fields", "id,email,first_name,last_name,name")
                    req.parameters = params
                    req.executeAsync()
                }

                override fun onCancel() = Unit

                override fun onError(error: FacebookException) {
                    CuraxFeedback.warn(
                        this@SignUpRegistrationActivity,
                        getString(R.string.social_facebook_failed, error.message ?: "Login error"),
                        long = true,
                    )
                }
            },
        )

        etFirstName = findViewById(R.id.etFirstName)
        etLastName = findViewById(R.id.etLastName)
        etEmail = findViewById(R.id.etEmail)
        etPassword = findViewById(R.id.etPassword)
        etConfirmPassword = findViewById(R.id.etConfirmPassword)
        btnCreateAccount = findViewById(R.id.btnCreateAccount)
        cbTermsAgree = findViewById(R.id.cbTermsAgree)
        cbMarketingEmails = findViewById(R.id.cbMarketingEmails)

        bindTermsDetail(findViewById(R.id.tvTermsDetail))
        cbMarketingEmails.isChecked = true

        AuthPasswordToggle.bind(findViewById<TextInputLayout>(R.id.tilPasswordSignup), this)
        AuthPasswordToggle.bind(findViewById<TextInputLayout>(R.id.tilConfirmSignup), this)

        val scrollSignUp = findViewById<NestedScrollView>(R.id.scrollSignUpRegister)
        val scrollChild = scrollSignUp.getChildAt(0)
        bindImeOverlayBottomPadding(scrollSignUp)
        bindScrollOnFieldFocus(
            scrollSignUp,
            scrollChild,
            etFirstName,
            etLastName,
            etEmail,
            etPassword,
            etConfirmPassword,
        )

        findViewById<TextView>(R.id.tvSignInHere).setOnClickListener { finish() }

        findViewById<ImageButton>(R.id.btnGoogleSignup).setOnClickListener {
            googleSignInClient.signOut()
                .continueWithTask { googleSignInClient.revokeAccess() }
                .addOnCompleteListener {
                    try {
                        googleSignInLauncher.launch(googleSignInClient.signInIntent)
                    } catch (e: Exception) {
                        CuraxFeedback.warn(
                            this,
                            getString(R.string.social_google_failed, e.message ?: "error"),
                            long = true,
                        )
                    }
                }
        }
        findViewById<ImageButton>(R.id.btnFacebookSignup).setOnClickListener {
            try {
                LoginManager.getInstance().logInWithReadPermissions(
                    this@SignUpRegistrationActivity,
                    listOf("email", "public_profile"),
                )
            } catch (e: Exception) {
                CuraxFeedback.warn(
                    this,
                    getString(R.string.social_facebook_failed, e.message ?: "error"),
                    long = true,
                )
            }
        }

        etFirstName.doOnTextChanged { _, _, _, _ -> refreshCreateButtonState() }
        etLastName.doOnTextChanged { _, _, _, _ -> refreshCreateButtonState() }
        etEmail.doOnTextChanged { _, _, _, _ -> refreshCreateButtonState() }
        etPassword.doOnTextChanged { _, _, _, _ -> refreshCreateButtonState() }
        etConfirmPassword.doOnTextChanged { _, _, _, _ -> refreshCreateButtonState() }
        cbTermsAgree.setOnCheckedChangeListener { _, _ -> refreshCreateButtonState() }
        refreshCreateButtonState()

        btnCreateAccount.setOnClickListener { onCreateAccountClicked() }
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

    private fun onGoogleSignedIn(account: GoogleSignInAccount) {
        val idToken = account.idToken?.trim()?.takeIf { it.isNotEmpty() }
        try {
            val email = account.email?.trim().orEmpty()
            if (email.isEmpty()) {
                CuraxFeedback.warn(this, getString(R.string.social_email_required_for_signup), long = true)
                return
            }
            pendingGoogleIdToken = idToken
            completeSignupAfterSocial(email, account.givenName, account.familyName)
        } finally {
            googleSignInClient.signOut()
        }
    }

    /** Same password for this email in both fields (visible), terms accepted, then /signup/start + OTP. */
    private fun completeSignupAfterSocial(email: String, first: String?, last: String?) {
        val (fn, ln) = ensureNamesForSignup(first, last, email)
        etFirstName.setText(fn)
        etLastName.setText(ln)
        etEmail.setText(email.trim())

        val pw = generateRandomSignupPassword()
        showPasswordAsPlainText(etPassword)
        showPasswordAsPlainText(etConfirmPassword)
        etPassword.setText(pw)
        etConfirmPassword.setText(pw)

        val tilPwd = findViewById<TextInputLayout>(R.id.tilPasswordSignup)
        val tilConf = findViewById<TextInputLayout>(R.id.tilConfirmSignup)
        tilPwd.setEndIconDrawable(R.drawable.ic_auth_password_visible)
        tilConf.setEndIconDrawable(R.drawable.ic_auth_password_visible)
        tilPwd.setEndIconContentDescription(getString(R.string.cd_hide_password))
        tilConf.setEndIconContentDescription(getString(R.string.cd_hide_password))
        val tint = ContextCompat.getColorStateList(this, R.color.auth_password_toggle_tint)
        tilPwd.setEndIconTintList(tint)
        tilConf.setEndIconTintList(tint)

        cbTermsAgree.isChecked = true
        refreshCreateButtonState()
        CuraxFeedback.info(this, getString(R.string.social_signup_auto_body))
        btnCreateAccount.post { onCreateAccountClicked() }
    }

    private fun ensureNamesForSignup(first: String?, last: String?, email: String): Pair<String, String> {
        var f = first?.trim().orEmpty()
        var l = last?.trim().orEmpty()
        val local = email.substringBefore("@").trim().ifEmpty { "user" }
        if (f.isEmpty()) {
            f = if (local.isNotEmpty()) {
                local.substring(0, 1).uppercase(Locale.getDefault()) + local.substring(1)
            } else {
                "User"
            }
        }
        if (l.isEmpty()) {
            l = "User"
        }
        return f to l
    }

    private fun generateRandomSignupPassword(): String {
        val random = SecureRandom()
        val bytes = ByteArray(24)
        random.nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP).trimEnd('=')
    }

    private fun showPasswordAsPlainText(et: TextInputEditText) {
        et.transformationMethod = HideReturnsTransformationMethod.getInstance()
        et.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
    }

    private fun refreshCreateButtonState() {
        val fieldsOk = listOf(etFirstName, etLastName, etEmail, etPassword, etConfirmPassword).all {
            it.text?.toString()?.trim().orEmpty().isNotEmpty()
        }
        btnCreateAccount.isEnabled = fieldsOk && cbTermsAgree.isChecked
    }

    /** SHA-1 with colons (uppercase hex) for the installed APK — same value Firebase expects under fingerprints. */
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

    private fun bindTermsDetail(tv: TextView) {
        val prefix = getString(R.string.sign_up_terms_prefix)
        val privacy = getString(R.string.sign_up_terms_privacy)
        val full = prefix + privacy
        val ss = SpannableString(full)
        val start = prefix.length
        val end = full.length
        ss.setSpan(
            object : ClickableSpan() {
                override fun onClick(widget: View) {
                    try {
                        startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.privacy_policy_url))),
                        )
                    } catch (_: Exception) {
                        CuraxFeedback.warn(this@SignUpRegistrationActivity, getString(R.string.privacy_open_failed))
                    }
                }

                override fun updateDrawState(ds: TextPaint) {
                    super.updateDrawState(ds)
                    ds.isUnderlineText = true
                    ds.color = ContextCompat.getColor(this@SignUpRegistrationActivity, R.color.auth_heading)
                }
            },
            start,
            end,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        tv.text = ss
        tv.movementMethod = LinkMovementMethod.getInstance()
    }

    /** Same pattern as [SignInActivity]: scroll focused field above IME when keyboard is open. */
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

    private fun bindImeOverlayBottomPadding(content: View) {
        val decor = window.decorView
        val baseBottomPad = content.paddingBottom
        imeInsetListener = ViewTreeObserver.OnGlobalLayoutListener {
            val wi = ViewCompat.getRootWindowInsets(decor)
            // Only pad when IME is actually shown. Rect fallback when keyboard closed was
            // inflating bottom padding on some layout passes (double-tap / selection), causing
            // a temporary bottom "cut" until insets settled seconds later.
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
            content.updatePadding(bottom = baseBottomPad + imeBottom)
        }
        decor.viewTreeObserver.addOnGlobalLayoutListener(imeInsetListener)
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

    private fun onCreateAccountClicked() {
        if (!cbTermsAgree.isChecked) {
            CuraxFeedback.warn(this, getString(R.string.sign_up_terms_required))
            return
        }
        val first = etFirstName.text?.toString()?.trim().orEmpty()
        val last = etLastName.text?.toString()?.trim().orEmpty()
        val email = etEmail.text?.toString()?.trim().orEmpty()
        val password = etPassword.text?.toString()?.trim().orEmpty()
        val confirm = etConfirmPassword.text?.toString()?.trim().orEmpty()

        if (first.isBlank() || last.isBlank()) {
            CuraxFeedback.warn(this, getString(R.string.names_required))
            return
        }
        if (email.isBlank() || password.isBlank()) {
            CuraxFeedback.warn(this, getString(R.string.sign_in_all_fields_required))
            return
        }
        if (password.length < 6) {
            CuraxFeedback.warn(this, getString(R.string.password_min_length))
            return
        }
        if (password != confirm) {
            CuraxFeedback.warn(this, getString(R.string.passwords_do_not_match))
            return
        }
        val base = apiBase()
        if (base.isEmpty()) {
            CuraxFeedback.warn(this, getString(R.string.set_api_url_for_codes), long = true)
            return
        }
        hideKeyboard()
        val label = getString(R.string.sign_up_button)
        btnCreateAccount.isEnabled = false
        btnCreateAccount.text = getString(R.string.please_wait)
        val displayName = "$first $last".trim()
        Thread {
            try {
                val json = JSONObject().apply {
                    put("email", email)
                    put("password", password)
                    put("first_name", first)
                    put("last_name", last)
                }
                val (code, jo) = postJson("/signup/start", json)
                runOnUiThread {
                    if (code == 200) {
                        AutofillHelper.commit(this@SignUpRegistrationActivity)
                        val googleTok = pendingGoogleIdToken?.trim().orEmpty()
                        if (googleTok.isNotEmpty()) {
                            btnCreateAccount.text = getString(R.string.please_wait)
                            Thread {
                                try {
                                    val verifyJson = JSONObject().apply {
                                        put("email", email)
                                        put("google_id_token", googleTok)
                                    }
                                    val (vCode, vJo) =
                                        postJson("/signup/verify-email", verifyJson)
                                    runOnUiThread {
                                        pendingGoogleIdToken = null
                                        btnCreateAccount.isEnabled = true
                                        btnCreateAccount.text = label
                                        if (vCode == 200) {
                                            val botId =
                                                UUID.randomUUID().toString().take(8)
                                            val apiKey =
                                                UUID.randomUUID().toString()
                                                    .replace("-", "").take(16)
                                            SignUpFlowState.set(
                                                email,
                                                password,
                                                botId,
                                                apiKey,
                                                nameForLink = displayName,
                                            )
                                            SignUpFlowState.persistWipToPrefs(prefs)
                                            startActivity(
                                                Intent(
                                                    this@SignUpRegistrationActivity,
                                                    SignUpLinkAdminActivity::class.java,
                                                ),
                                            )
                                            finish()
                                        } else {
                                            CuraxFeedback.warn(
                                                this@SignUpRegistrationActivity,
                                                ApiErrorMessages.userMessage(
                                                    this@SignUpRegistrationActivity,
                                                    vCode,
                                                    vJo,
                                                ),
                                                long = true,
                                            )
                                            startActivity(
                                                Intent(
                                                    this@SignUpRegistrationActivity,
                                                    SignUpActivity::class.java,
                                                )
                                                    .putExtra(
                                                        SignUpActivity.EXTRA_START_AT_OTP,
                                                        true,
                                                    )
                                                    .putExtra(
                                                        SignUpActivity.EXTRA_EMAIL,
                                                        email,
                                                    )
                                                    .putExtra(
                                                        SignUpActivity.EXTRA_PASSWORD,
                                                        password,
                                                    )
                                                    .putExtra(
                                                        SignUpActivity.EXTRA_DISPLAY_NAME,
                                                        displayName,
                                                    ),
                                            )
                                            finish()
                                        }
                                    }
                                } catch (_: Exception) {
                                    runOnUiThread {
                                        pendingGoogleIdToken = null
                                        btnCreateAccount.isEnabled = true
                                        btnCreateAccount.text = label
                                        CuraxFeedback.warn(
                                            this@SignUpRegistrationActivity,
                                            getString(R.string.error_network_unreachable),
                                            long = true,
                                        )
                                        startActivity(
                                            Intent(
                                                this@SignUpRegistrationActivity,
                                                SignUpActivity::class.java,
                                            )
                                                .putExtra(
                                                    SignUpActivity.EXTRA_START_AT_OTP,
                                                    true,
                                                )
                                                .putExtra(
                                                    SignUpActivity.EXTRA_EMAIL,
                                                    email,
                                                )
                                                .putExtra(
                                                    SignUpActivity.EXTRA_PASSWORD,
                                                    password,
                                                )
                                                .putExtra(
                                                    SignUpActivity.EXTRA_DISPLAY_NAME,
                                                    displayName,
                                                ),
                                        )
                                        finish()
                                    }
                                }
                            }.start()
                        } else {
                            btnCreateAccount.isEnabled = true
                            btnCreateAccount.text = label
                            startActivity(
                                Intent(this@SignUpRegistrationActivity, SignUpActivity::class.java)
                                    .putExtra(SignUpActivity.EXTRA_START_AT_OTP, true)
                                    .putExtra(SignUpActivity.EXTRA_EMAIL, email)
                                    .putExtra(SignUpActivity.EXTRA_PASSWORD, password)
                                    .putExtra(SignUpActivity.EXTRA_DISPLAY_NAME, displayName),
                            )
                            finish()
                        }
                    } else {
                        btnCreateAccount.isEnabled = true
                        btnCreateAccount.text = label
                        CuraxFeedback.warn(
                            this@SignUpRegistrationActivity,
                            ApiErrorMessages.userMessage(this@SignUpRegistrationActivity, code, jo),
                            long = true,
                        )
                    }
                }
            } catch (_: Exception) {
                runOnUiThread {
                    btnCreateAccount.isEnabled = true
                    btnCreateAccount.text = label
                    CuraxFeedback.warn(
                        this@SignUpRegistrationActivity,
                        getString(R.string.error_network_unreachable),
                        long = true,
                    )
                }
            }
        }.start()
    }

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
