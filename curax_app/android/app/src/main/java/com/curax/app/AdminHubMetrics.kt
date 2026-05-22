package com.curax.app

import android.content.Context
import android.content.Intent
import org.json.JSONArray

/** Keeps dashboard user totals in sync with the Users tab (same linked-users roster). */
object AdminHubMetrics {

    fun applyFromUsersArray(users: JSONArray) {
        AdminLinkedUserDirectory.ingestUsersJsonArray(users)
        publishCounts()
    }

    fun applyFromUiModels(models: List<AdminLinkedUserUiModel>) {
        AdminLinkedUserDirectory.ingestFromUiModels(models)
        publishCounts()
    }

    fun publishCounts() {
        if (!AdminLinkedUserDirectory.hasRosterCounts()) return
        AdminHubFragment.MetricsCache.recordSuccess(
            AdminLinkedUserDirectory.rosterUsersTotal,
            AdminLinkedUserDirectory.rosterLinked,
            AdminLinkedUserDirectory.rosterPending,
        )
    }

    fun broadcastRosterCountsUpdated(context: Context) {
        val app = context.applicationContext
        app.sendBroadcast(Intent(AlertEvents.ACTION_ADMIN_ROSTER_COUNTS_UPDATED))
        app.sendBroadcast(Intent(AlertEvents.ACTION_ADMIN_HUB_REFRESH_METRICS))
    }
}
