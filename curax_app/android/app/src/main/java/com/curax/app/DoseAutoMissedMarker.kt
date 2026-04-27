package com.curax.app

import android.content.Context
import android.content.Intent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * After scheduled dose + grace + a short buffer, if the user never marked the dose,
 * append a single [missed_auto] row and suppress further local medicine alarms for that box/day.
 */
object DoseAutoMissedMarker {

    private const val BUFFER_AFTER_GRACE_MS = 120_000L

    fun run(context: Context) {
        val app = context.applicationContext
        if (!StandaloneUi.isUserStandalone(app) || !AppRole.isUser(app)) return
        val dayKey = LocalAlertsController.localDayKeyToday()
        val tsFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val now = System.currentTimeMillis()
        var added = 0
        for (m in AdminDemoData.medicines) {
            if (m.dosePerDay <= 0) continue
            val sched = DoseMarkWindow.todayScheduledBaseMillis(m) ?: continue
            val deadline = sched + DoseIntakeClassifier.GRACE_AFTER_SCHEDULE_MS + BUFFER_AFTER_GRACE_MS
            if (now <= deadline) continue
            if (DoseTrackingLocalStore.isTakenForLocalDay(app, m.box, dayKey)) continue
            if (DoseTrackingLocalStore.hasSlotOutcomeForBoxDay(app, m.box, dayKey)) continue
            val ts = tsFmt.format(Date())
            DoseTrackingLocalStore.appendLogEntry(
                app,
                mapOf(
                    "timestamp" to ts,
                    "box" to m.box,
                    "medicine" to "${m.name} (auto)",
                    "dose_taken" to 0,
                    "remaining" to m.stock,
                    "kind" to "missed_auto",
                ),
            )
            DoseTrackingLocalStore.markTakenForDay(app, m.box, dayKey)
            added++
        }
        if (added > 0) {
            StandaloneOfflineMirror.persistMergedSnapshot(app)
            app.sendBroadcast(Intent(AlertEvents.ACTION_ADMIN_DATA_SYNCED))
            StandaloneUserMutationSink.notifyLocalChange(
                null,
                app,
                PendingSyncQueueStore.TYPE_DOSE,
                app.getString(R.string.pending_sync_title_dose),
                app.resources.getQuantityString(R.plurals.auto_missed_batch_subtitle, added, added),
            )
        }
    }
}
