package com.curax.app

import android.content.Context

class LocalUserStore(context: Context) {
    private val prefs = context.getSharedPreferences("curax_local_auth", Context.MODE_PRIVATE)

    var email: String
        get() = prefs.getString(KEY_EMAIL, "") ?: ""
        private set(value) = prefs.edit().putString(KEY_EMAIL, value).apply()

    var password: String
        get() = prefs.getString(KEY_PASSWORD, "") ?: ""
        private set(value) = prefs.edit().putString(KEY_PASSWORD, value).apply()

    var role: String
        get() = prefs.getString(KEY_ROLE, ROLE_USER) ?: ROLE_USER
        private set(value) = prefs.edit().putString(KEY_ROLE, value).apply()

    var pinEnabled: Boolean
        get() = prefs.getBoolean(KEY_PIN_ENABLED, false)
        private set(value) = prefs.edit().putBoolean(KEY_PIN_ENABLED, value).apply()

    var pinCode: String
        get() = prefs.getString(KEY_PIN_CODE, "") ?: ""
        private set(value) = prefs.edit().putString(KEY_PIN_CODE, value).apply()

    fun hasUser(): Boolean = email.isNotBlank() && password.isNotBlank() && role.isNotBlank()

    fun saveUser(email: String, password: String, role: String) {
        this.email = email.trim()
        this.password = password
        this.role = if (role == ROLE_ADMIN) ROLE_ADMIN else ROLE_USER
    }

    /** Same as [saveUser] but [commit] so launcher/home never reads an empty session right after link. */
    fun saveUserCommitted(email: String, password: String, role: String): Boolean =
        prefs.edit()
            .putString(KEY_EMAIL, email.trim())
            .putString(KEY_PASSWORD, password)
            .putString(KEY_ROLE, if (role == ROLE_ADMIN) ROLE_ADMIN else ROLE_USER)
            .commit()

    fun savePin(pin: String) {
        pinCode = pin
        pinEnabled = pin.isNotBlank()
    }

    fun disablePin() {
        pinCode = ""
        pinEnabled = false
    }

    /** Clear stored user so they must sign up again (e.g. after account removed by admin). */
    fun clearUser() {
        prefs.edit()
            .remove(KEY_EMAIL)
            .remove(KEY_PASSWORD)
            .remove(KEY_ROLE)
            .apply()
    }

    companion object {
        const val ROLE_USER = "user"
        const val ROLE_ADMIN = "admin"

        /**
         * Stored after Google/Facebook sign-in when the real account password is not available on device.
         * Satisfies [hasUser] and matches server-backed OAuth flows that do not require typing the password.
         */
        const val OAUTH_LOCAL_PASSWORD_PLACEHOLDER = "\u0001curax_oauth_local"

        private const val KEY_EMAIL = "email"
        private const val KEY_PASSWORD = "password"
        private const val KEY_ROLE = "role"
        private const val KEY_PIN_ENABLED = "pin_enabled"
        private const val KEY_PIN_CODE = "pin_code"
    }
}
