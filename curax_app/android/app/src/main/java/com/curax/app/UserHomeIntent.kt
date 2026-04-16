package com.curax.app

import android.content.Context
import android.content.Intent

object UserHomeIntent {
    /** Same tabbed home for Default and Standalone for now; [AppModeManager] is preference only. */
    fun forSignedInUser(context: Context): Intent =
        Intent(context, UserStandaloneActivity::class.java)
}
