package com.curax.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Fires standalone local alarms scheduled by [LocalAlertsController] (on-device medicine / plan / reminder engine).
 * Every fired alarm shows a heads-up notification + in-app alert row (when user role is standalone user).
 * This is separate from [DoseAutoMissedMarker], which only writes silent `missed_auto` log/suppress state.
 */
class LocalAlertReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != LocalAlertsController.ACTION_LOCAL_STANDALONE_ALERT) return
        val app = context.applicationContext
        if (!StandaloneUi.isUserStandalone(app)) return
        val id = intent.data?.getQueryParameter("id")?.trim().orEmpty()
        if (id.isEmpty()) return

        val payload = LocalAlertsController.getPayload(app, id) ?: run {
            Log.w(TAG, "Missing payload for alarm id=$id")
            return
        }

        val type = payload.optString("type", "local")
        if (type == LocalAlertsController.TYPE_STOCK_EXPIRY_SCAN) {
            LocalAlertsController.runDailyStockExpiryScan(app)
            LocalAlertsController.clearPayload(app, id)
            LocalAlertsController.reschedule(app)
            return
        }

        val title = payload.optString("title", "Curax")
        val message = payload.optString("message", "")
        val nid = (id.hashCode() and 0x7fff_0000) xor (System.currentTimeMillis() % 0xffff).toInt()
        val combined = if (title.isNotBlank() && title != message) "$title — $message" else message
        NotificationHelper.showAlertNotification(
            app,
            notificationId = nid,
            alertId = -(1L + (id.hashCode() and 0xfffffff)), // negative = not DB row
            type = type,
            message = combined,
            receivedAt = System.currentTimeMillis(),
        )
        if (AppRole.isUser(app) && StandaloneUi.isUserStandalone(app)) {
            AdminDemoData.prependStandaloneLocalAlert(type, combined, System.currentTimeMillis())
            StandaloneOfflineMirror.persistMergedSnapshot(app)
            app.sendBroadcast(Intent(AlertEvents.ACTION_ADMIN_DATA_SYNCED))
        }
        LocalAlertsController.clearPayload(app, id)

        // Roll forward next windows after a fire
        LocalAlertsController.reschedule(app)
    }

    private companion object {
        private const val TAG = "LocalAlertReceiver"
    }
}
