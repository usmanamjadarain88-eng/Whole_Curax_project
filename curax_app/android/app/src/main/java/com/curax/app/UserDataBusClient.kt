package com.curax.app

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Real-time user data sync (Data Bus WebSocket).
 * Register with the admin's **admin_access_code** (same room as desktop notify_admin), not connection_code.
 * On data_sync (backend called notify_admin), fetches GET /user/data once — no timers, no polling.
 */
object UserDataBusClient {
    private const val TAG = "UserDataBusClient"

    private var appContext: Context? = null
    private var client: OkHttpClient? = null
    private var ws: WebSocket? = null
    private var running = false
    private var currentAccessCode: String = ""
    private var currentWsUrl: String = ""
    private var currentApiBase: String = ""
    private var currentBotId: String = ""
    private var currentApiKey: String = ""
    private var fetchInFlight = false
    private var pendingFetch = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val snapshotHttp = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    private var reconnectDelayMs = 3000L
    private var reconnectRunnable: Runnable? = null
    /** True between WebSocket onOpen and onClosed/onFailure. */
    private var socketConnected = false

    private val apiFallbackRunnable = Runnable {
        if (isSocketConnected()) return@Runnable
        val ctx = appContext ?: return@Runnable
        val p = Prefs(ctx)
        val base = p.centralApiUrl.trim().removeSuffix("/")
        val bid = p.id.trim()
        val key = p.apiKey.trim()
        if (base.isEmpty() || bid.isEmpty() || key.isEmpty()) return@Runnable
        fetchAndApplyUserData(ctx, base, bid, key, null)
    }
    @Volatile
    private var onUserDataApplied: (() -> Unit)? = null

    fun setOnUserDataAppliedListener(listener: (() -> Unit)?) {
        onUserDataApplied = listener
    }

    private fun persistUserSnapshot(ctx: Context, data: JSONObject) {
        val prefs = Prefs(ctx)
        prefs.cachedUserDataSnapshotJson = data.toString()
        prefs.userStandaloneDataReady = true
    }

