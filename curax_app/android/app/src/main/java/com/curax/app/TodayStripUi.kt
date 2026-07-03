package com.curax.app

import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Binds the dashboard Today dose strip on [AdminOverviewFragment]. */
object TodayStripUi {

    fun bind(host: View, fragment: Fragment) {
        val ctx = host.context
        val container = host.findViewById<View>(R.id.containerTodayDoseStrip) ?: return
        if (!AppRole.isUser(ctx)) {
            container.visibility = View.GONE
            return
        }

        container.visibility = View.VISIBLE
        applyBlockThemes(host, ctx)

        val hasMeds = AdminDemoData.medicines.any { it.stock > 0 && it.effectiveScheduleTimes().isNotEmpty() }
        val summary = if (hasMeds) {
            TodayDoseSchedule.buildSummary(ctx)
        } else {
            null
        }
        val recentMissed = if (hasMeds && summary != null) {
            TodayDoseSchedule.dashboardMissedEntries(ctx, summary)
        } else {
            emptyList()
        }
        val missedTotal = recentMissed.size

        host.findViewById<TextView>(R.id.tvTodayDueCount)?.text =
            summary?.dueNow?.size?.toString() ?: "0"

        val next = summary?.next
        host.findViewById<TextView>(R.id.tvTodayNextDose)?.text = when {
            !hasMeds -> ctx.getString(R.string.today_dose_no_medicines)
            summary!!.dueNow.isNotEmpty() -> {
                val d = summary.dueNow.first()
                ctx.getString(R.string.today_dose_next_due_fmt, d.medicineName, d.slotHhMm)
            }
            next != null -> ctx.getString(
                R.string.today_dose_next_upcoming_fmt,
                next.medicineName,
                next.slotHhMm,
            )
            else -> ctx.getString(R.string.today_dose_next_none)
        }

        host.findViewById<TextView>(R.id.tvTodayMissedCount)?.text = missedTotal.toString()

        host.findViewById<TextView>(R.id.tvTodayStripHint)?.visibility = View.GONE

        // Missed list lives in the Today card + dialog — no duplicate red rows on dashboard.
        host.findViewById<LinearLayout>(R.id.llTodayRecentMissed)?.visibility = View.GONE

        val openDose = View.OnClickListener { openDoseTab(fragment) }
        host.findViewById<View>(R.id.cardTodayDue)?.setOnClickListener(openDose)
        host.findViewById<View>(R.id.cardTodayNext)?.setOnClickListener(openDose)
        host.findViewById<View>(R.id.cardTodayMissed)?.setOnClickListener {
            showMissedListDialog(fragment, recentMissed)
        }
    }

    private fun applyBlockThemes(host: View, ctx: android.content.Context) {
        val cards = listOfNotNull(
            host.findViewById<MaterialCardView>(R.id.cardTodayDue),
            host.findViewById<MaterialCardView>(R.id.cardTodayNext),
            host.findViewById<MaterialCardView>(R.id.cardTodayMissed),
        )
        val standalone = StandaloneUi.isUserStandalone(ctx)
        for (card in cards) {
            if (standalone) {
                card.setCardBackgroundColor(ContextCompat.getColor(ctx, R.color.standalone_inventory_card))
                card.strokeColor = ContextCompat.getColor(ctx, R.color.standalone_inventory_row_stroke)
                card.radius = 10f * ctx.resources.displayMetrics.density
                card.cardElevation = 1f * ctx.resources.displayMetrics.density
            } else {
                card.setCardBackgroundColor(ContextCompat.getColor(ctx, R.color.summary_card))
                card.strokeColor = ContextCompat.getColor(ctx, R.color.summary_stroke)
                card.radius = 10f * ctx.resources.displayMetrics.density
                card.strokeWidth = ctx.resources.getDimensionPixelSize(R.dimen.panel_stroke_width)
            }
        }
    }

    private fun bindRecentMissedRows(host: View, rows: List<TodayDoseSchedule.MissedEntry>) {
        val container = host.findViewById<LinearLayout>(R.id.llTodayRecentMissed) ?: return
        container.removeAllViews()
        if (rows.isEmpty()) {
            container.visibility = View.GONE
            return
        }
        container.visibility = View.VISIBLE
        val ctx = host.context
        val pad = (6 * ctx.resources.displayMetrics.density).toInt()
        for (row in rows) {
            val tv = TextView(ctx).apply {
                text = ctx.getString(
                    R.string.today_dose_recent_missed_line,
                    row.medicineName.ifBlank { row.box },
                    row.box,
                    row.slotHhMm,
                    formatMissedWhen(row.timestamp),
                )
                setTextColor(ContextCompat.getColor(ctx, R.color.standalone_alert_missed))
                textSize = 12f
                setPadding(0, pad, 0, 0)
            }
            container.addView(tv)
        }
    }

    private fun formatMissedWhen(ts: String): String {
        val t = ts.trim()
        if (t.length >= 16) return t.substring(11, 16)
        if (t.length >= 10) return t.take(10)
        return t
    }

    private fun showMissedListDialog(
        fragment: Fragment,
        entries: List<TodayDoseSchedule.MissedEntry>,
    ) {
        val ctx = fragment.requireContext()
        if (entries.isEmpty()) {
            CuraxFeedback.info(fragment, ctx.getString(R.string.today_dose_missed_none))
            return
        }
        val lines = entries.map { e ->
            ctx.getString(
                R.string.today_dose_missed_list_line,
                e.medicineName.ifBlank { e.box },
                e.box,
                e.slotHhMm,
                e.timestamp.take(16).replace('T', ' '),
            )
        }.toTypedArray()
        MaterialAlertDialogBuilder(ctx)
            .setTitle(ctx.getString(R.string.today_dose_missed_dialog_title))
            .setItems(lines, null)
            .setPositiveButton(R.string.today_dose_open_dose_tab) { _, _ ->
                openDoseTab(fragment)
            }
            .setNegativeButton(R.string.dismiss, null)
            .show()
    }

    fun openDoseTab(fragment: Fragment) {
        val act = fragment.activity as? UserStandaloneActivity ?: return
        act.openTab(UserStandaloneActivity.TAB_DOSE_TRACKING)
    }
}
