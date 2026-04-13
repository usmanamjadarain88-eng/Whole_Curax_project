package com.curax.app

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate

class Prefs(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("curax_prefs", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL
        set(value) = prefs.edit().putString(KEY_SERVER_URL, value).apply()

    var id: String
        get() = prefs.getString(KEY_BOT_ID, "") ?: ""
        set(value) = prefs.edit().putString(KEY_BOT_ID, value).apply()

    var botId: String
        get() = id
        set(value) {
            id = value
        }

    var apiKey: String
        get() = prefs.getString(KEY_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_API_KEY, value).apply()

    var fcmToken: String
        get() = prefs.getString(KEY_FCM_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_FCM_TOKEN, value).apply()

    var themeMode: Int
        get() = prefs.getInt(KEY_THEME_MODE, AppCompatDelegate.MODE_NIGHT_NO)
        set(value) = prefs.edit().putInt(KEY_THEME_MODE, value).apply()

    var appPin: String
        get() = prefs.getString(KEY_APP_PIN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_APP_PIN, value).apply()

    var autoLockSeconds: Int
        get() {
            val raw = prefs.getInt(KEY_AUTO_LOCK_SECONDS, 300)
            return when (raw) {
                300, 600, 1800 -> raw
                30, 60, 5, 15 -> 300
                900 -> 600
                else -> 300
            }
        }
        set(value) {
            val safe = if (value in setOf(300, 600, 1800)) value else 300
            prefs.edit().putInt(KEY_AUTO_LOCK_SECONDS, safe).apply()
        }

    var lastBackgroundAtMs: Long
        get() = prefs.getLong(KEY_LAST_BACKGROUND_AT_MS, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_BACKGROUND_AT_MS, value).apply()

    var lastExitWasClose: Boolean
        get() = prefs.getBoolean(KEY_LAST_EXIT_WAS_CLOSE, false)
        set(value) = prefs.edit().putBoolean(KEY_LAST_EXIT_WAS_CLOSE, value).apply()

    /** Once true, Logs and Reports never show dummy data again; they use only received alerts. */
    var hasEverConnected: Boolean
        get() = prefs.getBoolean(KEY_HAS_EVER_CONNECTED, false)
        set(value) = prefs.edit().putBoolean(KEY_HAS_EVER_CONNECTED, value).apply()

    /** Base URL for API (get-role, connect-to-admin). Uses default when not set. */
    var centralApiUrl: String
        get() = prefs.getString(KEY_CENTRAL_API_URL, "")?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_CENTRAL_API_URL
        set(value) = prefs.edit().putString(KEY_CENTRAL_API_URL, value?.trim().orEmpty()).apply()

    /** Cached connection code for admin (from get-role). Shown in Settings as "My connection code". */
    var connectionCode: String
        get() = prefs.getString(KEY_CONNECTION_CODE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CONNECTION_CODE, value?.trim().orEmpty()).apply()

    /**
     * Admin's access code for the data bus WebSocket room (same as desktop).
     * NOT the human connection_code — without this, user app never receives notify_admin pushes.
     */
    var databusAccessCode: String
        get() = prefs.getString(KEY_DATABUS_ACCESS_CODE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_DATABUS_ACCESS_CODE, value?.trim().orEmpty()).apply()

    /** After user links via "Connect to admin", store the admin id returned by backend. */
    var linkedAdminId: String
        get() = prefs.getString(KEY_LINKED_ADMIN_ID, "") ?: ""
        set(value) = prefs.edit().putString(KEY_LINKED_ADMIN_ID, value?.trim().orEmpty()).apply()

    var linkedAdminName: String
        get() = prefs.getString(KEY_LINKED_ADMIN_NAME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_LINKED_ADMIN_NAME, value?.trim().orEmpty()).apply()

    /** When admin signs up with access code, store it for GET /admin/data (one-time fetch when Dashboard is shown). */
    var adminAccessCode: String
        get() = prefs.getString(KEY_ADMIN_ACCESS_CODE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_ADMIN_ACCESS_CODE, value?.trim().orEmpty()).apply()

    /** Last server_time from GET /admin/data; sent as last_sync_time so backend returns only updated data. */
    var lastSyncTime: String
        get() = prefs.getString(KEY_LAST_SYNC_TIME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_LAST_SYNC_TIME, value?.trim().orEmpty()).apply()

    /** Data bus URL used for real-time admin data_sync over WebSocket. */
    var dataBusUrl: String
        get() = prefs.getString(KEY_DATA_BUS_URL, "")?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_DATA_BUS_URL
        set(value) = prefs.edit().putString(KEY_DATA_BUS_URL, value?.trim().orEmpty()).apply()

    /** When admin taps a connected user in Settings, act as that user; all changes go to that user's data. */
    var actAsUserId: String
        get() = prefs.getString(KEY_ACT_AS_USER_ID, "") ?: ""
        set(value) = prefs.edit().putString(KEY_ACT_AS_USER_ID, value?.trim().orEmpty()).apply()

    var actAsUserName: String
        get() = prefs.getString(KEY_ACT_AS_USER_NAME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_ACT_AS_USER_NAME, value?.trim().orEmpty()).apply()

    var userStandaloneMode: Boolean
        get() = prefs.getBoolean(KEY_USER_STANDALONE_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_USER_STANDALONE_MODE, value).apply()

    /** True once standalone has a cached/bootstrap snapshot to display. */
    var userStandaloneDataReady: Boolean
        get() = prefs.getBoolean(KEY_USER_STANDALONE_DATA_READY, false)
        set(value) = prefs.edit().putBoolean(KEY_USER_STANDALONE_DATA_READY, value).apply()

    /**
     * Cached standalone snapshot used only to bootstrap the user UI on app start.
     * Live DataBus updates overwrite this cache.
     */
    var cachedUserDataSnapshotJson: String
        get() = prefs.getString(KEY_CACHED_USER_DATA_JSON, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CACHED_USER_DATA_JSON, value.trim()).apply()

    companion object {
        private const val DEFAULT_SERVER_URL = "https://curax-relay.onrender.com"
        private const val DEFAULT_CENTRAL_API_URL = "https://whole-curax-project.vercel.app"
        private const val DEFAULT_DATA_BUS_URL = "https://databus-production-6eef.up.railway.app"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_BOT_ID = "bot_id"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_APP_PIN = "app_pin"
        private const val KEY_AUTO_LOCK_SECONDS = "auto_lock_minutes"
        private const val KEY_LAST_BACKGROUND_AT_MS = "last_background_at_ms"
        private const val KEY_LAST_EXIT_WAS_CLOSE = "last_exit_was_close"
        private const val KEY_HAS_EVER_CONNECTED = "has_ever_connected"
        private const val KEY_CENTRAL_API_URL = "central_api_url"
        private const val KEY_CONNECTION_CODE = "connection_code"
        private const val KEY_DATABUS_ACCESS_CODE = "databus_access_code"
        private const val KEY_LINKED_ADMIN_ID = "linked_admin_id"
        private const val KEY_LINKED_ADMIN_NAME = "linked_admin_name"
        private const val KEY_ADMIN_ACCESS_CODE = "admin_access_code"
        private const val KEY_LAST_SYNC_TIME = "last_sync_time"
        private const val KEY_DATA_BUS_URL = "data_bus_url"
        private const val KEY_ACT_AS_USER_ID = "act_as_user_id"
        private const val KEY_ACT_AS_USER_NAME = "act_as_user_name"
        private const val KEY_USER_STANDALONE_MODE = "user_standalone_mode"
        private const val KEY_USER_STANDALONE_DATA_READY = "user_standalone_data_ready"
        private const val KEY_CACHED_USER_DATA_JSON = "cached_user_data_snapshot_json"
        private const val KEY_HAS_REQUESTED_CONNECT_WAKE_PERMISSIONS = "has_requested_connect_wake_permissions"
        const val KEY_FCM_TOKEN = "fcm_token"
    }

    /** True after first Connect tap when we asked for battery + full-screen intent (so we don't ask again). */
    var hasRequestedConnectWakePermissions: Boolean
        get() = prefs.getBoolean(KEY_HAS_REQUESTED_CONNECT_WAKE_PERMISSIONS, false)
        set(value) = prefs.edit().putBoolean(KEY_HAS_REQUESTED_CONNECT_WAKE_PERMISSIONS, value).apply()
}
