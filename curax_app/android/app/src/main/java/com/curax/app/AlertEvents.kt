package com.curax.app

object AlertEvents {
    const val ACTION_ALERTS_UPDATED = "com.curax.app.ACTION_ALERTS_UPDATED"
    const val ACTION_CONNECTION_STATE_CHANGED = "com.curax.app.ACTION_CONNECTION_STATE_CHANGED"
    /** Sent when GET /admin/data has updated AdminDemoData so every tab can refresh (Settings, Alerts, Reminders, Logs, Reports). */
    const val ACTION_ADMIN_DATA_SYNCED = "com.curax.app.ACTION_ADMIN_DATA_SYNCED"
    /**
     * Hub-only: refresh linked-user stats + per-user dose preview (HTTP).
     * Fire after server snapshot/WebSocket sync — not on every local [ACTION_ADMIN_DATA_SYNCED].
     */
    const val ACTION_ADMIN_HUB_REFRESH_METRICS = "com.curax.app.ACTION_ADMIN_HUB_REFRESH_METRICS"
    /** User GET /user/data fetch started (HTTP in flight). Standalone UI may show sync ripple. */
    const val ACTION_USER_STANDALONE_DATA_FETCH_STARTED = "com.curax.app.ACTION_USER_STANDALONE_DATA_FETCH_STARTED"
    /** User GET /user/data fetch finished (success or failure). Standalone UI returns to idle pulse. */
    const val ACTION_USER_STANDALONE_DATA_FETCH_ENDED = "com.curax.app.ACTION_USER_STANDALONE_DATA_FETCH_ENDED"
    /** Server [user_display_mode] differed from local; home should relaunch to apply Default vs Standalone shell. */
    const val ACTION_USER_DISPLAY_MODE_FROM_SERVER = "com.curax.app.ACTION_USER_DISPLAY_MODE_FROM_SERVER"
    /** User data-bus WebSocket opened/closed/failed; sidebar should refresh realtime / health lines. */
    const val ACTION_USER_DATABUS_SOCKET_STATE = "com.curax.app.ACTION_USER_DATABUS_SOCKET_STATE"
    /** Pending outbound standalone sync queue changed (UI / tray). */
    const val ACTION_PENDING_SYNC_QUEUE_UPDATED = "com.curax.app.ACTION_PENDING_SYNC_QUEUE_UPDATED"
    const val EXTRA_CONNECTED = "connected"
}
