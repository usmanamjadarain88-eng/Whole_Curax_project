package com.curax.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import android.os.Handler
import android.os.Looper
import kotlin.math.min

class AlertConnectionService : Service() {

    private val binder = LocalBinder()
    private var webSocket: WebSocket? = null
    private var client: OkHttpClient? = null
    private var isConnecting = false

    private var lastServerUrl: String? = null
    private var lastBotId: String? = null
    private var lastApiKey: String? = null
    private var reconnectBackoffMs = 2000L
    private val handler = Handler(Looper.getMainLooper())
    private var reconnectRunnable: Runnable? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var screenOnReceiver: BroadcastReceiver? = null

    var onAlertReceived: ((type: String, message: String) -> Unit)? = null
    var onConnectionStateChanged: ((connected: Boolean) -> Unit)? = null

    inner class LocalBinder : Binder() {
        fun getService(): AlertConnectionService = this@AlertConnectionService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                val serverUrl = intent.getStringExtra(EXTRA_SERVER_URL) ?: return START_STICKY
                val botId = intent.getStringExtra(EXTRA_BOT_ID) ?: ""
                val apiKey = intent.getStringExtra(EXTRA_API_KEY) ?: ""
                lastServerUrl = serverUrl
                lastBotId = botId
                lastApiKey = apiKey
                reconnectBackoffMs = 2000L
                cancelReconnect()
                acquireWakeLock()
                startForeground(NOTIF_ID, createNotification(false))
                connect(serverUrl, botId, apiKey)
            }
            ACTION_UPDATE_FCM -> {
                val botId = intent.getStringExtra(EXTRA_BOT_ID) ?: lastBotId.orEmpty()
                val apiKey = intent.getStringExtra(EXTRA_API_KEY) ?: lastApiKey.orEmpty()
                val fcm = intent.getStringExtra(EXTRA_FCM_TOKEN).orEmpty()
                if (botId.isNotEmpty() && apiKey.isNotEmpty()) {
                    lastBotId = botId
                    lastApiKey = apiKey
                }
                if (fcm.isNotEmpty()) {
                    Prefs(this).fcmToken = fcm
                }
                if (isConnected() && botId.isNotEmpty() && apiKey.isNotEmpty()) {
                    sendRegister(botId, apiKey, Prefs(this).fcmToken)
                }
            }
            ACTION_DISCONNECT -> {
                lastServerUrl = null
                lastBotId = null
                lastApiKey = null
                cancelReconnect()
                disconnect()
                releaseWakeLock()
                unregisterScreenOnReceiver()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_RECONNECT_NOW -> {
                if (lastServerUrl != null && lastBotId != null && lastApiKey != null && !isConnected()) {
                    reconnectBackoffMs = 2000L
                    cancelReconnect()
                    handler.postDelayed({ connect(lastServerUrl!!, lastBotId!!, lastApiKey!!) }, 500L)
                }
            }
        }
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        registerScreenOnReceiver()
    }

    private fun cancelReconnect() {
        reconnectRunnable?.let { handler.removeCallbacks(it) }
        reconnectRunnable = null
    }

    private fun scheduleReconnect() {
        if (lastServerUrl == null || lastBotId == null || lastApiKey == null) return
        cancelReconnect()
        reconnectRunnable = Runnable {
            reconnectRunnable = null
            lastServerUrl?.let { url ->
                lastBotId?.let { bid ->
                    lastApiKey?.let { key ->
                        Log.d(TAG, "Auto-reconnect in ${reconnectBackoffMs}ms")
                        connect(url, bid, key)
                        reconnectBackoffMs = min(30000L, reconnectBackoffMs * 2)
                    }
                }
            }
        }
        handler.postDelayed(reconnectRunnable!!, reconnectBackoffMs)
    }

    private fun registerScreenOnReceiver() {
        if (screenOnReceiver != null) return
        screenOnReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != Intent.ACTION_SCREEN_ON) return
                if (lastServerUrl == null || lastBotId == null || lastApiKey == null) return
                if (isConnected()) return
                reconnectBackoffMs = 2000L
                cancelReconnect()
                handler.postDelayed({ connect(lastServerUrl!!, lastBotId!!, lastApiKey!!) }, 1000L)
                Log.d(TAG, "Screen on: reconnecting to relay so alerts stay connected")
            }
        }
        val filter = IntentFilter(Intent.ACTION_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenOnReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(screenOnReceiver, filter)
        }
    }

    private fun unregisterScreenOnReceiver() {
        try {
            screenOnReceiver?.let { unregisterReceiver(it) }
        } catch (_: Exception) {}
        screenOnReceiver = null
    }

    private fun buildWsUrl(serverUrl: String): String {
        var url = serverUrl.trim().lowercase()
        if (url.startsWith("http://")) url = "ws://" + url.removePrefix("http://")
        else if (url.startsWith("https://")) url = "wss://" + url.removePrefix("https://")
        else if (!url.startsWith("ws://") && !url.startsWith("wss://")) url = "wss://$url"
        return url.trimEnd('/')
    }

    private fun connect(serverUrl: String, botId: String, apiKey: String) {
        if (isConnecting) return
        disconnect()
        val wsUrl = buildWsUrl(serverUrl)
        isConnecting = true
        onConnectionStateChanged?.invoke(false)

        client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .pingInterval(25, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        val request = Request.Builder().url(wsUrl).build()
        webSocket = client!!.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                sendRegister(botId, apiKey, Prefs(this@AlertConnectionService).fcmToken)
                isConnecting = false
                reconnectBackoffMs = 2000L
                Log.d(TAG, "WebSocket connected; backend should store FCM for this bot_id + api_key")
                runOnMain { onConnectionStateChanged?.invoke(true) }
                runOnMain { updateNotification(true) }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "Alert: $text")
                try {
                    val obj = JSONObject(text)
                    if (obj.has("action") && obj.optString("action") == "alert") return
                    val type = obj.optString("type", "alert")
                    val message = obj.optString("message", text)
                    runOnMain {
                        onAlertReceived?.invoke(type, message)
                        updateNotification(true)
                    }
                } catch (_: Exception) {
                    runOnMain { onAlertReceived?.invoke("alert", text) }
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {}
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                isConnecting = false
                this@AlertConnectionService.webSocket = null
                runOnMain { onConnectionStateChanged?.invoke(false) }
                runOnMain { updateNotification(false) }
                if (lastServerUrl != null) scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                isConnecting = false
                this@AlertConnectionService.webSocket = null
                Log.e(TAG, "WebSocket error", t)
                runOnMain { onConnectionStateChanged?.invoke(false) }
                runOnMain { updateNotification(false) }
                if (lastServerUrl != null) scheduleReconnect()
            }
        })
    }

    private fun runOnMain(block: () -> Unit) {
        Handler(Looper.getMainLooper()).post(block)
    }

    private fun sendRegister(botId: String, apiKey: String, fcmToken: String) {
        try {
            val register = JSONObject().apply {
                put("bot_id", botId)
                put("api_key", apiKey)
                if (fcmToken.isNotEmpty()) put("fcm_token", fcmToken)
            }
            webSocket?.send(register.toString())
        } catch (_: Exception) {
        }
    }

    private fun disconnect() {
        try {
            webSocket?.close(1000, null)
        } catch (_: Exception) {
        }
        webSocket = null
        client = null
        isConnecting = false
        onConnectionStateChanged?.invoke(false)
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Curax:AlertConnectionWakeLock").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {
        }
        wakeLock = null
    }

    private fun createNotification(connected: Boolean): Notification {
        createChannel()
        val title = if (connected) "Curax - Linked" else "Curax - FCM active"
        val intent = Intent(this, LaunchActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP }
        val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(if (connected) "Receiving alerts by ID" else "Push alerts remain active")
            .setSmallIcon(R.drawable.ic_launcher_inset)
            .setContentIntent(pi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()
    }

    private fun updateNotification(connected: Boolean) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, createNotification(connected))
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            // Remove only the old legacy status channel; don't delete the active foreground channel.
            nm.deleteNotificationChannel("curax_alerts")
            val ch = NotificationChannel(CHANNEL_ID, "Curax Status", NotificationManager.IMPORTANCE_MIN).apply {
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            }
            nm.createNotificationChannel(ch)
        }
    }

    fun isConnected(): Boolean = webSocket != null && !isConnecting && client != null

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (lastServerUrl != null && lastBotId != null && lastApiKey != null) {
            val restart = Intent(this, AlertConnectionService::class.java).apply {
                action = ACTION_CONNECT
                putExtra(EXTRA_SERVER_URL, lastServerUrl)
                putExtra(EXTRA_BOT_ID, lastBotId)
                putExtra(EXTRA_API_KEY, lastApiKey)
            }
            startService(restart)
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        cancelReconnect()
        unregisterScreenOnReceiver()
        disconnect()
        releaseWakeLock()
        super.onDestroy()
    }

    companion object {
        const val TAG = "CuraxService"
        const val ACTION_CONNECT = "com.curax.app.CONNECT"
        const val ACTION_DISCONNECT = "com.curax.app.DISCONNECT"
        const val ACTION_RECONNECT_NOW = "com.curax.app.RECONNECT_NOW"
        const val ACTION_UPDATE_FCM = "com.curax.app.UPDATE_FCM"
        const val EXTRA_SERVER_URL = "server_url"
        const val EXTRA_BOT_ID = "bot_id"
        const val EXTRA_API_KEY = "api_key"
        const val EXTRA_FCM_TOKEN = "fcm_token"
        private const val CHANNEL_ID = "curax_status_channel"
        private const val NOTIF_ID = 1001
    }
}
