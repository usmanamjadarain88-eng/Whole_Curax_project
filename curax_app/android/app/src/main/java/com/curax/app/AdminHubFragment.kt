package com.curax.app

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.card.MaterialCardView
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Admin dashboard: summary metrics, volume charts, linkage donut, recent alerts.
 * Full roster and Care mode live on the Users tab.
 */
class AdminHubFragment : Fragment() {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private lateinit var prefs: Prefs
    private lateinit var store: LocalUserStore
    private lateinit var progressLoad: ProgressBar
    private lateinit var tvGreeting: TextView
    private lateinit var tvStatUsers: TextView
    private lateinit var tvStatRelay: TextView
    private lateinit var tvStatAlerts: TextView
    private lateinit var chartSparkline: AdminSparklineChartView
    private lateinit var chartBars: AdminBarChartView
    private lateinit var tvChartAlertsCaption: TextView
    private lateinit var chartDonut: AdminDonutChartView
    private lateinit var tvChartAccountsCaption: TextView
    private lateinit var tvLegendLinked: TextView
    private lateinit var tvLegendPending: TextView
    private lateinit var containerRecentAlerts: LinearLayout
    private lateinit var tvRecentAlertsEmpty: TextView
    private lateinit var cardAdminHubDosePreview: MaterialCardView
    private lateinit var containerRosterDosePreview: LinearLayout
    private lateinit var tvRosterDosePreviewEmpty: TextView

    private val loadGeneration = AtomicInteger(0)

    private var hubReceiverRegistered = false
    private val hubReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                AlertEvents.ACTION_ADMIN_DATA_SYNCED -> fetchDashboardMetrics()
                AlertEvents.ACTION_CONNECTION_STATE_CHANGED,
                AlertEvents.ACTION_ALERTS_UPDATED,
                -> refreshLocalStats()
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_admin_hub, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = Prefs(requireContext())
        store = LocalUserStore(requireContext())
        progressLoad = view.findViewById(R.id.progressAdminHubLoad)
        tvGreeting = view.findViewById(R.id.tvAdminHubGreeting)
        tvStatUsers = view.findViewById(R.id.tvStatUsersValue)
        tvStatRelay = view.findViewById(R.id.tvStatRelayValue)
        tvStatAlerts = view.findViewById(R.id.tvStatAlertsValue)
        chartSparkline = view.findViewById(R.id.chartAlertsSparkline)
        chartBars = view.findViewById(R.id.chartAlertsBars)
        tvChartAlertsCaption = view.findViewById(R.id.tvChartAlertsCaption)
        chartDonut = view.findViewById(R.id.chartAccountsDonut)
        tvChartAccountsCaption = view.findViewById(R.id.tvChartAccountsCaption)
        tvLegendLinked = view.findViewById(R.id.tvLegendLinked)
        tvLegendPending = view.findViewById(R.id.tvLegendPending)
        containerRecentAlerts = view.findViewById(R.id.containerRecentAlerts)
        tvRecentAlertsEmpty = view.findViewById(R.id.tvRecentAlertsEmpty)
        cardAdminHubDosePreview = view.findViewById(R.id.cardAdminHubDosePreview)
        containerRosterDosePreview = view.findViewById(R.id.containerRosterDosePreview)
        tvRosterDosePreviewEmpty = view.findViewById(R.id.tvRosterDosePreviewEmpty)

        val first = store.email.trim().substringBefore("@").ifEmpty { "" }.replaceFirstChar {
            if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
        }
        tvGreeting.text = if (first.isNotEmpty()) {
            getString(R.string.admin_hub_greeting, first)
        } else {
            getString(R.string.admin_hub_greeting_generic)
        }

