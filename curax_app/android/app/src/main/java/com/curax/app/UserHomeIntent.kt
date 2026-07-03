package com.curax.app

import android.content.Context
import android.content.Intent

object UserHomeIntent {
    /** Same tabbed home for Default and Standalone; restores saved mode before activity starts. */
    fun forSignedInUser(context: Context): Intent {
        AppModeManager.ensureAccountModeBeforeHome(context)
        return Intent(context, UserStandaloneActivity::class.java)
    }

    /**
     * Sign-in → home: keep one task, slide transition, no [CLEAR_TASK] blank flash.
     */
    fun forSignedInUserAfterSignIn(context: Context): Intent =
        forSignedInUser(context).apply {
            putExtra(UserStandaloneActivity.EXTRA_WARM_FROM_SIGN_IN, true)
        }

    /**
     * Opens user home as the root of a fresh task, clearing auth/onboarding activities underneath.
     * Use after signup link-admin finish so Back cannot return to OTP / link screens.
     */
    fun forSignedInUserClearingBackStack(context: Context): Intent =
        forSignedInUser(context).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            putExtra(UserStandaloneActivity.EXTRA_WARM_FROM_SIGN_IN, true)
        }
}
