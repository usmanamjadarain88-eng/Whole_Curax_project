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

    /**
     * Call before opening user home (launcher, PIN unlock, sign-in navigate) so the saved/server mode
     * is applied even after logout cleared the in-session pref.
     */
    fun ensureAccountModeBeforeHome(context: Context) {
        val app = context.applicationContext
        val store = LocalUserStore(app)
        if (!store.hasUser() || store.role != LocalUserStore.ROLE_USER) return
        val prefs = Prefs(app)
        val email = store.email
        val botId = prefs.id
        UserModeSheetPrefs.syncGlobalFlagFromAccount(app, botId, email)
        restoreSavedAccountMode(app, email, botId)
    }

    fun restoreSavedAccountMode(context: Context, email: String, botId: String = Prefs(context.applicationContext).id) {
        val saved = UserModeSheetPrefs.chosenStandaloneForAccount(context, botId, email) ?: return
        setStandaloneMode(context.applicationContext, saved)
    }

    /** Apply server `user_display_mode` without relaunching home (sign-in bootstrap). */
    fun applyDisplayModeFromAuthJson(context: Context, jo: JSONObject, notifyRelaunch: Boolean) {
        applyDisplayModeFromServerJson(context, jo, notifyRelaunch, bootstrapSignIn = true)
    }

    /**
     * @param bootstrapSignIn true on sign-in / first fetch before home — always trust server mode.
     */
    fun applyDisplayModeFromUserData(
        context: Context,
        data: JSONObject,
        notifyRelaunch: Boolean,
        bootstrapSignIn: Boolean = false,
    ) {
        applyDisplayModeFromServerJson(context, data, notifyRelaunch, bootstrapSignIn)
    }

    private fun applyDisplayModeFromServerJson(
        context: Context,
        json: JSONObject,
        notifyRelaunch: Boolean,
        bootstrapSignIn: Boolean = true,
    ) {
        val app = context.applicationContext
        val prefs = Prefs(app)
        val dm = json.optString("user_display_mode", "").trim().lowercase()
        if (dm != "standalone" && dm != "default") return
        val wantStandalone = dm == "standalone"
        val email = LocalUserStore(app).email
        val botId = prefs.id
        val accountDone = UserModeSheetPrefs.hasCompletedForAccount(app, botId, email)
        val localStandalone = prefs.userStandaloneMode
        val shouldApply = bootstrapSignIn ||
            !accountDone ||
            wantStandalone != localStandalone
        if (shouldApply) {
            applyDisplayModeValue(context, wantStandalone, notifyRelaunch)
            if (email.isNotBlank() || botId.isNotBlank()) {
                UserModeSheetPrefs.saveChosenStandalone(app, email, wantStandalone, botId)
            }
        }
        finalizeModeChoiceFromServer(context, wantStandalone)
    }

    fun finalizeModeChoiceFromServer(context: Context, standalone: Boolean? = null) {
        val app = context.applicationContext
        val prefs = Prefs(app)
        val botId = prefs.id.trim()
        if (botId.isEmpty()) return
        val email = LocalUserStore(app).email
        UserModeSheetPrefs.markCompletedForAccount(app, botId, email, standalone)
    }

    fun applyDisplayModeValueFromServer(context: Context, wantStandalone: Boolean, notifyRelaunch: Boolean) {
        applyDisplayModeValue(context, wantStandalone, notifyRelaunch)
        finalizeModeChoiceFromServer(context, wantStandalone)
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
