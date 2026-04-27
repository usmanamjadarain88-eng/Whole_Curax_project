package com.curax.app

import android.app.Activity
import android.text.InputType
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import android.view.View
import androidx.core.content.ContextCompat
import android.widget.EditText
import com.google.android.material.textfield.TextInputLayout

/** Password hidden = “eye off” icon; tap → show text + “eye on”. Fixes inverted Material password_toggle on some builds. */
object AuthPasswordToggle {

    fun bind(til: TextInputLayout, activity: Activity) {
        val et = til.editText as? EditText ?: return
        et.transformationMethod = PasswordTransformationMethod.getInstance()
        et.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        et.maxLines = 1
        et.isVerticalScrollBarEnabled = false
        et.overScrollMode = View.OVER_SCROLL_NEVER
        til.clipChildren = false
        til.clipToPadding = false
        til.endIconMode = TextInputLayout.END_ICON_CUSTOM
        til.setEndIconDrawable(R.drawable.ic_auth_password_hidden)
        til.setEndIconTintList(ContextCompat.getColorStateList(activity, R.color.auth_password_toggle_tint))
        til.setEndIconContentDescription(activity.getString(R.string.cd_show_password))
        til.setEndIconOnClickListener {
            val masked = et.transformationMethod is PasswordTransformationMethod
            if (masked) {
                et.transformationMethod = HideReturnsTransformationMethod.getInstance()
                et.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                til.setEndIconDrawable(R.drawable.ic_auth_password_visible)
                til.setEndIconContentDescription(activity.getString(R.string.cd_hide_password))
            } else {
                et.transformationMethod = PasswordTransformationMethod.getInstance()
                et.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                til.setEndIconDrawable(R.drawable.ic_auth_password_hidden)
                til.setEndIconContentDescription(activity.getString(R.string.cd_show_password))
            }
            et.maxLines = 1
            et.isVerticalScrollBarEnabled = false
            et.overScrollMode = View.OVER_SCROLL_NEVER
            til.setEndIconTintList(ContextCompat.getColorStateList(activity, R.color.auth_password_toggle_tint))
            val len = et.text?.length ?: 0
            if (len >= 0) et.setSelection(len)
        }
    }
}
