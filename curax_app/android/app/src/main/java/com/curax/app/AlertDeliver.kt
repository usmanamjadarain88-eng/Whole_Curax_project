package com.curax.app

import android.content.Context
import android.content.Intent

/**
 * Single path: every user/admin alert → saved, notification popup, sound, UI refresh.
 * Used by relay WebSocket and on-device AlarmManager ([LocalAlertReceiver]).
 */
object AlertDeliver {

    /**
     * @param type alert type key (medicine_pre_30, stock, admin_message, …)
     * @param message body shown in notification and Alerts list
     * @param userName relay user label for admin view (optional)
     * @param notificationId optional stable id; default derives from alertId
     */
    fun deliver(
        context: Context,
        type: String,
        message: String,
        userName: String = "",
        notificationId: Int? = null,
        receivedAt: Long = System.currentTimeMillis(),
        addToAlertsList: Boolean = true,
    ) {
        val app = context.applicationContext
        if (!AppRole.isAdmin(app) && !AppRole.isUser(app)) return
        val body = message.trim()
        if (body.isEmpty()) return

        val storedUser = AlertDisplayRules.linkedUserLabelForAlert(type, userName)
        val fp = AlertNotifyDedupe.fingerprint(type, body, receivedAt)
        val showNotification = AlertNotifyDedupe.shouldNotifyAndMark(app, fp)

        var alertId = -receivedAt
        if (addToAlertsList) {
            if (AppRole.isUser(app) && LocalAlertsUi.usesOnDeviceMedicineAlarms(app)) {
                AdminDemoData.prependStandaloneLocalAlert(type, body, receivedAt)
                if (StandaloneUi.isUserStandalone(app)) {
                    StandaloneOfflineMirror.persistMergedSnapshot(app)
                } else {
                    UserAlertsSnapshot.persistAlerts(app)
                }
            } else {
                alertId = try {
                    AlertDb(app).insertAlert(type, body, userName = storedUser, receivedAt = receivedAt)
                } catch (_: Exception) {
                    -receivedAt
                }
            }
        }

        if (showNotification) {
            val nid = notificationId
                ?: ((alertId.toInt() and 0x7fff_0000) xor (receivedAt % 0xffff).toInt())
            NotificationHelper.showAlertNotification(
                app,
                notificationId = nid,
                alertId = alertId,
                type = type,
                message = body,
                userName = storedUser,
                receivedAt = receivedAt,
            )
            playSound(app)
        }
        broadcastRefresh(app)
    }

    /** Standalone: tone from Settings → Alert sound. Default linked + admin: system alarm/notification. */
    private fun playSound(app: Context) {
        if (LocalAlertsUi.usesCustomAlertToneSettings(app)) {
            StandaloneAlertSoundPlayer.play(app)
        } else {
            AlertSoundHelper.playAlertSound(app)
        }
    }

    private fun broadcastRefresh(app: Context) {
        app.sendBroadcast(Intent(AlertEvents.ACTION_ALERTS_UPDATED))
        app.sendBroadcast(Intent(AlertEvents.ACTION_ADMIN_DATA_SYNCED))
    }
}
