package com.curax.app

import android.content.Context
import java.util.Locale

/**
 * Per-account memory that the first-home mode sheet was completed — survives logout.
 * Tracked by [bot_id] and normalized email so reinstall + sign-in can skip the sheet and restore mode.
 */
object UserModeSheetPrefs {

    private const val PREFS = "curax_user_mode_sheet"
    private const val KEY_DONE_BOTS = "done_bot_ids"
    private const val KEY_DONE_EMAILS = "done_emails"
    private const val KEY_CHOSEN_STANDALONE_EMAILS = "chosen_standalone_emails"
    private const val KEY_CHOSEN_STANDALONE_BOTS = "chosen_standalone_bot_ids"

    fun normalizeEmail(email: String): String = email.trim().lowercase(Locale.US)

    private fun sp(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun hasCompletedForBot(context: Context, botId: String): Boolean {
        val id = botId.trim()
        if (id.isEmpty()) return false
        val set = sp(context).getStringSet(KEY_DONE_BOTS, emptySet()) ?: emptySet()
        return id in set
    }

    fun hasCompletedForEmail(context: Context, email: String): Boolean {
        val em = normalizeEmail(email)
        if (em.isEmpty()) return false
        val set = sp(context).getStringSet(KEY_DONE_EMAILS, emptySet()) ?: emptySet()
        return em in set
    }

    fun hasCompletedForAccount(context: Context, botId: String, email: String = ""): Boolean {
        if (hasCompletedForBot(context, botId)) return true
        return hasCompletedForEmail(context, email)
    }

    /** Saved user choice — survives logout and reopen before server round-trip. */
    fun saveChosenStandalone(context: Context, email: String, standalone: Boolean, botId: String = "") {
        val app = context.applicationContext
        val editor = sp(app).edit()
        val em = normalizeEmail(email)
        if (em.isNotEmpty()) {
            val set = (sp(app).getStringSet(KEY_CHOSEN_STANDALONE_EMAILS, emptySet()) ?: emptySet()).toMutableSet()
            if (standalone) set.add(em) else set.remove(em)
            editor.putStringSet(KEY_CHOSEN_STANDALONE_EMAILS, set)
        }
        val bid = botId.trim()
        if (bid.isNotEmpty()) {
            val set = (sp(app).getStringSet(KEY_CHOSEN_STANDALONE_BOTS, emptySet()) ?: emptySet()).toMutableSet()
            if (standalone) set.add(bid) else set.remove(bid)
            editor.putStringSet(KEY_CHOSEN_STANDALONE_BOTS, set)
        }
        editor.apply()
    }

    /** null = never chose a mode for this account on this device. */
    fun chosenStandaloneForAccount(context: Context, botId: String, email: String = ""): Boolean? {
        if (!hasCompletedForAccount(context, botId, email)) return null
        val app = context.applicationContext
        val bid = botId.trim()
        if (bid.isNotEmpty() && hasCompletedForBot(app, bid)) {
            val set = sp(app).getStringSet(KEY_CHOSEN_STANDALONE_BOTS, emptySet()) ?: emptySet()
            return bid in set
        }
        val em = normalizeEmail(email)
        if (em.isEmpty()) return null
        val set = sp(app).getStringSet(KEY_CHOSEN_STANDALONE_EMAILS, emptySet()) ?: emptySet()
        return em in set
    }

    fun markCompletedForBot(context: Context, botId: String) {
        val id = botId.trim()
        if (id.isEmpty()) return
        val app = context.applicationContext
        val set = (sp(app).getStringSet(KEY_DONE_BOTS, emptySet()) ?: emptySet()).toMutableSet()
        set.add(id)
        sp(app).edit().putStringSet(KEY_DONE_BOTS, set).apply()
        Prefs(app).userInitialAppModeSheetCompleted = true
    }

    fun markCompletedForEmail(context: Context, email: String) {
        val em = normalizeEmail(email)
        if (em.isEmpty()) return
        val app = context.applicationContext
        val set = (sp(app).getStringSet(KEY_DONE_EMAILS, emptySet()) ?: emptySet()).toMutableSet()
        set.add(em)
        sp(app).edit().putStringSet(KEY_DONE_EMAILS, set).apply()
        Prefs(app).userInitialAppModeSheetCompleted = true
    }

    fun markCompletedForAccount(context: Context, botId: String, email: String = "", standalone: Boolean? = null) {
        markCompletedForBot(context, botId)
        val resolvedEmail = email.ifBlank { LocalUserStore(context.applicationContext).email }
        markCompletedForEmail(context, resolvedEmail)
        if (standalone != null) {
            saveChosenStandalone(context, resolvedEmail, standalone, botId)
        }
    }

    fun syncGlobalFlagFromBot(context: Context, botId: String) {
        syncGlobalFlagFromAccount(context, botId, "")
    }

    fun syncGlobalFlagFromAccount(context: Context, botId: String, email: String = "") {
        val prefs = Prefs(context.applicationContext)
        val resolvedEmail = email.ifBlank { LocalUserStore(context.applicationContext).email }
        prefs.userInitialAppModeSheetCompleted = hasCompletedForAccount(context, botId, resolvedEmail)
    }
}
