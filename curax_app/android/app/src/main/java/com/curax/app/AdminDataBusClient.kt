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
 * Real-time admin data sync client (Data Bus WebSocket).
 * Server sends { action: "data_sync", payload: {...same as GET /admin/data...} }.
 */
object AdminDataBusClient {
    private const val TAG = "AdminDataBusClient"

    /** Result of [fetchAdminSnapshotAsync]: applied, HTTP/parse failure, or skipped (prefs changed before apply). */
    enum class SnapshotResult { APPLIED, FAILED, SKIPPED_STALE }

    private var appContext: Context? = null
    private var client: OkHttpClient? = null
    private var ws: WebSocket? = null
    private var running = false
    private var currentAccessCode: String = ""
    private var currentWsUrl: String = ""
    private val mainHandler = Handler(Looper.getMainLooper())
    private var reconnectDelayMs = 3000L
    private var reconnectRunnable: Runnable? = null
    private val snapshotHttp = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    fun start(context: Context, accessCode: String, rawUrl: String) {
        val code = accessCode.trim()
        if (code.isEmpty()) return
        val url = toWsUrl(rawUrl)
        if (url.isEmpty()) return
        appContext = context.applicationContext
        currentAccessCode = code
        currentWsUrl = url
        running = true
        reconnectDelayMs = 3000L
        connect()
    }

    fun stop() {
        running = false
        cancelReconnect()
        try {
            ws?.close(1000, "stop")
        } catch (_: Exception) {}
        ws = null
        try {
            client?.dispatcher?.executorService?.shutdown()
        } catch (_: Exception) {}
        client = null
    }

    private fun connect() {
        if (!running || currentAccessCode.isEmpty() || currentWsUrl.isEmpty()) return
        cancelReconnect()
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
                reconnectDelayMs = 3000L
                val register = JSONObject().apply {
                    put("access_code", currentAccessCode)
                    put("client_type", "app")
                }
                webSocket.send(register.toString())
                Log.d(TAG, "Connected to data bus")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val obj = JSONObject(text)
                    val action = obj.optString("action", "")
                    if (action == "data_sync") {
                        val ctx = appContext ?: return
                        val prefs = Prefs(ctx)
                        // Data bus always fetches GET /admin/data without act_as_user_id (see databus server).
                        // If admin is managing a linked user, applying that payload would show admin's medicines, not the user's.
                        if (prefs.actAsUserId.isNotEmpty()) {
                            fetchAdminSnapshotAsync(ctx, null)
                            return
                        }
                        val payload = obj.optJSONObject("payload")
                        if (payload != null) {
                            mainHandler.post {
                                applyAdminDataJson(ctx, payload)
                                ctx.sendBroadcast(Intent(AlertEvents.ACTION_ADMIN_DATA_SYNCED))
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse data bus message", e)
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                ws = null
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                ws = null
                Log.w(TAG, "Data bus WS failure: ${t.message}")
                scheduleReconnect()
            }
        })
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
        return u.removeSuffix("/")
    }

    /**
     * GET /admin/data using stored adminAccessCode and optional actAsUserId.
     * Use this whenever the UI must match "manage user" mode; WebSocket pushes are admin-native only.
     */
    fun fetchAdminSnapshotAsync(context: Context, onComplete: ((SnapshotResult) -> Unit)? = null) {
        val app = context.applicationContext
        val prefs = Prefs(app)
        val base = prefs.centralApiUrl.trim().removeSuffix("/")
        val accessCode = prefs.adminAccessCode.trim()
        if (base.isEmpty() || accessCode.isEmpty()) {
            mainHandler.post { onComplete?.invoke(SnapshotResult.FAILED) }
            return
        }
        val actAsSnapshot = prefs.actAsUserId.trim()
        Thread {
            try {
                var url = "$base/admin/data?access_code=${URLEncoder.encode(accessCode, "UTF-8")}"
                if (actAsSnapshot.isNotEmpty()) {
                    url += "&act_as_user_id=${URLEncoder.encode(actAsSnapshot, "UTF-8")}"
                }
                val req = Request.Builder().url(url).get().build()
                val res = snapshotHttp.newCall(req).execute()
                if (res.isSuccessful) {
                    val body = res.body?.string() ?: "{}"
                    val json = JSONObject(body)
                    mainHandler.post {
                        val prefsNow = Prefs(app)
                        if (actAsSnapshot != prefsNow.actAsUserId.trim()) {
                            onComplete?.invoke(SnapshotResult.SKIPPED_STALE)
                            return@post
                        }
                        applyAdminDataJson(app, json)
                        app.sendBroadcast(Intent(AlertEvents.ACTION_ADMIN_DATA_SYNCED))
                        onComplete?.invoke(SnapshotResult.APPLIED)
                    }
                    return@Thread
                }
            } catch (e: Exception) {
                Log.w(TAG, "fetchAdminSnapshotAsync failed", e)
            }
            mainHandler.post { onComplete?.invoke(SnapshotResult.FAILED) }
        }.start()
    }

    /** Same shape as GET /admin/data or data_sync payload — used by HTTP refresh and WebSocket. */
    fun applyAdminDataJson(context: Context, data: JSONObject) {
        val prefs = Prefs(context)
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
}
