package com.curax.app

import android.content.Context
import android.view.View
import android.widget.HorizontalScrollView

object HealthHubPlanInsights {

    fun bind(host: View, ctx: Context) {
        host.findViewById<HorizontalScrollView>(R.id.hsv_health_hub_insights)?.visibility = View.GONE
    }
}
