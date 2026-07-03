package com.curax.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar

/**
 * Default-mode user shell: DS18B20 readings from ESP32 over BLE after first connect.
 */
class UserAmbientMonitorActivity : AppCompatActivity() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var demoTick = 0
    private var telemetryReceiverRegistered = false

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (isFinishing) return
            if (AmbientReadout.shouldShowDemo(this@UserAmbientMonitorActivity)) {
                demoTick += 1
            } else if (CuraxEsp32BleLink.isConnected()) {
                CuraxEsp32BleLink.sendTempQuery()
            }
            refreshReadings()
            mainHandler.postDelayed(this, 2500L)
        }
    }

    private val bleReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                CuraxEsp32BleLink.ACTION_TELEMETRY -> refreshReadings()
                CuraxEsp32BleLink.ACTION_CONNECTION_STATE -> {
                    if (intent.getBooleanExtra(CuraxEsp32BleLink.EXTRA_CONNECTED, false)) {
                        CuraxEsp32BleLink.sendTempQuery()
                    }
                    refreshReadings()
                }
            }
        }
    }

    private lateinit var tvTemp1: TextView
    private lateinit var tvTemp2: TextView
    private lateinit var tvHumidity: TextView
    private lateinit var tvUpdated: TextView
    private lateinit var tvBanner: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_user_ambient_monitor)

        findViewById<MaterialToolbar>(R.id.toolbarAmbient).setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        tvTemp1 = findViewById(R.id.tvAmbientTemp1)
        tvTemp2 = findViewById(R.id.tvAmbientTemp2)
        tvHumidity = findViewById(R.id.tvAmbientHumidity)
        tvUpdated = findViewById(R.id.tvAmbientUpdated)
        tvBanner = findViewById(R.id.tvAmbientDemoBanner)
    }

    override fun onStart() {
        super.onStart()
        CuraxEsp32BleLink.init(applicationContext)
        if (!telemetryReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(CuraxEsp32BleLink.ACTION_TELEMETRY)
                addAction(CuraxEsp32BleLink.ACTION_CONNECTION_STATE)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(bleReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(bleReceiver, filter)
            }
            telemetryReceiverRegistered = true
        }
        if (CuraxEsp32BleLink.isConnected()) {
            CuraxEsp32BleLink.sendTempQuery()
        }
        mainHandler.removeCallbacks(pollRunnable)
        mainHandler.post(pollRunnable)
    }

    override fun onStop() {
        mainHandler.removeCallbacks(pollRunnable)
        if (telemetryReceiverRegistered) {
            try {
                unregisterReceiver(bleReceiver)
            } catch (_: Exception) {
            }
            telemetryReceiverRegistered = false
        }
        super.onStop()
    }

    private fun refreshReadings() {
        val f = AmbientReadout.format(this, demoTick)
        tvTemp1.text = f.tempZone1
        tvTemp2.text = f.tempZone2
        tvHumidity.text = f.humidity
        tvUpdated.text = f.updatedLine
        tvBanner.visibility = View.VISIBLE
        tvBanner.text = when {
            f.showingDemo -> getString(R.string.user_ambient_demo_banner)
            f.isLive -> getString(R.string.user_ambient_live_banner)
            else -> getString(R.string.user_ambient_not_connected)
        }
    }
}
