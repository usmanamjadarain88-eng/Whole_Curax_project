package com.curax.app

import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Central entry points for **alert relay** ([AlertConnectionService]) intents.
 * Authoritative connected state for UI remains the bound [AlertConnectionService] when available;
 * [AlertConnectionService.relayConnectedHint] is a best-effort mirror for diagnostics or quick checks.
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
