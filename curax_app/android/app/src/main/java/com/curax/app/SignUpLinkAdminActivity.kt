package com.curax.app

import android.content.Intent
import android.os.Bundle
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doOnTextChanged
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.messaging.FirebaseMessaging
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Separate screen after email OTP: enter admin connection code and call
 * [POST /signup/link-admin] — same server effect as the legacy connect-to-admin flow,
 * so the user appears under that admin’s linked / connected users list.
 */
class SignUpLinkAdminActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var store: LocalUserStore
    private lateinit var etAdminConnectionCode: TextInputEditText
    private lateinit var tvLinkSubtitle: TextView
    private lateinit var btnLinkAdmin: MaterialButton

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_signup_link_admin)

        if (!SignUpFlowState.isReady()) {
            Toast.makeText(this, getString(R.string.request_failed), Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        prefs = Prefs(this)
        store = LocalUserStore(this)
        etAdminConnectionCode = findViewById(R.id.etAdminConnectionCode)
        tvLinkSubtitle = findViewById(R.id.tvLinkSubtitle)
        btnLinkAdmin = findViewById(R.id.btnLinkAdmin)

        tvLinkSubtitle.text = getString(R.string.signup_link_admin_email_line, SignUpFlowState.email)
        etAdminConnectionCode.doOnTextChanged { _, _, _, _ -> syncConnectButtonState() }
        syncConnectButtonState()

        btnLinkAdmin.setOnClickListener { onLinkAdminClicked() }
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

    private fun onLinkAdminClicked() {
        val connectionCode = etAdminConnectionCode.text?.toString()?.trim().orEmpty()
        if (connectionCode.isBlank()) {
            Toast.makeText(this, getString(R.string.connection_code_hint), Toast.LENGTH_SHORT).show()
            return
        }
        val base = apiBase()
        if (base.isEmpty()) {
            Toast.makeText(this, getString(R.string.set_api_url_for_codes), Toast.LENGTH_LONG).show()
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
                            Toast.makeText(this, messageFromResponse(jo), Toast.LENGTH_LONG).show()
                            syncConnectButtonState()
                        }
                    }
                } catch (_: Exception) {
                    runOnUiThread {
                        btnLinkAdmin.text = connectLabel
                        syncConnectButtonState()
                        Toast.makeText(this, getString(R.string.request_failed), Toast.LENGTH_LONG).show()
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

        prefs.id = botId
        prefs.apiKey = apiKey
        prefs.connectionCode = connectionCode
        prefs.databusAccessCode = databusAccessCode
        prefs.linkedAdminId = adminId
        prefs.linkedAdminName = adminName
        prefs.hasEverConnected = true
        if (fcmToken.isNotEmpty()) prefs.fcmToken = fcmToken

        store.saveUser(email, password, LocalUserStore.ROLE_USER)
        SignUpFlowState.clear()

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

        UserDataBusClient.fetchAndApplyUserData(this, base, botId, apiKey) {
            runOnUiThread {
                Toast.makeText(this, getString(R.string.linked_to_admin_success), Toast.LENGTH_SHORT).show()
                startActivity(
                    Intent(this, PinSetupActivity::class.java)
                        .putExtra(PinSetupActivity.EXTRA_NEXT_ROLE, LocalUserStore.ROLE_USER)
                )
                setResult(RESULT_OK)
                finish()
            }
        }
    }

    companion object {
        /** Matches backend `central_db._ADMIN_CODE_LENGTH` (connection_code format). */
        private const val MIN_ADMIN_CONNECTION_CODE_LEN = 8

        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
