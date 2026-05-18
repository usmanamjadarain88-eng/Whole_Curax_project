package com.curax.app

import android.app.Activity
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * One-time device permissions for reliable alerts (notifications, battery, exact alarms).
 * Default/admin: first **Connect for alerts** tap. Standalone: **Complete setup** in the sidebar.
 */
object DeviceAlertSetup {

    fun needsStandaloneSetup(prefs: Prefs): Boolean = !prefs.standaloneDeviceSetupCompleted

    fun startStandaloneSetup(activity: Activity, prefs: Prefs) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                activity,
                android.Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ConnectRelaySetup.requestNotificationPrompt(activity)
            return
        }
        finishStandaloneSetup(activity, prefs)
    }

    fun finishStandaloneSetup(activity: Activity, prefs: Prefs) {
        ConnectRelaySetup.runFirstConnectSystemPrompts(activity, prefs)
        promptExactAlarmsIfNeeded(activity)
        prefs.standaloneDeviceSetupCompleted = true
        MobileReliabilityCoordinator.onAppStart(activity)
        CuraxFeedback.info(activity, activity.getString(R.string.device_setup_complete_toast))
    }

    fun promptExactAlarmsIfNeeded(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val am = activity.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        if (am.canScheduleExactAlarms()) return
        try {
            activity.startActivity(
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                    data = Uri.parse("package:${activity.packageName}")
                },
            )
        } catch (_: Exception) {
        }
    }
}
