package com.curax.app

import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class CuraxFirebaseMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "FCM token: $token")

        val prefs = Prefs(this)
        prefs.fcmToken = token

        // Push new token to backend so alerts keep flowing via FCM (admin and user rows).
        pushFcmTokenToBackend(prefs, token)

        // Re-register latest token with relay immediately if connection settings are available.
        val serverUrl = prefs.serverUrl.trim()
        val id = prefs.id.trim()
        val apiKey = prefs.apiKey.trim()
        if (serverUrl.isNotEmpty() && id.isNotEmpty() && apiKey.isNotEmpty()) {
            val intent = Intent(this, AlertConnectionService::class.java).apply {
                action = AlertConnectionService.ACTION_UPDATE_FCM
                putExtra(AlertConnectionService.EXTRA_SERVER_URL, serverUrl)
                putExtra(AlertConnectionService.EXTRA_BOT_ID, id)
                putExtra(AlertConnectionService.EXTRA_API_KEY, apiKey)
                putExtra(AlertConnectionService.EXTRA_FCM_TOKEN, token)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            Log.d(TAG, "Relay token re-register requested")
        }
    }

    private fun pushFcmTokenToBackend(prefs: Prefs, fcmToken: String) {
        val base = prefs.centralApiUrl.trim().removeSuffix("/")
        val botId = prefs.id.trim()
        val apiKey = prefs.apiKey.trim()
        if (base.isEmpty() || botId.isEmpty() || apiKey.isEmpty() || fcmToken.isEmpty()) return
        Thread {
            try {
                val body = JSONObject().apply {
                    put("bot_id", botId)
                    put("api_key", apiKey)
                    put("fcm_token", fcmToken)
                }
                if (prefs.adminAccessCode.trim().isNotEmpty()) {
                    body.put("role", "admin")
                    body.put("access_code", prefs.adminAccessCode.trim())
                } else if (prefs.linkedAdminId.trim().isNotEmpty()) {
                    body.put("role", "user")
                    body.put("admin_id", prefs.linkedAdminId.trim())
                } else return@Thread
                val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).build()
                val req = Request.Builder()
                    .url("$base/save-credentials")
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                client.newCall(req).execute()
                Log.d(TAG, "FCM token pushed to backend")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to push FCM token to backend", e)
            }
        }.start()
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        val data = remoteMessage.data
        val type = data["type"] ?: "alert"
        val message = data["message"] ?: remoteMessage.notification?.body ?: "New alert"
        Log.d(TAG, "FCM alert: type=$type message=$message")
        var wakeLock: PowerManager.WakeLock? = null
        try {
            // Wake device so full-screen intent can turn screen on and user sees alert even when screen was off
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "Curax:FCMAlert"
            ).apply {
                setReferenceCounted(false)
                acquire(15_000L) // Hold so full-screen intent can fire and turn screen on
            }
            val db = AlertDb(this)
            val alertId = db.insertAlert(type, message)

            sendBroadcast(Intent(AlertEvents.ACTION_ALERTS_UPDATED))

            // Always show notification with full-screen intent so alert reaches user when screen is off / app inactive
            NotificationHelper.showAlertNotification(
                this,
                notificationId = alertId.toInt(),
                alertId = alertId,
                type = type,
                message = message
            )
        } catch (e: Exception) {
            Log.e(TAG, "FCM handle error", e)
        } finally {
            try {
                wakeLock?.let { if (it.isHeld) it.release() }
            } catch (_: Exception) {}
        }
    }

    companion object {
        private const val TAG = "CuraxFCM"
    }
}
