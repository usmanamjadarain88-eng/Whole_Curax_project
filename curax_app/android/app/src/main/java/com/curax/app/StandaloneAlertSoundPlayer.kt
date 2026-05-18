package com.curax.app

import android.content.Context
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper

/** Plays the standalone user's chosen alert tone (Settings → Alert sound). */
object StandaloneAlertSoundPlayer {

    @Volatile
    private var active: Ringtone? = null

    fun play(context: Context) {
        val app = context.applicationContext
        if (!StandaloneUi.isUserStandalone(app)) return
        val uri = NotificationHelper.resolveStandaloneAlertSoundUri(app) ?: return
        stop()
        try {
            val rt = RingtoneManager.getRingtone(app, uri) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                rt.audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                rt.isLooping = false
            }
            active = rt
            rt.play()
            Handler(Looper.getMainLooper()).postDelayed({ stop() }, 12_000L)
        } catch (_: Exception) {
            stop()
        }
    }

    fun stop() {
        try {
            active?.stop()
        } catch (_: Exception) {
        }
        active = null
    }
}
