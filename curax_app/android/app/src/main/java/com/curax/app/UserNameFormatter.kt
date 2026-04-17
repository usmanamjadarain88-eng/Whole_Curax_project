package com.curax.app

import java.util.Locale

object UserNameFormatter {
    /** First whitespace-delimited token, title-cased (e.g. "usman amjad" → "Usman"). */
    fun firstNameForHub(raw: String): String {
        val token = raw.trim().split(Regex("\\s+")).firstOrNull() ?: return ""
        if (token.isEmpty()) return ""
        return token.first().uppercaseChar() + token.substring(1).lowercase(Locale.getDefault())
    }
}
