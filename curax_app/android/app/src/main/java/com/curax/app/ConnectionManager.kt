package com.curax.app

import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Alert relay = live WebSocket to [RELAY_URL] (e.g. curax-relay.onrender.com).
 * Register with bot_id + api_key; server pushes {type, message} JSON for popups.
 * Not used for routine dose times (those are local AlarmManager alarms).
 */
object ConnectionManager {

    fun requestConnectRelay(context: Context, serverUrl: String, botId: String, apiKey: String) {
        val app = context.applicationContext
        val intent = Intent(app, AlertConnectionService::class.java).apply {
            action = AlertConnectionService.ACTION_CONNECT
            putExtra(AlertConnectionService.EXTRA_SERVER_URL, serverUrl)
            putExtra(AlertConnectionService.EXTRA_BOT_ID, botId)
            putExtra(AlertConnectionService.EXTRA_API_KEY, apiKey)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            app.startForegroundService(intent)
        } else {
            app.startService(intent)
        }
    }

    fun requestDisconnectRelay(context: Context) {
        context.applicationContext.startService(
            Intent(context.applicationContext, AlertConnectionService::class.java).apply {
                action = AlertConnectionService.ACTION_DISCONNECT
            },
        )
    }

    fun requestReconnectRelayNow(context: Context) {
        val app = context.applicationContext
        val intent = Intent(app, AlertConnectionService::class.java).apply {
            action = AlertConnectionService.ACTION_RECONNECT_NOW
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            app.startForegroundService(intent)
        } else {
            app.startService(intent)
        }
    }

    /** User/admin home resume: full relay connect from saved credentials after first Connect flow. */
    fun ensureRelayLiveOnAppOpen(context: Context) {
        val prefs = Prefs(context.applicationContext)
        if (!RelayAutoConnect.shouldAutoRestore(prefs)) return
        requestConnectRelay(context, prefs.serverUrl.trim(), prefs.id.trim(), prefs.apiKey.trim())
    }

    /** Best-effort; prefer service binder [AlertConnectionService.isConnected] in activities. */
    fun isRelayConnectedHint(): Boolean = AlertConnectionService.relayConnectedHint
}
