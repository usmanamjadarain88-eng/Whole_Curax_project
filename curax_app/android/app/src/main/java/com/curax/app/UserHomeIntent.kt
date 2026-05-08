package com.curax.app

import android.content.Context
import android.content.Intent

object UserHomeIntent {
    /** Same tabbed home for Default and Standalone for now; [AppModeManager] is preference only. */
    fun forSignedInUser(context: Context): Intent =
        Intent(context, UserStandaloneActivity::class.java)

    /**
     * Opens user home as the root of a fresh task, clearing auth/onboarding activities underneath.
     * Use after signup link or sign-in so Back cannot return to verify-PIN / continue screens.
     */
    fun forSignedInUserClearingBackStack(context: Context): Intent =
        forSignedInUser(context).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
}
