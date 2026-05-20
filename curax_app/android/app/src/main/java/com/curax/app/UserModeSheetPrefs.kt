package com.curax.app

import android.content.Context

/** Per-account (bot_id) memory that the first-home mode sheet was completed — survives logout. */
object UserModeSheetPrefs {

    private const val PREFS = "curax_user_mode_sheet"
    private const val KEY_DONE_BOTS = "done_bot_ids"

    fun hasCompletedForBot(context: Context, botId: String): Boolean {
        val id = botId.trim()
        if (id.isEmpty()) return false
        val set = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_DONE_BOTS, emptySet())
            ?: emptySet()
        return id in set
    }

    fun markCompletedForBot(context: Context, botId: String) {
        val id = botId.trim()
        if (id.isEmpty()) return
        val app = context.applicationContext
        val sp = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val set = (sp.getStringSet(KEY_DONE_BOTS, emptySet()) ?: emptySet()).toMutableSet()
        set.add(id)
        sp.edit().putStringSet(KEY_DONE_BOTS, set).apply()
        Prefs(app).userInitialAppModeSheetCompleted = true
    }

    fun syncGlobalFlagFromBot(context: Context, botId: String) {
        val prefs = Prefs(context.applicationContext)
        prefs.userInitialAppModeSheetCompleted = hasCompletedForBot(context, botId)
    }
}
