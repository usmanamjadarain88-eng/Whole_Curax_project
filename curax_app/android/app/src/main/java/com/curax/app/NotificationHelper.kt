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

    const val EXTRA_ALERT_ID = "extra_alert_id"
    const val EXTRA_ALERT_TYPE = "extra_alert_type"
    const val EXTRA_ALERT_MESSAGE = "extra_alert_message"
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
        receivedAt: Long = System.currentTimeMillis()
    ) {
        createChannel(context)

        val detailIntent = Intent(context, AlertDetailActivity::class.java).apply {
            putExtra(EXTRA_ALERT_ID, alertId)
            putExtra(EXTRA_ALERT_TYPE, type)
            putExtra(EXTRA_ALERT_MESSAGE, message)
            putExtra(EXTRA_ALERT_TIME, receivedAt)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NO_USER_ACTION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
        }

        val pi = TaskStackBuilder.create(context)
            .addNextIntentWithParentStack(detailIntent)
            .getPendingIntent(
                notificationId,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
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

        val soundUri: Uri? = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setSound(soundUri)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
        } catch (_: SecurityException) {
        }
    }
}
