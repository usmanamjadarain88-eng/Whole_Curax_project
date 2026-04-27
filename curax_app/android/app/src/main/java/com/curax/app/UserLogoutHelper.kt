package com.curax.app

import android.app.Activity
import android.content.Context
import android.content.Intent

/**
 * Clears local user session (credentials, relay keys, cached user data) and returns to sign-in.
 * Caller should disconnect [AlertConnectionService] (unbind + ACTION_DISCONNECT) before calling [clearLocalSession].
 */
object UserLogoutHelper {

    fun clearLocalSession(context: Context) {
        val app = context.applicationContext
        UserDataBusClient.stop()
        SignUpFlowState.clear()
        LocalUserStore(app).clearUser()
        val prefs = Prefs(app)
        prefs.id = ""
        prefs.apiKey = ""
        prefs.fcmToken = ""
        prefs.connectionCode = ""
        prefs.databusAccessCode = ""
        prefs.linkedAdminId = ""
        prefs.linkedAdminName = ""
        prefs.adminAccessCode = ""
        prefs.lastSyncTime = ""
        prefs.actAsUserId = ""
        prefs.actAsUserName = ""
        AppModeManager.setStandaloneMode(app, false)
        prefs.userInitialAppModeSheetCompleted = true
        prefs.userHomeColdStartCount = 0
        prefs.pinDeferredAutoPromptShown = false
        prefs.userStandaloneDataReady = false
        prefs.cachedUserDataSnapshotJson = ""
        prefs.hasEverConnected = false
        prefs.userProfilePictureDataUrl = ""
        LocalAlertsController.cancelAll(app)
        UserPlansLocalStore.clear(app)
        DoseTrackingLocalStore.clear(app)
        PendingSyncQueueStore.clear(app)
        AlertDb(app).clearAllAlerts()
        AppLockState.grantUnlock()
        AppLockState.clearBackgroundTimestamp()
    }

    fun navigateToSignIn(activity: Activity) {
        activity.finishAffinity()
        activity.startActivity(Intent(activity, SignInActivity::class.java))
    }
}
