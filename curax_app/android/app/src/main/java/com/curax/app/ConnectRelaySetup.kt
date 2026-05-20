package com.curax.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Notification / battery / full-screen prompts only on the **first** Connect tap.
 * After that, [ConnectionManager.requestReconnectRelayNow] restores relay when the app opens.
 */
object ConnectRelaySetup {

    const val REQ_POST_NOTIFICATIONS = 1

    /** True only after the user tapped Connect once and completed the first-time permission flow. */
    fun isRelaySetupComplete(prefs: Prefs): Boolean =
        prefs.hasRequestedConnectWakePermissions

    fun needsNotificationPrompt(context: Context, prefs: Prefs): Boolean {
        if (prefs.hasRequestedConnectWakePermissions) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        return ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.POST_NOTIFICATIONS,
        ) != PackageManager.PERMISSION_GRANTED
    }

    fun requestNotificationPrompt(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        ActivityCompat.requestPermissions(
            activity,
            arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
            REQ_POST_NOTIFICATIONS,
        )
    }

    /**
     * Battery + full-screen intent — once per install, on first Connect only (same order as admin drawer).
     * Call after notification permission returns (or when not needed on Android 12-).
     */
    fun runFirstConnectSystemPrompts(activity: Activity, prefs: Prefs) {
        if (prefs.hasRequestedConnectWakePermissions) return
        prefs.hasRequestedConnectWakePermissions = true
        promptBatteryOptimizationIfNeeded(activity)
        promptFullScreenIntentIfNeeded(activity)
        DeviceAlertSetup.promptExactAlarmsIfNeeded(activity)
    }

    fun promptBatteryOptimizationIfNeeded(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val pm = activity.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(activity.packageName)) return
        try {
            activity.startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${activity.packageName}")
                },
            )
        } catch (_: Exception) {
        }
    }

    fun promptFullScreenIntentIfNeeded(activity: Activity) {
        if (Build.VERSION.SDK_INT < 34) return
        val nm = activity.getSystemService(android.app.NotificationManager::class.java)
        if (nm.canUseFullScreenIntent()) return
        try {
            activity.startActivity(
                Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                    data = Uri.parse("package:${activity.packageName}")
                },
            )
            CuraxFeedback.warn(
                activity,
                "Enable Full-screen intent for CuraX to wake screen",
                long = true,
            )
        } catch (_: Exception) {
        }
    }
}
