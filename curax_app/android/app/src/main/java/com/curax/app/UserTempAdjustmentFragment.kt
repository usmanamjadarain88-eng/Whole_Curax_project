package com.curax.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.widget.AppCompatImageButton
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import java.util.Locale

/**
 * Default-mode Peltier UI: single target temperature per zone with ±1 °C hysteresis.
 * Live readings from ESP32 BLE (TEMP1/TEMP2); sends `TEMP_SET:...` when connected.
 */
class UserTempAdjustmentFragment : Fragment() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var demoTick = 0
    private var telemetryReceiverRegistered = false

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!isAdded) return
            if (AmbientReadout.shouldShowDemo(requireContext())) {
                demoTick += 1
            } else if (CuraxEsp32BleLink.isConnected()) {
                CuraxEsp32BleLink.sendTempQuery()
            }
            refreshLiveReadings()
            mainHandler.postDelayed(this, 2500L)
        }
    }

    private val bleReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                CuraxEsp32BleLink.ACTION_TELEMETRY -> refreshLiveReadings()
                CuraxEsp32BleLink.ACTION_CONNECTION_STATE -> refreshLiveReadings()
            }
        }
    }

    private var tvCurr1: TextView? = null
    private var tvCurr2: TextView? = null
    private var etTarget1: TextInputEditText? = null
    private var etTarget2: TextInputEditText? = null
    private var sw1: SwitchMaterial? = null
    private var sw2: SwitchMaterial? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_user_temp_adjustment, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        tvCurr1 = view.findViewById(R.id.tvTempCurrentP1)
        tvCurr2 = view.findViewById(R.id.tvTempCurrentP2)
        etTarget1 = view.findViewById(R.id.etPeltier1Target)
        etTarget2 = view.findViewById(R.id.etPeltier2Target)
        sw1 = view.findViewById(R.id.switchPeltier1)
        sw2 = view.findViewById(R.id.switchPeltier2)

        val ctx = requireContext()
        val prefs = Prefs(ctx)
        sw1?.isChecked = prefs.peltier1Enabled
        sw2?.isChecked = prefs.peltier2Enabled
        etTarget1?.setText(formatTarget(prefs.peltier1TargetC))
        etTarget2?.setText(formatTarget(prefs.peltier2TargetC))

        view.findViewById<MaterialButton>(R.id.btnApplyPeltier1).setOnClickListener {
            applyPeltier("peltier1", sw1, etTarget1)
        }
        view.findViewById<MaterialButton>(R.id.btnApplyPeltier2).setOnClickListener {
            applyPeltier("peltier2", sw2, etTarget2)
        }
        view.findViewById<MaterialButton>(R.id.btnApplyBothPeltiers).setOnClickListener {
            applyPeltier("peltier1", sw1, etTarget1)
            applyPeltier("peltier2", sw2, etTarget2)
        }
        view.findViewById<AppCompatImageButton>(R.id.btnRefreshPeltier1Readings).setOnClickListener {
            requestLiveRefresh()
        }
        view.findViewById<AppCompatImageButton>(R.id.btnRefreshPeltier2Readings).setOnClickListener {
            requestLiveRefresh()
        }

        refreshLiveReadings()
    }

    override fun onStart() {
        super.onStart()
        val ctx = context ?: return
        CuraxEsp32BleLink.init(ctx.applicationContext)
        if (!telemetryReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(CuraxEsp32BleLink.ACTION_TELEMETRY)
                addAction(CuraxEsp32BleLink.ACTION_CONNECTION_STATE)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ctx.registerReceiver(bleReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                ctx.registerReceiver(bleReceiver, filter)
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
                context?.unregisterReceiver(bleReceiver)
            } catch (_: Exception) {
            }
            telemetryReceiverRegistered = false
        }
        super.onStop()
    }

    private fun formatTarget(value: Float): String =
        String.format(Locale.US, "%.1f", value)

    private fun parseTarget(et: TextInputEditText?): Float? =
        et?.text?.toString()?.trim()?.toFloatOrNull()

    private fun refreshLiveReadings() {
        val ctx = context ?: return
        val t1 = AmbientReadout.zone1C(ctx, demoTick)
        val t2 = AmbientReadout.zone2C(ctx, demoTick)
        tvCurr1?.text = if (t1 != null) {
            getString(R.string.user_temp_current_reading, formatTarget(t1))
        } else {
            getString(R.string.user_temp_current_reading_none)
        }
        tvCurr2?.text = if (t2 != null) {
            getString(R.string.user_temp_current_reading, formatTarget(t2))
        } else {
            getString(R.string.user_temp_current_reading_none)
        }
    }

    private fun requestLiveRefresh() {
        val ctx = context ?: return
        CuraxEsp32BleLink.init(ctx.applicationContext)
        if (AmbientReadout.shouldShowDemo(ctx)) {
            demoTick += 1
            refreshLiveReadings()
            return
        }
        if (CuraxEsp32BleLink.isConnected()) {
            CuraxEsp32BleLink.sendTempQuery()
        } else {
            CuraxFeedback.info(this, getString(R.string.user_temp_hardware_pending))
        }
        refreshLiveReadings()
    }

    private fun applyPeltier(key: String, sw: SwitchMaterial?, targetEt: TextInputEditText?) {
        val target = parseTarget(targetEt)
        if (target == null) {
            CuraxFeedback.warn(this, getString(R.string.user_temp_invalid_target))
            return
        }
        val ctx = context ?: return
        val prefs = Prefs(ctx)
        // Apply = auto cooling ON for this zone (switch bhi ON karo)
        sw?.isChecked = true
        val enabled = true
        when (key) {
            "peltier1" -> {
                prefs.peltier1TargetC = target
                prefs.peltier1Enabled = enabled
            }
            "peltier2" -> {
                prefs.peltier2TargetC = target
                prefs.peltier2Enabled = enabled
            }
        }
        CuraxEsp32BleLink.init(ctx.applicationContext)
        if (!CuraxEsp32BleLink.isConnected()) {
            CuraxFeedback.info(this, getString(R.string.user_temp_hardware_pending))
            return
        }
        CuraxEsp32BleLink.sendTempSet(key, enabled, target)
        CuraxEsp32BleLink.sendTempQuery()
        CuraxFeedback.success(this, getString(R.string.user_temp_ble_sent))
    }
}
