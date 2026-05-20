package com.curax.app

import android.content.Context
import android.content.Intent
import org.json.JSONObject

/**
 * **Display mode** only: Default vs Standalone (medicine box theme, toolbar label).
 * Stored in [Prefs.userStandaloneMode]; server may override when user data sync includes display mode.
 * Not the same as **user vs admin role** ([AppRole]).
 */
object AppModeManager {

    enum class Mode {
        DEFAULT,
        STANDALONE,
    }

    fun getMode(context: Context): Mode {
        return if (Prefs(context.applicationContext).userStandaloneMode) {
            Mode.STANDALONE
        } else {
            Mode.DEFAULT
        }
    }

    fun isStandaloneMode(context: Context): Boolean =
        getMode(context) == Mode.STANDALONE

    fun setStandaloneMode(context: Context, standalone: Boolean) {
        Prefs(context.applicationContext).userStandaloneMode = standalone
    }

    /** Apply server `user_display_mode` without relaunching home (sign-in bootstrap). */
    fun applyDisplayModeFromAuthJson(context: Context, jo: JSONObject, notifyRelaunch: Boolean) {
        val dm = jo.optString("user_display_mode", "").trim().lowercase()
        if (dm == "standalone" || dm == "default") {
            applyDisplayModeValue(context, dm == "standalone", notifyRelaunch)
        }
    }

    /** Live databus/HTTP snapshot — skip when the user already chose a mode on the first-home sheet. */
    fun applyDisplayModeFromUserData(context: Context, data: JSONObject, notifyRelaunch: Boolean) {
        if (Prefs(context.applicationContext).userInitialAppModeSheetCompleted) return
        val dm = data.optString("user_display_mode", "").trim().lowercase()
        if (dm == "standalone" || dm == "default") {
            applyDisplayModeValue(context, dm == "standalone", notifyRelaunch)
        }
    }

    /** After admin link when the user already chose a mode on the first-home sheet. */
    fun applyDisplayModeValueFromServer(context: Context, wantStandalone: Boolean, notifyRelaunch: Boolean) {
        applyDisplayModeValue(context, wantStandalone, notifyRelaunch)
    }

    private fun applyDisplayModeValue(context: Context, wantStandalone: Boolean, notifyRelaunch: Boolean) {
        val app = context.applicationContext
        if (wantStandalone == Prefs(app).userStandaloneMode) return
        setStandaloneMode(app, wantStandalone)
        if (notifyRelaunch) {
            app.sendBroadcast(Intent(AlertEvents.ACTION_USER_DISPLAY_MODE_FROM_SERVER))
        }
    }
}