        refreshLocalStats()
        refreshVolumeCharts()
        applyDonutPlaceholder()
        fetchDashboardMetrics()
    }

    override fun onStart() {
        super.onStart()
        if (!hubReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(AlertEvents.ACTION_ADMIN_DATA_SYNCED)
                addAction(AlertEvents.ACTION_CONNECTION_STATE_CHANGED)
                addAction(AlertEvents.ACTION_ALERTS_UPDATED)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requireContext().registerReceiver(hubReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                requireContext().registerReceiver(hubReceiver, filter)
            }
            hubReceiverRegistered = true
        }
    }

    override fun onResume() {
        super.onResume()
        refreshLocalStats()
    }

    override fun onDestroyView() {
        if (hubReceiverRegistered) {
            try {
                requireContext().unregisterReceiver(hubReceiver)
            } catch (_: Exception) {
            }
            hubReceiverRegistered = false
        }
        super.onDestroyView()
    }

    private fun refreshLocalStats() {
        if (!this::tvStatAlerts.isInitialized) return
        val alertCount = AlertDb(requireContext()).getAllAlerts().size
        tvStatAlerts.text = alertCount.toString()
        refreshVolumeCharts()
        populateRecentAlerts()
        val relayOn = (activity as? AdminDashboardActivity)?.isAdminConnected() == true
        tvStatRelay.text = if (relayOn) {
            getString(R.string.admin_hub_relay_on)
        } else {
            getString(R.string.admin_hub_relay_off)
        }
        tvStatRelay.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                if (relayOn) android.R.color.holo_green_dark else android.R.color.holo_red_dark,
            ),
        )
    }

    private fun fetchDashboardMetrics() {
        if (!this::progressLoad.isInitialized) return
        val accessCode = prefs.adminAccessCode.trim()
        val base = prefs.centralApiUrl.trim().removeSuffix("/")
        if (base.isEmpty() || accessCode.isEmpty()) {
            progressLoad.visibility = View.GONE
            tvStatUsers.text = "0"
            applyDonutPlaceholder()
            cardAdminHubDosePreview.visibility = View.GONE
            return
        }
        cardAdminHubDosePreview.visibility = View.VISIBLE

        val gen = loadGeneration.incrementAndGet()
        progressLoad.visibility = View.VISIBLE

        Thread {
            try {
                val url =
                    "$base/admin/linked-users?access_code=${java.net.URLEncoder.encode(accessCode, "UTF-8")}&dose_preview=1"
                val res = http.newCall(Request.Builder().url(url).get().build()).execute()
                val body = res.body?.string().orEmpty()
                val data = if (body.isNotBlank()) JSONObject(body) else JSONObject()
                val usersArr = data.optJSONArray("users") ?: JSONArray()

                activity?.runOnUiThread {
                    if (gen != loadGeneration.get()) return@runOnUiThread
                    progressLoad.visibility = View.GONE
                    if (!res.isSuccessful) {
                        tvStatUsers.text = "—"
                        applyDonutPlaceholder()
                        refreshLocalStats()
                        populateRosterDosePreview(loadFailed = true)
                        return@runOnUiThread
                    }
                    tvStatUsers.text = usersArr.length().toString()
                    var linked = 0
                    var pending = 0
                    for (i in 0 until usersArr.length()) {
                        val u = usersArr.optJSONObject(i) ?: continue
                        val botId = u.optString("bot_id", "").trim()
                        if (botId.isNotEmpty()) linked++ else pending++
                    }
                    applyDonutFromCounts(linked, pending)
                    populateRosterDosePreview(usersArr = usersArr, loadFailed = false)
                    refreshLocalStats()
                }
            } catch (_: Exception) {
                activity?.runOnUiThread {
                    if (gen != loadGeneration.get()) return@runOnUiThread
                    progressLoad.visibility = View.GONE
                    tvStatUsers.text = "—"
                    applyDonutPlaceholder()
                    refreshLocalStats()
                    populateRosterDosePreview(loadFailed = true)
                }
            }
        }.start()
    }

    private fun computeSevenDayAlertBuckets(): IntArray {
        val buckets = IntArray(7)
        val alerts = AlertDb(requireContext()).getAllAlerts()
        val cal = Calendar.getInstance()
        for (i in 0 until 7) {
            cal.timeInMillis = System.currentTimeMillis()
            cal.add(Calendar.DAY_OF_YEAR, -(6 - i))
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            val start = cal.timeInMillis
            val end = start + 24L * 60 * 60 * 1000
            buckets[i] = alerts.count { it.receivedAt >= start && it.receivedAt < end }
        }
        return buckets
    }

    private fun refreshVolumeCharts() {
        if (!this::chartSparkline.isInitialized) return
        val buckets = computeSevenDayAlertBuckets()
        val sum = buckets.sum()
        val placeholder = sum == 0
        val series = if (placeholder) {
            AdminSparklineChartView.demoCurve()
        } else {
            AdminSparklineChartView.normalizeBuckets(buckets)
        }
        chartSparkline.placeholderMode = placeholder
        chartSparkline.series = series
        chartBars.placeholderMode = placeholder
        chartBars.counts = if (placeholder) IntArray(7) else buckets
        tvChartAlertsCaption.text = if (placeholder) {
            getString(R.string.admin_hub_chart_alerts_sample)
        } else {
            getString(R.string.admin_hub_chart_alerts_live)
        }
    }

    private fun populateRecentAlerts() {
        if (!this::containerRecentAlerts.isInitialized) return
        containerRecentAlerts.removeAllViews()
        val dbAlerts = AlertDb(requireContext()).getAllAlerts()
        val alerts = if (dbAlerts.isNotEmpty()) {
            dbAlerts.take(8)
        } else if (AppRole.isAdmin(requireContext())) {
            AdminDemoData.adminPreviewAlerts().take(8)
        } else {
            emptyList()
        }
        if (alerts.isEmpty()) {
            tvRecentAlertsEmpty.visibility = View.VISIBLE
            return
        }
        tvRecentAlertsEmpty.visibility = View.GONE
        val fmt = SimpleDateFormat("MMM d HH:mm", Locale.getDefault())
        val inflater = layoutInflater
        for (a in alerts) {
            val row = inflater.inflate(R.layout.item_recent_alert_row, containerRecentAlerts, false)
            row.findViewById<TextView>(R.id.tvRecentAlertUser).text =
                a.userName.ifBlank { getString(R.string.admin_user_display_fallback) }
            row.findViewById<TextView>(R.id.tvRecentAlertType).text =
                a.type.ifBlank { "—" }
            row.findViewById<TextView>(R.id.tvRecentAlertTime).text =
                fmt.format(Date(a.receivedAt))
            row.findViewById<TextView>(R.id.tvRecentAlertMsg).text = a.message
            containerRecentAlerts.addView(row)
        }
    }

    private fun applyDonutPlaceholder() {
        if (!this::chartDonut.isInitialized) return
        val green = ContextCompat.getColor(requireContext(), R.color.chart_status_normal)
        val amber = ContextCompat.getColor(requireContext(), R.color.chart_status_low)
        chartDonut.setSlices(
            listOf(
                AdminDonutChartView.Slice(0.72f, green),
                AdminDonutChartView.Slice(0.28f, amber),
            ),
            title = "",
            subtitle = "",
            placeholder = true,
        )
        tvLegendLinked.text = getString(R.string.admin_hub_legend_linked_fmt, 0)
        tvLegendPending.text = getString(R.string.admin_hub_legend_pending_fmt, 0)
        tvChartAccountsCaption.text = getString(R.string.admin_hub_chart_accounts_sample)
    }

    private fun formatDoseTakenAt(raw: String, displayFmt: SimpleDateFormat): String {
        if (raw.isBlank()) return "—"
        val patterns = arrayOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSSSSXXX",
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
        )
        for (pat in patterns) {
            try {
                val sdf = SimpleDateFormat(pat, Locale.US)
                if (pat.endsWith("'Z'")) sdf.timeZone = TimeZone.getTimeZone("UTC")
                sdf.parse(raw)?.time?.let { return displayFmt.format(Date(it)) }
            } catch (_: Exception) {
            }
        }
        return raw
    }

    private fun populateRosterDosePreview(usersArr: JSONArray = JSONArray(), loadFailed: Boolean = false) {
        if (!this::containerRosterDosePreview.isInitialized) return
        containerRosterDosePreview.removeAllViews()
        if (loadFailed) {
            tvRosterDosePreviewEmpty.text = getString(R.string.admin_hub_dose_preview_load_failed)
            tvRosterDosePreviewEmpty.visibility = View.VISIBLE
            return
        }
        if (usersArr.length() == 0) {
            tvRosterDosePreviewEmpty.text = getString(R.string.admin_hub_dose_preview_empty_no_users)
            tvRosterDosePreviewEmpty.visibility = View.VISIBLE
            return
        }
        val displayFmt = SimpleDateFormat("MMM d HH:mm", Locale.getDefault())
        var anyLine = false
        for (i in 0 until usersArr.length()) {
            val u = usersArr.optJSONObject(i) ?: continue
            val name = u.optString("name", "").trim().ifEmpty {
                u.optString("email", "").trim().ifEmpty { getString(R.string.admin_user_display_fallback) }
            }
            val arr = u.optJSONArray("recent_doses") ?: JSONArray()
            if (arr.length() == 0) continue
            anyLine = true
            val title = TextView(requireContext()).apply {
                text = name
                setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
                textSize = 14f
                setTypeface(null, android.graphics.Typeface.BOLD)
                val padTop = if (containerRosterDosePreview.childCount == 0) 0 else 12
                setPadding(0, padTop, 0, 4)
            }
            containerRosterDosePreview.addView(title)
            val maxRows = minOf(arr.length(), 8)
            for (j in 0 until maxRows) {
                val d = arr.optJSONObject(j) ?: continue
                val box = d.optString("box_id", "").trim().ifEmpty { "—" }
                val src = d.optString("source", "").trim().ifEmpty { "—" }
                val taken = d.optString("taken_at", "")
                val line = TextView(requireContext()).apply {
                    text = "· $box · $src · ${formatDoseTakenAt(taken, displayFmt)}"
                    setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
                    textSize = 12f
                    setPadding(8, 2, 0, 2)
                }
                containerRosterDosePreview.addView(line)
            }
        }
        if (anyLine) {
            tvRosterDosePreviewEmpty.visibility = View.GONE
        } else {
            tvRosterDosePreviewEmpty.text = getString(R.string.admin_hub_dose_preview_empty_no_logs)
            tvRosterDosePreviewEmpty.visibility = View.VISIBLE
        }
    }

    private fun applyDonutFromCounts(linked: Int, pending: Int) {
        if (!this::chartDonut.isInitialized) return
        val total = linked + pending
        if (total == 0) {
            applyDonutPlaceholder()
            return
        }
        val green = ContextCompat.getColor(requireContext(), R.color.chart_status_normal)
        val amber = ContextCompat.getColor(requireContext(), R.color.chart_status_low)
        chartDonut.setSlices(
            listOf(
                AdminDonutChartView.Slice(linked.toFloat(), green),
                AdminDonutChartView.Slice(pending.toFloat(), amber),
            ),
            title = total.toString(),
            subtitle = getString(R.string.admin_hub_chart_accounts_title),
            placeholder = false,
        )
        tvLegendLinked.text = getString(R.string.admin_hub_legend_linked_fmt, linked)
        tvLegendPending.text = getString(R.string.admin_hub_legend_pending_fmt, pending)
        tvChartAccountsCaption.text = getString(R.string.admin_hub_chart_accounts_live)
    }
}
