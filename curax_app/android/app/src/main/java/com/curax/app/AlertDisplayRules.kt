package com.curax.app

/**
 * Admin-hub alerts from the admin's own desktop (system start/unlock, login, test)
 * are not linked-user events — no "User" line in notifications or list rows.
 */
object AlertDisplayRules {

    fun isAdminHubSystemEvent(type: String): Boolean {
        val t = type.trim().lowercase()
        if (t.isEmpty()) return false
        return t == "system_started" ||
            t == "system_unlocked" ||
            t == "admin_login" ||
            t == "test_alert" ||
            t.contains("test_alert") ||
            (t.contains("test") && t.contains("alert"))
    }

    /** Persist / show in Alerts list — empty for admin-desktop system events. */
    fun linkedUserLabelForAlert(type: String, storedUserName: String): String {
        if (isAdminHubSystemEvent(type)) return ""
        val s = storedUserName.trim()
        if (s.isEmpty() || s.equals("user", ignoreCase = true) || s.equals("null", ignoreCase = true)) {
            return ""
        }
        return s
    }

    /** Notification subtext only for real linked-user alerts. */
    fun notificationSubtext(type: String, userName: String): String =
        linkedUserLabelForAlert(type, userName)
}
