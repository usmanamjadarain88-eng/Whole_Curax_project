package com.curax.app

import android.app.Activity
import android.content.Intent
import android.view.View
import android.widget.TextView

object ReliabilityCautionsUi {

    fun launch(activity: Activity) {
        activity.startActivity(Intent(activity, ReliabilityCautionsActivity::class.java))
    }

    fun bind(activity: Activity) {
        val root = activity.findViewById<View>(R.id.cardReliabilityCautions) ?: return
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
