package com.curax.app

import android.content.Context
import android.content.Intent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * After each scheduled time + [DoseIntakeClassifier.MISSED_AFTER_SCHEDULE_MS], if that slot was not
 * marked, records a `missed_auto` log row and per-slot suppress so alarms stop for that slot.
 * Does **not** show notifications or in-app alert rows — silent bookkeeping so sync / schedules stay correct.
 *
 * User-visible **time-based** dose reminders and missed-phase notifications (30/15 min before, exact,
 * 5/15/30 min after, etc.) come only from [LocalAlertsController] → [LocalAlertReceiver]; that path is unchanged.
 */
object DoseAutoMissedMarker {

    private val tsFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun run(context: Context) {
        val app = context.applicationContext
        if (!AppRole.isUser(app)) return
        if (!LocalAlertsUi.usesOnDeviceMedicineAlarms(app)) return
        if (StandaloneUi.isUserStandalone(app) &&
            StandaloneUserMutationGate.isStandaloneUserWithoutAdminLink(app)
        ) {
            return
        }
        AdminDemoData.warmScheduleTouchCache(app)
        val dayKey = LocalAlertsController.localDayKeyToday()
        val now = System.currentTimeMillis()
        var added = 0
        for (m in AdminDemoData.medicines) {
            if (m.dosePerAdministration() <= 0) continue
            if (m.stock <= 0) continue
            val single = !m.usesMultipleTimesPerDay()
            for (slot in m.effectiveScheduleTimes()) {
                val sched = DoseMarkWindow.todayMillisForHms(slot) ?: continue
                val missEvalEnd = sched + DoseIntakeClassifier.MISSED_AFTER_SCHEDULE_MS
                val scheduleTouch = AdminDemoData.medicineScheduleTouchMs(m.box)
                if (scheduleTouch > missEvalEnd) continue
                if (now <= missEvalEnd) continue
                if (DoseTrackingLocalStore.isTakenForSlot(app, m.box, dayKey, slot)) continue
                if (DoseTrackingLocalStore.hasSlotOutcomeForBoxSlot(app, m.box, dayKey, slot, single)) continue

                val ts = tsFmt.format(Date(now))
                DoseTrackingLocalStore.appendLogEntry(
                    app,
                    mapOf(
                        "timestamp" to ts,
                        "box" to m.box,
                        "medicine" to m.name,
                        "dose_taken" to "—",
                        "remaining" to m.stock,
                        "kind" to "missed_auto",
                        "scheduled_slot" to MedicineSchedule.normalizeToHhMm(slot),
                    ),
                )
                DoseTrackingLocalStore.markTakenForSlot(app, m.box, dayKey, slot)

                val slotNorm = MedicineSchedule.normalizeToHhMm(slot)
                val adminMsg = app.getString(
                    R.string.dose_admin_notify_missed_auto,
                    m.name,
                    m.box,
                    slotNorm,
                )
                AlertFlowLog.record(app, "missed_auto", m.box, slotNorm, dayKey, "admin_relay_post", adminMsg.take(80))
                UserRelayNotifyApi.notifyAdmin(app, "missed_dose", adminMsg)

                StandaloneUserMutationSink.notifyLocalChange(
                    null,
                    app,
                    PendingSyncQueueStore.TYPE_DOSE,
                    app.getString(R.string.pending_sync_title_dose),
                    app.getString(R.string.pending_sync_subtitle_not_synced),
                )
                added++
            }
        }
        if (added > 0) {
            StandaloneOfflineMirror.persistMergedSnapshot(app)
            app.sendBroadcast(Intent(AlertEvents.ACTION_ADMIN_DATA_SYNCED))
        }
    }
}
