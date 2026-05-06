package com.curax.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import androidx.core.widget.doOnTextChanged
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.firebase.messaging.FirebaseMessaging
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * After email OTP: enter admin connection code [POST /signup/link-admin], or choose an admin from
 * [GET /signup/admins-directory] and send [POST /signup/request-admin-link], then poll
 * [GET /signup/link-request-status] until the admin accepts.
 */
class SignUpLinkAdminActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var store: LocalUserStore
    private lateinit var etAdminConnectionCode: TextInputEditText
    private lateinit var tilAdminConnectionCode: TextInputLayout
    private lateinit var tvLinkSubtitle: TextView
    private lateinit var tvChooseAdmin: TextView
    private lateinit var tvPendingStatus: TextView
    private lateinit var btnLinkAdmin: AppCompatButton

    private val handler = Handler(Looper.getMainLooper())
    private var pollRunnable: Runnable? = null
    private var awaitingAdminAcceptance = false

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
        tvChooseAdmin = findViewById(R.id.tvChooseAdmin)
        tvPendingStatus = findViewById(R.id.tvPendingStatus)
        btnLinkAdmin = findViewById(R.id.btnLinkAdmin)

        tvLinkSubtitle.text = getString(R.string.signup_link_admin_email_line, SignUpFlowState.email)
        etAdminConnectionCode.doOnTextChanged { _, _, _, _ -> syncConnectButtonState() }
        syncConnectButtonState()

        btnLinkAdmin.setOnClickListener { onLinkAdminClicked() }
        tvChooseAdmin.setOnClickListener {
            hideKeyboard()
            loadAdminsAndPick()
        }

        refreshLinkStatusFromServer()
    }

    override fun onDestroy() {
        stopPolling()
        super.onDestroy()
    }

    /** Connect only when code length matches server admin connection codes (8 chars). */
    private fun syncConnectButtonState() {
        if (awaitingAdminAcceptance) {
            btnLinkAdmin.isEnabled = false
            btnLinkAdmin.alpha = 1f
            return
        }
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

    private fun refreshLinkStatusFromServer() {
        val base = apiBase()
        val email = SignUpFlowState.email
        val botId = SignUpFlowState.botId
        val apiKey = SignUpFlowState.apiKey
        if (base.isEmpty() || email.isBlank() || botId.isBlank() || apiKey.isBlank()) return
        Thread {
            try {
                val q =
                    "/signup/link-request-status?email=${Uri.encode(email)}&bot_id=${Uri.encode(botId)}&api_key=${Uri.encode(apiKey)}"
                val (code, jo) = getJson(q)
                runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    if (code != 200 || jo == null) return@runOnUiThread
                    when (jo.optString("status")) {
                        "pending" -> {
                            val name = jo.optString("admin_name").trim().ifBlank {
                                getString(R.string.link_admin_heading_line2)
                            }
                            enterPendingMode(name)
                            startPolling()
                        }
                        "accepted" -> handleAcceptedResponse(jo)
                        else -> { }
                    }
                }
            } catch (_: Exception) { }
        }.start()
    }

    private fun enterPendingMode(adminDisplayName: String) {
        awaitingAdminAcceptance = true
        tvPendingStatus.visibility = View.VISIBLE
        tvPendingStatus.text = getString(R.string.signup_waiting_admin_acceptance, adminDisplayName)
        tilAdminConnectionCode.isEnabled = false
        etAdminConnectionCode.isEnabled = false
        tvChooseAdmin.visibility = View.GONE
        btnLinkAdmin.isEnabled = false
        btnLinkAdmin.alpha = 1f
        btnLinkAdmin.text = getString(R.string.signup_waiting_short)
        syncConnectButtonState()
    }

    private fun startPolling() {
        stopPolling()
        val r = object : Runnable {
            override fun run() {
                if (isFinishing) return
                pollLinkStatusOnce()
                handler.postDelayed(this, POLL_INTERVAL_MS)
            }
        }
        pollRunnable = r
        handler.post(r)
    }

    private fun stopPolling() {
        pollRunnable?.let { handler.removeCallbacks(it) }
        pollRunnable = null
    }

    private fun pollLinkStatusOnce() {
        val base = apiBase()
        val email = SignUpFlowState.email
        val botId = SignUpFlowState.botId
        val apiKey = SignUpFlowState.apiKey
        if (base.isEmpty() || email.isBlank() || botId.isBlank() || apiKey.isBlank()) return
        Thread {
            try {
                val q =
                    "/signup/link-request-status?email=${Uri.encode(email)}&bot_id=${Uri.encode(botId)}&api_key=${Uri.encode(apiKey)}"
                val (code, jo) = getJson(q)
                runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    if (code == 200 && jo != null && jo.optString("status") == "accepted") {
                        handleAcceptedResponse(jo)
                    }
                }
            } catch (_: Exception) { }
        }.start()
    }

    private fun handleAcceptedResponse(jo: JSONObject) {
        stopPolling()
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            val fcmToken = if (task.isSuccessful) task.result?.trim().orEmpty() else ""
            val connectionCode = jo.optString("connection_code", "").trim()
            val base = apiBase()
            runOnUiThread {
                applyPrefsAfterLink(
                    jo,
                    connectionCode,
                    fcmToken,
                    base,
                    SignUpFlowState.email,
                    SignUpFlowState.password,
                    SignUpFlowState.botId,
                    SignUpFlowState.apiKey,
                )
            }
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
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            val fcmToken = if (task.isSuccessful) task.result?.trim().orEmpty() else ""
            Thread {
                try {
                    val displayName = SignUpFlowState.nameForLink.trim().ifBlank { email }
                    val json = JSONObject().apply {
                        put("email", email)
                        put("admin_id", adminId)
                        put("bot_id", botId)
                        put("api_key", apiKey)
                        put("name", displayName)
                        if (fcmToken.isNotEmpty()) put("fcm_token", fcmToken)
                    }
                    val (code, jo) = postJson("/signup/request-admin-link", json)
                    val connectLabel = getString(R.string.connect)
                    runOnUiThread {
                        if (isFinishing) return@runOnUiThread
                        btnLinkAdmin.text = connectLabel
                        if (code == 200 && jo != null) {
                            val nameFromServer = jo.optString("admin_name").trim().ifBlank { adminLabel }
                            enterPendingMode(nameFromServer)
                            startPolling()
                        } else {
                            CuraxFeedback.warn(this, ApiErrorMessages.userMessage(this, code, jo), long = true)
                            syncConnectButtonState()
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
    }

    private fun onLinkAdminClicked() {
        if (awaitingAdminAcceptance) return
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
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            val fcmToken = if (task.isSuccessful) task.result?.trim().orEmpty() else ""
            Thread {
                try {
                    val displayName = SignUpFlowState.nameForLink.trim().ifBlank { email }
                    val json = JSONObject().apply {
                        put("email", email)
                        put("connection_code", connectionCode)
                        put("bot_id", botId)
                        put("api_key", apiKey)
                        put("name", displayName)
                        if (fcmToken.isNotEmpty()) put("fcm_token", fcmToken)
                    }
                    val (code, jo) = postJson("/signup/link-admin", json)
                    runOnUiThread {
                        btnLinkAdmin.text = connectLabel
                        if (code == 200 && jo != null) {
                            applyPrefsAfterLink(jo, connectionCode, fcmToken, base, email, password, botId, apiKey)
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
    }

    private fun applyPrefsAfterLink(
        jo: JSONObject,
        connectionCode: String,
        fcmToken: String,
        base: String,
        email: String,
        password: String,
        botId: String,
        apiKey: String,
    ) {
        val adminId = when {
            jo.isNull("admin_id") -> ""
            else -> jo.get("admin_id").toString().trim()
        }
        val adminName = jo.optString("admin_name", "").trim()
        val databusAccessCode = jo.optString("databus_access_code", "").trim()
        val codeForPrefs = connectionCode.ifBlank { jo.optString("connection_code", "").trim() }

        prefs.id = botId
        prefs.apiKey = apiKey
        prefs.connectionCode = codeForPrefs
        prefs.databusAccessCode = databusAccessCode
        prefs.linkedAdminId = adminId
        prefs.linkedAdminName = adminName
        prefs.hasEverConnected = true
        prefs.userInitialAppModeSheetCompleted = false
        if (fcmToken.isNotEmpty()) prefs.fcmToken = fcmToken

        val fromServer = jo.optString("user_first_name", "").trim()
        val hubFirst = fromServer.ifBlank { UserNameFormatter.firstNameForHub(SignUpFlowState.nameForLink) }
        if (hubFirst.isNotEmpty()) prefs.userHubFirstName = hubFirst

        val fromFull = jo.optString("user_full_name", "").trim()
        if (fromFull.isNotEmpty()) prefs.userHubFullName = fromFull

        val fromUsername = jo.optString("user_username", "").trim()
        if (fromUsername.isNotEmpty()) prefs.userHubUsername = fromUsername

        val dm = jo.optString("user_display_mode", "").trim().lowercase()
        if (dm == "standalone" || dm == "default") {
            val wantStandalone = dm == "standalone"
            val was = prefs.userStandaloneMode
            if (wantStandalone != was) {
                AppModeManager.setStandaloneMode(this, wantStandalone)
                sendBroadcast(Intent(AlertEvents.ACTION_USER_DISPLAY_MODE_FROM_SERVER))
            }
        }

        store.saveUser(email, password, LocalUserStore.ROLE_USER)
        SignUpFlowState.clear()
        Prefs(this).clearSignupWipLink()

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

        UserDataBusClient.fetchAndApplyUserData(
            this,
            base,
            botId,
            apiKey,
            onSuccess = {
                runOnUiThread {
                    CuraxFeedback.successThen(this, R.string.linked_to_admin_success) {
                        val home = UserHomeIntent.forSignedInUser(this).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        }
                        startActivity(home)
                        setResult(RESULT_OK)
                        finish()
                    }
                }
            },
            onAuthRejected = { msg ->
                runOnUiThread {
                    UserLogoutHelper.clearLocalSession(this)
                    CuraxFeedback.warn(
                        this,
                        msg.ifBlank { getString(R.string.account_removed_by_admin) },
                        long = true,
                    )
                }
            },
        )
    }

    companion object {
        /** Matches backend `central_db._ADMIN_CODE_LENGTH` (connection_code format). */
        private const val MIN_ADMIN_CONNECTION_CODE_LEN = 8
        private const val POLL_INTERVAL_MS = 4000L

        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
