package com.curax.app

/**
 * Holds in-progress signup data between [SignUpActivity] (email verified) and
 * [SignUpLinkAdminActivity]. Cleared after a successful link or when invalid.
 */
internal object SignUpFlowState {
    @Volatile
    var email: String = ""

    @Volatile
    var password: String = ""

    @Volatile
    var botId: String = ""

    @Volatile
    var apiKey: String = ""

    /** Display name for POST /signup/link-admin (first + last from registration, else email). */
    @Volatile
    var nameForLink: String = ""

    fun set(email: String, password: String, botId: String, apiKey: String, nameForLink: String = "") {
        this.email = email.trim()
        this.password = password
        this.botId = botId.trim()
        this.apiKey = apiKey.trim()
        this.nameForLink = nameForLink.trim()
    }

    fun clear() {
        email = ""
        password = ""
        botId = ""
        apiKey = ""
        nameForLink = ""
    }

    fun isReady(): Boolean =
        email.isNotBlank() && password.isNotBlank() && botId.isNotBlank() && apiKey.isNotBlank()

    fun persistWipToPrefs(prefs: Prefs) {
        if (!isReady()) return
        prefs.signupWipEmail = email
        prefs.signupWipPassword = password
        prefs.signupWipBotId = botId
        prefs.signupWipApiKey = apiKey
        prefs.signupWipNameForLink = nameForLink
    }

    /** Restore after process death; returns true if all fields were present in prefs. */
    fun restoreWipFromPrefs(prefs: Prefs): Boolean {
        if (!prefs.hasSignupWipLink()) return false
        set(
            prefs.signupWipEmail,
            prefs.signupWipPassword,
            prefs.signupWipBotId,
            prefs.signupWipApiKey,
            nameForLink = prefs.signupWipNameForLink,
        )
        return isReady()
    }
}
