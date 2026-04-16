package com.curax.app

import android.content.Context

/**
 * **Account role** (user app vs admin app). Use this for permissions, read-only UI, API paths.
 * Do not confuse with [AppModeManager] (Default vs Standalone appearance for users).
 */
object AppRole {

    fun isUser(context: Context): Boolean =
        LocalUserStore(context.applicationContext).role == LocalUserStore.ROLE_USER

    fun isAdmin(context: Context): Boolean =
        LocalUserStore(context.applicationContext).role == LocalUserStore.ROLE_ADMIN
}
