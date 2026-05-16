package com.curax.app

import android.content.Context
import android.os.Handler
import android.os.Looper

/**
 * Entry point for standalone user local mutations: queue + swipe card + background flush.
 */
object StandaloneUserMutationSink {

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Host shows swipe-dismiss banners (set from [UserStandaloneActivity]). */
    @Volatile
    var swipeCardPresenter: ((title: String, subtitle: String) -> Unit)? = null

    fun notifyLocalChange(activity: android.app.Activity?, context: Context, type: String, title: String, subtitle: String) {
        val app = context.applicationContext
        if (!AppRole.isUser(app) || !StandaloneUi.isUserStandalone(app)) return
        PendingSyncQueueStore.enqueue(app, type, title, subtitle)
        val showSwipe = type != PendingSyncQueueStore.TYPE_MEDICINE && type != PendingSyncQueueStore.TYPE_DOSE
        if (showSwipe) {
            mainHandler.post {
                swipeCardPresenter?.invoke(title, subtitle)
            }
        }
        PendingSyncCoordinator.requestFlush(app)
    }
}
