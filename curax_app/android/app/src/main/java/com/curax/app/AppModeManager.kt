package com.curax.app

import android.content.Context

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
}
