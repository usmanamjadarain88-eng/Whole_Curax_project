package com.curax.app

import android.content.Context

/**
 * When GET /user/data merges new server alerts (e.g. admin chat while relay was offline),
 * show the same popup + sound as [AlertDeliver] without duplicating list rows.
 */
object SyncAlertNotifier {

    private val notifyTypes = setOf(
        "admin_message",
        "stock",
        "expiry",
        "urgent",
        "family",
        "alert",
    )

    fun notifyNewFromSync(context: Context, before: List<AlertItem>, after: List<AlertItem>) {
        val app = context.applicationContext
        if (!AppRole.isUser(app)) return
        val beforeKeys = before.map { AdminDemoData.alertDedupeKey(it) }.toSet()
        for (item in after) {
            val key = AdminDemoData.alertDedupeKey(item)
            if (key in beforeKeys) continue
            if (DeletedAlertsStore.isDeleted(app, item)) continue
            val t = item.type.trim().lowercase()
            if (t !in notifyTypes && !t.contains("stock") && !t.contains("expiry")) continue
            AlertDeliver.deliver(
                app,
                item.type,
                item.message,
                item.userName,
                receivedAt = item.receivedAt,
                addToAlertsList = false,
            )
        }
    }
}
