package com.curax.app

import android.content.Context
import android.media.RingtoneManager
import android.net.Uri

/**
 * Plays the default notification/alarm sound when an alert is received via WebSocket
 * while the app is in the foreground (so the user hears it immediately).
 */
object AlertSoundHelper {

    fun playAlertSound(context: Context) {
        try {
            val uri: Uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val ringtone = RingtoneManager.getRingtone(context.applicationContext, uri)
            ringtone.play()
        } catch (_: Exception) {
        }
    }
}
