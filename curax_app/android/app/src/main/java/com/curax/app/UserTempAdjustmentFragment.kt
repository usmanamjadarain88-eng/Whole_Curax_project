package com.curax.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import java.util.Locale
import kotlin.math.sin

/**
 * Default-mode Peltier UI; sends `TEMP_SET:...` over BLE Nordic UART when connected (same as desktop serial).
 */
class UserTempAdjustmentFragment : Fragment() {

    private val demoHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var demoTick = 0
    private val demoRunnable = object : Runnable {
        override fun run() {
            demoTick += 1
            val t = demoTick * 0.4
            val c1 = 17.8 + 0.9 * sin(t)
            val c2 = 6.0 + 0.5 * sin(t * 1.1 + 0.8)
            tvCurr1?.text = getString(R.string.user_temp_current_reading, String.format(Locale.US, "%.1f", c1))
            tvCurr2?.text = getString(R.string.user_temp_current_reading, String.format(Locale.US, "%.1f", c2))
            demoHandler.postDelayed(this, 2500L)
        }
    }

    private var tvCurr1: TextView? = null
    private var tvCurr2: TextView? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_user_temp_adjustment, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        tvCurr1 = view.findViewById(R.id.tvTempCurrentP1)
        tvCurr2 = view.findViewById(R.id.tvTempCurrentP2)

        val sw1 = view.findViewById<SwitchMaterial>(R.id.switchPeltier1)
        val sw2 = view.findViewById<SwitchMaterial>(R.id.switchPeltier2)
        val et1Min = view.findViewById<TextInputEditText>(R.id.etPeltier1Min)
        val et1Max = view.findViewById<TextInputEditText>(R.id.etPeltier1Max)
        val et2Min = view.findViewById<TextInputEditText>(R.id.etPeltier2Min)
        val et2Max = view.findViewById<TextInputEditText>(R.id.etPeltier2Max)

        fun parseRange(minEt: TextInputEditText, maxEt: TextInputEditText): Pair<Float, Float>? {
            val a = minEt.text?.toString()?.trim()?.toFloatOrNull()
            val b = maxEt.text?.toString()?.trim()?.toFloatOrNull()
            if (a == null || b == null) return null
            if (a >= b) return null
            return a to b
        }

        fun applyPeltier(key: String, sw: SwitchMaterial, minEt: TextInputEditText, maxEt: TextInputEditText) {
            val range = parseRange(minEt, maxEt)
            if (range == null) {
                CuraxFeedback.warn(this@UserTempAdjustmentFragment, getString(R.string.user_temp_invalid_range))
                return
            }
            val ctx = context ?: return
            CuraxEsp32BleLink.init(ctx.applicationContext)
            if (CuraxEsp32BleLink.isConnected()) {
                CuraxEsp32BleLink.sendTempSet(key, sw.isChecked, range.first, range.second)
                CuraxFeedback.success(this@UserTempAdjustmentFragment, getString(R.string.user_temp_ble_sent))
            } else {
                CuraxFeedback.info(this@UserTempAdjustmentFragment, getString(R.string.user_temp_hardware_pending))
            }
        }

        view.findViewById<MaterialButton>(R.id.btnApplyPeltier1).setOnClickListener {
            applyPeltier("peltier1", sw1, et1Min, et1Max)
        }
        view.findViewById<MaterialButton>(R.id.btnApplyPeltier2).setOnClickListener {
            applyPeltier("peltier2", sw2, et2Min, et2Max)
        }
        view.findViewById<MaterialButton>(R.id.btnApplyBothPeltiers).setOnClickListener {
            val r1 = parseRange(et1Min, et1Max)
            val r2 = parseRange(et2Min, et2Max)
            if (r1 == null || r2 == null) {
                CuraxFeedback.warn(this@UserTempAdjustmentFragment, getString(R.string.user_temp_invalid_range))
                return@setOnClickListener
            }
            val ctx = context ?: return@setOnClickListener
            CuraxEsp32BleLink.init(ctx.applicationContext)
            if (CuraxEsp32BleLink.isConnected()) {
                CuraxEsp32BleLink.sendTempSet("peltier1", sw1.isChecked, r1.first, r1.second)
                CuraxEsp32BleLink.sendTempSet("peltier2", sw2.isChecked, r2.first, r2.second)
                CuraxFeedback.success(this@UserTempAdjustmentFragment, getString(R.string.user_temp_ble_sent))
            } else {
                CuraxFeedback.info(this@UserTempAdjustmentFragment, getString(R.string.user_temp_hardware_pending))
            }
        }
    }

    override fun onResume() {
        super.onResume()
        context?.let { CuraxEsp32BleLink.init(it.applicationContext) }
        demoHandler.removeCallbacks(demoRunnable)
        demoHandler.post(demoRunnable)
    }

    override fun onPause() {
        demoHandler.removeCallbacks(demoRunnable)
        super.onPause()
    }
}
