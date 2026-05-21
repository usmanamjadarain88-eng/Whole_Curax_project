package com.curax.app

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import android.net.Uri
import org.json.JSONArray
import java.util.LinkedHashSet
import java.util.Locale

class Prefs(context: Context) {
    private val appContext: Context = context.applicationContext
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

    /**
     * User/admin tapped Connect once (permissions + relay setup). App reopen may restore relay silently.
     * Not set on sign-in or admin link alone — only after the Connect button flow.
     */
    var relayAutoConnectEnabled: Boolean
        get() = prefs.getBoolean(KEY_RELAY_AUTO_CONNECT_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_RELAY_AUTO_CONNECT_ENABLED, value).apply()

    /** Personal Health (standalone): sidebar Complete setup finished once. */
    var standaloneDeviceSetupCompleted: Boolean
        get() = prefs.getBoolean(KEY_STANDALONE_DEVICE_SETUP_COMPLETED, false)
        set(value) = prefs.edit().putBoolean(KEY_STANDALONE_DEVICE_SETUP_COMPLETED, value).apply()

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

    /**
     * Data bus HTTPS base (Central API posts /notify_admin here). Pref overrides [BuildConfig.DATABUS_PUBLIC_URL].
     * Set in android/local.properties as databus.public.url=… or env DATABUS_PUBLIC_URL for CI.
     */
    var dataBusUrl: String
        get() {
            val fromPref = prefs.getString(KEY_DATA_BUS_URL, "")?.trim().orEmpty()
            if (fromPref.isNotEmpty()) return fromPref
            val fromBuild = BuildConfig.DATABUS_PUBLIC_URL.trim()
            if (fromBuild.isNotEmpty()) return fromBuild
            return DEFAULT_DATA_BUS_URL
        }
        set(value) = prefs.edit().putString(KEY_DATA_BUS_URL, value?.trim().orEmpty()).apply()

    /**
     * Ably subscribe-only key (channel admin:<ACCESS_CODE>). Pref overrides [BuildConfig.DATABUS_ABLY_SUBSCRIBE_KEY].
     * Empty = raw WebSocket to [dataBusUrl]. Fill local.properties databus.ably.subscribe.key=… (gitignored).
     */
    var dataBusAblySubscribeKey: String
        get() {
            val fromPref = prefs.getString(KEY_DATA_BUS_ABLY_SUBSCRIBE_KEY, "")?.trim().orEmpty()
            if (fromPref.isNotEmpty()) return fromPref
            return BuildConfig.DATABUS_ABLY_SUBSCRIBE_KEY.trim()
        }
        set(value) = prefs.edit().putString(KEY_DATA_BUS_ABLY_SUBSCRIBE_KEY, value?.trim().orEmpty()).apply()

    /** When admin taps a connected user in Settings, act as that user; all changes go to that user's data. */
    var actAsUserId: String
        get() = prefs.getString(KEY_ACT_AS_USER_ID, "") ?: ""
        set(value) = prefs.edit().putString(KEY_ACT_AS_USER_ID, value?.trim().orEmpty()).apply()

    var actAsUserName: String
        get() = prefs.getString(KEY_ACT_AS_USER_NAME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_ACT_AS_USER_NAME, value?.trim().orEmpty()).apply()

    /**
     * Server `user_display_mode` for the user currently in Care mode (`standalone` | `default` | empty).
     * Drives [CareUi.useStandaloneLayoutsInCare]; cleared when [actAsUserId] is cleared.
     */
    var actAsUserDisplayMode: String
        get() = prefs.getString(KEY_ACT_AS_USER_DISPLAY_MODE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_ACT_AS_USER_DISPLAY_MODE, value?.trim().orEmpty()).apply()

    var userStandaloneMode: Boolean
        /** Default false = Default mode until the user saves Standalone in the mode popup. */
        get() = prefs.getBoolean(KEY_USER_STANDALONE_MODE, false)
        set(value) {
            prefs.edit().putBoolean(KEY_USER_STANDALONE_MODE, value).commit()
        }

