package com.curax.app

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.TaskStackBuilder

/**
 * Alert detail navigation:
 * - From Alerts list: push detail only → back returns to the screen you were on (Alerts tab).
 * - From notification / cold start: home on Alerts tab + detail → back returns to Alerts list.
 */
object AlertNavigation {

    const val EXTRA_OPEN_ALERTS_TAB = "extra_open_alerts_tab"

    private fun alertsTabIndex(context: Context): Int {
        val store = LocalUserStore(context)
        return if (store.role == LocalUserStore.ROLE_ADMIN) 2 else 1
    }

    fun homeIntent(context: Context): Intent {
        val store = LocalUserStore(context)
        return if (store.role == LocalUserStore.ROLE_ADMIN) {
            Intent(context, AdminDashboardActivity::class.java)
        } else {
            UserHomeIntent.forSignedInUser(context)
        }
    }

    /** Home shell with Alerts tab selected (under detail when opened from notification). */
    fun homeOnAlertsTabIntent(context: Context): Intent {
        val tab = alertsTabIndex(context)
        return homeIntent(context).apply {
            if (LocalUserStore(context).role == LocalUserStore.ROLE_ADMIN) {
                putExtra(EXTRA_OPEN_ALERTS_TAB, true)
            } else {
                putExtra(UserStandaloneActivity.EXTRA_RELAUNCH_TAB, tab)
            }
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
    }

    fun detailIntent(
        context: Context,
        alertId: Long,
        type: String,
        message: String,
        receivedAt: Long,
        userName: String,
        fromInternalNav: Boolean,
    ): Intent = Intent(context, AlertDetailActivity::class.java).apply {
        putExtra(NotificationHelper.EXTRA_ALERT_ID, alertId)
        putExtra(NotificationHelper.EXTRA_ALERT_TYPE, type)
        putExtra(NotificationHelper.EXTRA_ALERT_MESSAGE, message)
        putExtra(NotificationHelper.EXTRA_ALERT_USER_NAME, userName)
        putExtra(NotificationHelper.EXTRA_ALERT_TIME, receivedAt)
        putExtra(NotificationHelper.EXTRA_INTERNAL_NAV, fromInternalNav)
    }

    /** User tapped a row on the Alerts screen — preserve current back stack. */
    fun launchDetailFromAlertsList(
        context: Context,
        alertId: Long,
        type: String,
        message: String,
        receivedAt: Long,
        userName: String,
    ) {
        context.startActivity(
            detailIntent(context, alertId, type, message, receivedAt, userName, fromInternalNav = true),
        )
    }

    /** Notification tap, PIN unlock target, or cold start — back goes to Alerts tab. */
    fun launchDetailFromNotification(
        context: Context,
        alertId: Long,
        type: String,
        message: String,
        receivedAt: Long,
        userName: String,
    ) {
        val home = homeOnAlertsTabIntent(context)
        val detail = detailIntent(context, alertId, type, message, receivedAt, userName, fromInternalNav = false).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        TaskStackBuilder.create(context)
            .addNextIntent(home)
            .addNextIntent(detail)
            .startActivities()
    }

    /** @deprecated Use [launchDetailFromAlertsList] or [launchDetailFromNotification]. */
    fun launchDetailWithHome(
        context: Context,
        alertId: Long,
        type: String,
        message: String,
        receivedAt: Long,
        userName: String,
        fromInternalNav: Boolean,
    ) {
        if (fromInternalNav) {
            launchDetailFromAlertsList(context, alertId, type, message, receivedAt, userName)
        } else {
            launchDetailFromNotification(context, alertId, type, message, receivedAt, userName)
        }
    }

    fun pendingIntentFromNotification(
        context: Context,
        notificationId: Int,
        alertId: Long,
        type: String,
        message: String,
        receivedAt: Long,
        userName: String,
    ): PendingIntent? {
        val home = homeOnAlertsTabIntent(context)
        val detail = detailIntent(context, alertId, type, message, receivedAt, userName, fromInternalNav = false)
        return TaskStackBuilder.create(context)
            .addNextIntent(home)
            .addNextIntent(detail)
            .getPendingIntent(
                notificationId,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
    }

    fun routeFromNotificationTap(context: Context, intent: Intent) {
        val alertId = intent.getLongExtra(NotificationHelper.EXTRA_ALERT_ID, -1L)
        val type = intent.getStringExtra(NotificationHelper.EXTRA_ALERT_TYPE).orEmpty()
        val message = intent.getStringExtra(NotificationHelper.EXTRA_ALERT_MESSAGE).orEmpty()
        val time = intent.getLongExtra(NotificationHelper.EXTRA_ALERT_TIME, System.currentTimeMillis())
        val userName = intent.getStringExtra(NotificationHelper.EXTRA_ALERT_USER_NAME).orEmpty()

        if (!AppLockState.isUnlockValid() && AppLockPolicy.shouldRequireLockOnEntry(context)) {
            context.startActivity(
                Intent(context, PinEntryActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
                    putExtra(PinEntryActivity.EXTRA_TARGET, PinEntryActivity.TARGET_ALERT_DETAIL)
                    putExtra(NotificationHelper.EXTRA_ALERT_ID, alertId)
                    putExtra(NotificationHelper.EXTRA_ALERT_TYPE, type)
                    putExtra(NotificationHelper.EXTRA_ALERT_MESSAGE, message)
                    putExtra(NotificationHelper.EXTRA_ALERT_USER_NAME, userName)
                    putExtra(NotificationHelper.EXTRA_ALERT_TIME, time)
                },
            )
            return
        }
        launchDetailFromNotification(context, alertId, type, message, time, userName)
    }
}
