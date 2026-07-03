package com.curax.app

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Sidebar / ambient / T-adjustment readout.
 * Demo values only until the first successful ESP32 BLE connection; after that, live-only forever.
 */
object AmbientReadout {

    data class Formatted(
        val tempZone1: String,
        val tempZone2: String,
        val humidity: String,
        val updatedLine: String,
        val isLive: Boolean,
        val showingDemo: Boolean,
    )

    fun shouldShowDemo(context: Context): Boolean {
        CuraxEsp32BleLink.init(context)
        return !Prefs(context).esp32BleEverConnected
    }

    fun format(context: Context, demoTick: Int = 0): Formatted {
        CuraxEsp32BleLink.init(context)
        val prefs = Prefs(context)

        if (!prefs.esp32BleEverConnected) {
            val demo = AmbientDemoReadout.format(context, demoTick.coerceAtLeast(1))
            return Formatted(
                tempZone1 = demo.tempZone1,
                tempZone2 = demo.tempZone2,
                humidity = demo.humidity,
                updatedLine = demo.updatedLine,
                isLive = false,
                showingDemo = true,
            )
        }

        if (CuraxEsp32BleLink.isConnected()) {
            val t1 = CuraxEsp32BleLink.tempZone1C
            val t2 = CuraxEsp32BleLink.tempZone2C
            val hum = CuraxEsp32BleLink.humidityPct
            val ts = CuraxEsp32BleLink.lastTelemetryMs().takeIf { it > 0L } ?: System.currentTimeMillis()
            val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            return Formatted(
                tempZone1 = t1?.let { String.format(Locale.US, "%.1f °C", it) } ?: "— °C",
                tempZone2 = t2?.let { String.format(Locale.US, "%.1f °C", it) } ?: "— °C",
                humidity = hum?.let { String.format(Locale.US, "%.0f %%", it) } ?: "— %",
                updatedLine = context.getString(R.string.user_ambient_last_updated, fmt.format(Date(ts))),
                isLive = t1 != null || t2 != null,
                showingDemo = false,
            )
        }

        return Formatted(
            tempZone1 = "— °C",
            tempZone2 = "— °C",
            humidity = "— %",
            updatedLine = context.getString(R.string.user_ambient_not_connected),
            isLive = false,
            showingDemo = false,
        )
    }

    fun zone1C(context: Context, demoTick: Int = 0): Float? {
        if (shouldShowDemo(context)) {
            return AmbientDemoReadout.rawValues(demoTick.coerceAtLeast(1)).first.toFloat()
        }
        return CuraxEsp32BleLink.tempZone1C
    }

    fun zone2C(context: Context, demoTick: Int = 0): Float? {
        if (shouldShowDemo(context)) {
            return AmbientDemoReadout.rawValues(demoTick.coerceAtLeast(1)).second.toFloat()
        }
        return CuraxEsp32BleLink.tempZone2C
    }
}