    /**
     * False only after signup link-admin until the mandatory first-home mode bottom sheet completes.
     * Default true so existing installs are not forced through the sheet.
     */
    var userInitialAppModeSheetCompleted: Boolean
        get() = prefs.getBoolean(KEY_USER_INITIAL_MODE_SHEET, true)
        set(value) {
            prefs.edit().putBoolean(KEY_USER_INITIAL_MODE_SHEET, value).commit()
        }

    /** Counts [UserStandaloneActivity] cold starts (savedInstanceState == null) for deferred PIN prompt timing. */
    var userHomeColdStartCount: Int
        get() = prefs.getInt(KEY_USER_HOME_COLD_START_COUNT, 0)
        set(value) {
            prefs.edit().putInt(KEY_USER_HOME_COLD_START_COUNT, value).apply()
        }

    /** True after the automatic "Secure your app with PIN?" dialog was shown once from home. */
    var pinDeferredAutoPromptShown: Boolean
        get() = prefs.getBoolean(KEY_PIN_DEFERRED_AUTO_PROMPT_SHOWN, false)
        set(value) {
            prefs.edit().putBoolean(KEY_PIN_DEFERRED_AUTO_PROMPT_SHOWN, value).commit()
        }

    /** True once standalone has a cached/bootstrap snapshot to display. */
    var userStandaloneDataReady: Boolean
        get() = prefs.getBoolean(KEY_USER_STANDALONE_DATA_READY, false)
        set(value) = prefs.edit().putBoolean(KEY_USER_STANDALONE_DATA_READY, value).apply()

    /** Last signed-in user bot_id — used to wipe [AdminDemoData] when a different account opens the app. */
    var lastActiveSessionBotId: String
        get() = prefs.getString(KEY_LAST_ACTIVE_SESSION_BOT_ID, "") ?: ""
        set(value) = prefs.edit().putString(KEY_LAST_ACTIVE_SESSION_BOT_ID, value.trim()).apply()

    /**
     * Cached standalone snapshot used only to bootstrap the user UI on app start.
     * Live DataBus updates overwrite this cache.
     */
    var cachedUserDataSnapshotJson: String
        get() = prefs.getString(KEY_CACHED_USER_DATA_JSON, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CACHED_USER_DATA_JSON, value.trim()).apply()

    /** Title greeting for standalone Health hub (first name from signup / server). */
    var userHubFirstName: String
        get() = prefs.getString(KEY_USER_HUB_FIRST_NAME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_USER_HUB_FIRST_NAME, value.trim()).apply()

    /** Full display name from backend (sidebar under avatar); falls back to [userHubFirstName] in UI when empty. */
    var userHubFullName: String
        get() = prefs.getString(KEY_USER_HUB_FULL_NAME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_USER_HUB_FULL_NAME, value.trim()).apply()

    /** [users.username] from backend — shown under avatar when set (signup display name). */
    var userHubUsername: String
        get() = prefs.getString(KEY_USER_HUB_USERNAME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_USER_HUB_USERNAME, value.trim()).apply()

    /** User avatar as `data:image/jpeg;base64,...` from server + local selection (also under profile_picture in cached JSON). */
    var userProfilePictureDataUrl: String
        get() = prefs.getString(KEY_USER_PROFILE_PICTURE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_USER_PROFILE_PICTURE, value).apply()

    /** Standalone local alerts: optional ringtone URI string ([StandaloneLocalAlertSettingsActivity]). Empty = default alarm/notification. */
    var standaloneLocalAlertSoundUri: String
        get() = prefs.getString(KEY_STANDALONE_LOCAL_ALERT_SOUND_URI, "") ?: ""
        set(value) = prefs.edit().putString(KEY_STANDALONE_LOCAL_ALERT_SOUND_URI, value.trim()).apply()

    /** Saved sounds list for [StandaloneAlertSoundPickerActivity]; JSON array of URI strings (deduped). */
    private var standaloneLocalAlertSoundLibraryJson: String
        get() = prefs.getString(KEY_STANDALONE_ALERT_SOUND_LIBRARY_JSON, "[]") ?: "[]"
        set(value) {
            prefs.edit().putString(KEY_STANDALONE_ALERT_SOUND_LIBRARY_JSON, value).commit()
        }

