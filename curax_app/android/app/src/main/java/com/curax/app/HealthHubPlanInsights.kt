package com.curax.app

import android.content.Context
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

object HealthHubPlanInsights {

    private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
        timeZone = TimeZone.getDefault()
    }

    fun bind(host: View, ctx: Context) {
        val hsv = host.findViewById<HorizontalScrollView>(R.id.hsv_health_hub_insights) ?: return
        if (!StandaloneUi.isUserStandalone(ctx)) {
            hsv.visibility = View.GONE
            return
        }
        hsv.visibility = View.VISIBLE

        val plans = UserPlansLocalStore.readCache(ctx.applicationContext)
        val today = dayFmt.format(Calendar.getInstance(TimeZone.getDefault()).time)

        val adherence = DoseAdherenceCalculator.weeklyAdherencePercent(ctx.applicationContext)
        // UI shows a single subtitle line per card (ellipsize in layout).
        val adhBody = ctx.getString(R.string.insight_adherence_body_fmt, adherence)

        val overdue = plans.count { !it.isDone && it.planDate.isNotBlank() && it.planDate < today }
        val openToday = plans.count { !it.isDone && it.planDate == today }
        val plansBody = if (overdue == 0 && openToday == 0) {
            ctx.getString(R.string.insight_plans_body_none)
        } else {
            ctx.getString(R.string.insight_plans_body_overdue_fmt, overdue, openToday)
        }

        var tip = ctx.getString(R.string.insight_tip_good)
        if (adherence < 70) {
            tip = ctx.getString(R.string.insight_tip_low_adherence)
        }
        if (overdue >= 3 || (overdue >= 1 && adherence < 80)) {
            tip = ctx.getString(R.string.insight_tip_missed_routine)
        }

        host.findViewById<TextView>(R.id.tv_insight_adherence_body)?.text = adhBody
        host.findViewById<TextView>(R.id.tv_insight_plans_body)?.text = plansBody
        host.findViewById<TextView>(R.id.tv_insight_tip_body)?.text = tip
    }
}
