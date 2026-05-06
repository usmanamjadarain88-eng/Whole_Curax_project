package com.curax.app

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.ably.lib.realtime.AblyRealtime
import io.ably.lib.realtime.CompletionListener
import io.ably.lib.types.ClientOptions
import io.ably.lib.types.ErrorInfo
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
 * Real-time user data sync (self-hosted WebSocket or Ably when prefs Ably subscribe key is set).
 * On data_sync (backend called notify_admin), fetches GET /user/data once — no timers, no polling.
 */
object UserDataBusClient {
    private const val TAG = "UserDataBusClient"

    private var appContext: Context? = null
    private var client: OkHttpClient? = null
    private var ws: WebSocket? = null
    private var ablyRealtime: AblyRealtime? = null
    private var useAblyTransport = false
    private var currentAblyKey: String = ""
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
    /** True when transport is up (WebSocket onOpen or Ably channel attached). */
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
     * Start or refresh the data-bus connection (admin_access_code room).
     * If already connected with the same credentials, no-op (avoids dropping the socket on every resume).
     */
    fun start(context: Context, accessCode: String, rawUrl: String, apiBase: String, botId: String, apiKey: String) {
        val code = accessCode.trim()
        if (code.isEmpty()) return
        val appCtx = context.applicationContext
        val ablyKey = Prefs(appCtx).dataBusAblySubscribeKey.trim()
        val useAbly = ablyKey.isNotEmpty()
        val wsUrl = if (useAbly) "ably" else toWsUrl(rawUrl)
        if (!useAbly && wsUrl.isEmpty()) return
        val base = apiBase.trim().removeSuffix("/")
        if (base.isEmpty()) return
        if (botId.isBlank() || apiKey.isBlank()) return

        val bid = botId.trim()
        val key = apiKey.trim()
        synchronized(this) {
            if (running &&
                socketConnected &&
                currentAccessCode == code &&
                currentApiBase == base &&
                currentBotId == bid &&
                currentApiKey == key &&
                useAblyTransport == useAbly &&
                currentWsUrl == wsUrl &&
                currentAblyKey == ablyKey &&
                (ws != null || ablyRealtime != null)
            ) {
                Log.d(TAG, "Data bus already connected; skip restart")
                mainHandler.post {
                    appContext?.sendBroadcast(Intent(AlertEvents.ACTION_USER_DATABUS_SOCKET_STATE))
                }
                return
            }
        }

        appContext = appCtx
        currentAccessCode = code
        currentWsUrl = wsUrl
        currentApiBase = base
        currentBotId = bid
        currentApiKey = key
        useAblyTransport = useAbly
        currentAblyKey = if (useAbly) ablyKey else ""
        running = true
        reconnectDelayMs = 3000L
        connect()
    }

    fun stop() {
        mainHandler.removeCallbacks(apiFallbackRunnable)
        running = false
        socketConnected = false
        cancelReconnect()
        try {
            ablyRealtime?.close()
        } catch (_: Exception) {}
        ablyRealtime = null
        useAblyTransport = false
        currentAblyKey = ""
        try {
            ws?.close(1000, "stop")
        } catch (_: Exception) {}
        ws = null
        try {
            client?.dispatcher?.executorService?.shutdown()
        } catch (_: Exception) {}
        client = null
        fetchInFlight = false
    }

    private fun connect() {
        if (!running || currentAccessCode.isEmpty()) return
        if (!useAblyTransport && currentWsUrl.isEmpty()) return
        if (useAblyTransport) {
            connectAbly()
        } else {
            connectWebSocket()
        }
    }

