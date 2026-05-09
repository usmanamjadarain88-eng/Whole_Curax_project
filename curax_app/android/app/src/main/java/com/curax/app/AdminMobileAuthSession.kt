package com.curax.app

/**
 * Holds the admin password briefly between [AdminRegistrationActivity] (email/password step)
 * and [AdminMobileVerifyActivity] (OTP step). Cleared after successful sign-in or when verify activity finishes.
 */
object AdminMobileAuthSession {
    @Volatile
    var passwordPlain: String? = null

    fun clear() {
        passwordPlain = null
    }
}
