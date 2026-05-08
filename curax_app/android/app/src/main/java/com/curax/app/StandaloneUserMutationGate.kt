package com.curax.app

import android.app.Activity
import android.content.Context
import androidx.fragment.app.Fragment

/**
 * Standalone user home is demo/read-only until [Prefs.linkedAdminId] is set after linking an admin.
 */
object StandaloneUserMutationGate {

    fun isStandaloneUserWithoutAdminLink(context: Context): Boolean {
        if (!AppRole.isUser(context)) return false
        if (!StandaloneUi.isUserStandalone(context)) return false
        return Prefs(context).linkedAdminId.trim().isEmpty()
    }

    fun allowMutations(context: Context): Boolean =
        !isStandaloneUserWithoutAdminLink(context)

    /** @return true if the caller should continue with a write/action */
    fun warnIfBlocked(fragment: Fragment): Boolean {
        if (allowMutations(fragment.requireContext())) return true
        CuraxFeedback.warn(fragment, R.string.standalone_connect_admin_first)
        return false
    }

    /** @return true if the caller should continue with a write/action */
    fun warnIfBlocked(activity: Activity): Boolean {
        if (allowMutations(activity)) return true
        CuraxFeedback.warn(activity, R.string.standalone_connect_admin_first)
        return false
    }
}
