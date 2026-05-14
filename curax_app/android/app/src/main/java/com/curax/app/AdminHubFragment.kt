package com.curax.app

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.card.MaterialCardView
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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
    private lateinit var swipeHub: SwipeRefreshLayout
    private lateinit var progressLoad: ProgressBar
    private lateinit var tvGreeting: TextView
    private lateinit var tvStatUsers: TextView
    private lateinit var tvStatRelay: TextView
    private lateinit var tvStatAlerts: TextView
    private lateinit var chartSparkline: AdminSparklineChartView
    private lateinit var chartBars: AdminBarChartView
    private lateinit var tvChartAlertsCaption: TextView
    private lateinit var readinessView: AdminConnectionReadinessView
    private lateinit var tvChartAccountsCaption: TextView
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

    private val hubMetricsHandler = Handler(Looper.getMainLooper())
    private var hubMetricsRunnable: Runnable? = null
    private val silentDoseGen = AtomicInteger(0)
    @Volatile
    private var hubMetricsFetchForced: Boolean = false

    private var hubReceiverRegistered = false
    private val hubReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val host = this@AdminHubFragment
            if (!host.isAdded) return
            when (intent?.action) {
                AlertEvents.ACTION_ADMIN_DATA_SYNCED -> {
                    if (!host::tvStatAlerts.isInitialized) return
                    host.refreshLocalStats()
                    host.refreshVolumeCharts()
                    host.refreshPulseStats()
                    host.scheduleHubMetricsFetchDebounced(force = true)
                }
                AlertEvents.ACTION_ADMIN_HUB_REFRESH_METRICS ->
                    host.scheduleHubMetricsFetchDebounced(force = true)
                AlertEvents.ACTION_CONNECTION_STATE_CHANGED,
                AlertEvents.ACTION_ALERTS_UPDATED,
                -> {
                    if (!host::tvStatAlerts.isInitialized) return
                    host.refreshLocalStats()
                }
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_admin_hub, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = Prefs(requireContext())
        store = LocalUserStore(requireContext())
        swipeHub = view.findViewById(R.id.swipeAdminHub)
        progressLoad = view.findViewById(R.id.progressAdminHubLoad)
        tvGreeting = view.findViewById(R.id.tvAdminHubGreeting)
        tvStatUsers = view.findViewById(R.id.tvStatUsersValue)
        tvStatRelay = view.findViewById(R.id.tvStatRelayValue)
        tvStatAlerts = view.findViewById(R.id.tvStatAlertsValue)
        chartSparkline = view.findViewById(R.id.chartAlertsSparkline)
        chartBars = view.findViewById(R.id.chartAlertsBars)
        tvChartAlertsCaption = view.findViewById(R.id.tvChartAlertsCaption)
        readinessView = view.findViewById(R.id.readinessView)
        tvChartAccountsCaption = view.findViewById(R.id.tvChartAccountsCaption)
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
        if (MetricsCache.hasSummary()) {
            tvStatUsers.text = MetricsCache.usersTotal.toString()
            applyDonutFromCounts(MetricsCache.linked, MetricsCache.pending)
        } else {
            applyDonutPlaceholder()
        }
        refreshPulseStats()
        wireHubSnapshotNavigation()
        setupHubPullToRefresh(view)
    }

    private fun setupHubPullToRefresh(root: View) {
        if (!this::swipeHub.isInitialized) return
        val accent = ContextCompat.getColor(requireContext(), R.color.button_primary_bg)
        swipeHub.setColorSchemeColors(accent)
        swipeHub.setProgressBackgroundColorSchemeColor(
            ContextCompat.getColor(requireContext(), R.color.surface_bg),
        )
        swipeHub.setOnChildScrollUpCallback { _, child -> child?.canScrollVertically(-1) == true }
        swipeHub.setOnRefreshListener {
            AdminDataBusClient.fetchAdminSnapshotAsync(requireContext()) { _ ->
                if (!isAdded) return@fetchAdminSnapshotAsync
                swipeHub.isRefreshing = false
                refreshLocalStats()
                refreshVolumeCharts()
                refreshPulseStats()
                fetchDashboardMetrics(force = true, suppressProgressBar = true)
            }
        }
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
                addAction(AlertEvents.ACTION_ADMIN_HUB_REFRESH_METRICS)
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
        scheduleHubMetricsFetchDebounced(force = false)
    }

    override fun onDestroyView() {
        hubMetricsRunnable?.let { hubMetricsHandler.removeCallbacks(it) }
        hubMetricsRunnable = null
        if (hubReceiverRegistered) {
            try {
                requireContext().unregisterReceiver(hubReceiver)
            } catch (_: Exception) {
            }
            hubReceiverRegistered = false
        }
        super.onDestroyView()
    }

    /**
     * Coalesces burst events into a single linked-users + dose-preview fetch.
     * @param force bypasses resume-interval throttling (e.g. explicit [AlertEvents.ACTION_ADMIN_HUB_REFRESH_METRICS]).
     */
    private fun scheduleHubMetricsFetchDebounced(force: Boolean = false) {
        val prefs = Prefs(requireContext())
        val base = prefs.centralApiUrl.trim().removeSuffix("/")
        val accessCode = prefs.adminAccessCode.trim()
        if (base.isEmpty() || accessCode.isEmpty()) return
        if (force) hubMetricsFetchForced = true
        hubMetricsRunnable?.let { hubMetricsHandler.removeCallbacks(it) }
        val r = Runnable {
            hubMetricsRunnable = null
            val forced = hubMetricsFetchForced
            hubMetricsFetchForced = false
            fetchDashboardMetrics(force = forced)
        }
        hubMetricsRunnable = r
        hubMetricsHandler.postDelayed(r, 650L)
    }

    private fun refreshLocalStats() {
        if (!this::tvStatAlerts.isInitialized) return
        val dbCount = AlertDb(requireContext()).getAllAlerts().size
        tvStatAlerts.text = AdminDemoData.totalAdminAlertsVisibleCount(
            AppRole.isAdmin(requireContext()),
            dbCount,
        ).toString()
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

    private fun fetchDashboardMetrics(force: Boolean = false, suppressProgressBar: Boolean = false) {
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
        val now = System.currentTimeMillis()
        if (!force &&
            MetricsCache.lastSuccessAtMs > 0L &&
            now - MetricsCache.lastSuccessAtMs < MetricsCache.RESUME_MIN_INTERVAL_MS
        ) {
            restoreHubSummaryFromCache()
            fetchHubDosePreviewSilently()
            return
        }
        cardAdminHubDosePreview.visibility = View.VISIBLE

        val gen = loadGeneration.incrementAndGet()
        silentDoseGen.incrementAndGet()
        progressLoad.visibility = when {
            suppressProgressBar -> View.GONE
            MetricsCache.hasSummary() -> View.GONE
            else -> View.VISIBLE
        }
        val enc = java.net.URLEncoder.encode(accessCode, "UTF-8")
        val urlFast = "$base/admin/linked-users?access_code=$enc"

        Thread {
            try {
                val res1 = http.newCall(Request.Builder().url(urlFast).get().build()).execute()
                val body1 = res1.body?.string().orEmpty()
                val data1 = if (body1.isNotBlank()) JSONObject(body1) else JSONObject()
                val usersFast = data1.optJSONArray("users") ?: JSONArray()
                if (res1.isSuccessful) {
                    AdminLinkedUserDirectory.ingestUsersJsonArray(usersFast)
                }

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
                    MetricsCache.recordSuccess(usersFast.length(), linked, pending)
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
        if (!this::readinessView.isInitialized) return
        lastLinkageLinked = 0
        lastLinkagePending = 0
        readinessView.bind(0, 0, placeholder = true)
        tvChartAccountsCaption.text = getString(R.string.admin_hub_chart_accounts_sample)
        refreshPulseStats()
    }

    private fun parseTakenAtMillis(raw: String): Long? {
        if (raw.isBlank()) return null
        val patterns = arrayOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSSSSXXX",
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd HH:mm:ss.SSS",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd'T'HH:mm:ss",
        )
        for (pat in patterns) {
            try {
                val sdf = SimpleDateFormat(pat, Locale.US)
                if (pat.endsWith("'Z'")) sdf.timeZone = TimeZone.getTimeZone("UTC")
                sdf.parse(raw)?.time?.let { return it }
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun formatDoseTakenAt(raw: String, displayFmt: SimpleDateFormat): String {
        if (raw.isBlank()) return "—"
        parseTakenAtMillis(raw)?.let { return displayFmt.format(Date(it)) }
        return raw
    }

    /** Uses server `name` (includes first/last/username/email fallback from API). */
    private fun hubLinkedUserDisplayName(u: JSONObject): String {
        val name = u.optString("name", "").trim()
        if (name.isNotEmpty() && !name.equals("null", ignoreCase = true)) return name
        val email = u.optString("email", "").trim()
        val fromEmail = if (email.contains("@")) email.substringBefore("@").trim() else ""
        return fromEmail.ifEmpty { getString(R.string.admin_user_display_fallback) }
    }

    private fun dosePreviewText(d: JSONObject, vararg keys: String): String {
        for (key in keys) {
            if (!d.has(key) || d.isNull(key)) continue
            val s = d.optString(key, "").trim()
            if (s.isNotEmpty() && !s.equals("null", ignoreCase = true)) return s
        }
        return ""
    }

    private fun dosePreviewQuantity(d: JSONObject): Int {
        val keys = arrayOf("dose_quantity", "quantity", "dose_qty", "qty", "dose_taken")
        for (key in keys) {
            if (!d.has(key) || d.isNull(key)) continue
            try {
                val dbl = d.optDouble(key, Double.NaN)
                if (!dbl.isNaN()) return dbl.toInt().coerceAtLeast(0)
            } catch (_: Exception) {
            }
            val s = d.optString(key, "").trim()
            s.toIntOrNull()?.let { return it.coerceAtLeast(0) }
        }
        return 1
    }

    /** Auto-missed rows use "Name (auto)" locally; show clean label on admin hub. */
    private fun hubDoseMedicineLabel(raw: String): String {
        val t = raw.trim()
        if (t.lowercase(Locale.getDefault()).endsWith(" (auto)")) {
            return t.substring(0, t.length - " (auto)".length).trim().ifEmpty { t }
        }
        return t
    }

    private fun postClearUserDoseHistory(userId: String) {
        val base = prefs.centralApiUrl.trim().removeSuffix("/")
        val ac = prefs.adminAccessCode.trim()
        if (base.isEmpty() || ac.isEmpty() || userId.isEmpty()) return
        Thread {
            var ok = false
            try {
                val payload = JSONObject().apply {
                    put("access_code", ac)
                    put("user_id", userId)
                }.toString()
                val req = Request.Builder()
                    .url("$base/admin/clear-user-dose-logs")
                    .post(payload.toRequestBody(JSON_MEDIA))
                    .build()
                http.newCall(req).execute().use { res -> ok = res.isSuccessful }
            } catch (_: Exception) {
                ok = false
            }
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                if (ok) {
                    CuraxFeedback.success(this, getString(R.string.admin_hub_dose_history_cleared))
                    scheduleHubMetricsFetchDebounced(force = true)
                } else {
                    CuraxFeedback.warn(this, getString(R.string.admin_hub_dose_history_clear_failed))
                }
            }
        }.start()
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
        val displayFmt = SimpleDateFormat("MMM d, HH:mm:ss", Locale.getDefault())
        val inflater = layoutInflater
        var anyBlock = false
        for (i in 0 until usersArr.length()) {
            val u = usersArr.optJSONObject(i) ?: continue
            val blockTitle = hubLinkedUserDisplayName(u)
            val arr = u.optJSONArray("recent_doses") ?: JSONArray()
            if (arr.length() == 0) continue
            val doseObjs = mutableListOf<JSONObject>()
            for (j in 0 until arr.length()) {
                arr.optJSONObject(j)?.let { doseObjs.add(it) }
            }
            doseObjs.sortByDescending { obj ->
                parseTakenAtMillis(obj.optString("taken_at", "")) ?: Long.MIN_VALUE
            }
            val seenKeys = HashSet<String>()
            val uniqueRows = mutableListOf<JSONObject>()
            for (d in doseObjs) {
                val rawT = d.optString("taken_at", "").trim()
                val medRaw = dosePreviewText(
                    d,
                    "medicine_name",
                    "medicineName",
                    "med_name",
                    "medicine",
                    "drug_name",
                )
                val medNorm = hubDoseMedicineLabel(medRaw).trim().lowercase(Locale.getDefault())
                val boxNorm = d.optString("box_id", "").trim().lowercase(Locale.getDefault())
                val key = "${rawT.lowercase(Locale.getDefault())}|$boxNorm|$medNorm"
                if (!seenKeys.add(key)) continue
                uniqueRows.add(d)
                if (uniqueRows.size >= 50) break
            }
            if (uniqueRows.isEmpty()) continue
            anyBlock = true
            val block = inflater.inflate(R.layout.admin_hub_user_dose_block, containerHubDoseTables, false)
            val userId = u.optString("id", "").trim()
            block.findViewById<TextView>(R.id.tvHubDoseBlockUserName).text = blockTitle
            block.findViewById<ImageButton>(R.id.btnHubDoseClearHistory).apply {
                visibility = if (userId.isNotEmpty()) View.VISIBLE else View.GONE
                setOnClickListener {
                    if (userId.isEmpty()) return@setOnClickListener
                    AlertDialog.Builder(requireContext())
                        .setTitle(R.string.admin_hub_clear_dose_history_title)
                        .setMessage(getString(R.string.admin_hub_clear_dose_history_message, blockTitle))
                        .setPositiveButton(R.string.delete) { _, _ -> postClearUserDoseHistory(userId) }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
            }
            val rowsParent = block.findViewById<LinearLayout>(R.id.containerHubDoseRowsForUser)
            for (d in uniqueRows) {
                val row = inflater.inflate(R.layout.item_dose_history_row, rowsParent, false)
                row.findViewById<TextView>(R.id.tvDoseHistTs).text =
                    formatDoseTakenAt(d.optString("taken_at", ""), displayFmt)
                row.findViewById<TextView>(R.id.tvDoseHistBox).text =
                    d.optString("box_id", "").trim().ifEmpty { "—" }
                val medNameRaw = dosePreviewText(
                    d,
                    "medicine_name",
                    "medicineName",
                    "med_name",
                    "medicine",
                    "drug_name",
                )
                val medName = hubDoseMedicineLabel(medNameRaw)
                row.findViewById<TextView>(R.id.tvDoseHistMed).text =
                    medName.ifEmpty { "—" }
                val dq = dosePreviewQuantity(d)
                row.findViewById<TextView>(R.id.tvDoseHistDose).text =
                    if (dq <= 0 && medNameRaw.contains("(auto)", ignoreCase = true)) "—" else dq.toString()
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
        if (!this::readinessView.isInitialized) return
        lastLinkageLinked = linked
        lastLinkagePending = pending
        val total = linked + pending
        if (total == 0) {
            applyDonutPlaceholder()
            return
        }
        readinessView.bind(linked, pending, placeholder = false)
        tvChartAccountsCaption.text = getString(R.string.admin_hub_chart_accounts_live)
        refreshPulseStats()
    }

    private fun restoreHubSummaryFromCache() {
        if (!this::tvStatUsers.isInitialized || !MetricsCache.hasSummary()) return
        tvStatUsers.text = MetricsCache.usersTotal.toString()
        applyDonutFromCounts(MetricsCache.linked, MetricsCache.pending)
        refreshLocalStats()
        refreshPulseStats()
        cardAdminHubDosePreview.visibility = View.VISIBLE
    }

    /** One lightweight dose-preview request without clearing counts or showing the hub progress bar. */
    private fun fetchHubDosePreviewSilently() {
        if (!this::containerHubDoseTables.isInitialized) return
        val accessCode = prefs.adminAccessCode.trim()
        val base = prefs.centralApiUrl.trim().removeSuffix("/")
        if (base.isEmpty() || accessCode.isEmpty()) return
        val enc = java.net.URLEncoder.encode(accessCode, "UTF-8")
        val urlDose = "$base/admin/linked-users?access_code=$enc&dose_preview=1"
        val g = silentDoseGen.incrementAndGet()
        Thread {
            try {
                val res2 = http.newCall(Request.Builder().url(urlDose).get().build()).execute()
                val body2 = res2.body?.string().orEmpty()
                val data2 = if (body2.isNotBlank()) JSONObject(body2) else JSONObject()
                val usersDose = data2.optJSONArray("users") ?: JSONArray()
                activity?.runOnUiThread {
                    if (!isAdded || g != silentDoseGen.get()) return@runOnUiThread
                    if (!res2.isSuccessful) {
                        populateHubDoseTables(loadFailed = true)
                    } else {
                        populateHubDoseTables(usersArr = usersDose, loadFailed = false)
                    }
                    refreshLocalStats()
                }
            } catch (_: Exception) {
                activity?.runOnUiThread {
                    if (!isAdded || g != silentDoseGen.get()) return@runOnUiThread
                    populateHubDoseTables(loadFailed = true)
                }
            }
        }.start()
    }

    companion object MetricsCache {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        /** Min time between automatic hub linked-users + dose-preview pulls when only re-opening the Dashboard tab. */
        const val RESUME_MIN_INTERVAL_MS = 5 * 60 * 1000L
        @Volatile var lastSuccessAtMs: Long = 0L
        @Volatile var usersTotal: Int = -1
        @Volatile var linked: Int = 0
        @Volatile var pending: Int = 0

        fun hasSummary(): Boolean = usersTotal >= 0

        fun recordSuccess(total: Int, linkedCt: Int, pendingCt: Int) {
            usersTotal = total
            linked = linkedCt
            pending = pendingCt
            lastSuccessAtMs = System.currentTimeMillis()
        }
    }
}
