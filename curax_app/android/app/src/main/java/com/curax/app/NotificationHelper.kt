package com.curax.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.TaskStackBuilder

object NotificationHelper {

    private const val CHANNEL_ID = "curax_alert_channel"

    /** Default / Smart System: notification sound only (not alarm stream). */
    private fun defaultNotificationSoundUri(): Uri? =
        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

    /** Standalone: custom or alarm URI. Default mode: notification URI only. */
    fun resolveStandaloneAlertSoundUri(context: Context): Uri? {
        if (!LocalAlertsUi.usesCustomAlertToneSettings(context)) return defaultNotificationSoundUri()
        val raw = Prefs(context).standaloneLocalAlertSoundUri.trim()
        if (raw.isEmpty()) {
            return RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: defaultNotificationSoundUri()
        }
        if (raw.equals("silent", ignoreCase = true)) return null
        return try {
            Uri.parse(raw)
        } catch (_: Exception) {
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: defaultNotificationSoundUri()
        }
    }

    private fun channelAudioAttributes(context: Context): AudioAttributes =
        AudioAttributes.Builder().apply {
            if (LocalAlertsUi.usesCustomAlertToneSettings(context)) {
                setUsage(AudioAttributes.USAGE_ALARM)
                setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            } else {
                setUsage(AudioAttributes.USAGE_NOTIFICATION)
                setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            }
        }.build()

    private fun resolveStandaloneVibrate(context: Context): Boolean {
        if (!LocalAlertsUi.usesCustomAlertToneSettings(context)) return true
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
        ensureAlertChannel(context, resolveStandaloneAlertSoundUri(context))
    }

    /** Android 8+ ignores per-notification sound; channel must match the user's selected tone. */
    private fun ensureAlertChannel(context: Context, soundUri: Uri?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val importance = NotificationManager.IMPORTANCE_HIGH
        val audioAttrs = channelAudioAttributes(context)
        val ch = NotificationChannel(CHANNEL_ID, "CuraX Alerts", importance).apply {
            setShowBadge(true)
            enableVibration(true)
            enableLights(true)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            if (soundUri != null) {
                setSound(soundUri, audioAttrs)
            } else {
                setSound(null, null)
            }
        }
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(ch)
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
        val soundUri = resolveStandaloneAlertSoundUri(context)
        ensureAlertChannel(context, soundUri)

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
            type.equals("admin_message", ignoreCase = true) -> "Message from admin"
            type.contains("dose_taken", ignoreCase = true) -> "Dose taken"
            type.equals("reminder", ignoreCase = true) -> "Reminder"
            type.equals("plan", ignoreCase = true) -> "Planned item"
            type.contains("medicine", ignoreCase = true) || type == "time" || type == "pre" -> "Medicine reminder"
            type.contains("missed", ignoreCase = true) || type == "urgent" || type == "family" -> "Missed dose"
            else -> "CuraX Alert"
        }

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
            .setCategory(
                if (LocalAlertsUi.usesCustomAlertToneSettings(context)) {
                    NotificationCompat.CATEGORY_ALARM
                } else {
                    NotificationCompat.CATEGORY_REMINDER
                },
            )
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
