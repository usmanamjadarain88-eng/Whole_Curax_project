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
        disconnectRealtimeTransport(app)
        SignUpFlowState.clear()
        LocalUserStore(app).clearUser()
        val prefs = Prefs(app)
        prefs.clearSignupWipLink()
        prefs.awaitingAdminLinkApproval = false
        prefs.awaitingAdminChosenDisplayName = ""
        prefs.id = ""
        prefs.apiKey = ""
        prefs.connectionCode = ""
        prefs.databusAccessCode = ""
        prefs.linkedAdminId = ""
        prefs.linkedAdminName = ""
        prefs.adminAccessCode = ""
        prefs.lastSyncTime = ""
        prefs.actAsUserId = ""
        prefs.actAsUserName = ""
        prefs.actAsUserDisplayMode = ""
        AppModeManager.setStandaloneMode(app, false)
        // Mode sheet completion is per bot_id ([UserModeSheetPrefs]) — do not reset on logout.
        prefs.userHomeColdStartCount = 0
        prefs.pinDeferredAutoPromptShown = false
        prefs.userStandaloneDataReady = false
        prefs.cachedUserDataSnapshotJson = ""
        prefs.hasEverConnected = false
        prefs.relayAutoConnectEnabled = false
        prefs.hasRequestedConnectWakePermissions = false
        prefs.standaloneDeviceSetupCompleted = false
        prefs.userProfilePictureDataUrl = ""
        prefs.esp32CachedDevicePin = ""
        UserSessionIsolate.clearSessionIdentity(app)
        UserSessionIsolate.clearUserScopedData(app)
        AppLockState.grantUnlock()
        AppLockState.clearBackgroundTimestamp()
    }

    fun navigateToSignIn(activity: Activity) {
        activity.finishAffinity()
        activity.startActivity(Intent(activity, SignInActivity::class.java))
    }

    /** Stop relay + databus so the next sign-in (or admin test) never inherits the previous socket. */
    fun disconnectRealtimeTransport(context: Context) {
        val app = context.applicationContext
        try {
            ConnectionManager.requestDisconnectRelay(app)
        } catch (_: Exception) {
        }
        try {
            UserDataBusClient.stop()
        } catch (_: Exception) {
        }
        try {
            AdminDataBusClient.stop()
        } catch (_: Exception) {
        }
    }
}