    fun getStandaloneAlertSoundLibrary(): List<String> {
        val raw = standaloneLocalAlertSoundLibraryJson.trim()
        if (raw.isEmpty()) return emptyList()
        return try {
            val a = JSONArray(raw)
            buildList {
                for (i in 0 until a.length()) {
                    val s = a.optString(i).trim()
                    if (s.isNotEmpty()) add(s)
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun normalizeStandaloneSoundUriKey(uriStr: String): String {
        val t = uriStr.trim()
        if (t.isEmpty() || t.equals("silent", ignoreCase = true)) return t.lowercase()
        return try {
            Uri.parse(t).toString()
        } catch (_: Exception) {
            t
        }
    }

    /** Whether this URI is present in the saved library ([addStandaloneAlertSoundToLibrary]). */
    fun isStandaloneAlertSoundInLibrary(uriStr: String): Boolean {
        val key = normalizeStandaloneSoundUriKey(uriStr)
        if (key.isEmpty() || key == "silent") return false
        return getStandaloneAlertSoundLibrary().any { normalizeStandaloneSoundUriKey(it) == key }
    }

    private fun saveStandaloneAlertSoundLibrary(uris: List<String>) {
        val seen = LinkedHashSet<String>()
        val distinct = uris.mapNotNull { u ->
            val k = normalizeStandaloneSoundUriKey(u)
            if (k.isEmpty() || k == "silent") null
            else if (seen.add(k)) u.trim() else null
        }.take(48)
        val a = JSONArray()
        distinct.forEach { a.put(it) }
        standaloneLocalAlertSoundLibraryJson = a.toString()
    }

    /** @return false if already present (duplicate). */
    fun addStandaloneAlertSoundToLibrary(uriStr: String): Boolean {
        val t = uriStr.trim()
        if (t.isEmpty() || t.equals("silent", ignoreCase = true)) return false
        val key = normalizeStandaloneSoundUriKey(t)
        val list = getStandaloneAlertSoundLibrary().toMutableList()
        if (list.any { normalizeStandaloneSoundUriKey(it) == key }) return false
        list.add(t)
        saveStandaloneAlertSoundLibrary(list)
        return true
    }

    fun removeStandaloneAlertSoundFromLibrary(uriStr: String) {
        val key = normalizeStandaloneSoundUriKey(uriStr)
        val list = getStandaloneAlertSoundLibrary().filter { normalizeStandaloneSoundUriKey(it) != key }
        saveStandaloneAlertSoundLibrary(list)
    }

    /** Tones hidden from picker (device-list URIs); does not delete files on disk. */
    fun getHiddenStandaloneToneKeys(): Set<String> {
        val raw = prefs.getString(KEY_STANDALONE_HIDDEN_TONE_KEYS_JSON, "[]") ?: "[]"
        if (raw.isBlank()) return emptySet()
        return try {
            val a = JSONArray(raw)
            buildSet {
                for (i in 0 until a.length()) {
                    val s = a.optString(i).trim()
                    if (s.isNotEmpty()) add(s)
                }
            }
        } catch (_: Exception) {
            emptySet()
        }
    }

    fun addHiddenStandaloneToneKey(normalizedKey: String) {
        val k = normalizedKey.trim()
        if (k.isEmpty() || k.equals("silent", ignoreCase = true)) return
        val cur = getHiddenStandaloneToneKeys().toMutableSet()
        if (!cur.add(k)) return
        val a = JSONArray()
        cur.take(96).forEach { a.put(it) }
        prefs.edit().putString(KEY_STANDALONE_HIDDEN_TONE_KEYS_JSON, a.toString()).commit()
    }

    /** Normalized display title so hidden built-ins stay hidden even if MediaStore URI changes. */
    fun normalizeStandaloneToneTitleKey(title: String): String =
        title.trim().lowercase(Locale.US).replace(Regex("\\s+"), " ")

    fun getHiddenStandaloneToneTitles(): Set<String> {
        val raw = prefs.getString(KEY_STANDALONE_HIDDEN_TONE_TITLES_JSON, "[]") ?: "[]"
        if (raw.isBlank()) return emptySet()
        return try {
            val a = JSONArray(raw)
            buildSet {
                for (i in 0 until a.length()) {
                    val s = normalizeStandaloneToneTitleKey(a.optString(i))
                    if (s.isNotEmpty()) add(s)
                }
            }
        } catch (_: Exception) {
            emptySet()
        }
    }

    fun addHiddenStandaloneToneTitle(rawTitle: String) {
        val t = normalizeStandaloneToneTitleKey(rawTitle)
        if (t.isEmpty()) return
        val cur = getHiddenStandaloneToneTitles().toMutableSet()
        if (!cur.add(t)) return
        val a = JSONArray()
        cur.take(96).forEach { key -> a.put(key) }
        prefs.edit().putString(KEY_STANDALONE_HIDDEN_TONE_TITLES_JSON, a.toString()).commit()
    }

    /**
     * v4: clears library + URI + hidden list once. Swipe-left works for every tone row: library = remove;
     * device-only = hide from this list ([addHiddenStandaloneToneKey]).
     */
    fun runOneTimeStandaloneSoundLibraryResetIfNeeded() {
        if (prefs.getBoolean(KEY_STANDALONE_SOUND_LIB_RESET_V4, false)) return
        prefs.edit()
            .putString(KEY_STANDALONE_ALERT_SOUND_LIBRARY_JSON, "[]")
            .putString(KEY_STANDALONE_LOCAL_ALERT_SOUND_URI, "")
            .putString(KEY_STANDALONE_HIDDEN_TONE_KEYS_JSON, "[]")
            .putString(KEY_STANDALONE_HIDDEN_TONE_TITLES_JSON, "[]")
            .putBoolean(KEY_STANDALONE_SOUND_LIB_RESET_V4, true)
            .commit()
    }

    /**
     * When the app is removed and installed again, [android.content.pm.PackageInfo.firstInstallTime]
     * changes; clear custom alert sounds so only built‑ins show. Updates keep the same install time,
     * so the user's library is preserved across normal upgrades (adb install -r still keeps data).
     */
    fun applyStandaloneSoundLibraryInstallGuard(context: Context) {
        val fi = packageFirstInstallTimeMs(context) ?: return
        val prev = prefs.getLong(KEY_STANDALONE_SOUND_PACKAGE_FIRST_INSTALL_MS, -1L)
        if (prev == -1L) {
            prefs.edit().putLong(KEY_STANDALONE_SOUND_PACKAGE_FIRST_INSTALL_MS, fi).apply()
            return
        }
        if (prev != fi) {
            prefs.edit()
                .putString(KEY_STANDALONE_ALERT_SOUND_LIBRARY_JSON, "[]")
                .putString(KEY_STANDALONE_LOCAL_ALERT_SOUND_URI, "")
                .putString(KEY_STANDALONE_HIDDEN_TONE_KEYS_JSON, "[]")
                .putString(KEY_STANDALONE_HIDDEN_TONE_TITLES_JSON, "[]")
                .putLong(KEY_STANDALONE_SOUND_PACKAGE_FIRST_INSTALL_MS, fi)
                .commit()
        }
    }

    private fun packageFirstInstallTimeMs(context: Context): Long? {
        return try {
            val pm = context.packageManager
            val pn = context.packageName
            val pi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(pn, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION") pm.getPackageInfo(pn, 0)
            }
            pi.firstInstallTime
        } catch (_: Exception) {
            null
        }
    }

    var standaloneLocalAlertVibrate: Boolean
        get() = prefs.getBoolean(KEY_STANDALONE_LOCAL_ALERT_VIBRATE, true)
        set(value) = prefs.edit().putBoolean(KEY_STANDALONE_LOCAL_ALERT_VIBRATE, value).apply()

    /** Default snooze duration shown in settings (minutes); full snooze UX may expand later. */
    var standaloneLocalAlertSnoozeMinutes: Int
        get() = prefs.getInt(KEY_STANDALONE_LOCAL_ALERT_SNOOZE_MIN, 5).coerceIn(1, 120)
        set(value) {
            prefs.edit().putInt(KEY_STANDALONE_LOCAL_ALERT_SNOOZE_MIN, value.coerceIn(1, 120)).apply()
        }

    /** True after first Connect tap when we asked for battery + full-screen intent (so we don't ask again). */
    var hasRequestedConnectWakePermissions: Boolean
        get() = prefs.getBoolean(KEY_HAS_REQUESTED_CONNECT_WAKE_PERMISSIONS, false)
        set(value) = prefs.edit().putBoolean(KEY_HAS_REQUESTED_CONNECT_WAKE_PERMISSIONS, value).apply()

    /** Last paired ESP32 for Nordic UART (dose LED / temp commands) in default user mode. */
    var esp32BleDeviceAddress: String
        get() = prefs.getString(KEY_ESP32_BLE_ADDR, "") ?: ""
        set(value) = prefs.edit().putString(KEY_ESP32_BLE_ADDR, value.trim()).apply()

    var esp32BleDeviceName: String
        get() = prefs.getString(KEY_ESP32_BLE_NAME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_ESP32_BLE_NAME, value.trim()).apply()

    /** Last device PIN after successful SET_PASSWORD over BLE (digits only); convenience only. */
    var esp32CachedDevicePin: String
        get() = prefs.getString(KEY_ESP32_CACHED_PIN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_ESP32_CACHED_PIN, value.filter { it.isDigit() }).apply()

    /**
     * In-memory-only [SignUpFlowState] is lost when the process dies. Persist email verified → link-admin
     * credentials so Continue / cold resume still work.
     */
    var signupWipEmail: String
        get() = prefs.getString(KEY_SIGNUP_WIP_EMAIL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SIGNUP_WIP_EMAIL, value.trim()).apply()

    var signupWipPassword: String
        get() = prefs.getString(KEY_SIGNUP_WIP_PASSWORD, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SIGNUP_WIP_PASSWORD, value).apply()

    var signupWipBotId: String
        get() = prefs.getString(KEY_SIGNUP_WIP_BOT_ID, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SIGNUP_WIP_BOT_ID, value.trim()).apply()

    var signupWipApiKey: String
        get() = prefs.getString(KEY_SIGNUP_WIP_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SIGNUP_WIP_API_KEY, value.trim()).apply()

    var signupWipNameForLink: String
        get() = prefs.getString(KEY_SIGNUP_WIP_NAME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SIGNUP_WIP_NAME, value.trim()).apply()

    fun hasSignupWipLink(): Boolean {
        val e = signupWipEmail.trim()
        val p = signupWipPassword
        val b = signupWipBotId.trim()
        val k = signupWipApiKey.trim()
        return e.isNotEmpty() && p.isNotEmpty() && b.isNotEmpty() && k.isNotEmpty()
    }

    /**
     * Single synchronous [commit] before opening home after a directory admin link request (or sign-in resume).
     * Async [.apply] on many pref fields previously raced: the next activity could miss [awaitingAdminLinkApproval]
     * and treat GET /user/data 404 as logout.
     */
    fun commitAwaitingAdminHomeSession(
        chosenAdminDisplayName: String,
        botId: String,
        apiKey: String,
        emailForWip: String,
        passwordForWip: String,
        nameForLinkForWip: String,
    ): Boolean {
        val ed = prefs.edit()
            .putBoolean(KEY_AWAITING_ADMIN_LINK, true)
            .putString(KEY_AWAITING_ADMIN_CHOSEN_NAME, chosenAdminDisplayName.trim())
            .putString(KEY_BOT_ID, botId.trim())
            .putString(KEY_API_KEY, apiKey.trim())
            .putString(KEY_LINKED_ADMIN_ID, "")
            .putString(KEY_LINKED_ADMIN_NAME, "")
            .putString(KEY_CONNECTION_CODE, "")
            .putString(KEY_DATABUS_ACCESS_CODE, "")
            .putBoolean(KEY_HAS_EVER_CONNECTED, false)
            .putBoolean(
                KEY_USER_INITIAL_MODE_SHEET,
                !UserModeSheetPrefs.hasCompletedForBot(appContext, botId.trim()),
            )
            .putBoolean(KEY_USER_STANDALONE_DATA_READY, false)
            .putBoolean(KEY_USER_STANDALONE_MODE, false)
            .putString(KEY_USER_HUB_FIRST_NAME, "")
            .putString(KEY_USER_HUB_FULL_NAME, "")
            .putString(KEY_USER_HUB_USERNAME, "")
            .putString(KEY_USER_PROFILE_PICTURE, "")
            .putString(KEY_SIGNUP_WIP_EMAIL, emailForWip.trim())
            .putString(KEY_SIGNUP_WIP_PASSWORD, passwordForWip)
            .putString(KEY_SIGNUP_WIP_BOT_ID, botId.trim())
            .putString(KEY_SIGNUP_WIP_API_KEY, apiKey.trim())
            .putString(KEY_SIGNUP_WIP_NAME, nameForLinkForWip.trim())
            .remove(KEY_CACHED_USER_DATA_JSON)
        val ok = ed.commit()
        if (ok) {
            UserSessionIsolate.ensureSessionForBotId(appContext, botId)
            // Directory-request home must not show the previous account's reminders / alerts / logs.
            UserSessionIsolate.clearUserScopedData(appContext)
            Prefs(appContext).lastActiveSessionBotId = botId.trim()
        }
        return ok
    }

    fun clearSignupWipLink() {
        prefs.edit()
            .remove(KEY_SIGNUP_WIP_EMAIL)
            .remove(KEY_SIGNUP_WIP_PASSWORD)
            .remove(KEY_SIGNUP_WIP_BOT_ID)
            .remove(KEY_SIGNUP_WIP_API_KEY)
            .remove(KEY_SIGNUP_WIP_NAME)
            .apply()
    }

    /** True after user chose an admin from directory until server link completes. */
    var awaitingAdminLinkApproval: Boolean
        get() = prefs.getBoolean(KEY_AWAITING_ADMIN_LINK, false)
        set(value) = prefs.edit().putBoolean(KEY_AWAITING_ADMIN_LINK, value).apply()

    /** Chosen admin display name for sidebar copy while [awaitingAdminLinkApproval]. */
    var awaitingAdminChosenDisplayName: String
        get() = prefs.getString(KEY_AWAITING_ADMIN_CHOSEN_NAME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_AWAITING_ADMIN_CHOSEN_NAME, value.trim()).apply()

    companion object {
        private const val DEFAULT_SERVER_URL = "https://curax-relay.onrender.com"
        private const val DEFAULT_CENTRAL_API_URL = "https://whole-curax-project.vercel.app"
        private const val DEFAULT_DATA_BUS_URL = "https://databus.vercel.app"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_BOT_ID = "bot_id"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_APP_PIN = "app_pin"
        private const val KEY_AUTO_LOCK_SECONDS = "auto_lock_minutes"
        private const val KEY_LAST_BACKGROUND_AT_MS = "last_background_at_ms"
        private const val KEY_LAST_EXIT_WAS_CLOSE = "last_exit_was_close"
        private const val KEY_HAS_EVER_CONNECTED = "has_ever_connected"
        private const val KEY_RELAY_AUTO_CONNECT_ENABLED = "relay_auto_connect_enabled"
        private const val KEY_CENTRAL_API_URL = "central_api_url"
        private const val KEY_CONNECTION_CODE = "connection_code"
        private const val KEY_DATABUS_ACCESS_CODE = "databus_access_code"
        private const val KEY_LINKED_ADMIN_ID = "linked_admin_id"
        private const val KEY_LINKED_ADMIN_NAME = "linked_admin_name"
        private const val KEY_ADMIN_ACCESS_CODE = "admin_access_code"
        private const val KEY_LAST_SYNC_TIME = "last_sync_time"
        private const val KEY_DATA_BUS_URL = "data_bus_url"
        private const val KEY_DATA_BUS_ABLY_SUBSCRIBE_KEY = "data_bus_ably_subscribe_key"
        private const val KEY_ACT_AS_USER_ID = "act_as_user_id"
        private const val KEY_ACT_AS_USER_NAME = "act_as_user_name"
        private const val KEY_ACT_AS_USER_DISPLAY_MODE = "act_as_user_display_mode"
        private const val KEY_USER_STANDALONE_MODE = "user_standalone_mode"
        private const val KEY_USER_INITIAL_MODE_SHEET = "user_initial_app_mode_sheet_completed"
        private const val KEY_USER_HOME_COLD_START_COUNT = "user_home_cold_start_count"
        private const val KEY_PIN_DEFERRED_AUTO_PROMPT_SHOWN = "pin_deferred_auto_prompt_shown"
        private const val KEY_USER_STANDALONE_DATA_READY = "user_standalone_data_ready"
        private const val KEY_LAST_ACTIVE_SESSION_BOT_ID = "last_active_session_bot_id"
        private const val KEY_CACHED_USER_DATA_JSON = "cached_user_data_snapshot_json"
        private const val KEY_USER_HUB_FIRST_NAME = "user_hub_first_name"
        private const val KEY_USER_HUB_FULL_NAME = "user_hub_full_name"
        private const val KEY_USER_HUB_USERNAME = "user_hub_username"
        private const val KEY_USER_PROFILE_PICTURE = "user_profile_picture_data_url"
        private const val KEY_STANDALONE_LOCAL_ALERT_SOUND_URI = "standalone_local_alert_sound_uri"
        private const val KEY_STANDALONE_ALERT_SOUND_LIBRARY_JSON = "standalone_alert_sound_library_json"
        private const val KEY_STANDALONE_HIDDEN_TONE_KEYS_JSON = "standalone_alert_hidden_tone_keys_json"
        private const val KEY_STANDALONE_HIDDEN_TONE_TITLES_JSON = "standalone_alert_hidden_tone_titles_json"
        private const val KEY_STANDALONE_SOUND_LIB_RESET_V4 = "standalone_sound_lib_reset_v4"
        private const val KEY_STANDALONE_SOUND_PACKAGE_FIRST_INSTALL_MS = "standalone_sound_pkg_first_install_ms"
        private const val KEY_STANDALONE_LOCAL_ALERT_VIBRATE = "standalone_local_alert_vibrate"
        private const val KEY_STANDALONE_LOCAL_ALERT_SNOOZE_MIN = "standalone_local_alert_snooze_min"
        private const val KEY_HAS_REQUESTED_CONNECT_WAKE_PERMISSIONS = "has_requested_connect_wake_permissions"
        private const val KEY_STANDALONE_DEVICE_SETUP_COMPLETED = "standalone_device_setup_completed"
        private const val KEY_ESP32_BLE_ADDR = "esp32_ble_device_address"
        private const val KEY_ESP32_BLE_NAME = "esp32_ble_device_name"
        private const val KEY_ESP32_CACHED_PIN = "esp32_cached_device_pin"
        private const val KEY_SIGNUP_WIP_EMAIL = "signup_wip_email"
        private const val KEY_SIGNUP_WIP_PASSWORD = "signup_wip_password"
        private const val KEY_SIGNUP_WIP_BOT_ID = "signup_wip_bot_id"
        private const val KEY_SIGNUP_WIP_API_KEY = "signup_wip_api_key"
        private const val KEY_SIGNUP_WIP_NAME = "signup_wip_name_for_link"
        private const val KEY_AWAITING_ADMIN_LINK = "awaiting_admin_link_approval"
        private const val KEY_AWAITING_ADMIN_CHOSEN_NAME = "awaiting_admin_chosen_display_name"
    }
}
