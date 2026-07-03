package com.curax.app

import android.content.Context
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Shared admin HTTP client + offline guard.
 * Every admin tab should check [isOnline] before network work so no net never hangs or kills the app.
 */
object AdminNetwork {
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .build()

    fun isOnline(context: Context): Boolean = PendingSyncCoordinator.isOnline(context)
}
