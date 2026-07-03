package com.curax.app

import android.content.Context
import android.net.Uri

/** Builds and parses admin invite links for patient signup / linking. */
object CuraxInviteLink {

    private const val HTTPS_HOST = "curax.app"
    private const val HTTPS_PATH = "/join"
    private const val CUSTOM_SCHEME = "curax"
    private const val CUSTOM_HOST = "join"
    private const val QUERY_CODE = "code"

    /** Opens the app directly when installed (preferred for Android share). */
    fun buildAppDeepLink(connectionCode: String): String {
        val code = connectionCode.trim()
        val enc = java.net.URLEncoder.encode(code, Charsets.UTF_8.name())
        return "$CUSTOM_SCHEME://$CUSTOM_HOST?$QUERY_CODE=$enc"
    }

    /** HTTPS fallback for users without the app yet. */
    fun buildHttpsUrl(connectionCode: String): String {
        val code = connectionCode.trim()
        val enc = java.net.URLEncoder.encode(code, Charsets.UTF_8.name())
        return "https://$HTTPS_HOST$HTTPS_PATH?$QUERY_CODE=$enc"
    }

    /** Primary share URL — opens the app when installed (avoids parking-page HTTPS hosts). */
    fun buildShareUrl(connectionCode: String): String = buildAppDeepLink(connectionCode)

    fun buildShareMessage(context: Context, connectionCode: String): String {
        val appLink = buildAppDeepLink(connectionCode)
        return context.getString(R.string.invite_link_share_message, appLink)
    }

    fun parseConnectionCode(uri: Uri?): String? {
        if (uri == null) return null
        val fromQuery = uri.getQueryParameter(QUERY_CODE)?.trim().orEmpty()
        if (fromQuery.isNotEmpty()) return fromQuery
        if (uri.scheme.equals(CUSTOM_SCHEME, ignoreCase = true) &&
            uri.host.equals(CUSTOM_HOST, ignoreCase = true)
        ) {
            val seg = uri.pathSegments?.firstOrNull()?.trim().orEmpty()
            if (seg.isNotEmpty()) return seg
        }
        if (uri.scheme.equals("https", ignoreCase = true) &&
            uri.host.equals(HTTPS_HOST, ignoreCase = true) &&
            uri.path?.startsWith(HTTPS_PATH) == true
        ) {
            val seg = uri.pathSegments?.lastOrNull()?.trim().orEmpty()
            if (seg.isNotEmpty() && !seg.equals("join", ignoreCase = true)) return seg
        }
        return null
    }
}
