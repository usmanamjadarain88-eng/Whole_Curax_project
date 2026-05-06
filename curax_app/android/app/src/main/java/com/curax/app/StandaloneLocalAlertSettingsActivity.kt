package com.curax.app

import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView

/**
 * Standalone-only: ringtone, vibration, default snooze for on-device local alerts
 * ([LocalAlertReceiver] / [NotificationHelper]).
 */
class StandaloneLocalAlertSettingsActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var ivSoundPreviewSlash: ImageView

    private var summaryPreviewRingtone: Ringtone? = null
    private var summaryPreviewActive = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_standalone_local_alert_settings)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        prefs = Prefs(this)
        prefs.applyStandaloneSoundLibraryInstallGuard(this)
        prefs.runOneTimeStandaloneSoundLibraryResetIfNeeded()
        ivSoundPreviewSlash = findViewById(R.id.ivStandaloneSoundPreviewSlash)

        findViewById<MaterialToolbar>(R.id.toolbarStandaloneAlerts).setNavigationOnClickListener {
            saveSnoozeFromField()
            onBackPressedDispatcher.onBackPressed()
        }

        findViewById<SwitchCompat>(R.id.switchStandaloneVibrate).apply {
            isChecked = prefs.standaloneLocalAlertVibrate
            setOnCheckedChangeListener { _, checked ->
                prefs.standaloneLocalAlertVibrate = checked
            }
        }

        findViewById<TextInputEditText>(R.id.etStandaloneSnoozeMinutes).setText(
            prefs.standaloneLocalAlertSnoozeMinutes.toString(),
        )

        findViewById<MaterialButton>(R.id.btnStandalonePickSound).setOnClickListener {
            saveSnoozeFromField()
            // Avoid nested ActivityResult (settings→picker + picker→OpenDocument): breaks on some OEMs.
            startActivity(Intent(this, StandaloneAlertSoundPickerActivity::class.java))
        }

        findViewById<FrameLayout>(R.id.flStandaloneSoundPreview).setOnClickListener {
            toggleSummaryPreview()
        }

        bindSoundSummary()
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(0, 0)
    }

    override fun onResume() {
        super.onResume()
        bindSoundSummary()
        volumeControlStream = AudioManager.STREAM_ALARM
    }

    private fun saveSnoozeFromField() {
        val raw = findViewById<TextInputEditText>(R.id.etStandaloneSnoozeMinutes).text?.toString()?.trim().orEmpty()
        prefs.standaloneLocalAlertSnoozeMinutes = raw.toIntOrNull() ?: prefs.standaloneLocalAlertSnoozeMinutes
    }

    private fun bindSoundSummary() {
        val tv = findViewById<TextView>(R.id.tvStandaloneAlertSoundSummary)
        val raw = prefs.standaloneLocalAlertSoundUri.trim()
        if (raw.isEmpty()) {
            tv.text = getString(R.string.standalone_alert_sound_default_summary)
            return
        }
        if (raw.equals("silent", ignoreCase = true)) {
            tv.text = getString(R.string.standalone_alert_sound_silent)
            return
        }
        val uri = try {
            Uri.parse(raw)
        } catch (_: Exception) {
            tv.text = getString(R.string.standalone_alert_sound_default_summary)
            return
        }
        if (uri == Uri.EMPTY || uri.scheme.isNullOrBlank()) {
            tv.text = getString(R.string.standalone_alert_sound_silent)
            return
        }
        val fallback = uri.lastPathSegment?.takeIf { it.isNotBlank() }
            ?: getString(R.string.standalone_alert_sound_custom)
        tv.text = fallback
        val appCtx = applicationContext
        Thread {
            val title = try {
                RingtoneManager.getRingtone(appCtx, uri)?.getTitle(appCtx)
            } catch (_: Exception) {
                null
            }
            val label = title?.takeIf { it.isNotBlank() } ?: fallback
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                tv.text = label
            }
        }.start()
    }

    private fun setPreviewPlayingUi(playing: Boolean) {
        ivSoundPreviewSlash.visibility = if (playing) View.VISIBLE else View.GONE
    }

    private fun stopSummaryPreview() {
        summaryPreviewRingtone?.stop()
        summaryPreviewRingtone = null
        summaryPreviewActive = false
        setPreviewPlayingUi(false)
    }

    private fun toggleSummaryPreview() {
        if (summaryPreviewActive) {
            stopSummaryPreview()
            return
        }
        val uri = NotificationHelper.resolveStandaloneAlertSoundUri(this)
        if (uri == null) {
            CuraxFeedback.info(this, getString(R.string.standalone_alert_preview_silent))
            return
        }
        try {
            stopSummaryPreview()
            val rt = RingtoneManager.getRingtone(applicationContext, uri) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                rt.audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                rt.isLooping = false
            }
            rt.play()
            summaryPreviewRingtone = rt
            summaryPreviewActive = true
            setPreviewPlayingUi(true)
        } catch (_: Exception) {
            setPreviewPlayingUi(false)
        }
    }

    override fun onPause() {
        saveSnoozeFromField()
        stopSummaryPreview()
        super.onPause()
    }
}
