package com.curax.app

import android.content.Context
import android.os.Build
import android.view.autofill.AutofillManager

/** Lets Google / Samsung / etc. password managers offer to save after successful auth. */
object AutofillHelper {

    fun commit(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val afm = context.getSystemService(AutofillManager::class.java) ?: return
        try {
            afm.commit()
        } catch (_: Exception) {
            // Ignore if autofill disabled or no session
        }
    }
}
