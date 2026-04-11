package com.curax.app

object AlertEvents {
    const val ACTION_ALERTS_UPDATED = "com.curax.app.ACTION_ALERTS_UPDATED"
    const val ACTION_CONNECTION_STATE_CHANGED = "com.curax.app.ACTION_CONNECTION_STATE_CHANGED"
    /** Sent when GET /admin/data has updated AdminDemoData so every tab can refresh (Settings, Alerts, Reminders, Logs, Reports). */
    const val ACTION_ADMIN_DATA_SYNCED = "com.curax.app.ACTION_ADMIN_DATA_SYNCED"
    const val EXTRA_CONNECTED = "connected"
}
