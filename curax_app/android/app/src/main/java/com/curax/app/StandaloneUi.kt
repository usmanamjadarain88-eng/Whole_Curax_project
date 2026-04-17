package com.curax.app

import android.content.Context

/** User role + Standalone display mode: tabbed home in [UserStandaloneActivity] only. */
object StandaloneUi {
    fun isUserStandalone(context: Context): Boolean =
        AppRole.isUser(context) && AppModeManager.isStandaloneMode(context)
}
