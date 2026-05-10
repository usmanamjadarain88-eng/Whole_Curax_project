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
 * Admin home: greeting, actionable snapshot tiles, charts, and per-user dose history tables.
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
    private lateinit var tvPulseAlertsValue: TextView
    private lateinit var tvPulsePeakValue: TextView
    private lateinit var tvPulseReadyValue: TextView
    private lateinit var cardHubStatUsers: MaterialCardView
    private lateinit var cardHubStatRelay: MaterialCardView
    private lateinit var cardHubStatAlerts: MaterialCardView
    private lateinit var cardAdminHubDosePreview: MaterialCardView
    private lateinit var containerHubDoseTables: LinearLayout
    private lateinit var tvRosterDosePreviewEmpty: TextView

    private var lastLinkageLinked: Int = 0
    private var lastLinkagePending: Int = 0

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
        tvPulseAlertsValue = view.findViewById(R.id.tvPulseAlertsValue)
        tvPulsePeakValue = view.findViewById(R.id.tvPulsePeakValue)
        tvPulseReadyValue = view.findViewById(R.id.tvPulseReadyValue)
        cardHubStatUsers = view.findViewById(R.id.cardHubStatUsers)
        cardHubStatRelay = view.findViewById(R.id.cardHubStatRelay)
        cardHubStatAlerts = view.findViewById(R.id.cardHubStatAlerts)
        cardAdminHubDosePreview = view.findViewById(R.id.cardAdminHubDosePreview)
        containerHubDoseTables = view.findViewById(R.id.containerHubDoseTables)
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
        refreshPulseStats()
        wireHubSnapshotNavigation()
        fetchDashboardMetrics()
    }

    private fun wireHubSnapshotNavigation() {
        cardHubStatUsers.setOnClickListener {
            (activity as? AdminDashboardActivity)?.navigateAdminHomeToUsersTab()
        }
        cardHubStatRelay.setOnClickListener {
            (activity as? AdminDashboardActivity)?.openAdminDrawerForRelay()
        }
        cardHubStatAlerts.setOnClickListener {
            (activity as? AdminDashboardActivity)?.navigateAdminHomeToAlertsTab()
        }
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
            refreshPulseStats()
            cardAdminHubDosePreview.visibility = View.GONE
            return
        }
        cardAdminHubDosePreview.visibility = View.VISIBLE

        val gen = loadGeneration.incrementAndGet()
        progressLoad.visibility = View.VISIBLE
        val enc = java.net.URLEncoder.encode(accessCode, "UTF-8")
        val urlFast = "$base/admin/linked-users?access_code=$enc"

        Thread {
            try {
                val res1 = http.newCall(Request.Builder().url(urlFast).get().build()).execute()
                val body1 = res1.body?.string().orEmpty()
                val data1 = if (body1.isNotBlank()) JSONObject(body1) else JSONObject()
                val usersFast = data1.optJSONArray("users") ?: JSONArray()

                activity?.runOnUiThread {
                    if (gen != loadGeneration.get()) return@runOnUiThread
                    progressLoad.visibility = View.GONE
                    if (!res1.isSuccessful) {
                        tvStatUsers.text = "—"
                        applyDonutPlaceholder()
                        refreshLocalStats()
                        refreshPulseStats()
                        populateHubDoseTables(loadFailed = true)
                        return@runOnUiThread
                    }
                    tvStatUsers.text = usersFast.length().toString()
                    var linked = 0
                    var pending = 0
                    for (i in 0 until usersFast.length()) {
                        val u = usersFast.optJSONObject(i) ?: continue
                        val botId = u.optString("bot_id", "").trim()
                        if (botId.isNotEmpty()) linked++ else pending++
                    }
                    applyDonutFromCounts(linked, pending)
                    refreshLocalStats()
                    refreshPulseStats()
                    containerHubDoseTables.removeAllViews()
                    tvRosterDosePreviewEmpty.text = getString(R.string.admin_hub_dose_preview_loading)
                    tvRosterDosePreviewEmpty.visibility = View.VISIBLE
                }

                if (res1.isSuccessful) {
                    val urlDose = "$base/admin/linked-users?access_code=$enc&dose_preview=1"
                    val res2 = http.newCall(Request.Builder().url(urlDose).get().build()).execute()
                    val body2 = res2.body?.string().orEmpty()
                    val data2 = if (body2.isNotBlank()) JSONObject(body2) else JSONObject()
                    val usersDose = data2.optJSONArray("users") ?: JSONArray()

                    activity?.runOnUiThread {
                        if (gen != loadGeneration.get()) return@runOnUiThread
                        if (!res2.isSuccessful) {
                            populateHubDoseTables(loadFailed = true)
                        } else {
                            populateHubDoseTables(usersArr = usersDose, loadFailed = false)
                        }
                        refreshLocalStats()
                    }
                }
            } catch (_: Exception) {
                activity?.runOnUiThread {
                    if (gen != loadGeneration.get()) return@runOnUiThread
                    progressLoad.visibility = View.GONE
                    tvStatUsers.text = "—"
                    applyDonutPlaceholder()
                    refreshLocalStats()
                    refreshPulseStats()
                    populateHubDoseTables(loadFailed = true)
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
        refreshPulseStats()
    }

    private fun refreshPulseStats() {
        if (!this::tvPulseAlertsValue.isInitialized) return
        val buckets = computeSevenDayAlertBuckets()
        tvPulseAlertsValue.text = buckets.sum().toString()
        val maxIdx = buckets.indices.maxByOrNull { buckets[it] } ?: 0
        val cal = Calendar.getInstance()
        cal.timeInMillis = System.currentTimeMillis()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        cal.add(Calendar.DAY_OF_YEAR, -(6 - maxIdx))
        tvPulsePeakValue.text = SimpleDateFormat("EEE", Locale.getDefault()).format(cal.time)
        val total = lastLinkageLinked + lastLinkagePending
        val pct = if (total > 0) (lastLinkageLinked * 100 / total) else 0
        tvPulseReadyValue.text = "${pct}%"
    }

    private fun applyDonutPlaceholder() {
        if (!this::chartDonut.isInitialized) return
        lastLinkageLinked = 0
        lastLinkagePending = 0
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
        refreshPulseStats()
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

    /** Uses server `name` only (same headline rule as Users tab rows). */
    private fun hubLinkedUserDisplayName(u: JSONObject): String {
        val name = u.optString("name", "").trim()
        return name.ifEmpty { getString(R.string.admin_user_display_fallback) }
    }

    private fun populateHubDoseTables(usersArr: JSONArray = JSONArray(), loadFailed: Boolean = false) {
        if (!this::containerHubDoseTables.isInitialized) return
        containerHubDoseTables.removeAllViews()
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
        val displayFmt = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
        val inflater = layoutInflater
        var anyBlock = false
        for (i in 0 until usersArr.length()) {
            val u = usersArr.optJSONObject(i) ?: continue
            val blockTitle = hubLinkedUserDisplayName(u)
            val arr = u.optJSONArray("recent_doses") ?: JSONArray()
            if (arr.length() == 0) continue
            anyBlock = true
            val block = inflater.inflate(R.layout.admin_hub_user_dose_block, containerHubDoseTables, false)
            block.findViewById<TextView>(R.id.tvHubDoseBlockUserName).text = blockTitle
            val rowsParent = block.findViewById<LinearLayout>(R.id.containerHubDoseRowsForUser)
            val maxRows = minOf(arr.length(), 50)
            for (j in 0 until maxRows) {
                val d = arr.optJSONObject(j) ?: continue
                val row = inflater.inflate(R.layout.item_dose_history_row, rowsParent, false)
                row.findViewById<TextView>(R.id.tvDoseHistTs).text =
                    formatDoseTakenAt(d.optString("taken_at", ""), displayFmt)
                row.findViewById<TextView>(R.id.tvDoseHistBox).text =
                    d.optString("box_id", "").trim().ifEmpty { "—" }
                val medName = d.optString("medicine_name", "").trim()
                row.findViewById<TextView>(R.id.tvDoseHistMed).text =
                    medName.ifEmpty { "—" }
                val dq = when {
                    d.has("dose_quantity") && !d.isNull("dose_quantity") ->
                        d.optInt("dose_quantity", 1).coerceAtLeast(1)
                    else -> 1
                }
                row.findViewById<TextView>(R.id.tvDoseHistDose).text = dq.toString()
                val remTxt = when {
                    d.has("stock_quantity") && !d.isNull("stock_quantity") -> {
                        try {
                            d.getInt("stock_quantity").toString()
                        } catch (_: Exception) {
                            "—"
                        }
                    }
                    else -> "—"
                }
                row.findViewById<TextView>(R.id.tvDoseHistRem).text = remTxt
                rowsParent.addView(row)
            }
            containerHubDoseTables.addView(block)
        }
        if (anyBlock) {
            tvRosterDosePreviewEmpty.visibility = View.GONE
        } else {
            tvRosterDosePreviewEmpty.text = getString(R.string.admin_hub_dose_preview_empty_no_logs)
            tvRosterDosePreviewEmpty.visibility = View.VISIBLE
        }
    }

    private fun applyDonutFromCounts(linked: Int, pending: Int) {
        if (!this::chartDonut.isInitialized) return
        lastLinkageLinked = linked
        lastLinkagePending = pending
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
        refreshPulseStats()
    }
}
