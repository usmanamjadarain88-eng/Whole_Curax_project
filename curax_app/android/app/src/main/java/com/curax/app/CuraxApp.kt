package com.curax.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
class CuraxApp : Application() {

    private var startedActivities = 0

    override fun onCreate() {
        super.onCreate()
        val prefs = Prefs(this)
        val savedMode = prefs.themeMode
        val mode = when (savedMode) {
            AppCompatDelegate.MODE_NIGHT_YES,
            AppCompatDelegate.MODE_NIGHT_NO -> savedMode
            else -> AppCompatDelegate.MODE_NIGHT_NO
        }
        if (mode != savedMode) {
            prefs.themeMode = mode
        }
        AppCompatDelegate.setDefaultNightMode(mode)
        prefs.applyStandaloneSoundLibraryInstallGuard(this)

        // Fresh process: always open the main admin dashboard, not "managing a user" from last session.
        if (AppRole.isAdmin(this)) {
            prefs.actAsUserId = ""
            prefs.actAsUserName = ""
            prefs.actAsUserDisplayMode = ""
        }

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivities += 1
                Prefs(this@CuraxApp).lastExitWasClose = false
                AppVisibility.isForeground = true
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivities = (startedActivities - 1).coerceAtLeast(0)
                if (startedActivities == 0 && !activity.isChangingConfigurations) {
                    AppVisibility.isForeground = false
                    val now = System.currentTimeMillis()
                    AppLockState.markBackgroundNow()
                    Prefs(this@CuraxApp).lastBackgroundAtMs = now
                }
            }

            override fun onActivityDestroyed(activity: Activity) {
                if (startedActivities == 0 && activity.isFinishing && !activity.isChangingConfigurations &&
                    (activity is MainActivity || activity is UserStandaloneActivity || activity is AdminDashboardActivity)) {
                    Prefs(this@CuraxApp).lastExitWasClose = true
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        })

        Thread { MobileReliabilityCoordinator.onAppStart(this) }.start()
    }
}

