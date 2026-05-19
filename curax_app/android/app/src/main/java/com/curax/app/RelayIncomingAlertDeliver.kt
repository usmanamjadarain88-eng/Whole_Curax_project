package com.curax.app

import android.content.Context

/** Relay WebSocket JSON → [AlertDeliver] (popup + sound + saved). */
object RelayIncomingAlertDeliver {

    fun deliver(context: Context, type: String, message: String, userName: String) {
        val app = context.applicationContext
        when {
            AppRole.isAdmin(app) -> AlertDeliver.deliver(app, type, message, userName)
            AppRole.isUser(app) -> AlertDeliver.deliver(app, type, message, userName)
        }
    }
}
