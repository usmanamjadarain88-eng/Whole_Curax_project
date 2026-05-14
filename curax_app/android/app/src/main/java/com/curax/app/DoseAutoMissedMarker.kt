package com.curax.app

import android.content.Context
import android.content.Intent

/**
 * After scheduled dose + grace + a short buffer, if the user never marked the dose,
 * mark the box/day as handled for local alarms only (no dose-history row; history is for explicit Mark dose).
 */
object DoseAutoMissedMarker {

    private const val BUFFER_AFTER_GRACE_MS = 120_000L

    fun run(context: Context) {
        val app = context.applicationContext
        if (!StandaloneUi.isUserStandalone(app) || !AppRole.isUser(app)) return
        if (StandaloneUserMutationGate.isStandaloneUserWithoutAdminLink(app)) return
        val dayKey = LocalAlertsController.localDayKeyToday()
        val now = System.currentTimeMillis()
        var added = 0
        for (m in AdminDemoData.medicines) {
            if (m.dosePerDay <= 0) continue
            val sched = DoseMarkWindow.todayScheduledBaseMillis(m) ?: continue
            val deadline = sched + DoseIntakeClassifier.GRACE_AFTER_SCHEDULE_MS + BUFFER_AFTER_GRACE_MS
            if (now <= deadline) continue
            if (DoseTrackingLocalStore.isTakenForLocalDay(app, m.box, dayKey)) continue
            if (DoseTrackingLocalStore.hasSlotOutcomeForBoxDay(app, m.box, dayKey)) continue
            // Suppress further local alarms for this box/day only — do not write dose history rows
            // (history should reflect explicit Mark dose, not auto-missed logging).
            DoseTrackingLocalStore.markTakenForDay(app, m.box, dayKey)
            added++
        }
        if (added > 0) {
            StandaloneOfflineMirror.persistMergedSnapshot(app)
            app.sendBroadcast(Intent(AlertEvents.ACTION_ADMIN_DATA_SYNCED))
        }
    }
}
