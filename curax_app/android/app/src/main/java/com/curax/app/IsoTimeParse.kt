package com.curax.app

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Parses API / Postgres timestamps for alert list times (avoids midnight fallback). */
object IsoTimeParse {

    fun toMillisOrNull(raw: String): Long? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        val patterns = arrayOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSSSSXXX",
            "yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ssZ",
            "yyyy-MM-dd HH:mm:ss.SSS",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd",
        )
        for (pat in patterns) {
            try {
                val sdf = SimpleDateFormat(pat, Locale.US)
                if (pat.endsWith("'Z'") || pat.endsWith("XXX") || pat.endsWith("Z")) {
                    sdf.timeZone = TimeZone.getTimeZone("UTC")
                }
                sdf.parse(s)?.time?.let { return it }
            } catch (_: Exception) {
            }
        }
        return null
    }

    fun toMillis(raw: String, fallback: Long = System.currentTimeMillis()): Long =
        toMillisOrNull(raw) ?: fallback
}
