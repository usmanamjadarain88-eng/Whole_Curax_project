package com.curax.app

import android.content.Context
import android.content.Intent

/** Default handling for relay JSON → user notification + alert row (admin chat, etc.). */
object RelayIncomingAlertDeliver {

    fun deliverToUser(context: Context, type: String, message: String, userName: String) {
        val app = context.applicationContext
        if (!AppRole.isUser(app)) return
        val storedUser = AlertDisplayRules.linkedUserLabelForAlert(type, userName)
        val receivedAt = System.currentTimeMillis()
        val alertId = try {
            AlertDb(app).insertAlert(type, message, userName = storedUser, receivedAt = receivedAt)
        } catch (_: Exception) {
            -(System.currentTimeMillis() % Int.MAX_VALUE).toInt()
        }
        NotificationHelper.showAlertNotification(
            app,
            notificationId = (alertId.toInt() and 0x7fff_0000) xor (receivedAt % 0xffff).toInt(),
            alertId = alertId,
            type = type,
            message = message,
            userName = storedUser,
            receivedAt = receivedAt,
        )
        if (StandaloneUi.isUserStandalone(app)) {
            AdminDemoData.prependStandaloneLocalAlert(type, message, receivedAt)
            StandaloneOfflineMirror.persistMergedSnapshot(app)
            StandaloneAlertSoundPlayer.play(app)
        }
        app.sendBroadcast(Intent(AlertEvents.ACTION_ADMIN_DATA_SYNCED))
    }
}
