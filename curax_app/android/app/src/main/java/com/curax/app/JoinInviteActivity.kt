package com.curax.app

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Entry for invite deep links — stores code and routes to sign-in or link flow. */
class JoinInviteActivity : AppCompatActivity() {

    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(22, TimeUnit.SECONDS)
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_join_invite)
        val code = CuraxInviteLink.parseConnectionCode(intent?.data)?.trim().orEmpty()
        if (code.isEmpty()) {
            CuraxFeedback.warn(this, getString(R.string.invite_link_invalid))
            finishToHomeOrSignIn()
            return
        }
        val prefs = Prefs(this)
        prefs.pendingInviteConnectionCode = code
        val store = LocalUserStore(this)

        when {
            !store.hasUser() -> {
                startActivity(
                    Intent(this, SignInActivity::class.java).apply {
                        putExtra(SignInActivity.EXTRA_FROM_INVITE_LINK, true)
                    },
                )
                finish()
            }
            store.role == LocalUserStore.ROLE_ADMIN -> {
                CuraxFeedback.warn(this, getString(R.string.invite_link_admin_use_patient_app))
                finish()
            }
            prefs.linkedAdminId.isNotEmpty() -> {
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.invite_link_already_linked_title)
                    .setMessage(getString(R.string.invite_link_already_linked_body, prefs.linkedAdminName))
                    .setPositiveButton(android.R.string.ok, null)
                    .setOnDismissListener { finish() }
                    .show()
            }
            else -> confirmAndLinkLoggedInUser(code)
        }
    }

    private fun confirmAndLinkLoggedInUser(code: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.invite_link_connect_title)
            .setMessage(getString(R.string.invite_link_connect_body, code))
            .setPositiveButton(R.string.connect) { _, _ -> linkLoggedInUser(code) }
            .setNegativeButton(R.string.cancel) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun linkLoggedInUser(code: String) {
        val prefs = Prefs(this)
        val store = LocalUserStore(this)
        val email = store.email.trim()
        val password = store.password
        val botId = prefs.id.trim()
        val apiKey = prefs.apiKey.trim()
        val base = prefs.centralApiUrl.trim().removeSuffix("/")
        if (email.isEmpty() || botId.isEmpty() || apiKey.isEmpty() || base.isEmpty()) {
            CuraxFeedback.warn(this, getString(R.string.invite_link_need_sign_in))
            finishToHomeOrSignIn()
            return
        }
        Thread {
            var errMsg = getString(R.string.request_failed)
            try {
                val json = JSONObject().apply {
                    put("email", email)
                    put("connection_code", code)
                    put("bot_id", botId)
                    put("api_key", apiKey)
                    put("name", prefs.userHubFullName.ifBlank { email })
                }
                val body = json.toString().toRequestBody("application/json".toMediaType())
                val res = http.newCall(
                    Request.Builder().url("$base/signup/link-admin").post(body).build(),
                ).execute()
                val jo = try {
                    JSONObject(res.body?.string().orEmpty())
                } catch (_: Exception) {
                    JSONObject()
                }
                if (res.isSuccessful) {
                    runOnUiThread {
                        SignupAdminLinkHelper.applyServerLinkSuccess(
                            this,
                            jo,
                            code,
                            email,
                            password,
                            botId,
                            apiKey,
                            http,
                            prefs.userHubFullName.ifBlank { email },
                            onUserDataApplied = {
                                prefs.pendingInviteConnectionCode = ""
                                CuraxFeedback.success(this, getString(R.string.invite_link_connected_ok))
                                startActivity(UserHomeIntent.forSignedInUser(this))
                                finish()
                            },
                            onAuthRejected = { msg ->
                                CuraxFeedback.warn(this, msg, long = true)
                                finish()
                            },
                        )
                    }
                    return@Thread
                }
                errMsg = ApiErrorMessages.userMessage(this, res.code, jo)
            } catch (_: Exception) {
                errMsg = getString(R.string.error_network_unreachable)
            }
            runOnUiThread {
                CuraxFeedback.warn(this, errMsg, long = true)
                finish()
            }
        }.start()
    }

    private fun finishToHomeOrSignIn() {
        val store = LocalUserStore(this)
        if (store.hasUser() && store.role == LocalUserStore.ROLE_USER) {
            startActivity(UserHomeIntent.forSignedInUser(this))
        } else {
            startActivity(Intent(this, SignInActivity::class.java))
        }
        finish()
    }
}
