package com.curax.app

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doOnTextChanged
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import java.util.UUID

class SignUpActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_signup)

        val store = LocalUserStore(this)
        val prefs = Prefs(this)
        val etEmail = findViewById<TextInputEditText>(R.id.etEmail)
        val etPassword = findViewById<TextInputEditText>(R.id.etPassword)
        val btnSignIn = findViewById<MaterialButton>(R.id.btnSignUp)
        val tvSignInAdmin = findViewById<TextView>(R.id.tvSignUpAdmin)

        fun syncSignUpButtonEnabled() {
            val emailOk = etEmail.text?.toString()?.trim().orEmpty().isNotEmpty()
            val passwordOk = etPassword.text?.toString()?.trim().orEmpty().isNotEmpty()
            btnSignIn.isEnabled = emailOk && passwordOk
        }
        etEmail.doOnTextChanged { _, _, _, _ -> syncSignUpButtonEnabled() }
        etPassword.doOnTextChanged { _, _, _, _ -> syncSignUpButtonEnabled() }
        syncSignUpButtonEnabled()

        btnSignIn.setOnClickListener {
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
                    /*
                     * === RESTORE: admin connection code + POST /connect-to-admin ===
                     * When etConnectionCode is back in activity_signup.xml, re-enable:
                     * - val connectionCode = etConnectionCode.text...
                     * - require non-blank connectionCode
                     * - OkHttp POST to "$base/connect-to-admin" with JSON (connection_code, bot_id, api_key, …)
                     * - on success: prefs + UserDataBusClient.fetchAndApplyUserData(...); then saveUser + PinSetupActivity
                     *
                    val connectionCode = etConnectionCode.text?.toString()?.trim().orEmpty()
                    val base = prefs.centralApiUrl.trim().trimEnd('/')
                    btnSignIn.isEnabled = false
                    val progress = android.app.ProgressDialog(this).apply {
                        setMessage("Linking to admin...")
                        setCancelable(false)
                        show()
                    }
                    Thread {
                        try {
                            val botId = UUID.randomUUID().toString().take(8)
                            val apiKey = UUID.randomUUID().toString().replace("-", "").take(16)
                            val json = org.json.JSONObject().apply {
                                put("connection_code", connectionCode)
                                put("bot_id", botId)
                                put("api_key", apiKey)
                                put("name", email)
                                put("email", email)
                            }
                            val req = okhttp3.Request.Builder()
                                .url("$base/connect-to-admin")
                                .post(json.toString().toRequestBody("application/json".toMediaType()))
                                .build()
                            // … execute, apply prefs, fetch user data, saveUser, PinSetupActivity …
                        } catch (_: Exception) { }
                    }.start()
                    */

                    // Temporary: sign in without admin connection (until UI + block above are restored).
                    val botId = UUID.randomUUID().toString().take(8)
                    val apiKey = UUID.randomUUID().toString().replace("-", "").take(16)
                    prefs.id = botId
                    prefs.apiKey = apiKey
                    prefs.connectionCode = ""
                    prefs.databusAccessCode = ""
                    prefs.linkedAdminId = ""
                    prefs.linkedAdminName = ""
                    prefs.hasEverConnected = false
                    store.saveUser(email, password, LocalUserStore.ROLE_USER)
                    Toast.makeText(this, getString(R.string.sign_in_success), Toast.LENGTH_SHORT).show()
                    startActivity(
                        Intent(this, PinSetupActivity::class.java)
                            .putExtra(PinSetupActivity.EXTRA_NEXT_ROLE, LocalUserStore.ROLE_USER)
                    )
                    finish()
                }
            }
        }

        tvSignInAdmin.setOnClickListener {
            startActivity(Intent(this, AdminRegistrationActivity::class.java))
        }
    }
}
