package com.curax.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.TaskStackBuilder

object NotificationHelper {

    private const val CHANNEL_ID = "curax_alert_channel"

    private fun defaultAlertSoundUri(context: Context): Uri? =
        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

    /** Ringtone for standalone local alarms ([LocalAlertReceiver]); respects [Prefs.standaloneLocalAlertSoundUri]. */
    fun resolveStandaloneAlertSoundUri(context: Context): Uri? {
        if (!StandaloneUi.isUserStandalone(context)) return defaultAlertSoundUri(context)
        val raw = Prefs(context).standaloneLocalAlertSoundUri.trim()
        if (raw.isEmpty()) return defaultAlertSoundUri(context)
        if (raw.equals("silent", ignoreCase = true)) return null
        return try {
            Uri.parse(raw)
        } catch (_: Exception) {
            defaultAlertSoundUri(context)
        }
    }

    private fun resolveStandaloneVibrate(context: Context): Boolean {
        if (!StandaloneUi.isUserStandalone(context)) return true
        return Prefs(context).standaloneLocalAlertVibrate
    }

    const val EXTRA_ALERT_ID = "extra_alert_id"
    const val EXTRA_ALERT_TYPE = "extra_alert_type"
    const val EXTRA_ALERT_MESSAGE = "extra_alert_message"
    const val EXTRA_ALERT_USER_NAME = "extra_alert_user_name"
    const val EXTRA_ALERT_TIME = "extra_alert_time"
    const val EXTRA_INTERNAL_NAV = "extra_internal_nav"
    const val EXTRA_WAKE_SCREEN = "extra_wake_screen"

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val soundUri: Uri? = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            // IMPORTANCE_HIGH so lock screen pe show hota hai + screen wake
            val importance = NotificationManager.IMPORTANCE_HIGH
            val ch = NotificationChannel(CHANNEL_ID, "Curax Alerts", importance).apply {
                setShowBadge(true)
                enableVibration(true)
                enableLights(true)
                setSound(soundUri, null)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }

    fun showAlertNotification(
        context: Context,
        notificationId: Int,
        alertId: Long,
        type: String,
        message: String,
        receivedAt: Long = System.currentTimeMillis(),
        userName: String = "",
    ) {
        createChannel(context)

        val subUser = AlertDisplayRules.notificationSubtext(type, userName)
        val pi = AlertNavigation.pendingIntentFromNotification(
            context,
            notificationId,
            alertId,
            type,
            message,
            receivedAt,
            subUser,
        )

        val title = when {
            type.contains("stock", ignoreCase = true) -> "Stock alert"
            type.contains("expiry", ignoreCase = true) -> "Expiry alert"
            type.equals("reminder", ignoreCase = true) -> "Reminder"
            type.equals("plan", ignoreCase = true) -> "Planned item"
            type.contains("medicine", ignoreCase = true) || type == "time" || type == "pre" -> "Medicine reminder"
            type.contains("missed", ignoreCase = true) -> "Missed dose"
            else -> "Curax Alert"
        }

        val soundUri = resolveStandaloneAlertSoundUri(context)
        val vibrateOn = resolveStandaloneVibrate(context)
        val b = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .apply {
                if (subUser.isNotEmpty()) setSubText(subUser)
            }
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .apply {
                if (pi != null) setContentIntent(pi)
            }
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setSound(soundUri)
        if (vibrateOn) {
            b.setVibrate(longArrayOf(0L, 380L, 220L, 380L))
        } else {
            b.setVibrate(null)
        }
        val notification = b.build()

        try {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
        } catch (_: SecurityException) {
        }
    }
}
