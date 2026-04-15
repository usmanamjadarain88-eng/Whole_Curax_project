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
import java.util.UUID
import java.util.concurrent.TimeUnit

class SignInActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var store: LocalUserStore
    private lateinit var etEmail: TextInputEditText
    private lateinit var etPassword: TextInputEditText
    private lateinit var btnSignIn: MaterialButton

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
        findViewById<TextView>(R.id.tvSignUpHere).setOnClickListener {
            startActivity(Intent(this, SignUpRegistrationActivity::class.java))
        }
        findViewById<TextView>(R.id.tvSignUpAdmin).setOnClickListener {
            startActivity(Intent(this, AdminRegistrationActivity::class.java))
        }

        fun syncBtn() {
            val ok = etEmail.text?.toString()?.trim().orEmpty().isNotEmpty() &&
                etPassword.text?.toString()?.trim().orEmpty().isNotEmpty()
            btnSignIn.isEnabled = ok
        }
        etEmail.doOnTextChanged { _, _, _, _ -> syncBtn() }
        etPassword.doOnTextChanged { _, _, _, _ -> syncBtn() }
        syncBtn()

        btnSignIn.setOnClickListener { onSignInClicked() }
    }

    private fun apiBase(): String = prefs.centralApiUrl.trim().removeSuffix("/")

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        imm.hideSoftInputFromWindow(currentFocus?.windowToken ?: window.decorView.windowToken, 0)
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

    private fun onSignInClicked() {
        val email = etEmail.text?.toString()?.trim().orEmpty()
        val password = etPassword.text?.toString()?.trim().orEmpty()
        if (email.isBlank() || password.isBlank()) {
            Toast.makeText(this, getString(R.string.sign_in_all_fields_required), Toast.LENGTH_SHORT).show()
            return
        }
        if (password.length < 6) {
            Toast.makeText(this, getString(R.string.password_min_length), Toast.LENGTH_SHORT).show()
            return
        }
        val base = apiBase()
        if (base.isEmpty()) {
            Toast.makeText(this, getString(R.string.set_api_url_for_codes), Toast.LENGTH_LONG).show()
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
                        Toast.makeText(this@SignInActivity, messageFromResponse(jo), Toast.LENGTH_LONG).show()
                    }
                }
            } catch (_: Exception) {
                runOnUiThread {
                    btnSignIn.isEnabled = true
                    btnSignIn.text = label
                    Toast.makeText(this@SignInActivity, getString(R.string.request_failed), Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun handleSignInSuccess(email: String, password: String, base: String, jo: JSONObject) {
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
                    Toast.makeText(this, getString(R.string.request_failed), Toast.LENGTH_LONG).show()
                    return
                }
                applyActiveUserPrefs(jo, email, password, botId, apiKey, base)
            }
            else -> Toast.makeText(this, getString(R.string.request_failed), Toast.LENGTH_LONG).show()
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
                        Toast.makeText(this@SignInActivity, getString(R.string.sign_in_success), Toast.LENGTH_SHORT).show()
                        startActivity(
                            Intent(this@SignInActivity, PinSetupActivity::class.java)
                                .putExtra(PinSetupActivity.EXTRA_NEXT_ROLE, LocalUserStore.ROLE_USER)
                        )
                        finish()
                    }
                }
            }
        }
    }

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
