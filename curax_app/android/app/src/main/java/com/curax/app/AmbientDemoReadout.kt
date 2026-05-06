package com.curax.app

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.sin

/** Demo ambient readings until hardware backs live values; shared by sidebar preview and full screen. */
object AmbientDemoReadout {

    data class Formatted(
        val tempZone1: String,
        val tempZone2: String,
        val humidity: String,
        val updatedLine: String,
    )

    fun rawValues(tickSeq: Int): Triple<Double, Double, Double> {
        val t = tickSeq * 0.35
        val base1 = 17.5 + 1.2 * sin(t)
        val base2 = 6.2 + 0.8 * sin(t * 1.3 + 1.0)
        val hum = (48.0 + 6.0 * sin(t * 0.7 + 0.5)).coerceIn(30.0, 75.0)
        return Triple(base1, base2, hum)
    }

    fun format(context: Context, tickSeq: Int): Formatted {
        val (base1, base2, hum) = rawValues(tickSeq)
        val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        return Formatted(
            tempZone1 = String.format(Locale.US, "%.1f °C", base1),
            tempZone2 = String.format(Locale.US, "%.1f °C", base2),
            humidity = String.format(Locale.US, "%.0f %%", hum),
            updatedLine = context.getString(R.string.user_ambient_last_updated, fmt.format(Date())),
        )
    }
}