    private fun connectAbly() {
        val ctx = appContext ?: return
        val subscribeKey = Prefs(ctx).dataBusAblySubscribeKey.trim()
        if (subscribeKey.isEmpty()) return
        cancelReconnect()
        synchronized(this) { socketConnected = false }
        try {
            ablyRealtime?.close()
        } catch (_: Exception) {}
        ablyRealtime = null
        try {
            val opts = ClientOptions()
            opts.key = subscribeKey
            val ably = AblyRealtime(opts)
            ablyRealtime = ably
            val channelName = "admin:${currentAccessCode.trim().uppercase()}"
            val channel = ably.channels.get(channelName, null)
            channel.subscribe { message ->
                try {
                    val raw = message.data ?: return@subscribe
                    val text = raw as? String ?: return@subscribe
                    val obj = JSONObject(text)
                    if (obj.optString("action", "") == "data_sync") {
                        triggerFetch()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse Ably data bus message", e)
                }
            }
            channel.attach(object : CompletionListener {
                override fun onSuccess() {
                    mainHandler.removeCallbacks(apiFallbackRunnable)
                    reconnectDelayMs = 3000L
                    synchronized(this@UserDataBusClient) { socketConnected = true }
                    Log.d(TAG, "Connected to data bus (Ably user_app room=$currentAccessCode)")
                    val actx = appContext
                    if (actx != null && !Prefs(actx).userStandaloneDataReady) {
                        triggerFetch()
                    }
                    mainHandler.post {
                        actx?.sendBroadcast(Intent(AlertEvents.ACTION_USER_DATABUS_SOCKET_STATE))
                    }
                }

                override fun onError(reason: ErrorInfo?) {
                    Log.w(TAG, "Ably channel attach failed: ${reason?.message}")
                    synchronized(this@UserDataBusClient) { socketConnected = false }
                    scheduleReconnect()
                    mainHandler.post {
                        appContext?.sendBroadcast(Intent(AlertEvents.ACTION_USER_DATABUS_SOCKET_STATE))
                    }
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "Ably connect failed: ${e.message}")
            scheduleReconnect()
        }
        val ctxNotify = appContext
        mainHandler.post {
            ctxNotify?.sendBroadcast(Intent(AlertEvents.ACTION_USER_DATABUS_SOCKET_STATE))
        }
    }

    private fun connectWebSocket() {
        if (!running || currentAccessCode.isEmpty() || currentWsUrl.isEmpty()) return
        cancelReconnect()
        synchronized(this) { socketConnected = false }
        try {
            ws?.close(1000, "reconnect")
        } catch (_: Exception) {}
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
                val actx = appContext
                if (actx != null && !Prefs(actx).userStandaloneDataReady) {
                    triggerFetch()
                }
                mainHandler.post {
                    actx?.sendBroadcast(Intent(AlertEvents.ACTION_USER_DATABUS_SOCKET_STATE))
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
                val actx = appContext
                mainHandler.post {
                    actx?.sendBroadcast(Intent(AlertEvents.ACTION_USER_DATABUS_SOCKET_STATE))
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                synchronized(this@UserDataBusClient) { socketConnected = false }
                ws = null
                Log.w(TAG, "Data bus WS failure: ${t.message}")
                scheduleReconnect()
                val actx = appContext
                mainHandler.post {
                    actx?.sendBroadcast(Intent(AlertEvents.ACTION_USER_DATABUS_SOCKET_STATE))
                }
            }
        })
        val ctxNotify = appContext
        mainHandler.post {
            ctxNotify?.sendBroadcast(Intent(AlertEvents.ACTION_USER_DATABUS_SOCKET_STATE))
        }
    }

    private fun triggerFetch() {
        synchronized(this) {
            if (fetchInFlight) {
                pendingFetch = true
                return
            }
            fetchInFlight = true
        }
        appContext?.let { ctx ->
            mainHandler.post {
                ctx.sendBroadcast(Intent(AlertEvents.ACTION_USER_STANDALONE_DATA_FETCH_STARTED))
            }
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
                appContext?.let { ctx ->
                    mainHandler.post {
                        ctx.sendBroadcast(Intent(AlertEvents.ACTION_USER_STANDALONE_DATA_FETCH_ENDED))
                    }
                }
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
                return
            }
            fetchInFlight = true
        }
        mainHandler.post {
            context.applicationContext.sendBroadcast(Intent(AlertEvents.ACTION_USER_STANDALONE_DATA_FETCH_STARTED))
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
                mainHandler.post {
                    context.applicationContext.sendBroadcast(Intent(AlertEvents.ACTION_USER_STANDALONE_DATA_FETCH_ENDED))
                }
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
        if (serverTime.isNotEmpty()) {
            val before = prefs.lastSyncTime.trim()
            prefs.lastSyncTime = serverTime
            if (serverTime != before) {
                HealthHubHistoryStore.appendSyncEvent(ctx.applicationContext, serverTime)
            }
        }

        val ufn = data.optString("user_first_name", "").trim()
        if (ufn.isNotEmpty()) prefs.userHubFirstName = ufn

        val ufull = data.optString("user_full_name", "").trim()
        if (ufull.isNotEmpty()) prefs.userHubFullName = ufull

        val uuname = data.optString("user_username", "").trim()
        if (uuname.isNotEmpty()) prefs.userHubUsername = uuname

        val pic = data.optString("profile_picture", "").trim()
        prefs.userProfilePictureDataUrl = pic

        val dm = data.optString("user_display_mode", "").trim().lowercase()
        if (dm == "standalone" || dm == "default") {
            val wantStandalone = dm == "standalone"
            val was = prefs.userStandaloneMode
            if (wantStandalone != was) {
                AppModeManager.setStandaloneMode(ctx, wantStandalone)
                ctx.sendBroadcast(Intent(AlertEvents.ACTION_USER_DISPLAY_MODE_FROM_SERVER))
            }
        }

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
        DoseTrackingLocalStore.mergeFromPayloadArray(ctx, data.optJSONArray("dose_log"))
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

    /** True when the data-bus session is up (WebSocket or Ably channel). */
    fun isSocketConnected(): Boolean = synchronized(this) {
        running && socketConnected && (ws != null || ablyRealtime != null)
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
