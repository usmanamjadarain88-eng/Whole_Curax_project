package com.curax.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * After reboot or app update: restore cached data, re-register user alarms, reconnect relay.
 */
class LocalAlertsBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
            -> Unit
            else -> return
        }
        MobileReliabilityCoordinator.onDeviceWake(context.applicationContext)
    }
}
