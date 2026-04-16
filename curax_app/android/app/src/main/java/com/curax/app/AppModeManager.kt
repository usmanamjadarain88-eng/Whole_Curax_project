package com.curax.app

import android.content.Context

/**
 * **Display mode** only: Default vs Standalone (medicine box theme, toolbar label).
 * This is stored in [Prefs.userStandaloneMode] — not the same as **user vs admin role** ([AppRole]).
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
