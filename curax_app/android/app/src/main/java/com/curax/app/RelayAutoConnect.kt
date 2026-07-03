package com.curax.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder

/**
 * Keeps the relay WebSocket up after first Connect (user↔admin channel).
 * User local alarms handle dose times; relay delivers server-driven alerts to admin/user.
 */
object RelayAutoConnect {

    fun userLinkedToAdmin(prefs: Prefs): Boolean =
        prefs.linkedAdminId.trim().isNotEmpty()

    fun shouldAutoRestore(prefs: Prefs): Boolean {
        if (!prefs.relayAutoConnectEnabled) return false
        if (prefs.id.trim().isEmpty() || prefs.apiKey.trim().isEmpty()) return false
        if (prefs.serverUrl.trim().isEmpty()) return false
        return true
    }

    /**
     * Called only after the user taps **Connect for alerts** and finishes the permission flow.
     * Sign-in, admin link, and databus sync must not enable relay auto-restore (multi-account testing).
     */
    fun markConnectFlowComplete(context: Context) {
        val app = context.applicationContext
        if (!AppRole.isUser(app)) return
        val prefs = Prefs(app)
        if (prefs.id.trim().isEmpty() || prefs.apiKey.trim().isEmpty()) return
        if (prefs.serverUrl.trim().isEmpty()) return
        prefs.relayAutoConnectEnabled = true
    }

    @Deprecated(
        "Use markConnectFlowComplete after Connect button only",
        ReplaceWith("markConnectFlowComplete(context)"),
    )
    fun enableForLinkedUser(context: Context) {
        // Intentionally no-op — prevents sign-in / link from auto-connecting another user's relay.
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
        if (!shouldAutoRestore(prefs)) {
            onConnected(isRelayLive(activity, connectionService))
            return
        }
        val live = isRelayLive(activity, connectionService)
        if (!live) {
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
        if (!live) {
            ConnectionManager.ensureRelayLiveOnAppOpen(activity)
        } else {
            ConnectionManager.requestReconnectRelayNow(activity)
        }
        onConnected(isRelayLive(activity, connectionService))
    }
}
