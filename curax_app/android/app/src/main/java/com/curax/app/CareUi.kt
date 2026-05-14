package com.curax.app

import android.content.Context

/**
 * Admin "Care mode" ([Prefs.actAsUserId] set): mirror the linked user's app shell in admin fragments
 * without treating the device as a real standalone user ([StandaloneUi] stays false).
 */
object CareUi {

    fun isAdminCareMode(context: Context): Boolean {
        val app = context.applicationContext
        return AppRole.isAdmin(app) && Prefs(app).actAsUserId.trim().isNotEmpty()
    }

    /** True when the managed user is in Personal Health (standalone) mode — use standalone XML layouts. */
    fun useStandaloneLayoutsInCare(context: Context): Boolean {
        val app = context.applicationContext
        val p = Prefs(app)
        if (!AppRole.isAdmin(app) || p.actAsUserId.trim().isEmpty()) return false
        return p.actAsUserDisplayMode.trim().equals("standalone", ignoreCase = true)
    }

    /** Standalone user home shell OR admin care mirroring a standalone linked user. */
    fun effectiveStandaloneShell(context: Context): Boolean =
        StandaloneUi.isUserStandalone(context) || useStandaloneLayoutsInCare(context)
}
