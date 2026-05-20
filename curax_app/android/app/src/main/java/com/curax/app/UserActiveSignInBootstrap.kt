package com.curax.app

import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.snackbar.Snackbar
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** Finish active user sign-in after email OTP (shared by SignInActivity and SignUpActivity). */
object UserActiveSignInBootstrap {

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    fun complete(
        activity: AppCompatActivity,
        jo: JSONObject,
        email: String,
        password: String,
        base: String,
        http: OkHttpClient,
        prefs: Prefs,
        store: LocalUserStore,
        onNavigateHome: () -> Unit,
    ) {
        val botId = jo.optString("bot_id", "").trim()
        val apiKey = jo.optString("api_key", "").trim()
        if (botId.isEmpty() || apiKey.isEmpty()) {
            CuraxFeedback.warn(activity, activity.getString(R.string.request_failed), long = true)
            return
        }

        UserLogoutHelper.disconnectRealtimeTransport(activity)
        UserSessionIsolate.ensureSessionForBotId(activity, botId)
        UserSessionIsolate.clearUserPresentationPrefs(activity)
        prefs.relayAutoConnectEnabled = false
        prefs.hasRequestedConnectWakePermissions = false

        prefs.id = botId
        prefs.apiKey = apiKey
        UserModeSheetPrefs.syncGlobalFlagFromBot(activity, botId)
        prefs.connectionCode = jo.optString("connection_code", "").trim()
        prefs.databusAccessCode = jo.optString("databus_access_code", "").trim()
        prefs.linkedAdminId = jo.optString("admin_id", "").trim()
        prefs.linkedAdminName = jo.optString("admin_name", "").trim()

        store.saveUser(email, password, LocalUserStore.ROLE_USER)
        SignUpFlowState.clear()
        prefs.clearSignupWipLink()

        AppModeManager.applyDisplayModeFromAuthJson(activity, jo, notifyRelaunch = false)

        val adminId = prefs.linkedAdminId.trim()
        if (adminId.isNotEmpty()) {
            Thread {
                try {
                    val body = JSONObject().apply {
                        put("bot_id", botId)
                        put("api_key", apiKey)
                        put("role", "user")
                        put("admin_id", adminId)
                        put("timezone", java.util.TimeZone.getDefault().id)
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

        var shellReadyPosted = false
        fun postSignInSuccessThenHome() {
            if (shellReadyPosted) return
            shellReadyPosted = true
            activity.runOnUiThread {
                if (activity.isFinishing) return@runOnUiThread
                CuraxFeedback.successThen(
                    activity,
                    R.string.sign_in_success,
                    delayMs = 380L,
                    snackbarDuration = Snackbar.LENGTH_SHORT,
                ) {
                    onNavigateHome()
                }
            }
        }

        UserDataBusClient.fetchAndApplyUserData(
            activity,
            base,
            botId,
            apiKey,
            broadcastFetchUi = false,
            notifyDisplayModeChange = false,
            onSuccess = { postSignInSuccessThenHome() },
            onFetchFinished = {
                activity.runOnUiThread {
                    if (shellReadyPosted || activity.isFinishing) return@runOnUiThread
                    CuraxFeedback.warn(
                        activity,
                        activity.getString(R.string.request_failed),
                        long = true,
                    )
                }
            },
            onAuthRejected = { msg ->
                activity.runOnUiThread {
                    UserLogoutHelper.clearLocalSession(activity)
                    CuraxFeedback.warn(
                        activity,
                        msg.ifBlank { activity.getString(R.string.account_removed_by_admin) },
                        long = true,
                    )
                }
            },
        )
    }
}
