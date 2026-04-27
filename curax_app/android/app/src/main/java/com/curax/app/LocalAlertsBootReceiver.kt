package com.curax.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Re-applies standalone local alarm schedules after reboot. */
class LocalAlertsBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext
        if (!StandaloneUi.isUserStandalone(app)) return
        LocalAlertsController.reschedule(app)
    }
}
