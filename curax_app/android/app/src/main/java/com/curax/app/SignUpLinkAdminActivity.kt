package com.curax.app

import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.view.ViewTreeObserver
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.NestedScrollView
import androidx.appcompat.widget.AppCompatButton
import androidx.core.widget.doOnTextChanged
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * After email OTP: enter admin connection code [POST /signup/link-admin], or choose an admin from
 * [GET /signup/admins-directory] and send [POST /signup/request-admin-link]. Choose-admin path sends
 * the request then opens the full user app (standalone); acceptance is finalized in the background
 * via [AwaitingAdminLinkCoordinator].
 */
class SignUpLinkAdminActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var store: LocalUserStore
    private lateinit var etAdminConnectionCode: TextInputEditText
    private lateinit var tilAdminConnectionCode: TextInputLayout
    private lateinit var tvLinkSubtitle: TextView
    private lateinit var tvChooseAdminLink: TextView
    private lateinit var btnLinkAdmin: AppCompatButton

    private var imeInsetListener: ViewTreeObserver.OnGlobalLayoutListener? = null

    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(22, TimeUnit.SECONDS)
        .writeTimeout(22, TimeUnit.SECONDS)
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_signup_link_admin)

        if (!SignUpFlowState.isReady()) {
            CuraxFeedback.warn(this, getString(R.string.request_failed))
            finish()
            return
        }

        prefs = Prefs(this)
        store = LocalUserStore(this)
        etAdminConnectionCode = findViewById(R.id.etAdminConnectionCode)
        tilAdminConnectionCode = findViewById(R.id.tilAdminConnectionCode)
        tvLinkSubtitle = findViewById(R.id.tvLinkSubtitle)
        tvChooseAdminLink = findViewById(R.id.tvChooseAdminLink)
        btnLinkAdmin = findViewById(R.id.btnLinkAdmin)

        tvLinkSubtitle.text = getString(R.string.signup_link_admin_email_line, SignUpFlowState.email)
        etAdminConnectionCode.doOnTextChanged { _, _, _, _ -> syncConnectButtonState() }
        syncConnectButtonState()

        val pendingInvite = prefs.pendingInviteConnectionCode.trim()
        if (pendingInvite.isNotEmpty()) {
            etAdminConnectionCode.setText(pendingInvite)
            syncConnectButtonState()
        }

        btnLinkAdmin.setOnClickListener { onLinkAdminClicked() }
        tvChooseAdminLink.setOnClickListener {
            hideKeyboard()
            loadAdminsAndPick()
        }

        val scrollLink = findViewById<NestedScrollView>(R.id.scrollSignUpLinkAdmin)
        val scrollChild = scrollLink.getChildAt(0)
        bindImeOverlayBottomPadding(scrollLink)
        bindScrollOnFieldFocus(scrollLink, scrollChild, etAdminConnectionCode)
    }

    override fun onDestroy() {
        imeInsetListener?.let { window.decorView.viewTreeObserver.removeOnGlobalLayoutListener(it) }
        imeInsetListener = null
        super.onDestroy()
    }

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

    /** Connect only when code length matches server admin connection codes (8 chars). */
    private fun syncConnectButtonState() {
        val len = etAdminConnectionCode.text?.toString()?.trim().orEmpty().length
        val ok = len >= MIN_ADMIN_CONNECTION_CODE_LEN
        btnLinkAdmin.isEnabled = ok
        btnLinkAdmin.alpha = if (ok) 1f else 0.45f
    }

    private fun apiBase(): String = prefs.centralApiUrl.trim().removeSuffix("/")

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        val token = currentFocus?.windowToken ?: window.decorView.windowToken
        imm.hideSoftInputFromWindow(token, 0)
    }

    private fun getJson(pathAndQuery: String): Pair<Int, JSONObject?> {
        val base = apiBase()
        if (base.isEmpty()) return Pair(-1, null)
        val req = Request.Builder()
            .url("$base$pathAndQuery")
            .get()
            .build()
        http.newCall(req).execute().use { res ->
            val raw = res.body?.string().orEmpty()
            val jo = ApiErrorMessages.parseResponseBody(raw, res.code)
            return Pair(res.code, jo)
        }
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

    private fun loadAdminsAndPick() {
        val base = apiBase()
        if (base.isEmpty()) {
            CuraxFeedback.warn(this, getString(R.string.set_api_url_for_codes), long = true)
            return
        }
        Thread {
            try {
                val (code, jo) = getJson("/signup/admins-directory")
                runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    if (code != 200 || jo == null) {
                        CuraxFeedback.warn(this, getString(R.string.signup_load_admins_failed), long = true)
                        return@runOnUiThread
                    }
                    val arr = jo.optJSONArray("admins") ?: JSONArray()
                    if (arr.length() == 0) {
                        CuraxFeedback.warn(this, getString(R.string.signup_no_admins_available), long = true)
                        return@runOnUiThread
                    }
                    val labels = Array(arr.length()) { i ->
                        val o = arr.optJSONObject(i)
                        val n = o?.optString("name")?.trim().orEmpty()
                        if (n.isNotEmpty()) n else o?.optString("id").orEmpty()
                    }
                    AlertDialog.Builder(this)
                        .setTitle(R.string.signup_choose_admin_dialog_title)
                        .setItems(labels) { _, which ->
                            val o = arr.optJSONObject(which) ?: return@setItems
                            submitAdminLinkRequest(o.optString("id"), labels[which])
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
            } catch (_: Exception) {
                runOnUiThread {
                    if (!isFinishing) {
                        CuraxFeedback.warn(this, getString(R.string.error_network_unreachable), long = true)
                    }
                }
            }
        }.start()
    }

    private fun submitAdminLinkRequest(adminId: String, adminLabel: String) {
        val base = apiBase()
        if (base.isEmpty()) {
            CuraxFeedback.warn(this, getString(R.string.set_api_url_for_codes), long = true)
            return
        }
        val email = SignUpFlowState.email
        val botId = SignUpFlowState.botId
        val apiKey = SignUpFlowState.apiKey
        hideKeyboard()
        btnLinkAdmin.isEnabled = false
        btnLinkAdmin.alpha = 1f
        btnLinkAdmin.text = getString(R.string.please_wait)
        Thread {
            try {
                val displayName = SignUpFlowState.nameForLink.trim().ifBlank { email }
                val json = JSONObject().apply {
                    put("email", email)
                    put("admin_id", adminId)
                    put("bot_id", botId)
                    put("api_key", apiKey)
                    put("name", displayName)
                }
                val (code, jo) = postJson("/signup/request-admin-link", json)
                val connectLabel = getString(R.string.connect)
                runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    btnLinkAdmin.text = connectLabel
                    syncConnectButtonState()
                    if (code == 200 && jo != null) {
                        val adminShown = jo.optString("admin_name").trim().ifBlank { adminLabel }
                        openFullAppAfterAdminRequestSent(adminShown)
                    } else {
                        CuraxFeedback.warn(this, ApiErrorMessages.userMessage(this, code, jo), long = true)
                    }
                }
            } catch (_: Exception) {
                runOnUiThread {
                    if (!isFinishing) {
                        btnLinkAdmin.text = getString(R.string.connect)
                        syncConnectButtonState()
                        CuraxFeedback.warn(this, getString(R.string.error_network_unreachable), long = true)
                    }
                }
            }
        }.start()
    }

    /**
     * Opens home in default mode until the user picks Smart System vs Personal Health on the first-home sheet.
     * Admin acceptance is applied later via [AwaitingAdminLinkCoordinator].
     */
    private fun openFullAppAfterAdminRequestSent(chosenAdminDisplayName: String) {
        val email = SignUpFlowState.email
        val password = SignUpFlowState.password
        val botId = SignUpFlowState.botId
        val apiKey = SignUpFlowState.apiKey
        val nameForLink = SignUpFlowState.nameForLink.trim().ifBlank { email }

        UserLogoutHelper.disconnectRealtimeTransport(this)
        if (!prefs.commitAwaitingAdminHomeSession(
                chosenAdminDisplayName = chosenAdminDisplayName.trim(),
                botId = botId,
                apiKey = apiKey,
                emailForWip = email,
                passwordForWip = password,
                nameForLinkForWip = nameForLink,
            )
        ) {
            CuraxFeedback.warn(this, getString(R.string.request_failed), long = true)
            return
        }
        UserModeSheetPrefs.syncGlobalFlagFromAccount(this, botId, email)
        SignUpFlowState.clear()
        if (!store.saveUserCommitted(email, password, LocalUserStore.ROLE_USER)) {
            CuraxFeedback.warn(this, getString(R.string.request_failed), long = true)
            return
        }
        AppModeManager.restoreSavedAccountMode(this, email)

        CuraxFeedback.successThen(
            this,
            getString(R.string.signup_admin_request_sent_message),
            delayMs = 420L,
            snackbarDuration = Snackbar.LENGTH_LONG,
        ) {
            navigateHomeAndFinish()
        }
    }

    private fun navigateHomeAndFinish() {
        if (isFinishing) return
        setResult(RESULT_OK)
        startActivity(UserHomeIntent.forSignedInUserAfterSignIn(this))
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.auth_slide_in_from_left, R.anim.auth_slide_out_to_right)
    }

    private fun onLinkAdminClicked() {
        val connectionCode = etAdminConnectionCode.text?.toString()?.trim().orEmpty()
        if (connectionCode.isBlank()) {
            CuraxFeedback.warn(this, getString(R.string.connection_code_hint))
            return
        }
        val base = apiBase()
        if (base.isEmpty()) {
            CuraxFeedback.warn(this, getString(R.string.set_api_url_for_codes), long = true)
            return
        }
        val email = SignUpFlowState.email
        val password = SignUpFlowState.password
        val botId = SignUpFlowState.botId
        val apiKey = SignUpFlowState.apiKey

        hideKeyboard()
        val connectLabel = getString(R.string.connect)
        btnLinkAdmin.isEnabled = false
        btnLinkAdmin.alpha = 1f
        btnLinkAdmin.text = getString(R.string.please_wait)
        Thread {
            try {
                val displayName = SignUpFlowState.nameForLink.trim().ifBlank { email }
                val json = JSONObject().apply {
                    put("email", email)
                    put("connection_code", connectionCode)
                    put("bot_id", botId)
                    put("api_key", apiKey)
                    put("name", displayName)
                }
                val (code, jo) = postJson("/signup/link-admin", json)
                runOnUiThread {
                    btnLinkAdmin.text = connectLabel
                    if (code == 200 && jo != null) {
                        applyPrefsAfterLink(jo, connectionCode, base, email, password, botId, apiKey)
                    } else {
                        CuraxFeedback.warn(this, ApiErrorMessages.userMessage(this, code, jo), long = true)
                        syncConnectButtonState()
                    }
                }
            } catch (_: Exception) {
                runOnUiThread {
                    btnLinkAdmin.text = connectLabel
                    syncConnectButtonState()
                    CuraxFeedback.warn(this, getString(R.string.error_network_unreachable), long = true)
                }
            }
        }.start()
    }

    private fun applyPrefsAfterLink(
        jo: JSONObject,
        connectionCode: String,
        base: String,
        email: String,
        password: String,
        botId: String,
        apiKey: String,
    ) {
        val nameFb = SignUpFlowState.nameForLink.trim().ifBlank { email }
        SignupAdminLinkHelper.applyServerLinkSuccess(
            this,
            jo,
            connectionCode,
            email,
            password,
            botId,
            apiKey,
            http,
            nameForLinkFallback = nameFb,
            onUserDataApplied = {
                CuraxFeedback.successThen(this, R.string.linked_to_admin_success) {
                    if (!isFinishing) {
                        setResult(RESULT_OK)
                        startActivity(UserHomeIntent.forSignedInUserAfterSignIn(this))
                        finish()
                        @Suppress("DEPRECATION")
                        overridePendingTransition(
                            R.anim.auth_slide_in_from_left,
                            R.anim.auth_slide_out_to_right,
                        )
                    }
                }
            },
            onAuthRejected = { msg ->
                UserLogoutHelper.clearLocalSession(this)
                CuraxFeedback.warn(
                    this,
                    msg.ifBlank { getString(R.string.account_removed_by_admin) },
                    long = true,
                )
            },
        )
    }

    companion object {
        /** Matches backend `central_db._ADMIN_CODE_LENGTH` (connection_code format). */
        private const val MIN_ADMIN_CONNECTION_CODE_LEN = 8

        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
