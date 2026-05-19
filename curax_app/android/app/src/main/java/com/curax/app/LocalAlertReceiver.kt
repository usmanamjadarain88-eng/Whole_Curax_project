package com.curax.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log

/**
 * Fires local medicine alarms ([LocalAlertsController]) and default-mode escalation alarms.
 * Works when the app is not open — system wakes this receiver at the scheduled time.
 */
class LocalAlertReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != LocalAlertsController.ACTION_LOCAL_STANDALONE_ALERT) return
        val app = context.applicationContext
        if (!AppRole.isUser(app)) return
        val id = intent.data?.getQueryParameter("id")?.trim().orEmpty()
        if (id.isEmpty()) return

        val pending = goAsync()
        var wakeLock: PowerManager.WakeLock? = null
        try {
            wakeLock = (app.getSystemService(Context.POWER_SERVICE) as PowerManager).newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "CuraX:LocalAlert",
            ).apply {
                setReferenceCounted(false)
                acquire(60_000L)
            }

            val localAlarms = LocalAlertsUi.usesOnDeviceMedicineAlarms(app)
            val payload = when {
                localAlarms -> LocalAlertsController.getPayload(app, id)
                    ?: MissedDoseEscalationController.getPayload(app, id)
                else -> MissedDoseEscalationController.getPayload(app, id)
            } ?: run {
                Log.w(TAG, "Missing payload for alarm id=$id")
                return
            }

            val type = payload.optString("type", "local")
            val box = payload.optString("box_id")
            val dayKey = payload.optString("dose_date")
            val slot = payload.optString("schedule_time")

            if (DoseSlotAlertGuard.shouldSkip(app, payload)) {
                AlertFlowLog.record(app, type, box, slot, dayKey, "skipped", "dose already marked")
                if (localAlarms) LocalAlertsController.clearPayload(app, id)
                else MissedDoseEscalationController.clearPayload(app, id)
                return
            }

            if (type == MissedDoseEscalationWatchdog.TYPE) {
                MissedDoseEscalationWatchdog.runCheck(app)
                MissedDoseEscalationWatchdog.scheduleNext(app)
                return
            }

            if (type == LocalAlertsController.TYPE_STOCK_EXPIRY_SCAN) {
                if (!localAlarms) return
                LocalAlertsController.runDailyStockExpiryScan(app)
                LocalAlertsController.clearPayload(app, id)
                MobileReliabilityCoordinator.onAppStart(app)
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
                val title = payload.optString("title", "CuraX")
                val message = payload.optString("message", "")
                val combined = if (title.isNotBlank() && title != message) "$title — $message" else message
                val nid = (id.hashCode() and 0x7fff_0000) xor (System.currentTimeMillis() % 0xffff).toInt()
                AlertFlowLog.record(app, type, box, slot, dayKey, "local_deliver", combined.take(80))
                AlertDeliver.deliver(
                    app,
                    type = type,
                    message = combined,
                    notificationId = nid,
                )
            }

            val medicineEmailKinds = mapOf(
                "medicine_pre_30" to "pre30",
                "medicine_pre_15" to "pre15",
                "medicine_time" to "exact",
                "missed_dose_5" to "post5",
                "missed_dose_15" to "post15",
            )
            medicineEmailKinds[type]?.let { kind ->
                if (MedicineEmailDedupe.tryClaim(app, box, dayKey, slot, kind)) {
                    AlertFlowLog.record(app, type, box, slot, dayKey, "user_email_post", kind)
                    UserMedicineEmailApi.postReminder(
                        app,
                        kind = kind,
                        boxId = box,
                        medicineName = payload.optString("medicine_name"),
                        scheduleTime = slot,
                        doseDate = dayKey,
                    )
                }
            }

            if (type == "missed_dose_60") {
                val msg = payload.optString("message", "")
                if (msg.isNotBlank()) {
                    AlertFlowLog.record(app, type, box, slot, dayKey, "admin_relay_post", "missed logged")
                    UserRelayNotifyApi.notifyAdmin(app, "missed_dose", msg)
                }
            }

            if (type in escalationTypes) {
                val phase = if (type.contains("15")) 15 else 30
                if (MissedDoseEscalationDedupe.tryClaim(app, box, dayKey, slot, phase)) {
                    AlertFlowLog.record(app, type, box, slot, dayKey, "admin_escalation_post", "+$phase")
                    MissedDoseEscalationApi.postEscalation(
                        app,
                        phase = phase,
                        boxId = box,
                        medicineName = payload.optString("medicine_name"),
                        scheduleTime = slot,
                        doseDate = dayKey,
                    )
                }
            }

            if (localAlarms) {
                LocalAlertsController.clearPayload(app, id)
            } else {
                MissedDoseEscalationController.clearPayload(app, id)
            }
            MobileReliabilityCoordinator.onAppStart(app)
        } finally {
            try {
                wakeLock?.let { if (it.isHeld) it.release() }
            } catch (_: Exception) {
            }
            pending.finish()
        }
    }

    private companion object {
        private const val TAG = "LocalAlertReceiver"
    }
}
