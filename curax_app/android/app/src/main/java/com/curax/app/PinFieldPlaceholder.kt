package com.curax.app

import android.text.SpannableString
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.textfield.TextInputEditText

/** Visible [EditText] hint at 12sp; typed PIN stays at the field's [android:textSize]. */
object PinFieldPlaceholder {

    private const val HINT_SP = 12

    fun bind(activity: AppCompatActivity, edit: TextInputEditText, @StringRes hintRes: Int) {
        val hintText = activity.getString(hintRes)
        val hintPx = (HINT_SP * activity.resources.displayMetrics.scaledDensity).toInt()
        val hintColor = ContextCompat.getColor(activity, R.color.text_secondary)
        edit.hint = SpannableString(hintText).apply {
            setSpan(AbsoluteSizeSpan(hintPx, false), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(hintColor), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
}
