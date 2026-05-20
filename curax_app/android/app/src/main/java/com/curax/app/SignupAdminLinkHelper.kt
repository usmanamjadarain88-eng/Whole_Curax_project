package com.curax.app

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Shared completion path for /signup/link-admin and accepted /signup/link-request-status.
 */
object SignupAdminLinkHelper {

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    fun applyServerLinkSuccess(
        activity: AppCompatActivity,
        jo: JSONObject,
        connectionCode: String,
        email: String,
        password: String,
        botId: String,
        apiKey: String,
        http: OkHttpClient,
        nameForLinkFallback: String,
        onUserDataApplied: () -> Unit,
        onAuthRejected: (String) -> Unit,
    ) {
        val prefs = Prefs(activity)
        val store = LocalUserStore(activity)
        UserLogoutHelper.disconnectRealtimeTransport(activity)
        UserSessionIsolate.ensureSessionForBotId(activity, botId)
        prefs.awaitingAdminLinkApproval = false
        prefs.awaitingAdminChosenDisplayName = ""

        val adminId = when {
            jo.isNull("admin_id") -> ""
            else -> jo.get("admin_id").toString().trim()
        }
        val adminName = jo.optString("admin_name", "").trim()
        val databusAccessCode = jo.optString("databus_access_code", "").trim()
        val codeForPrefs = connectionCode.ifBlank { jo.optString("connection_code", "").trim() }

        prefs.id = botId
        prefs.apiKey = apiKey
        UserModeSheetPrefs.syncGlobalFlagFromBot(activity, botId)
        prefs.connectionCode = codeForPrefs
        prefs.databusAccessCode = databusAccessCode
        prefs.linkedAdminId = adminId
        prefs.linkedAdminName = adminName
        if (databusAccessCode.isNotEmpty()) {
            UserDataBusClient.reconnectFromPrefs(activity)
        }
        val fromServer = jo.optString("user_first_name", "").trim()
        val hubFirst = fromServer.ifBlank { UserNameFormatter.firstNameForHub(nameForLinkFallback) }
        if (hubFirst.isNotEmpty()) prefs.userHubFirstName = hubFirst

        val fromFull = jo.optString("user_full_name", "").trim()
        if (fromFull.isNotEmpty()) prefs.userHubFullName = fromFull

        val fromUsername = jo.optString("user_username", "").trim()
        if (fromUsername.isNotEmpty()) prefs.userHubUsername = fromUsername

        val dm = jo.optString("user_display_mode", "").trim().lowercase()
        if (!prefs.userInitialAppModeSheetCompleted) {
            if (dm == "standalone" || dm == "default") {
                AppModeManager.applyDisplayModeValueFromServer(
                    activity,
                    dm == "standalone",
                    notifyRelaunch = true,
                )
            }
        } else {
            UserDisplayModeApi.postDisplayModeAsync(activity, prefs.userStandaloneMode)
        }

        store.saveUser(email, password, LocalUserStore.ROLE_USER)
        SignUpFlowState.clear()
        prefs.clearSignupWipLink()

        val base = prefs.centralApiUrl.trim().removeSuffix("/")

        UserDataBusClient.fetchAndApplyUserData(
            activity,
            base,
            botId,
            apiKey,
            onSuccess = { onUserDataApplied() },
            onAuthRejected = { msg -> onAuthRejected(msg) },
        )
    }
}
