package com.curax.app

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.PopupWindow
import android.widget.RadioGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.ActionMenuView
import com.google.android.material.appbar.MaterialToolbar

/** Small anchored popup near the mode icon: radios + ✕ (dismiss) / ✓ (save). */
object UserModePopup {

    /** Mode action is usually the rightmost [ActionMenuView] child (after theme). */
    fun anchorForModeIcon(toolbar: MaterialToolbar): View {
        for (i in 0 until toolbar.childCount) {
            val c = toolbar.getChildAt(i)
            if (c is ActionMenuView) {
                val n = c.childCount
                if (n >= 2) return c.getChildAt(n - 1)
                if (n == 1) return c.getChildAt(0)
            }
        }
        return toolbar
    }

    fun show(activity: AppCompatActivity, anchor: View) {
        val content = LayoutInflater.from(activity).inflate(R.layout.user_mode_popup, null)
        val rg = content.findViewById<RadioGroup>(R.id.rg_user_mode)

        rg.clearCheck()
        if (AppModeManager.isStandaloneMode(activity)) {
            rg.check(R.id.rb_user_mode_standalone)
        } else {
            rg.check(R.id.rb_user_mode_default)
        }

        val popup = PopupWindow(
            content,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true,
        )
        popup.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        popup.isOutsideTouchable = true
        popup.isFocusable = true
        popup.elevation = 14f

        content.findViewById<ImageButton>(R.id.btn_user_mode_popup_cancel).setOnClickListener {
            popup.dismiss()
        }
        content.findViewById<ImageButton>(R.id.btn_user_mode_popup_ok).setOnClickListener {
            val checkedId = rg.checkedRadioButtonId
            val wantStandalone = when (checkedId) {
                R.id.rb_user_mode_standalone -> true
                R.id.rb_user_mode_default -> false
                else -> AppModeManager.isStandaloneMode(activity)
            }
            AppModeManager.setStandaloneMode(activity, wantStandalone)
            popup.dismiss()
            if (activity is UserStandaloneActivity) {
                activity.notifyUserAppModePreferenceChanged()
            }
        }

        content.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val pw = content.measuredWidth
        val aw = anchor.width.coerceAtLeast(1)
        // Center popup under the toggle icon (default x=0 aligns left edges; too far right before).
        val xoff = (aw - pw) / 2
        popup.showAsDropDown(anchor, xoff, 0)
    }
}
