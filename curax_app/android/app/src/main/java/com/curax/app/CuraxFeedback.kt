package com.curax.app

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.R as MaterialR
import com.google.android.material.snackbar.Snackbar

object CuraxFeedback {

    fun success(activity: Activity, message: CharSequence) {
        snackbar(
            activity,
            message,
            R.color.feedback_success_bg,
            R.color.feedback_on_success,
            Snackbar.LENGTH_LONG,
        )?.show()
    }

    fun success(activity: Activity, @StringRes resId: Int) {
        success(activity, activity.getString(resId))
    }

    fun success(fragment: Fragment, message: CharSequence) {
        if (fragment.isAdded) success(fragment.requireActivity(), message)
    }

    fun success(fragment: Fragment, @StringRes resId: Int) {
        if (fragment.isAdded) success(fragment.requireActivity(), resId)
    }

    fun successWithUndo(
        activity: Activity,
        message: CharSequence,
        @StringRes actionRes: Int = R.string.undo,
        onAction: () -> Unit,
    ) {
        val root = contentRoot(activity) ?: return
        Snackbar.make(root, message, Snackbar.LENGTH_LONG).apply {
            setBackgroundTint(ContextCompat.getColor(activity, R.color.feedback_success_bg))
            setTextColor(ContextCompat.getColor(activity, R.color.feedback_on_success))
            setActionTextColor(ContextCompat.getColor(activity, R.color.feedback_on_success))
            animationMode = Snackbar.ANIMATION_MODE_FADE
            setAction(activity.getString(actionRes)) { onAction() }
        }.show()
    }

    fun successWithUndo(
        fragment: Fragment,
        message: CharSequence,
        @StringRes actionRes: Int = R.string.undo,
        onAction: () -> Unit,
    ) {
        if (fragment.isAdded) successWithUndo(fragment.requireActivity(), message, actionRes, onAction)
    }

    /**
     * Shows success Snackbar, then runs [after] after [delayMs] so the message can register
     * before [Activity.finish] or navigation (use [snackbarDuration] + fade transitions for a smooth handoff).
     */
    fun successThen(
        activity: Activity,
        message: CharSequence,
        delayMs: Long = 580L,
        snackbarDuration: Int = Snackbar.LENGTH_LONG,
        after: () -> Unit,
    ) {
        snackbar(
            activity,
            message,
            R.color.feedback_success_bg,
            R.color.feedback_on_success,
            snackbarDuration,
        )?.apply { animationMode = Snackbar.ANIMATION_MODE_FADE }?.show()
        if (delayMs <= 0L) {
            activity.window.decorView.post {
                if (!activity.isFinishing) after()
            }
        } else {
            activity.window.decorView.postDelayed({
                if (!activity.isFinishing) after()
            }, delayMs)
        }
    }

    fun successThen(
        activity: Activity,
        @StringRes resId: Int,
        delayMs: Long = 580L,
        snackbarDuration: Int = Snackbar.LENGTH_LONG,
        after: () -> Unit,
    ) {
        successThen(activity, activity.getString(resId), delayMs, snackbarDuration, after)
    }

    /**
     * Validation, API, and in-app issues — same custom Snackbar pattern as [success],
     * but dark bar, elevation (shadow), and app icon (not amber).
     */
    fun warn(activity: Activity, message: CharSequence, long: Boolean = message.length > 80) {
        val duration = if (long) Snackbar.LENGTH_LONG else Snackbar.LENGTH_SHORT
        snackbar(activity, message, R.color.feedback_error_bg, R.color.feedback_on_error, duration)
            ?.apply { styleAsError(activity) }
            ?.show()
    }

    fun warn(activity: Activity, @StringRes resId: Int, long: Boolean = false) {
        warn(activity, activity.getString(resId), long)
    }

    fun warn(fragment: Fragment, message: CharSequence, long: Boolean = message.length > 80) {
        if (fragment.isAdded) warn(fragment.requireActivity(), message, long)
    }

    fun warn(fragment: Fragment, @StringRes resId: Int, long: Boolean = false) {
        if (fragment.isAdded) warn(fragment.requireActivity(), resId, long)
    }

    /** Short status lines (connect / disconnect) — theme default styling. */
    fun info(activity: Activity, message: CharSequence) {
        val root = contentRoot(activity) ?: return
        Snackbar.make(root, message, Snackbar.LENGTH_SHORT).show()
    }

    fun info(fragment: Fragment, message: CharSequence) {
        if (fragment.isAdded) info(fragment.requireActivity(), message)
    }

    private fun contentRoot(activity: Activity): View? {
        val decor = activity.window?.decorView as? ViewGroup ?: return null
        return decor.findViewById(android.R.id.content)
            ?: if (decor.childCount > 0) decor.getChildAt(0) else null
    }

    private fun snackbar(
        activity: Activity,
        message: CharSequence,
        bgRes: Int,
        fgRes: Int,
        duration: Int,
    ): Snackbar? {
        val root = contentRoot(activity) ?: return null
        return Snackbar.make(root, message, duration).apply {
            setBackgroundTint(ContextCompat.getColor(activity, bgRes))
            setTextColor(ContextCompat.getColor(activity, fgRes))
            animationMode = Snackbar.ANIMATION_MODE_FADE
        }
    }

    private fun Snackbar.styleAsError(activity: Activity) {
        val dm = activity.resources.displayMetrics
        val density = dm.density
        view.elevation = 12f * density
        view.translationZ = 4f * density
        val tv = view.findViewById<TextView>(MaterialR.id.snackbar_text) ?: return
        val icon = snackbarErrorIcon(activity) ?: return
        val pad = (10 * density).toInt()
        tv.compoundDrawablePadding = pad
        tv.setCompoundDrawablesRelative(icon, null, null, null)
    }

    private fun snackbarErrorIcon(activity: Activity): Drawable? {
        val px = (22 * activity.resources.displayMetrics.density).toInt().coerceAtLeast(1)
        val raw = try {
            activity.packageManager.getApplicationIcon(activity.applicationInfo)
        } catch (_: Exception) {
            null
        } ?: AppCompatResources.getDrawable(activity, R.drawable.ic_launcher_inset) ?: return null
        val d = raw.mutate()
        return when (d) {
            is AdaptiveIconDrawable -> {
                try {
                    val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
                    val c = Canvas(bmp)
                    d.setBounds(0, 0, px, px)
                    d.draw(c)
                    BitmapDrawable(activity.resources, bmp)
                } catch (_: Exception) {
                    null
                }
            }
            else -> {
                d.setBounds(0, 0, px, px)
                d
            }
        }
    }
}
