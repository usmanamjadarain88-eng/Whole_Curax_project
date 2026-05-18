package com.curax.app

import android.app.Activity
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder

object ReliabilityCautionsUi {

    fun launch(activity: Activity) {
        if (activity is AppCompatActivity) {
            showDialog(activity)
        }
    }

    fun showDialog(activity: AppCompatActivity) {
        val content = activity.layoutInflater.inflate(R.layout.dialog_reliability_cautions, null, false)
        bind(content)
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.reliability_caution_title)
            .setView(content)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    fun bind(activity: Activity) {
        val root = activity.findViewById<View>(R.id.cardReliabilityCautions) ?: return
        bind(root)
    }

    private fun bind(root: View) {
        bindRow(root, R.id.cautionRowForceStop, R.string.reliability_caution_force_stop)
        bindRow(root, R.id.cautionRowBattery, R.string.reliability_caution_battery)
        bindRow(root, R.id.cautionRowFamilyEmail, R.string.reliability_caution_family_email)
    }

    private fun bindRow(root: View, rowId: Int, textRes: Int) {
        root.findViewById<View>(rowId)
            ?.findViewById<TextView>(R.id.tvCautionLine)
            ?.setText(textRes)
    }
}
