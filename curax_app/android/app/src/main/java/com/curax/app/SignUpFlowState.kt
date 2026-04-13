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

    fun set(email: String, password: String, botId: String, apiKey: String) {
        this.email = email.trim()
        this.password = password
        this.botId = botId.trim()
        this.apiKey = apiKey.trim()
    }

    fun clear() {
        email = ""
        password = ""
        botId = ""
        apiKey = ""
    }

    fun isReady(): Boolean =
        email.isNotBlank() && password.isNotBlank() && botId.isNotBlank() && apiKey.isNotBlank()
}
