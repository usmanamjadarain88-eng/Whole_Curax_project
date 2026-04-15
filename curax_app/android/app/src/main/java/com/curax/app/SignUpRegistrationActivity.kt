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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class SignUpRegistrationActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var etFirstName: TextInputEditText
    private lateinit var etLastName: TextInputEditText
    private lateinit var etEmail: TextInputEditText
    private lateinit var etPassword: TextInputEditText
    private lateinit var etConfirmPassword: TextInputEditText
    private lateinit var btnCreateAccount: MaterialButton

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sign_up_register)

        prefs = Prefs(this)

        etFirstName = findViewById(R.id.etFirstName)
        etLastName = findViewById(R.id.etLastName)
        etEmail = findViewById(R.id.etEmail)
        etPassword = findViewById(R.id.etPassword)
        etConfirmPassword = findViewById(R.id.etConfirmPassword)
        btnCreateAccount = findViewById(R.id.btnCreateAccount)

        findViewById<TextView>(R.id.tvSignInHere).setOnClickListener { finish() }

        fun syncBtn() {
            val ok = listOf(etFirstName, etLastName, etEmail, etPassword, etConfirmPassword).all {
                it.text?.toString()?.trim().orEmpty().isNotEmpty()
            }
            btnCreateAccount.isEnabled = ok
        }
        etFirstName.doOnTextChanged { _, _, _, _ -> syncBtn() }
        etLastName.doOnTextChanged { _, _, _, _ -> syncBtn() }
        etEmail.doOnTextChanged { _, _, _, _ -> syncBtn() }
        etPassword.doOnTextChanged { _, _, _, _ -> syncBtn() }
        etConfirmPassword.doOnTextChanged { _, _, _, _ -> syncBtn() }
        syncBtn()

        btnCreateAccount.setOnClickListener { onCreateAccountClicked() }
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

    private fun onCreateAccountClicked() {
        val first = etFirstName.text?.toString()?.trim().orEmpty()
        val last = etLastName.text?.toString()?.trim().orEmpty()
        val email = etEmail.text?.toString()?.trim().orEmpty()
        val password = etPassword.text?.toString()?.trim().orEmpty()
        val confirm = etConfirmPassword.text?.toString()?.trim().orEmpty()

        if (first.isBlank() || last.isBlank()) {
            Toast.makeText(this, getString(R.string.names_required), Toast.LENGTH_SHORT).show()
            return
        }
        if (email.isBlank() || password.isBlank()) {
            Toast.makeText(this, getString(R.string.sign_in_all_fields_required), Toast.LENGTH_SHORT).show()
            return
        }
        if (password.length < 6) {
            Toast.makeText(this, getString(R.string.password_min_length), Toast.LENGTH_SHORT).show()
            return
        }
        if (password != confirm) {
            Toast.makeText(this, getString(R.string.passwords_do_not_match), Toast.LENGTH_SHORT).show()
            return
        }
        val base = apiBase()
        if (base.isEmpty()) {
            Toast.makeText(this, getString(R.string.set_api_url_for_codes), Toast.LENGTH_LONG).show()
            return
        }
        hideKeyboard()
        val label = getString(R.string.create_account_button)
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
                    btnCreateAccount.isEnabled = true
                    btnCreateAccount.text = label
                    if (code == 200) {
                        startActivity(
                            Intent(this@SignUpRegistrationActivity, SignUpActivity::class.java)
                                .putExtra(SignUpActivity.EXTRA_START_AT_OTP, true)
                                .putExtra(SignUpActivity.EXTRA_EMAIL, email)
                                .putExtra(SignUpActivity.EXTRA_PASSWORD, password)
                                .putExtra(SignUpActivity.EXTRA_DISPLAY_NAME, displayName)
                        )
                        finish()
                    } else {
                        Toast.makeText(this@SignUpRegistrationActivity, messageFromResponse(jo), Toast.LENGTH_LONG).show()
                    }
                }
            } catch (_: Exception) {
                runOnUiThread {
                    btnCreateAccount.isEnabled = true
                    btnCreateAccount.text = label
                    Toast.makeText(this@SignUpRegistrationActivity, getString(R.string.request_failed), Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
