package com.curax.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Fires standalone local alarms ([LocalAlertsController]) and default-mode admin escalation
 * alarms ([MissedDoseEscalationController]).
 */
class LocalAlertReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != LocalAlertsController.ACTION_LOCAL_STANDALONE_ALERT) return
        val app = context.applicationContext
        if (!AppRole.isUser(app)) return
        val id = intent.data?.getQueryParameter("id")?.trim().orEmpty()
        if (id.isEmpty()) return

        val standalone = StandaloneUi.isUserStandalone(app)
        val payload = when {
            standalone -> LocalAlertsController.getPayload(app, id)
            else -> MissedDoseEscalationController.getPayload(app, id)
        } ?: run {
            Log.w(TAG, "Missing payload for alarm id=$id")
            return
        }

        val type = payload.optString("type", "local")
        if (type == LocalAlertsController.TYPE_STOCK_EXPIRY_SCAN) {
            if (!standalone) return
            LocalAlertsController.runDailyStockExpiryScan(app)
            LocalAlertsController.clearPayload(app, id)
            LocalAlertsController.reschedule(app)
            return
        }

        val adminOnlyTypes = setOf(
            "missed_dose_30",
            "missed_dose_15_admin",
            "missed_dose_30_admin",
        )
        val escalationTypes = setOf(
            "missed_dose_15",
            "missed_dose_30",
            "missed_dose_15_admin",
            "missed_dose_30_admin",
        )

        if (type !in adminOnlyTypes) {
            val title = payload.optString("title", "Curax")
            val message = payload.optString("message", "")
            val nid = (id.hashCode() and 0x7fff_0000) xor (System.currentTimeMillis() % 0xffff).toInt()
            val combined = if (title.isNotBlank() && title != message) "$title — $message" else message
            NotificationHelper.showAlertNotification(
                app,
                notificationId = nid,
                alertId = -(1L + (id.hashCode() and 0xfffffff)),
                type = type,
                message = combined,
                receivedAt = System.currentTimeMillis(),
            )
            if (standalone) {
                AdminDemoData.prependStandaloneLocalAlert(type, combined, System.currentTimeMillis())
                StandaloneOfflineMirror.persistMergedSnapshot(app)
                app.sendBroadcast(Intent(AlertEvents.ACTION_ADMIN_DATA_SYNCED))
            }
        }

        if (type in escalationTypes) {
            val phase = if (type.contains("15")) 15 else 30
            MissedDoseEscalationApi.postEscalation(
                app,
                phase = phase,
                boxId = payload.optString("box_id"),
                medicineName = payload.optString("medicine_name"),
                scheduleTime = payload.optString("schedule_time"),
                doseDate = payload.optString("dose_date"),
            )
        }

        if (standalone) {
            LocalAlertsController.clearPayload(app, id)
            LocalAlertsController.reschedule(app)
        } else {
            MissedDoseEscalationController.clearPayload(app, id)
            MissedDoseEscalationController.reschedule(app)
        }
    }

    private companion object {
        private const val TAG = "LocalAlertReceiver"
    }
}
