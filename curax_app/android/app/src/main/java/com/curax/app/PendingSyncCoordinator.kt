package com.curax.app

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper

/**
 * Flushes [PendingSyncQueueStore] to the server with retries (max 3 attempts per wave).
 */
object PendingSyncCoordinator {

    private const val MAX_ATTEMPTS = 3
    private val mainHandler = Handler(Looper.getMainLooper())

    fun isOnline(context: Context): Boolean {
        val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
        // Wi‑Fi with no route (captive portal / airplane-with-wifi) still reports INTERNET; VALIDATED avoids long HTTP hangs.
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    fun requestFlush(context: Context) {
        val app = context.applicationContext
        if (!AppRole.isUser(app) || !StandaloneUi.isUserStandalone(app)) return
        if (PendingSyncQueueStore.pendingCount(app) == 0) return
        Thread { processQueueBlocking(app) }.start()
    }

    private fun processQueueBlocking(app: Context) {
        if (!isOnline(app)) {
            mainHandler.post {
                app.sendBroadcast(Intent(AlertEvents.ACTION_PENDING_SYNC_QUEUE_UPDATED))
            }
            return
        }
        val prefs = Prefs(app)
        val base = prefs.centralApiUrl.trim()
        val botId = prefs.id.trim()
        val apiKey = prefs.apiKey.trim()
        if (base.isEmpty() || botId.isEmpty() || apiKey.isEmpty()) return

        val body = StandaloneOfflineMirror.buildStandaloneSyncPayload(app).apply {
            put("bot_id", botId)
            put("api_key", apiKey)
        }
        val (ok, err) = UserStandaloneSyncApi.postSync(base, body)
        if (ok) {
            PendingSyncQueueStore.clear(app)
            mainHandler.post {
                app.sendBroadcast(Intent(AlertEvents.ACTION_ADMIN_DATA_SYNCED))
            }
            return
        }
        PendingSyncQueueStore.bumpAllAttempts(app)
        PendingSyncQueueStore.removeExhausted(app, MAX_ATTEMPTS)
        // Retry after delay if anything left
        if (PendingSyncQueueStore.pendingCount(app) > 0) {
            mainHandler.postDelayed({
                Thread { processQueueBlocking(app) }.start()
            }, 5_000L)
        }
        mainHandler.post {
            app.sendBroadcast(Intent(AlertEvents.ACTION_PENDING_SYNC_QUEUE_UPDATED))
        }
    }
}