    fun restoreCachedUserData(context: Context): Boolean {
        val cached = Prefs(context).cachedUserDataSnapshotJson.trim()
        if (cached.isEmpty()) return false
        return try {
            val data = JSONObject(cached)
            applyUserPayload(context.applicationContext, data)
            Prefs(context).userStandaloneDataReady = true
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Start or refresh the data-bus WebSocket (admin_access_code room).
     * If already connected with the same credentials, no-op (avoids dropping the socket on every resume).
     */
    fun start(context: Context, accessCode: String, rawUrl: String, apiBase: String, botId: String, apiKey: String) {
        val code = accessCode.trim()
        if (code.isEmpty()) return
        val wsUrl = toWsUrl(rawUrl)
        if (wsUrl.isEmpty()) return
        val base = apiBase.trim().removeSuffix("/")
        if (base.isEmpty()) return
        if (botId.isBlank() || apiKey.isBlank()) return

        val bid = botId.trim()
        val key = apiKey.trim()
        synchronized(this) {
            if (running &&
                socketConnected &&
                ws != null &&
                currentAccessCode == code &&
                currentWsUrl == wsUrl &&
                currentApiBase == base &&
                currentBotId == bid &&
                currentApiKey == key
            ) {
                Log.d(TAG, "Data bus already connected; skip restart")
                return
            }
        }

        appContext = context.applicationContext
        currentAccessCode = code
        currentWsUrl = wsUrl
        currentApiBase = base
        currentBotId = bid
        currentApiKey = key
        running = true
        reconnectDelayMs = 3000L
        connect()
    }

    fun stop() {
        mainHandler.removeCallbacks(apiFallbackRunnable)
        running = false
        socketConnected = false
        cancelReconnect()
        try { ws?.close(1000, "stop") } catch (_: Exception) {}
        ws = null
        try { client?.dispatcher?.executorService?.shutdown() } catch (_: Exception) {}
        client = null
        fetchInFlight = false
    }

    private fun connect() {
        if (!running || currentAccessCode.isEmpty() || currentWsUrl.isEmpty()) return
        cancelReconnect()
        synchronized(this) { socketConnected = false }
        try { ws?.close(1000, "reconnect") } catch (_: Exception) {}
        ws = null

        if (client == null) {
            client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .pingInterval(25, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }
        val req = Request.Builder().url(currentWsUrl).build()
        ws = client!!.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                mainHandler.removeCallbacks(apiFallbackRunnable)
                reconnectDelayMs = 3000L
                synchronized(this@UserDataBusClient) { socketConnected = true }
                val register = JSONObject().apply {
                    put("access_code", currentAccessCode)
                    put("client_type", "user_app")
                }
                webSocket.send(register.toString())
                Log.d(TAG, "Connected to data bus (user_app room=$currentAccessCode, ws=$currentWsUrl)")
                val ctx = appContext
                if (ctx != null && !Prefs(ctx).userStandaloneDataReady) {
                    // First open for a fresh install: pull one snapshot so cache can be seeded.
                    triggerFetch()
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val obj = JSONObject(text)
                    val action = obj.optString("action", "")
                    if (action == "data_sync") {
                        triggerFetch()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse data bus message", e)
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                synchronized(this@UserDataBusClient) { socketConnected = false }
                ws = null
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                synchronized(this@UserDataBusClient) { socketConnected = false }
                ws = null
                Log.w(TAG, "Data bus WS failure: ${t.message}")
                scheduleReconnect()
            }
        })
    }

    private fun triggerFetch() {
        synchronized(this) {
            if (fetchInFlight) {
                pendingFetch = true
                return
            }
            fetchInFlight = true
        }
        Thread {
            try {
                val url = "$currentApiBase/user/data?bot_id=${java.net.URLEncoder.encode(currentBotId, "UTF-8")}&api_key=${java.net.URLEncoder.encode(currentApiKey, "UTF-8")}"
                snapshotHttp.newCall(Request.Builder().url(url).get().build()).execute().use { res ->
                    if (res.isSuccessful) {
                        val body = res.body?.string() ?: "{}"
                        val data = JSONObject(body)
                        val ctx = appContext
                        if (ctx != null) {
                            mainHandler.post {
                                applyUserPayload(ctx, data)
                                persistUserSnapshot(ctx, data)
                                onUserDataApplied?.invoke()
                                ctx.sendBroadcast(Intent(AlertEvents.ACTION_ADMIN_DATA_SYNCED))
                            }
                        }
                        Unit
                    } else {
                        Log.w(TAG, "user/data fetch failed: HTTP ${res.code}")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch user data", e)
            } finally {
                synchronized(this) {
                    fetchInFlight = false
                    if (pendingFetch) {
                        pendingFetch = false
                        mainHandler.post { triggerFetch() }
                    }
                }
            }
        }.start()
    }

    /**
     * One-time GET /user/data and apply to AdminDemoData + broadcast.
     * Call when entering standalone so existing backend data shows immediately (no wait for socket).
     * Uses same fetchInFlight lock as triggerFetch to avoid race with socket data_sync.
     */
    fun fetchAndApplyUserData(
        context: Context,
        apiBase: String,
        botId: String,
        apiKey: String,
        onComplete: (() -> Unit)? = null
    ) {
        val base = apiBase.trim().removeSuffix("/")
        val bid = botId.trim()
        val key = apiKey.trim()
        if (base.isEmpty() || bid.isEmpty() || key.isEmpty()) return
        var completionCalled = false
        fun completeOnce() {
            if (completionCalled) return
            completionCalled = true
            onComplete?.invoke()
        }
        synchronized(this) {
            if (fetchInFlight) {
                // Socket is already fetching; it will apply data, so skip bootstrap to avoid race.
                return
            }
            fetchInFlight = true
        }
        Thread {
            try {
                val url = "$base/user/data?bot_id=${URLEncoder.encode(bid, "UTF-8")}&api_key=${URLEncoder.encode(key, "UTF-8")}"
                snapshotHttp.newCall(Request.Builder().url(url).get().build()).execute().use { res ->
                    if (res.isSuccessful) {
                        val body = res.body?.string() ?: "{}"
                        val data = JSONObject(body)
                        mainHandler.post {
                            applyUserPayload(context.applicationContext, data)
                            persistUserSnapshot(context.applicationContext, data)
                            onUserDataApplied?.invoke()
                            context.sendBroadcast(Intent(AlertEvents.ACTION_ADMIN_DATA_SYNCED))
                            completeOnce()
                        }
                    } else {
                        Log.w(TAG, "Bootstrap user/data fetch failed: HTTP ${res.code}")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Bootstrap fetch user data failed", e)
            } finally {
                synchronized(this) {
                    fetchInFlight = false
                    if (pendingFetch) {
                        pendingFetch = false
                        mainHandler.post { triggerFetch() }
                    }
                }
                mainHandler.post { completeOnce() }
            }
        }.start()
    }

    private fun applyUserPayload(ctx: Context, data: JSONObject) {
        val prefs = Prefs(ctx)
        val serverTime = data.optString("server_time", "").trim()
        if (serverTime.isNotEmpty()) prefs.lastSyncTime = serverTime

        val medicinesArray = data.optJSONArray("medicines") ?: JSONArray()
        val list = mutableListOf<Map<String, Any?>>()
        for (i in 0 until medicinesArray.length()) {
            val o = medicinesArray.optJSONObject(i) ?: continue
            val m = mutableMapOf<String, Any?>()
            m["name"] = o.optString("name")
            m["box_id"] = o.optString("box_id")
            m["dosage"] = o.optString("dosage")
            m["low_stock"] = o.optInt("low_stock", 5)
            m["quantity"] = o.optInt("quantity", 0)
            m["expiry"] = o.optString("expiry")
            m["exact_time"] = o.optString("exact_time")
            m["dose_per_day"] = o.optInt("dose_per_day", 0)
            m["instructions"] = o.optString("instructions")
            val times = o.optJSONArray("times")
            m["times"] = if (times != null) (0 until times.length()).map { times.optString(it) } else emptyList<String>()
            list.add(m)
        }
        AdminDemoData.replaceMedicines(AdminDemoData.fromApiMedicines(list))
        AdminDemoData.replaceApiAlerts(AdminDemoData.fromApiAlerts(data.optJSONArray("alerts")))
        AdminDemoData.replaceMedicalReminders(AdminDemoData.fromApiMedicalReminders(data.optJSONObject("medical_reminders")))
        AdminDemoData.replaceAlertSettings(AdminDemoData.fromApiAlertSettings(data.optJSONObject("alert_settings")))
    }

    private fun scheduleReconnect() {
        if (!running) return
        cancelReconnect()
        reconnectRunnable = Runnable {
            reconnectRunnable = null
            connect()
            reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(60000L)
        }
        mainHandler.postDelayed(reconnectRunnable!!, reconnectDelayMs)
    }

    private fun cancelReconnect() {
        reconnectRunnable?.let { mainHandler.removeCallbacks(it) }
        reconnectRunnable = null
    }

    private fun toWsUrl(raw: String): String {
        var u = raw.trim()
        if (u.isEmpty()) return ""
        if (u.startsWith("https://")) u = "wss://" + u.removePrefix("https://")
        else if (u.startsWith("http://")) u = "ws://" + u.removePrefix("http://")
        else if (!u.startsWith("wss://") && !u.startsWith("ws://")) u = "wss://$u"
        u = u.removeSuffix("/")
        if (u.endsWith("/notify_admin")) {
            val base = u.removeSuffix("/notify_admin")
            return base
        }
        return u
    }

    /** True when the data-bus WebSocket session is up (see [start]). */
    fun isSocketConnected(): Boolean = synchronized(this) {
        running && socketConnected && ws != null
    }

    /** True after [start] until [stop] (socket may still be handshaking or reconnecting). */
    fun isDataBusRunning(): Boolean = synchronized(this) { running }

    /** 0 = socket up, 1 = reconnecting (client running, socket down), 2 = off. */
    fun getRealtimeConnectionState(): Int = synchronized(this) {
        when {
            socketConnected -> 0
            running -> 1
            else -> 2
        }
    }

    /**
     * If the socket is still down after [delayMs], runs one [fetchAndApplyUserData] using prefs
     * (same snapshot path as bootstrap). Cancelled when the socket opens.
     */
    fun scheduleApiFallbackIfDataBusOffline(context: Context, delayMs: Long = 5000L) {
        appContext = context.applicationContext
        mainHandler.removeCallbacks(apiFallbackRunnable)
        mainHandler.postDelayed(apiFallbackRunnable, delayMs)
    }
}
