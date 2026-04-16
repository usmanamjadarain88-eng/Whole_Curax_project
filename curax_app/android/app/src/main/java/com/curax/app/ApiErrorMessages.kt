package com.curax.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Turns central API JSON (and odd proxy bodies) into user-visible strings.
 * Backend errors use `message` + optional `hint` / `detail` (string or JSON array).
 */
object ApiErrorMessages {

    internal const val MSG_EMPTY_BODY = "empty_response_body"
    internal const val MSG_NON_JSON = "non_json_response"

    fun parseResponseBody(raw: String, httpCode: Int): JSONObject {
        if (raw.isBlank()) {
            return JSONObject().apply {
                put("message", MSG_EMPTY_BODY)
                put("_http_code", httpCode)
            }
        }
        return try {
            JSONObject(raw)
        } catch (_: Exception) {
            JSONObject().apply {
                put("message", MSG_NON_JSON)
                put("_raw_preview", raw.trim().take(400))
                put("_http_code", httpCode)
            }
        }
    }

    fun userMessage(context: Context, httpCode: Int, jo: JSONObject?): String {
        if (jo == null) {
            return if (httpCode < 0) {
                context.getString(R.string.error_network_unreachable)
            } else {
                context.getString(R.string.request_failed)
            }
        }

        val msgKey = jo.optString("message", "").trim().lowercase()
        if (msgKey == MSG_NON_JSON) {
            val prev = jo.optString("_raw_preview", "").trim()
            return if (prev.isNotEmpty()) {
                context.getString(R.string.error_non_json_response, prev.take(200))
            } else {
                context.getString(R.string.error_non_json_short)
            }
        }
        if (msgKey == MSG_EMPTY_BODY) {
            return context.getString(R.string.error_empty_server_response)
        }

        val friendly = friendlyForMessageKey(context, msgKey)
        if (friendly != null) {
            if (msgKey == "email_already_registered" || msgKey == "email_signup_in_progress") {
                return friendly
            }
            val extra = hintOrDetail(jo).trim()
            return if (extra.isNotEmpty() && !friendly.contains(extra)) {
                "$friendly\n\n$extra"
            } else {
                friendly
            }
        }

        val hint = jo.optString("hint", "").trim()
        val detail = detailAsString(jo)
        val m = jo.optString("message", "").trim().ifEmpty { jo.optString("error", "").trim() }

        if (m.isNotEmpty()) {
            val extra = hint.ifEmpty { detail }
            return if (extra.isNotEmpty()) "$m\n\n$extra" else m
        }

        if (detail.isNotEmpty()) return detail
        if (hint.isNotEmpty()) return hint

        if (jo.has("_raw_preview")) {
            return context.getString(
                R.string.error_non_json_response,
                jo.optString("_raw_preview", "").trim().take(200),
            )
        }

        return when {
            httpCode in 500..599 -> context.getString(R.string.error_server_try_later)
            httpCode == 404 -> context.getString(R.string.error_signup_path_not_found)
            msgKey == MSG_EMPTY_BODY -> context.getString(R.string.error_empty_server_response)
            httpCode in 400..499 -> context.getString(R.string.error_http_generic, httpCode)
            else -> context.getString(R.string.request_failed)
        }
    }

    private fun friendlyForMessageKey(context: Context, key: String): String? = when (key) {
        "email_already_registered", "email_signup_in_progress" ->
            context.getString(R.string.api_err_email_already_registered)
        "invalid_email" -> context.getString(R.string.api_err_invalid_email)
        "password_too_short" -> context.getString(R.string.api_err_password_too_short)
        "signup_not_configured" -> context.getString(R.string.api_err_signup_not_configured)
        "database_error" -> context.getString(R.string.error_server_try_later)
        "unknown_email" -> context.getString(R.string.api_err_unknown_email)
        "invalid_password" -> context.getString(R.string.api_err_invalid_password)
        "invalid_state" -> context.getString(R.string.api_err_invalid_state)
        "link_failed" -> context.getString(R.string.api_err_link_failed)
        "invalid_connection_code" -> context.getString(R.string.invalid_connection_code)
        else -> null
    }

    private fun hintOrDetail(jo: JSONObject): String {
        val h = jo.optString("hint", "").trim()
        if (h.isNotEmpty()) return h
        return detailAsString(jo)
    }

    private fun detailAsString(jo: JSONObject): String {
        if (!jo.has("detail")) return ""
        return when (val v = jo.opt("detail")) {
            null -> ""
            is String -> v.trim()
            is JSONArray -> {
                val sb = StringBuilder()
                for (i in 0 until v.length()) {
                    when (val it = v.opt(i)) {
                        is JSONObject -> {
                            val msg = it.optString("msg", "").trim()
                                .ifEmpty { it.optString("message", "").trim() }
                                .ifEmpty { it.toString() }
                            if (msg.isNotEmpty()) {
                                if (sb.isNotEmpty()) sb.append("\n")
                                sb.append(msg)
                            }
                        }
                        is String -> if (it.isNotBlank()) {
                            if (sb.isNotEmpty()) sb.append("\n")
                            sb.append(it.trim())
                        }
                    }
                }
                sb.toString()
            }
            else -> v.toString().trim()
        }
    }
}
