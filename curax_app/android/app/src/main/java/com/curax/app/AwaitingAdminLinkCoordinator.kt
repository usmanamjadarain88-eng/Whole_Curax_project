package com.curax.app

import android.content.Intent
import android.net.Uri
import androidx.appcompat.app.AppCompatActivity
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * While [Prefs.awaitingAdminLinkApproval] is true, periodically checks
 * [GET /signup/link-request-status] and finalizes the session when the admin accepts.
 */
object AwaitingAdminLinkCoordinator {

    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(22, TimeUnit.SECONDS)
        .writeTimeout(22, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var lastPollElapsedRealtimeMs = 0L

    fun pollIfNeeded(activity: AppCompatActivity) {
        val prefs = Prefs(activity)
        if (!prefs.awaitingAdminLinkApproval) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastPollElapsedRealtimeMs < 4_000L) return
        lastPollElapsedRealtimeMs = now

        val store = LocalUserStore(activity)
        val email = store.email.trim()
        val botId = prefs.id.trim()
        val apiKey = prefs.apiKey.trim()
        val base = prefs.centralApiUrl.trim().removeSuffix("/")
        if (email.isEmpty() || botId.isEmpty() || apiKey.isEmpty() || base.isEmpty()) return

        Thread {
            try {
                val q =
                    "/signup/link-request-status?email=${Uri.encode(email)}&bot_id=${Uri.encode(botId)}&api_key=${Uri.encode(apiKey)}"
                val req = Request.Builder().url("$base$q").get().build()
                http.newCall(req).execute().use { res ->
                    val raw = res.body?.string().orEmpty()
                    val jo = ApiErrorMessages.parseResponseBody(raw, res.code)
                    if (res.code != 200 || jo == null || jo.optString("status") != "accepted") return@use
                    val snapshot = JSONObject(jo.toString())
                    val pwd = store.password
                    if (activity.isFinishing) return@use
                    SignupAdminLinkHelper.applyServerLinkSuccess(
                        activity,
                        snapshot,
                        snapshot.optString("connection_code", "").trim(),
                        email,
                        pwd,
                        botId,
                        apiKey,
                        http,
                        nameForLinkFallback = email,
                        onUserDataApplied = {
                            CuraxFeedback.success(
                                activity,
                                activity.getString(R.string.linked_to_admin_success),
                            )
                            activity.sendBroadcast(Intent(AlertEvents.ACTION_CONNECTION_STATE_CHANGED))
                            if (activity is UserStandaloneActivity) {
                                activity.applyAdminLinkAcceptedUiRefresh()
                            }
                            },
                            onAuthRejected = { msg ->
                                UserLogoutHelper.clearLocalSession(activity)
                                CuraxFeedback.warn(
                                    activity,
                                    msg.ifBlank { activity.getString(R.string.account_removed_by_admin) },
                                    long = true,
                                )
                            },
                    )
                }
            } catch (_: Exception) {
            }
        }.start()
    }
}
