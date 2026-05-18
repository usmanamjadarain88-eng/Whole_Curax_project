package com.curax.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder

/**
 * After the user completes first Connect, every app open restores relay + UI (even if they
 * tapped Disconnect earlier in the same install).
 */
object RelayAutoConnect {

    fun shouldAutoRestore(prefs: Prefs): Boolean {
        if (!prefs.relayAutoConnectEnabled) return false
        if (prefs.id.trim().isEmpty() || prefs.apiKey.trim().isEmpty()) return false
        if (prefs.serverUrl.trim().isEmpty()) return false
        return true
    }

    fun isRelayLive(context: Context, connectionService: AlertConnectionService?): Boolean =
        connectionService?.isConnected() == true || ConnectionManager.isRelayConnectedHint()

    /** Call from Activity.onResume / onCreate after views exist. */
    fun restoreOnAppOpen(
        activity: Activity,
        prefs: Prefs,
        connectionService: AlertConnectionService?,
        serviceConnection: ServiceConnection,
        onConnecting: () -> Unit,
        onConnected: (Boolean) -> Unit,
    ) {
        if (AppRole.isUser(activity) && StandaloneUi.isUserStandalone(activity)) return
        if (!shouldAutoRestore(prefs)) {
            onConnected(isRelayLive(activity, connectionService))
            return
        }
        if (!isRelayLive(activity, connectionService)) {
            onConnecting()
        }
        try {
            activity.bindService(
                Intent(activity, AlertConnectionService::class.java),
                serviceConnection,
                Context.BIND_AUTO_CREATE,
            )
        } catch (_: Exception) {
        }
        ConnectionManager.ensureRelayLiveOnAppOpen(activity)
        onConnected(isRelayLive(activity, connectionService))
    }
}
