package com.curax.app

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Home screen widget: next scheduled dose; tap opens Dashboard. */
class CuraxNextDoseWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        for (id in appWidgetIds) {
            updateWidget(context, appWidgetManager, id)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) {
            updateAll(context)
        }
    }

    companion object {
        const val ACTION_REFRESH = "com.curax.app.action.WIDGET_NEXT_DOSE_REFRESH"

        private val timeFmt = SimpleDateFormat("h:mm a", Locale.getDefault()).apply {
            timeZone = TimeZone.getDefault()
        }

        private fun formatWidgetTime(context: Context, millis: Long): String {
            val todayKey = LocalAlertsController.localDayKeyToday()
            val cal = Calendar.getInstance(TimeZone.getDefault()).apply { timeInMillis = millis }
            val dayKey = String.format(
                Locale.US,
                "%04d%02d%02d",
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH) + 1,
                cal.get(Calendar.DAY_OF_MONTH),
            )
            val hm = timeFmt.format(Date(millis))
            return if (dayKey == todayKey) hm else context.getString(R.string.widget_next_dose_time_tomorrow_fmt, hm)
        }

        fun updateAll(context: Context) {
            val app = context.applicationContext
            val mgr = AppWidgetManager.getInstance(app)
            val cn = ComponentName(app, CuraxNextDoseWidgetProvider::class.java)
            val ids = mgr.getAppWidgetIds(cn)
            if (ids.isEmpty()) return
            for (id in ids) {
                updateWidget(app, mgr, id)
            }
        }

        private fun updateWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            widgetId: Int,
        ) {
            val app = context.applicationContext
            if (AppRole.isUser(app)) {
                UserAlarmScheduler.restoreCacheIfNeeded(app)
            }
            val views = RemoteViews(context.packageName, R.layout.widget_next_dose)
            val preview = if (AppRole.isUser(context)) {
                TodayDoseSchedule.nextDosePreview(context)
            } else {
                null
            }

            if (preview == null) {
                views.setTextViewText(R.id.tvWidgetNextDoseLine, context.getString(R.string.widget_next_dose_empty))
                views.setTextViewText(R.id.tvWidgetNextDoseSub, context.getString(R.string.widget_next_dose_empty_sub))
                views.setViewVisibility(R.id.tvWidgetNextDoseSub, View.VISIBLE)
                views.setViewVisibility(R.id.llWidgetMetaRow, View.GONE)
                views.setViewVisibility(R.id.tvWidgetStatus, View.GONE)
            } else {
                val timeLabel = formatWidgetTime(context, preview.scheduledMillis)
                views.setTextViewText(R.id.tvWidgetNextDoseLine, preview.medicineName)
                views.setTextViewText(R.id.tvWidgetTime, timeLabel)
                views.setTextViewText(
                    R.id.tvWidgetBox,
                    context.getString(R.string.widget_next_dose_box_fmt, preview.box),
                )
                views.setViewVisibility(R.id.tvWidgetNextDoseSub, View.GONE)
                views.setViewVisibility(R.id.llWidgetMetaRow, View.VISIBLE)

                if (preview.isDueNow) {
                    views.setTextViewText(
                        R.id.tvWidgetStatus,
                        context.getString(R.string.widget_next_dose_status_due),
                    )
                    views.setInt(R.id.tvWidgetStatus, "setBackgroundResource", R.drawable.bg_widget_badge_due)
                    views.setViewVisibility(R.id.tvWidgetStatus, View.VISIBLE)
                } else {
                    val minsUntil = ((preview.scheduledMillis - System.currentTimeMillis()) / 60_000L)
                        .coerceAtLeast(1L)
                        .toInt()
                    if (minsUntil < 24 * 60) {
                        views.setTextViewText(
                            R.id.tvWidgetStatus,
                            context.getString(R.string.widget_next_dose_status_in_min, minsUntil),
                        )
                        views.setInt(
                            R.id.tvWidgetStatus,
                            "setBackgroundResource",
                            R.drawable.bg_widget_badge_upcoming_sage,
                        )
                        views.setTextColor(R.id.tvWidgetStatus, 0xFF0E7A57.toInt())
                        views.setViewVisibility(R.id.tvWidgetStatus, View.VISIBLE)
                    } else {
                        views.setViewVisibility(R.id.tvWidgetStatus, View.GONE)
                    }
                }
            }

            val open = UserHomeIntent.forSignedInUser(context).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(UserStandaloneActivity.EXTRA_RELAUNCH_TAB, UserStandaloneActivity.TAB_DASHBOARD)
            }
            val pi = android.app.PendingIntent.getActivity(
                context,
                widgetId,
                open,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.widgetNextDoseRoot, pi)
            appWidgetManager.updateAppWidget(widgetId, views)
        }
    }
}
