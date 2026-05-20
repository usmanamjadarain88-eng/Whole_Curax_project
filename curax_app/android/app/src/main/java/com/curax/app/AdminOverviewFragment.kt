package com.curax.app

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.print.PrintAttributes
import android.print.PrintManager
import android.util.TypedValue
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.webkit.WebView
import androidx.activity.result.contract.ActivityResultContracts
import android.webkit.WebViewClient
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.curax.app.AdherenceLineChartView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.TimeUnit
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class AdminOverviewFragment : Fragment() {

    data class InventoryItem(
        val id: Long,
        var name: String,
        var stock: Int,
        var dosePerDay: Int,
        var exactTime: String,
        var expiry: String,
        var status: String,
        var box: String,
        var addedAt: Long,
        /** When true, [scheduleTimesList] holds all daily times (max 4); otherwise single [exactTime]. */
        var useMultipleTimesPerDay: Boolean = false,
        var scheduleTimesList: MutableList<String> = mutableListOf(),
    ) {
        fun displayTimesLabel(): String {
            val times = effectiveTimesForApi()
            return times.joinToString(" · ")
        }

        fun effectiveTimesForApi(): List<String> {
            val cleaned = MedicineSchedule.dedupeSorted(scheduleTimesList)
            if (useMultipleTimesPerDay && cleaned.size > 1) {
                return cleaned.take(MedicineSchedule.MAX_SCHEDULE_SLOTS)
            }
            val one = MedicineSchedule.normalizeToHhMm(exactTime)
            return listOf(if (one.isNotEmpty()) one else "08:00")
        }

        fun dosePerAdministrationAmount(): Int = dosePerDay.coerceAtLeast(1)

        fun totalDoseUnitsPerDay(): Int {
            val slots = effectiveTimesForApi().size.coerceAtLeast(1)
            return if (useMultipleTimesPerDay && slots > 1) {
                dosePerAdministrationAmount() * slots
            } else {
                dosePerAdministrationAmount()
            }
        }

        /** Compact dose column: multi → "1×3", single → "2". */
        fun displayDoseCell(): String {
            val slots = effectiveTimesForApi().size.coerceAtLeast(1)
            return if (useMultipleTimesPerDay && slots > 1) {
                "${dosePerDay}×$slots"
            } else {
                dosePerDay.toString()
            }
        }
    }

    private val allItems = mutableListOf<InventoryItem>()
    private var selectedItemId: Long? = null
    private lateinit var adapter: AdminInventoryAdapter
    private var adherenceChart: AdherenceLineChartView? = null
    private var weekOffset: Int = 0 // 0 = current 7 days, 1 = previous 7, etc.
    private var lastDonutTotal: Int = -1
    private var donutPulseAnim: ValueAnimator? = null
    private var alertsReceiverRegistered = false
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var adminPollRunnable: Runnable? = null
    private var adminPollInFlight: Boolean = false
    private val adminPollIntervalMs: Long = 5000L
    private var healthHubIndicatorSyncing: Boolean = false
    /** Throttle admin Care dashboard GET /admin/data on tab resumes (no periodic polling). */
    private var lastAdminCareDashboardFetchElapsedMs: Long = 0L
    private var activeHealthHubSheet: BottomSheetDialog? = null
    /** Bumped when standalone Health Hub plan count should ignore an in-flight [UserPlansApi.fetchPlans]. */
    private var healthHubPlanFetchSeq: Int = 0
    /** While the planned-items sheet is open, invoked after a plan is created on the full-screen activity. */
    private var healthHubPlannedReloadCallback: (() -> Unit)? = null

    private val healthHubCreatePlanLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            healthHubPlannedReloadCallback?.invoke()
        }
    }

    private val dataSyncReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                AlertEvents.ACTION_ALERTS_UPDATED -> {
                    if (Prefs(requireContext()).hasEverConnected) {
                        view?.post { refreshAdherenceChartFromAlerts() }
                    }
                    view?.post {
                        if ((isUserApp() && StandaloneUi.isUserStandalone(requireContext())) ||
                            CareUi.useStandaloneLayoutsInCare(requireContext())) {
                            view?.let { refreshStandaloneHealthHubPillCounts(it) }
                            StandaloneOfflineMirror.persistMergedSnapshot(requireContext())
                        }
                    }
                }
                AlertEvents.ACTION_ADMIN_DATA_SYNCED -> {
                    view?.post { refreshStandaloneFromMemory() }
                }
                AlertEvents.ACTION_USER_STANDALONE_DATA_FETCH_STARTED -> {
                    view?.post {
                        val v = view ?: return@post
                        setHealthHubSyncing(v, true)
                    }
                }
                AlertEvents.ACTION_USER_STANDALONE_DATA_FETCH_ENDED -> {
                    view?.post {
                        val v = view ?: return@post
                        setHealthHubSyncing(v, false)
                    }
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val layout = if (CareUi.effectiveStandaloneShell(requireContext())) {
            R.layout.fragment_admin_overview_standalone
        } else {
            R.layout.fragment_admin_overview
        }
        return inflater.inflate(layout, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // In user standalone mode, don't seed until bootstrap fetch completes (prevents showing dummy data).
        // For admin mode, seed immediately (admin data is loaded separately).
        ensureInventorySeeded()
        setupInventory(view)
        if (view.findViewById<View>(R.id.cardB1) != null) {
            setupBoxClicks(view)
        }
        applyStandaloneMedicineBoxGoldTheme(view)
        setupAdherenceChart(view)
        refreshDashboard(view)
        refreshInventoryList(view)
        setupStandaloneHealthHubStatus(view)
        setupStandaloneHealthHubPills(view)
        setupKpiClicks(view)
        // Socket-only sync: no periodic HTTP polling.
        startAdminPollingIfNeeded(view)
        view.post { setupOverviewPullToRefresh(view) }
    }

    private fun setupOverviewPullToRefresh(root: View) {
        val swipe = root.findViewById<SwipeRefreshLayout>(R.id.swipeAdminOverview) ?: return
        // User home shell already wraps the pager in pull-to-refresh; a nested swipe here fires on tab swipes.
        if (activity is UserStandaloneActivity) {
            swipe.isEnabled = false
            swipe.isRefreshing = false
            return
        }
        val accent = ContextCompat.getColor(requireContext(), R.color.button_primary_bg)
        swipe.setColorSchemeColors(accent)
        swipe.setProgressBackgroundColorSchemeColor(
            ContextCompat.getColor(requireContext(), R.color.surface_bg),
        )
        swipe.setOnChildScrollUpCallback { _, child -> child?.canScrollVertically(-1) == true }
        swipe.setOnRefreshListener {
            if (isUserApp()) {
                val prefs = Prefs(requireContext())
                val base = prefs.centralApiUrl.trim().removeSuffix("/")
                val botId = prefs.id.trim()
                val apiKey = prefs.apiKey.trim()
                if (botId.isEmpty() || apiKey.isEmpty() || base.isEmpty()) {
                    swipe.isRefreshing = false
                    return@setOnRefreshListener
                }
                UserDataBusClient.fetchAndApplyUserData(
                    requireContext(),
                    base,
                    botId,
                    apiKey,
                    onFetchFinished = { swipe.isRefreshing = false },
                )
            } else {
                AdminDataBusClient.fetchAdminSnapshotAsync(requireContext()) { result ->
                    swipe.isRefreshing = false
                    if (result == AdminDataBusClient.SnapshotResult.APPLIED) {
                        root.post {
                            allItems.clear()
                            seedInventory()
                            refreshInventoryList(root)
                            refreshDashboard(root)
                            adherenceChart?.data = computeAdherenceData()
                        }
                    }
                }
            }
        }
    }

    private fun setupStandaloneHealthHubPills(view: View) {
        if (!CareUi.effectiveStandaloneShell(requireContext())) return
        view.findViewById<View>(R.id.health_hub_pill_planned)?.setOnClickListener {
            showHealthHubPlannedItemsBottomSheet()
        }
        view.findViewById<View>(R.id.health_hub_pill_alerts)?.setOnClickListener {
            showHealthHubAlertsBottomSheet()
        }
        view.findViewById<View>(R.id.health_hub_pill_sync)?.setOnClickListener {
            showHealthHubSyncHistoryBottomSheet()
        }
    }

    override fun onStart() {
        super.onStart()
        if (!alertsReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(AlertEvents.ACTION_ALERTS_UPDATED)
                addAction(AlertEvents.ACTION_ADMIN_DATA_SYNCED)
                addAction(AlertEvents.ACTION_USER_STANDALONE_DATA_FETCH_STARTED)
                addAction(AlertEvents.ACTION_USER_STANDALONE_DATA_FETCH_ENDED)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requireContext().registerReceiver(dataSyncReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                requireContext().registerReceiver(dataSyncReceiver, filter)
            }
            alertsReceiverRegistered = true
        }
    }

    override fun onResume() {
        super.onResume()
        val v = view ?: return
        if (isUserApp()) {
            // No network fetch on tab switch; repaint from memory off the immediate resume path.
            v.post { refreshStandaloneFromMemory() }
            return
        }
        fetchAdminDataWhenDashboardShown(v)
        allItems.clear()
        seedInventory()
        refreshInventoryList(v)
        refreshDashboard(v)
        adherenceChart?.data = computeAdherenceData()
    }

    /** Fetch full admin data from server when Dashboard is shown so desktop add/remove medicine is reflected. */
    private fun fetchAdminDataWhenDashboardShown(v: View) {
        val prefs = Prefs(requireContext())
        val base = prefs.centralApiUrl.trim().removeSuffix("/")
        val accessCode = prefs.adminAccessCode.trim()
        if (base.isEmpty()) return
        if (!isUserApp() && accessCode.isEmpty()) return
        val botId = prefs.id.trim()
        val apiKey = prefs.apiKey.trim()
        if (isUserApp() && (botId.isEmpty() || apiKey.isEmpty())) return
        if (!isUserApp()) {
            val now = SystemClock.elapsedRealtime()
            if (lastAdminCareDashboardFetchElapsedMs != 0L &&
                now - lastAdminCareDashboardFetchElapsedMs < 15_000L
            ) {
                return
            }
        }
        Thread {
            try {
                var url = if (isUserApp()) {
                    "$base/user/data?bot_id=${URLEncoder.encode(botId, "UTF-8")}&api_key=${URLEncoder.encode(apiKey, "UTF-8")}"
                } else {
                    "$base/admin/data?access_code=${URLEncoder.encode(accessCode, "UTF-8")}"
                }
                if (!isUserApp() && prefs.actAsUserId.isNotEmpty()) {
                    url += "&act_as_user_id=${URLEncoder.encode(prefs.actAsUserId, "UTF-8")}"
                }
                val req = Request.Builder().url(url).get().build()
                val res = http.newCall(req).execute()
                if (!res.isSuccessful) return@Thread
                val body = res.body?.string() ?: "{}"
                try {
                    val data = JSONObject(body)
                    val serverTime = data.optString("server_time", "").trim()
                    if (serverTime.isNotEmpty()) prefs.lastSyncTime = serverTime

                    val medicinesArray = data.optJSONArray("medicines") ?: JSONArray()
                    val list = mutableListOf<Map<String, Any?>>()
                    for (i in 0 until medicinesArray.length()) {
                        val o = medicinesArray.optJSONObject(i) ?: continue
                        val m = mutableMapOf<String, Any?>()
                        m["name"] = o.optString("name")
                        m["box_id"] = o.optString("box_id")
                        m["dosage"] = o.optString("dosage")
                        m["low_stock"] = o.optInt("low_stock", 5)
                        m["quantity"] = o.optInt("quantity", 0)
                        m["expiry"] = o.optString("expiry")
                        m["exact_time"] = o.optString("exact_time")
                        m["dose_per_day"] = o.optInt("dose_per_day", 0)
                        m["instructions"] = o.optString("instructions")
                        val times = o.optJSONArray("times")
                        m["times"] = if (times != null) (0 until times.length()).map { times.optString(it) } else emptyList<String>()
                        list.add(m)
                    }

                    activity?.runOnUiThread {
                        if (isUserApp()) {
                            val ufn = data.optString("user_first_name", "").trim()
                            if (ufn.isNotEmpty()) Prefs(requireContext()).userHubFirstName = ufn
                            val ufull = data.optString("user_full_name", "").trim()
                            if (ufull.isNotEmpty()) Prefs(requireContext()).userHubFullName = ufull
                            val uuname = data.optString("user_username", "").trim()
                            if (uuname.isNotEmpty()) Prefs(requireContext()).userHubUsername = uuname
                        }
                        // Use AdminDataBusClient to apply all data (medicines, alerts, medical_reminders, alert_settings)
                        // This ensures medical reminders and settings are also loaded when dashboard is shown
                        AdminDataBusClient.applyAdminDataJson(requireContext(), data)
                        if (!isUserApp()) {
                            lastAdminCareDashboardFetchElapsedMs = SystemClock.elapsedRealtime()
                            AdminDataBusClient.broadcastSnapshotAppliedForUi(requireContext())
                        }
                        allItems.clear()
                        seedInventory()
                        refreshInventoryList(v)
                        refreshDashboard(v)
                        adherenceChart?.data = computeAdherenceData()
                    }
                } catch (_: Exception) { }
            } catch (_: Exception) { }
        }.start()
    }

    /** One-time full fetch (no last_sync_time) to populate boxes when backend has data but first load was empty. */
    private fun fetchFullAdminDataThenRefresh(v: View, prefs: Prefs) {
        val base = prefs.centralApiUrl.trim().removeSuffix("/")
        val accessCode = prefs.adminAccessCode.trim()
        if (base.isEmpty()) return
        if (!isUserApp() && accessCode.isEmpty()) return
        val botId = prefs.id.trim()
        val apiKey = prefs.apiKey.trim()
        if (isUserApp() && (botId.isEmpty() || apiKey.isEmpty())) return
        Thread {
            try {
                var url = if (isUserApp()) {
                    "$base/user/data?bot_id=${URLEncoder.encode(botId, "UTF-8")}&api_key=${URLEncoder.encode(apiKey, "UTF-8")}"
                } else {
                    "$base/admin/data?access_code=${URLEncoder.encode(accessCode, "UTF-8")}"
                }
                if (!isUserApp() && prefs.actAsUserId.isNotEmpty()) {
                    url += "&act_as_user_id=${URLEncoder.encode(prefs.actAsUserId, "UTF-8")}"
                }
                val req = Request.Builder().url(url).get().build()
                val res = http.newCall(req).execute()
                if (res.isSuccessful) {
                    val body = res.body?.string() ?: "{}"
                    try {
                        val data = JSONObject(body)
                        val serverTime = data.optString("server_time", "").trim()
                        if (serverTime.isNotEmpty()) prefs.lastSyncTime = serverTime
                        val medicinesArray = data.optJSONArray("medicines") ?: JSONArray()
                        val list = mutableListOf<Map<String, Any?>>()
                        for (i in 0 until medicinesArray.length()) {
                            val o = medicinesArray.optJSONObject(i) ?: continue
                            val m = mutableMapOf<String, Any?>()
                            m["name"] = o.optString("name")
                            m["box_id"] = o.optString("box_id")
                            m["dosage"] = o.optString("dosage")
                            m["low_stock"] = o.optInt("low_stock", 5)
                            m["quantity"] = o.optInt("quantity", 0)
                            m["expiry"] = o.optString("expiry")
                            m["exact_time"] = o.optString("exact_time")
                            m["dose_per_day"] = o.optInt("dose_per_day", 0)
                            m["instructions"] = o.optString("instructions")
                            val times = o.optJSONArray("times")
                            m["times"] = if (times != null) (0 until times.length()).map { times.optString(it) } else emptyList<String>()
                            list.add(m)
                        }
                        val medicinesList = AdminDemoData.fromApiMedicines(list)
                        val alertsArray = data.optJSONArray("alerts")
                        val apiAlerts = AdminDemoData.fromApiAlerts(alertsArray)
                        activity?.runOnUiThread {
                            if (isUserApp()) {
                                val ufn = data.optString("user_first_name", "").trim()
                                if (ufn.isNotEmpty()) Prefs(requireContext()).userHubFirstName = ufn
                                val ufull = data.optString("user_full_name", "").trim()
                                if (ufull.isNotEmpty()) Prefs(requireContext()).userHubFullName = ufull
                                val uuname = data.optString("user_username", "").trim()
                                if (uuname.isNotEmpty()) Prefs(requireContext()).userHubUsername = uuname
                            }
                            AdminDemoData.replaceMedicines(requireContext(), medicinesList)
                            AdminDemoData.replaceApiAlerts(apiAlerts)
                            val n = AdminDemoData.medicines.size
                            allItems.clear()
                            seedInventory()
                            refreshInventoryList(v)
                            refreshDashboard(v)
                            adherenceChart?.data = computeAdherenceData()
                            if (n > 0) {
                                if (!CareUi.effectiveStandaloneShell(requireContext())) {
                                    CuraxFeedback.success(requireActivity(), "Imported $n medicines from desktop.")
                                }
                            } else {
                                CuraxFeedback.warn(requireActivity(), "No medicines imported from desktop.", long = true)
                            }
                        }
                    } catch (_: Exception) { }
                }
            } catch (_: Exception) { }
        }.start()
    }

    override fun onStop() {
        adminPollRunnable?.let { mainHandler.removeCallbacks(it) }
        adminPollRunnable = null
        adminPollInFlight = false
        super.onStop()
    }

    override fun onDestroyView() {
        dismissActiveHealthHubSheet()
        cancelHealthHubStatusAnimations()
        if (alertsReceiverRegistered) {
            try {
                requireContext().unregisterReceiver(dataSyncReceiver)
            } catch (_: Exception) {}
            alertsReceiverRegistered = false
        }
        super.onDestroyView()
    }

    /** Polling disabled: admin sync is handled by DataBus socket push. */
    private fun startAdminPollingIfNeeded(view: View) {
        adminPollRunnable = null
        adminPollInFlight = false
    }

    /** Refresh consumption trend from AlertDb when new alerts arrive (real-time). */
    private fun refreshAdherenceChartFromAlerts() {
        val points = computeAdherenceData()
        // Avoid wiping chart with an all-null series.
        if (points.isNotEmpty() && points.all { it.adherencePercent == null }) {
            return
        }
        adherenceChart?.data = points
        view?.let { v ->
            val tvIndicator = v.findViewById<TextView>(R.id.tvChartPageIndicator) ?: return@let
            val btnNext = v.findViewById<MaterialButton>(R.id.btnChartNext) ?: return@let
            val (start, end) = getWeekRangeLabels()
            tvIndicator.text = "$start - $end"
            btnNext.isEnabled = weekOffset > 0
        }
    }

    private fun seedInventory() {
        if (allItems.isNotEmpty()) return
        var order = 0L
        AdminDemoData.medicines.forEach { m ->
            allItems.add(
                InventoryItem(
                    id = System.nanoTime() + order,
                    name = m.name,
                    stock = m.stock,
                    dosePerDay = m.dosePerDay,
                    exactTime = m.effectiveScheduleTimes().firstOrNull() ?: m.exactTime,
                    expiry = m.expiry,
                    status = m.status,
                    box = m.box,
                    addedAt = System.currentTimeMillis() - (10000L - order * 10),
                    useMultipleTimesPerDay = m.usesMultipleTimesPerDay(),
                    scheduleTimesList = m.effectiveScheduleTimes().toMutableList(),
                ),
            )
            order++
        }
    }

    private fun ensureInventorySeeded() {
        val prefs = Prefs(requireContext())
        if (isUserApp()) {
            if (prefs.awaitingAdminLinkApproval) return
            if (!prefs.userStandaloneDataReady) return
            val linked = prefs.linkedAdminId.trim().isNotEmpty()
            if (linked && !prefs.userStandaloneDataReady) return
        }
        if (allItems.isEmpty() && AdminDemoData.medicines.isNotEmpty()) {
            seedInventory()
        }
    }

    fun refreshStandaloneFromMemory() {
        val v = view ?: return
        if (!isUserApp()) return
        allItems.clear()
        seedInventory()
        if (allItems.none { it.id == selectedItemId }) {
            selectedItemId = null
            if (::adapter.isInitialized) {
                adapter.setSelectedId(null)
            }
        }
        refreshInventoryList(v)
        refreshDashboard(v)
        adherenceChart?.data = computeAdherenceData()
        // Do not persist snapshot here: tab switches call this every resume and would rewrite prefs +
        // reschedule all local alarms on the main thread (jank). Persist runs after real data changes/sync.
    }

    private fun setupInventory(view: View) {
        adapter = AdminInventoryAdapter(
            onItemClick = { item ->
                selectedItemId = item.id
                adapter.setSelectedId(selectedItemId)
                if (isUserApp()) {
                    showBoxDetailsDialog(item)
                }
            },
            computedStatus = { computedStatus(it) }
        )

        view.findViewById<RecyclerView>(R.id.rvInventory).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@AdminOverviewFragment.adapter
        }

        view.findViewById<MaterialButton>(R.id.btnAddMedicine)?.setOnClickListener {
            showAddDialog(view)
        }

        view.findViewById<MaterialButton>(R.id.btnEditMedicine)?.setOnClickListener {
            val selected = allItems.find { it.id == selectedItemId }
            if (selected == null) {
                CuraxFeedback.warn(this, "Select a medicine first")
            } else {
                showEditDialog(view, selected)
            }
        }

        view.findViewById<HorizontalScrollView>(R.id.hsvStandaloneMedicineChips)?.let {
            it.attachHorizontalScrollNestedHandoff(immediateDisallowOnDown = true)
        }
        view.findViewById<HorizontalScrollView>(R.id.hsv_health_hub_insights)?.let {
            it.attachHorizontalScrollNestedHandoff(immediateDisallowOnDown = true)
        }

        view.findViewById<HorizontalScrollView>(R.id.hsvStandaloneInventory)?.let { it.attachHorizontalScrollNestedHandoff() }
        view.findViewById<HorizontalScrollView>(R.id.hsvAdminInventoryTable)?.let { it.attachHorizontalScrollNestedHandoff() }

        view.findViewById<MaterialButton>(R.id.btnRemoveMedicine)?.setOnClickListener {
            val index = allItems.indexOfFirst { it.id == selectedItemId }
            if (index < 0) {
                CuraxFeedback.warn(this, "Select a medicine first")
            } else {
                allItems.removeAt(index)
                selectedItemId = null
                adapter.setSelectedId(null)
                syncIntoSharedDemoData()
                saveMedicinesToApi()
                maybeStandaloneUserPushMedicine(getString(R.string.pending_sync_detail_medicine_removed))
                refreshDashboard(view)
                refreshInventoryList(view)
                if (!CareUi.effectiveStandaloneShell(requireContext())) {
                    CuraxFeedback.success(this, "Box cleared")
                }
            }
        }

        if (isUserApp()) {
            view.findViewById<MaterialButton>(R.id.btnAddMedicine)?.visibility = View.GONE
            view.findViewById<MaterialButton>(R.id.btnEditMedicine)?.visibility = View.GONE
            view.findViewById<MaterialButton>(R.id.btnRemoveMedicine)?.visibility = View.GONE
        }
    }

    /** Green boxes in Default mode; gold when the visible shell is standalone (user app or admin care). */
    private fun applyStandaloneMedicineBoxGoldTheme(view: View) {
        val ctx = requireContext()
        if (!isUserApp() && !CareUi.useStandaloneLayoutsInCare(ctx)) return
        if (view.findViewById<MaterialCardView>(R.id.cardB1) == null) return
        val goldShell = if (isUserApp()) {
            AppModeManager.isStandaloneMode(ctx)
        } else {
            CareUi.useStandaloneLayoutsInCare(ctx)
        }
        if (!goldShell) {
            restoreUserMedBoxColors(view)
            return
        }
        val bg = ContextCompat.getColor(ctx, R.color.med_box_standalone_bg)
        val stroke = ContextCompat.getColor(ctx, R.color.med_box_standalone_stroke)
        val strokePx = (2f * resources.displayMetrics.density).toInt().coerceAtLeast(2)
        val textColor = ContextCompat.getColor(ctx, R.color.med_box_standalone_text)
        val cardIds = intArrayOf(
            R.id.cardB1, R.id.cardB2, R.id.cardB3, R.id.cardB4, R.id.cardB5, R.id.cardB6,
        )
        for (id in cardIds) {
            val card = view.findViewById<MaterialCardView>(id) ?: continue
            card.setCardBackgroundColor(bg)
            card.strokeColor = stroke
            card.strokeWidth = strokePx
            val inner = card.getChildAt(0) as? ViewGroup ?: continue
            for (i in 0 until inner.childCount) {
                val ch = inner.getChildAt(i)
                if (ch is TextView) ch.setTextColor(textColor)
            }
        }
    }

    private fun restoreUserMedBoxColors(view: View) {
        val ctx = requireContext()
        if (!isUserApp() && !CareUi.useStandaloneLayoutsInCare(ctx)) return
        if (view.findViewById<MaterialCardView>(R.id.cardB1) == null) return
        val bg = ContextCompat.getColor(ctx, R.color.med_box_bg)
        val stroke = ContextCompat.getColor(ctx, R.color.med_box_stroke)
        val label = ContextCompat.getColor(ctx, R.color.med_box_label)
        val nameCol = ContextCompat.getColor(ctx, R.color.med_box_text)
        val strokePx = (2f * resources.displayMetrics.density).toInt().coerceAtLeast(2)
        val rows = listOf(
            Triple(R.id.cardB1, R.id.tvBoxB1Name, R.id.tvBoxB1Qty),
            Triple(R.id.cardB2, R.id.tvBoxB2Name, R.id.tvBoxB2Qty),
            Triple(R.id.cardB3, R.id.tvBoxB3Name, R.id.tvBoxB3Qty),
            Triple(R.id.cardB4, R.id.tvBoxB4Name, R.id.tvBoxB4Qty),
            Triple(R.id.cardB5, R.id.tvBoxB5Name, R.id.tvBoxB5Qty),
            Triple(R.id.cardB6, R.id.tvBoxB6Name, R.id.tvBoxB6Qty),
        )
        for ((cardId, nameId, qtyId) in rows) {
            val card = view.findViewById<MaterialCardView>(cardId) ?: continue
            card.setCardBackgroundColor(bg)
            card.strokeColor = stroke
            card.strokeWidth = strokePx
            val inner = card.getChildAt(0) as? ViewGroup ?: continue
            val header = inner.getChildAt(0)
            if (header is TextView) header.setTextColor(label)
            view.findViewById<TextView>(nameId)?.setTextColor(nameCol)
            view.findViewById<TextView>(qtyId)?.setTextColor(label)
        }
    }

    /** After [AppModeManager] mode changes while the overview is visible. */
    fun refreshMedBoxThemeForUserMode() {
        val v = view ?: return
        if (!isUserApp()) return
        applyStandaloneMedicineBoxGoldTheme(v)
    }

    private fun setupBoxClicks(view: View) {
        bindBoxClick(view, R.id.cardB1, "B1")
        bindBoxClick(view, R.id.cardB2, "B2")
        bindBoxClick(view, R.id.cardB3, "B3")
        bindBoxClick(view, R.id.cardB4, "B4")
        bindBoxClick(view, R.id.cardB5, "B5")
        bindBoxClick(view, R.id.cardB6, "B6")
    }

    private fun bindBoxClick(view: View, cardId: Int, box: String) {
        view.findViewById<View>(cardId).setOnClickListener {
            val item = allItems.find { it.box.equals(box, true) }
            if (item == null) {
                val msg = if (isUserApp()) {
                    getString(R.string.standalone_medicines_empty_slot_hint)
                } else {
                    "$box is empty"
                }
                CuraxFeedback.warn(this, msg)
            } else {
                showBoxDetailsDialog(item)
            }
        }
    }

    private fun showBoxDetailsDialog(item: InventoryItem) {
        val ctx = requireContext()
        val status = computedStatus(item)
        val boxLabel = item.box.ifBlank { "—" }
        val timesLabel = item.displayTimesLabel().ifBlank { "—" }
        val detail = buildString {
            appendLine(ctx.getString(R.string.medicine_detail_name, item.name))
            appendLine(ctx.getString(R.string.medicine_detail_box, boxLabel))
            appendLine(ctx.getString(R.string.medicine_detail_stock, item.stock))
            val slots = item.effectiveTimesForApi().size
            val doseLine = if (item.useMultipleTimesPerDay && slots > 1) {
                ctx.getString(
                    R.string.medicine_detail_dose_per_time_multi,
                    item.dosePerAdministrationAmount(),
                    item.totalDoseUnitsPerDay(),
                    slots,
                )
            } else {
                ctx.getString(R.string.medicine_detail_dose_per_day_single, item.dosePerDay)
            }
            appendLine(doseLine)
            appendLine(ctx.getString(R.string.medicine_detail_times, timesLabel))
            appendLine(ctx.getString(R.string.medicine_detail_status, status))
            append(ctx.getString(R.string.medicine_detail_expiry, item.expiry.ifBlank { "—" }))
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle(ctx.getString(R.string.medicine_detail_dialog_title, boxLabel))
            .setMessage(detail)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showAddDialog(view: View) {
        if (!isAdded) return
        val available = availableBoxes()
        if (available.isEmpty()) {
            CuraxFeedback.warn(this, getString(R.string.medicine_form_all_boxes_full))
            return
        }
        openMedicineEditor(view, null, available)
    }

    private fun showEditDialog(view: View, existing: InventoryItem) {
        openMedicineEditor(view, existing, null)
    }

    private fun openMedicineEditor(rootView: View, existing: InventoryItem?, addBoxes: List<String>?) {
        if (!isAdded) return
        val ctx = requireContext()
        val form = layoutInflater.inflate(R.layout.dialog_medicine_form, null, false)
        val etName = form.findViewById<TextInputEditText>(R.id.etMedName)
        val etStock = form.findViewById<TextInputEditText>(R.id.etMedStock)
        val tilDose = form.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.tilMedDose)
        val etDose = form.findViewById<TextInputEditText>(R.id.etMedDose)
        val tilBox = form.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.tilMedBox)
        val etBox = form.findViewById<TextInputEditText>(R.id.etMedBox)
        val actvBox = form.findViewById<android.widget.AutoCompleteTextView>(R.id.actvMedBox)
        val swMulti = form.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.swMultiTime)
        val rowSingle = form.findViewById<LinearLayout>(R.id.rowSingleTime)
        val tvSingle = form.findViewById<TextView>(R.id.tvSingleTime)
        val btnPickSingle = form.findViewById<MaterialButton>(R.id.btnPickSingleTime)
        val llMulti = form.findViewById<LinearLayout>(R.id.llMultiTimes)
        val btnAddRow = form.findViewById<MaterialButton>(R.id.btnAddTimeRow)
        val tvExpiry = form.findViewById<TextView>(R.id.tvExpiry)
        val btnExpiry = form.findViewById<MaterialButton>(R.id.btnPickExpiry)

        val expiryFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        var selectedExpiry = existing?.expiry?.takeIf { it.isNotBlank() }
            ?: expiryFormat.format(Calendar.getInstance(Locale.US).time)
        fun refreshExpiryLabel() {
            tvExpiry.text = getString(R.string.medicine_form_expiry_value, selectedExpiry)
        }
        refreshExpiryLabel()

        var singleTime = MedicineSchedule.normalizeToHhMm(existing?.exactTime ?: "08:00").ifEmpty { "08:00" }
        fun refreshSingleTimeLabel() {
            tvSingle.text = getString(
                R.string.medicine_form_time_value,
                MedicineSchedule.formatDisplay12h(singleTime),
            )
        }
        refreshSingleTimeLabel()

        fun readTimesFromRows(): List<String> {
            val out = mutableListOf<String>()
            for (i in 0 until llMulti.childCount) {
                val t = (llMulti.getChildAt(i).tag as? String).orEmpty()
                if (t.isNotEmpty()) out.add(MedicineSchedule.normalizeToHhMm(t))
            }
            return MedicineSchedule.dedupeSorted(out)
        }

        fun addTimeRow(initial: String, pickerTag: String) {
            val row = layoutInflater.inflate(R.layout.item_medicine_time_row, llMulti, false)
            val t0 = MedicineSchedule.normalizeToHhMm(initial).ifEmpty { "08:00" }
            row.tag = t0
            row.findViewById<TextView>(R.id.tvTimeLabel).text =
                getString(R.string.medicine_form_time_row_label, MedicineSchedule.formatDisplay12h(t0))
            row.findViewById<MaterialButton>(R.id.btnEditTime).setOnClickListener {
                val cur = (row.tag as? String) ?: t0
                val parts = cur.split(":")
                val picker = MaterialTimePicker.Builder()
                    .setTimeFormat(TimeFormat.CLOCK_24H)
                    .setHour(parts.getOrNull(0)?.toIntOrNull() ?: 8)
                    .setMinute(parts.getOrNull(1)?.toIntOrNull() ?: 0)
                    .build()
                picker.addOnPositiveButtonClickListener {
                    val nt = "${picker.hour.toString().padStart(2, '0')}:${picker.minute.toString().padStart(2, '0')}"
                    row.tag = nt
                    row.findViewById<TextView>(R.id.tvTimeLabel).text =
                        getString(R.string.medicine_form_time_row_label, MedicineSchedule.formatDisplay12h(nt))
                }
                picker.show(parentFragmentManager, "med_editor_row_$pickerTag")
            }
            row.findViewById<MaterialButton>(R.id.btnRemoveTime).setOnClickListener {
                if (llMulti.childCount <= 2) {
                    CuraxFeedback.warn(this@AdminOverviewFragment, getString(R.string.medicine_form_need_two_times))
                    return@setOnClickListener
                }
                llMulti.removeView(row)
            }
            llMulti.addView(row)
        }

        fun seedMultiRowsFromList(seed: List<String>) {
            llMulti.removeAllViews()
            val list = MedicineSchedule.dedupeSorted(seed).toMutableList()
            if (list.size < 2) {
                list.add("12:00")
                if (MedicineSchedule.dedupeSorted(list).size < 2) list.add("18:00")
            }
            val dedup = MedicineSchedule.dedupeSorted(list)
            dedup.forEachIndexed { idx, t -> addTimeRow(t, "${idx}_${System.nanoTime()}") }
        }

        fun applyMultiUi() {
            val on = swMulti.isChecked
            rowSingle.visibility = if (on) View.GONE else View.VISIBLE
            llMulti.visibility = if (on) View.VISIBLE else View.GONE
            btnAddRow.visibility = if (on) View.VISIBLE else View.GONE
            if (on && llMulti.childCount == 0) {
                seedMultiRowsFromList(listOf(singleTime, "12:00"))
            }
            tilDose.hint = getString(
                if (on) R.string.medicine_form_dose_per_time else R.string.medicine_form_dose_per_day,
            )
        }

        etName.setText(existing?.name.orEmpty())
        etStock.setText(existing?.stock?.toString().orEmpty())
        etDose.setText((existing?.dosePerDay ?: 1).toString())
        swMulti.isChecked = existing?.useMultipleTimesPerDay == true
        if (swMulti.isChecked) {
            val seed = MedicineSchedule.dedupeSorted(existing?.scheduleTimesList ?: emptyList())
                .ifEmpty { listOf(singleTime, "12:00") }
            seedMultiRowsFromList(seed)
        }
        applyMultiUi()

        swMulti.setOnCheckedChangeListener { _, _ ->
            if (swMulti.isChecked && llMulti.childCount < 2) {
                seedMultiRowsFromList(listOf(singleTime, "12:00"))
            }
            applyMultiUi()
        }
        btnAddRow.setOnClickListener {
            if (llMulti.childCount >= MedicineSchedule.MAX_SCHEDULE_SLOTS) {
                CuraxFeedback.warn(this, getString(R.string.medicine_form_max_times))
                return@setOnClickListener
            }
            addTimeRow("12:00", "add_${System.nanoTime()}")
        }

        btnPickSingle.setOnClickListener {
            val parts = singleTime.split(":")
            val picker = MaterialTimePicker.Builder()
                .setTimeFormat(TimeFormat.CLOCK_24H)
                .setHour(parts.getOrNull(0)?.toIntOrNull() ?: 8)
                .setMinute(parts.getOrNull(1)?.toIntOrNull() ?: 0)
                .build()
            picker.addOnPositiveButtonClickListener {
                singleTime = "${picker.hour.toString().padStart(2, '0')}:${picker.minute.toString().padStart(2, '0')}"
                refreshSingleTimeLabel()
            }
            picker.show(parentFragmentManager, "med_editor_single")
        }

        btnExpiry.setOnClickListener {
            val cal = try {
                Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                    time = expiryFormat.parse(selectedExpiry) ?: Date()
                }
            } catch (_: Exception) {
                Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            }
            val picker = MaterialDatePicker.Builder.datePicker().setSelection(cal.timeInMillis).build()
            picker.addOnPositiveButtonClickListener { millis ->
                val c = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = millis }
                selectedExpiry = "${c.get(Calendar.YEAR)}-${(c.get(Calendar.MONTH) + 1).toString().padStart(2, '0')}-${c.get(Calendar.DAY_OF_MONTH).toString().padStart(2, '0')}"
                refreshExpiryLabel()
            }
            picker.show(parentFragmentManager, "med_editor_expiry")
        }

        if (existing == null && addBoxes != null) {
            tilBox.visibility = View.GONE
            etBox.visibility = View.GONE
            actvBox.visibility = View.VISIBLE
            actvBox.hint = getString(R.string.medicine_form_select_box)
            actvBox.setAdapter(ArrayAdapter(ctx, android.R.layout.simple_list_item_1, addBoxes))
            actvBox.setText(addBoxes.first(), false)
        } else {
            tilBox.visibility = View.VISIBLE
            etBox.visibility = View.VISIBLE
            actvBox.visibility = View.GONE
            etBox.setText(existing?.box.orEmpty())
        }

        val title = if (existing == null) getString(R.string.medicine_form_add_title) else getString(R.string.medicine_form_edit_title)
        val posLabel = if (existing == null) getString(R.string.medicine_form_save_add) else getString(R.string.medicine_form_save_edit)
        val dlg = MaterialAlertDialogBuilder(ctx)
            .setTitle(title)
            .setView(form)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(posLabel) { _, _ ->
                val name = etName.text?.toString()?.trim().orEmpty()
                val stock = etStock.text?.toString()?.trim()?.toIntOrNull() ?: -1
                val dosePerDay = etDose.text?.toString()?.trim()?.toIntOrNull() ?: -1
                val expiry = selectedExpiry
                val multi = swMulti.isChecked
                val effTimes = if (multi) readTimesFromRows() else listOf(MedicineSchedule.normalizeToHhMm(singleTime))
                val normTimes = MedicineSchedule.dedupeSorted(effTimes)
                val box = if (existing == null) {
                    actvBox.text.toString().trim().uppercase(Locale.US)
                } else {
                    etBox.text.toString().trim().uppercase(Locale.US)
                }

                if (name.isBlank() || stock < 0 || dosePerDay !in 1..12 || expiry.isBlank()) {
                    CuraxFeedback.warn(this, getString(R.string.medicine_form_invalid_basic))
                    return@setPositiveButton
                }
                if (multi && normTimes.size < 2) {
                    CuraxFeedback.warn(this, getString(R.string.medicine_form_need_two_times))
                    return@setPositiveButton
                }
                if (multi && normTimes.size > MedicineSchedule.MAX_SCHEDULE_SLOTS) {
                    CuraxFeedback.warn(this, getString(R.string.medicine_form_max_times))
                    return@setPositiveButton
                }

                if (existing == null) {
                    val avail = addBoxes ?: emptyList()
                    if (box !in avail.map { it.uppercase(Locale.US) }) {
                        CuraxFeedback.warn(this, getString(R.string.medicine_form_invalid_box))
                        return@setPositiveButton
                    }
                    val newItem = InventoryItem(
                        id = System.nanoTime(),
                        name = name,
                        stock = stock,
                        dosePerDay = dosePerDay,
                        exactTime = normTimes.first(),
                        expiry = expiry,
                        status = "Normal",
                        box = box,
                        addedAt = System.currentTimeMillis(),
                        useMultipleTimesPerDay = multi,
                        scheduleTimesList = normTimes.toMutableList(),
                    )
                    newItem.status = computedStatus(newItem)
                    allItems.add(newItem)
                    selectedItemId = newItem.id
                    adapter.setSelectedId(selectedItemId)
                    syncIntoSharedDemoData()
                    saveMedicinesToApi()
                    maybeStandaloneUserPushMedicine(getString(R.string.pending_sync_detail_medicine_added, name))
                    refreshDashboard(rootView)
                    refreshInventoryList(rootView)
                    if (!CareUi.effectiveStandaloneShell(requireContext())) {
                        CuraxFeedback.success(this, getString(R.string.medicine_form_added_ok, box))
                    }
                } else {
                    val validBox = isValidMedicineBoxInput(box)
                    val occupiedByOther = allItems.any { it.id != existing.id && it.box.equals(box, true) }
                    if (!validBox || occupiedByOther) {
                        CuraxFeedback.warn(this, getString(R.string.medicine_form_invalid_box_edit))
                        return@setPositiveButton
                    }
                    existing.name = name
                    existing.stock = stock
                    existing.dosePerDay = dosePerDay
                    existing.exactTime = normTimes.first()
                    existing.expiry = expiry
                    existing.box = box
                    existing.useMultipleTimesPerDay = multi
                    existing.scheduleTimesList.clear()
                    existing.scheduleTimesList.addAll(normTimes)
                    existing.status = computedStatus(existing)
                    selectedItemId = existing.id
                    adapter.setSelectedId(selectedItemId)
                    syncIntoSharedDemoData()
                    saveMedicinesToApi()
                    maybeStandaloneUserPushMedicine(getString(R.string.pending_sync_detail_medicine_updated, name))
                    refreshDashboard(rootView)
                    refreshInventoryList(rootView)
                    if (!CareUi.effectiveStandaloneShell(requireContext())) {
                        CuraxFeedback.success(this, getString(R.string.medicine_form_updated_ok))
                    }
                }
                UserAlarmScheduler.rescheduleAll(ctx.applicationContext)
            }
            .create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(ContextCompat.getColor(ctx, R.color.connection_panel_title))
            dlg.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
        }
        dlg.show()
    }

    private fun refreshInventoryList(view: View) {
        ensureInventorySeeded()
        val working = allItems.sortedBy { it.box }
        adapter.submitList(working)

        if (working.none { it.id == selectedItemId }) {
            selectedItemId = null
            adapter.setSelectedId(null)
        }

        val avail = availableBoxes()
        view.findViewById<TextView>(R.id.tvInventoryHint)?.apply {
            if (CareUi.effectiveStandaloneShell(requireContext())) {
                visibility = View.GONE
                text = ""
            } else {
                visibility = View.VISIBLE
                text = if (avail.isEmpty()) {
                    "6/6 boxes filled. Tap row to Edit or Clear Box"
                } else {
                    "${working.size}/6 filled. Empty: ${avail.joinToString(", ")}"
                }
            }
        }

        if (isUserApp()) {
            view.findViewById<MaterialButton>(R.id.btnAddMedicine)?.visibility = View.GONE
        } else {
            view.findViewById<MaterialButton>(R.id.btnAddMedicine)?.visibility = if (avail.isEmpty()) View.GONE else View.VISIBLE
        }

        val chipsSection = view.findViewById<View>(R.id.standaloneMedicineChipsSection)
        if (chipsSection?.visibility == View.VISIBLE &&
            view.findViewById<LinearLayout>(R.id.llStandaloneMedicineChips) != null
        ) {
            val chipList = if (CareUi.effectiveStandaloneShell(requireContext())) {
                val list = working.toMutableList()
                var pad = 0
                while (list.size < 3) {
                    list.add(
                        InventoryItem(
                            id = StandaloneMedicineChipAdapter.PLACEHOLDER_CHIP_MAX_ID - 1 - pad,
                            name = "",
                            stock = 0,
                            dosePerDay = 0,
                            exactTime = "",
                            expiry = "",
                            status = "Normal",
                            box = "",
                            addedAt = 0L,
                        ),
                    )
                    pad++
                }
                list
            } else {
                working
            }
            rebuildStandaloneMedicineChipRow(view, chipList)
        }
    }

    private fun rebuildStandaloneMedicineChipRow(view: View, items: List<InventoryItem>) {
        val ll = view.findViewById<LinearLayout>(R.id.llStandaloneMedicineChips) ?: return
        val inflater = LayoutInflater.from(requireContext())
        val onPlaceholder: () -> Unit = {
            CuraxFeedback.warn(
                this,
                getString(R.string.standalone_medicines_empty_slot_hint),
                long = false,
            )
        }
        val computed: (InventoryItem) -> String = { computedStatus(it) }
        populateStandaloneMedicineChipRow(
            ll,
            inflater,
            items,
            computed,
            selectedBoxUpper = null,
            onItemClick = { sel ->
                selectedItemId = sel.id
                adapter.setSelectedId(selectedItemId)
                if (isUserApp()) showBoxDetailsDialog(sel)
            },
            onPlaceholderClick = onPlaceholder,
        )
    }

    private fun parseMedicineBoxIndex(box: String): Int? {
        val s = box.trim().uppercase(Locale.US)
        if (!s.startsWith("B") || s.length < 2) return null
        val n = s.substring(1).toIntOrNull() ?: return null
        return if (n > 0) n else null
    }

    /** B1–B6 only in default mode; B1, B2, … in standalone / care-standalone shell. */
    private fun isValidMedicineBoxInput(box: String): Boolean {
        val s = box.trim().uppercase(Locale.US)
        return if (CareUi.effectiveStandaloneShell(requireContext())) {
            s.matches(Regex("^B([1-9][0-9]*)$"))
        } else {
            s in setOf("B1", "B2", "B3", "B4", "B5", "B6")
        }
    }

    private fun availableBoxes(): List<String> {
        val used = allItems.map { it.box.uppercase(Locale.US) }.toSet()
        if (!CareUi.effectiveStandaloneShell(requireContext())) {
            return listOf("B1", "B2", "B3", "B4", "B5", "B6").filter { it !in used }
        }
        val maxUsed = allItems.mapNotNull { parseMedicineBoxIndex(it.box) }.maxOrNull() ?: 0
        val hi = maxOf(maxUsed + 24, 6)
        return (1..hi).map { "B$it" }.filter { it !in used }.sortedBy { parseMedicineBoxIndex(it) ?: 0 }
    }

    private fun applyStandaloneHealthHubTitle(view: View) {
        val tv = view.findViewById<TextView>(R.id.tv_standalone_health_hub_title) ?: return
        val first = Prefs(requireContext()).userHubFirstName.trim()
        tv.text = if (first.isNotEmpty()) {
            getString(R.string.standalone_health_hub_title_format, first)
        } else {
            getString(R.string.standalone_health_hub_fallback_title)
        }
    }

    private fun cancelHealthHubStatusAnimations() {
        view?.findViewById<HealthHubEcgWaveView>(R.id.health_hub_ecg_wave)?.stopAnimation()
    }

    private fun setupStandaloneHealthHubStatus(view: View) {
        if (!isUserApp()) return
        val ecg = view.findViewById<HealthHubEcgWaveView>(R.id.health_hub_ecg_wave) ?: return
        applyStandaloneHealthHubTitle(view)
        ecg.setCycleDurationMs(if (healthHubIndicatorSyncing) 1300L else 2000L)
        ecg.startAnimation()
    }

    private fun setHealthHubSyncing(view: View, syncing: Boolean) {
        healthHubIndicatorSyncing = syncing
        val ecg = view.findViewById<HealthHubEcgWaveView>(R.id.health_hub_ecg_wave) ?: return
        ecg.stopAnimation()
        ecg.setCycleDurationMs(if (syncing) 1300L else 2000L)
        ecg.startAnimation()
    }

    private fun refreshDashboard(view: View) {
        applyStandaloneHealthHubTitle(view)
        ensureInventorySeeded()
        val total = allItems.size
        val boxesWithMedicine = allItems.count { it.stock > 0 }
        val threshold = AdminDemoData.getLowStockThreshold()
        // Mutually exclusive buckets so counts match the bar + total (expiring wins over low stock).
        val exp = allItems.count { isExpiringSoon(it.expiry) }
        val low = allItems.count { !isExpiringSoon(it.expiry) && it.stock <= threshold }
        val normal = allItems.count { !isExpiringSoon(it.expiry) && it.stock > threshold }
        val emptyBoxes = if (CareUi.effectiveStandaloneShell(requireContext())) {
            0
        } else {
            (6 - allItems.size).coerceAtLeast(0)
        }
        val zeroStockItems = allItems.count { it.stock == 0 }
        val refill = if (CareUi.effectiveStandaloneShell(requireContext())) {
            zeroStockItems
        } else {
            emptyBoxes + zeroStockItems
        }

        if (view.findViewById<TextView>(R.id.tvBoxB1Name) != null) {
            bindMedicineBoxes(view)
        }

        val legacyDonut = view.findViewById<View>(R.id.frameDonut)
        if (legacyDonut != null) {
            animateDonutSection(view, boxesWithMedicine)
        } else {
            view.findViewById<TextView>(R.id.tvDonutTotal)?.text = total.toString()
            lastDonutTotal = total
        }

        val legacyStockLabels = legacyDonut != null
        if (legacyStockLabels) {
            view.findViewById<TextView>(R.id.tvDistNormal).text = "Normal: $normal"
            view.findViewById<TextView>(R.id.tvDistLow).text = "Low: $low"
            view.findViewById<TextView>(R.id.tvDistExpiring).text = "Expiring: $exp"
        } else {
            view.findViewById<TextView>(R.id.tvDistNormal)?.text = normal.toString()
            view.findViewById<TextView>(R.id.tvDistLow)?.text = low.toString()
            view.findViewById<TextView>(R.id.tvDistExpiring)?.text = exp.toString()
        }

        view.findViewById<View>(R.id.ll_stock_bar_segments)?.let {
            layoutStandaloneStockBar(view, normal, low, exp)
            view.findViewById<TextView>(R.id.tv_stock_snapshot_caption)?.text = when {
                total == 0 -> getString(R.string.stock_snapshot_caption_empty)
                else -> getString(R.string.stock_snapshot_caption_split, total)
            }
        }

        view.findViewById<TextView>(R.id.tvKpiTotal)?.text = total.toString()
        view.findViewById<TextView>(R.id.tvKpiLow)?.text = low.toString()
        view.findViewById<TextView>(R.id.tvKpiExpiring)?.text = exp.toString()
        view.findViewById<TextView>(R.id.tvKpiRefill)?.text = refill.toString()

        if ((isUserApp() && StandaloneUi.isUserStandalone(requireContext())) ||
            CareUi.useStandaloneLayoutsInCare(requireContext())) {
            refreshStandaloneHealthHubPillCounts(view)
            HealthHubPlanInsights.bind(view, requireContext())
        }
        view.findViewById<TextView>(R.id.tv_standalone_summary_sync)?.text =
            formatStandaloneSyncLabel(Prefs(requireContext()).lastSyncTime)

        updateTrendFromInventory(view)
        if (isUserApp() || CareUi.useStandaloneLayoutsInCare(requireContext())) {
            applyStandaloneMedicineBoxGoldTheme(view)
        }
        maybeShowStandaloneDoseNudges()
    }

    private fun maybeStandaloneUserPushMedicine(detail: String) {
        if (!isUserApp() || !StandaloneUi.isUserStandalone(requireContext())) return
        StandaloneOfflineMirror.persistMergedSnapshot(requireContext())
        StandaloneUserMutationSink.notifyLocalChange(
            activity,
            requireContext(),
            PendingSyncQueueStore.TYPE_MEDICINE,
            getString(R.string.pending_sync_title_medicine),
            detail,
        )
    }

    private fun maybeShowStandaloneDoseNudges() {
        // Standalone / care standalone: no "missed multiple doses" or adherence pop-ups on open.
        if (CareUi.effectiveStandaloneShell(requireContext())) return
        if (!isUserApp() || !StandaloneUi.isUserStandalone(requireContext())) return
        val act = activity ?: return
        if (DoseNudgeController.shouldShowMissedDosesNudge(requireContext())) {
            DoseNudgeController.markMissedNudgeShown(requireContext())
            MaterialAlertDialogBuilder(act)
                .setTitle(R.string.nudge_multiple_missed_title)
                .setMessage(R.string.nudge_multiple_missed_body)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        if (DoseNudgeController.shouldShowLowAdherenceDashboardWarning(requireContext())) {
            DoseNudgeController.markAdherenceNudgeShown(requireContext())
            MaterialAlertDialogBuilder(act)
                .setTitle(R.string.nudge_low_adherence_title)
                .setMessage(R.string.nudge_low_adherence_body)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    /** Care-team alerts in memory + local inbox (same basis as Health Hub alerts sheet). */
    private fun standaloneHealthHubAlertCount(): Int = runCatching {
        AdminDemoData.getApiAlerts().size + AlertDb(requireContext()).getAllAlerts().size
    }.getOrDefault(0)

    /**
     * Health Hub pills: planned = user plan count; alerts = synced API + local inbox.
     * Reads cached plan count immediately, then reconciles from network (stale responses dropped via [healthHubPlanFetchSeq]).
     */
    private fun refreshStandaloneHealthHubPillCounts(host: View) {
        if (!CareUi.effectiveStandaloneShell(requireContext())) return
        val tvPlanned = host.findViewById<TextView>(R.id.tv_standalone_summary_total) ?: return
        val tvAlerts = host.findViewById<TextView>(R.id.tv_standalone_summary_alerts) ?: return
        tvAlerts.text = standaloneHealthHubAlertCount().toString()
        tvPlanned.text = UserPlansLocalStore.readCache(requireContext()).size.toString()
        val seq = ++healthHubPlanFetchSeq
        val appCtx = requireContext().applicationContext
        Thread {
            val (list, _) = UserPlansApi.fetchPlans(appCtx)
            activity?.runOnUiThread {
                if (!isAdded || seq != healthHubPlanFetchSeq) return@runOnUiThread
                host.findViewById<TextView>(R.id.tv_standalone_summary_total)?.text = list.size.toString()
                HealthHubPlanInsights.bind(host, appCtx)
            }
        }.start()
    }

    /** Short label for Health hub "Last sync" so it fits the pill without harsh clipping. */
    private fun formatStandaloneSyncLabel(raw: String): String {
        val t = raw.trim()
        if (t.isEmpty()) return "—"
        return try {
            val normalized = t.replace(' ', 'T').substringBefore('Z').substringBefore('+')
            val dot = normalized.indexOf('.')
            val base = if (dot >= 0) normalized.substring(0, dot) else normalized.take(19)
            val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
                timeZone = TimeZone.getDefault()
            }
            val d = parser.parse(base) ?: return if (t.length > 24) t.take(23) + "…" else t
            val out = SimpleDateFormat("MMM d · HH:mm", Locale.getDefault()).apply {
                timeZone = TimeZone.getDefault()
            }
            out.format(d)
        } catch (_: Exception) {
            if (t.length > 24) t.take(23) + "…" else t
        }
    }

    /** Standalone stock snapshot: proportional bar (Normal / Low / Expiring). */
    private fun layoutStandaloneStockBar(view: View, normal: Int, low: Int, expiring: Int) {
        val container = view.findViewById<LinearLayout>(R.id.ll_stock_bar_segments) ?: return
        val vN = view.findViewById<View>(R.id.view_stock_bar_normal) ?: return
        val vL = view.findViewById<View>(R.id.view_stock_bar_low) ?: return
        val vE = view.findViewById<View>(R.id.view_stock_bar_exp) ?: return
        val ctx = requireContext()
        val neutral = ContextCompat.getDrawable(ctx, R.drawable.bg_stock_seg_neutral)
        val dn = ContextCompat.getDrawable(ctx, R.drawable.bg_stock_seg_normal)
        val dl = ContextCompat.getDrawable(ctx, R.drawable.bg_stock_seg_low)
        val de = ContextCompat.getDrawable(ctx, R.drawable.bg_stock_seg_exp)
        val gapPx = (4 * ctx.resources.displayMetrics.density).toInt()

        fun lpSeg(weight: Float, marginEndPx: Int) = LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.MATCH_PARENT,
            weight,
        ).apply {
            marginEnd = marginEndPx
        }

        val sum = normal + low + expiring
        if (sum == 0) {
            listOf(vN, vL, vE).forEach {
                it.visibility = View.VISIBLE
                it.background = neutral
                it.alpha = 0.35f
            }
            vN.layoutParams = lpSeg(1f, gapPx)
            vL.layoutParams = lpSeg(1f, gapPx)
            vE.layoutParams = lpSeg(1f, 0)
            container.weightSum = 3f
        } else {
            listOf(vN, vL, vE).forEach { it.alpha = 1f }
            vN.background = dn
            vL.background = dl
            vE.background = de
            container.weightSum = sum.toFloat()
            if (normal > 0) {
                vN.visibility = View.VISIBLE
                val endAfterN = low > 0 || expiring > 0
                vN.layoutParams = lpSeg(normal.toFloat(), if (endAfterN) gapPx else 0)
            } else {
                vN.visibility = View.GONE
            }
            if (low > 0) {
                vL.visibility = View.VISIBLE
                val endAfterL = expiring > 0
                vL.layoutParams = lpSeg(low.toFloat(), if (endAfterL) gapPx else 0)
            } else {
                vL.visibility = View.GONE
            }
            if (expiring > 0) {
                vE.visibility = View.VISIBLE
                vE.layoutParams = lpSeg(expiring.toFloat(), 0)
            } else {
                vE.visibility = View.GONE
            }
        }
        container.requestLayout()
    }

    private fun animateDonutSection(view: View, total: Int) {
        val donutCircle = view.findViewById<View>(R.id.viewDonutCircle)
        val tvTotal = view.findViewById<TextView>(R.id.tvDonutTotal)

        val isFirstLoad = lastDonutTotal < 0

        if (isFirstLoad) {
            donutCircle.scaleX = 0.82f
            donutCircle.scaleY = 0.82f
            donutCircle.alpha = 0.4f
            tvTotal.alpha = 0f
            tvTotal.text = "0"
        }

        val circleEntrance = if (isFirstLoad) {
            AnimatorSet().apply {
                playTogether(
                    ObjectAnimator.ofFloat(donutCircle, "scaleX", 0.82f, 1.06f, 1f).apply {
                        duration = 280
                        interpolator = OvershootInterpolator(1.0f)
                    },
                    ObjectAnimator.ofFloat(donutCircle, "scaleY", 0.82f, 1.06f, 1f).apply {
                        duration = 280
                        interpolator = OvershootInterpolator(1.0f)
                    },
                    ObjectAnimator.ofFloat(donutCircle, "alpha", 0.4f, 1f).apply { duration = 200 },
                    ObjectAnimator.ofFloat(tvTotal, "alpha", 0f, 1f).apply { duration = 180 }
                )
            }
        } else null

        val startCount = if (isFirstLoad) 0 else lastDonutTotal.coerceAtLeast(0)
        val countAnim = ValueAnimator.ofInt(startCount, total).apply {
            duration = 260
            addUpdateListener { anim ->
                tvTotal.text = (anim.animatedValue as Int).toString()
            }
        }

        val popTotal = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(tvTotal, "scaleX", 1f, 1.14f, 1f).apply {
                    duration = 200
                    interpolator = OvershootInterpolator(0.8f)
                },
                ObjectAnimator.ofFloat(tvTotal, "scaleY", 1f, 1.14f, 1f).apply {
                    duration = 200
                    interpolator = OvershootInterpolator(0.8f)
                }
            )
        }

        if (circleEntrance != null) {
            circleEntrance.start()
            circleEntrance.doOnEnd {
                countAnim.start()
                countAnim.doOnEnd {
                    popTotal.start()
                    startDonutPulse(donutCircle)
                }
            }
        } else {
            countAnim.start()
            countAnim.doOnEnd { popTotal.start() }
        }

        lastDonutTotal = total
    }

    private fun startDonutPulse(donutCircle: View) {
        donutPulseAnim?.cancel()
        donutPulseAnim = ValueAnimator.ofFloat(1f, 1.02f, 1f).apply {
            duration = 1200
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            addUpdateListener { anim ->
                val s = anim.animatedValue as Float
                donutCircle.scaleX = s
                donutCircle.scaleY = s
            }
            start()
        }
    }

    private fun isDarkModeEnabled(): Boolean {
        val mode = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        return mode == android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    override fun onPause() {
        donutPulseAnim?.cancel()
        donutPulseAnim = null
        super.onPause()
    }

    private fun AnimatorSet.doOnEnd(action: () -> Unit) {
        addListener(object : android.animation.Animator.AnimatorListener {
            override fun onAnimationStart(animation: android.animation.Animator) {}
            override fun onAnimationRepeat(animation: android.animation.Animator) {}
            override fun onAnimationCancel(animation: android.animation.Animator) {}
            override fun onAnimationEnd(animation: android.animation.Animator) {
                action()
            }
        })
    }

    private fun ValueAnimator.doOnEnd(action: () -> Unit) {
        addListener(object : android.animation.Animator.AnimatorListener {
            override fun onAnimationStart(animation: android.animation.Animator) {}
            override fun onAnimationRepeat(animation: android.animation.Animator) {}
            override fun onAnimationCancel(animation: android.animation.Animator) {}
            override fun onAnimationEnd(animation: android.animation.Animator) {
                action()
            }
        })
    }

    /** Status from data only: Expiring > Low (stock <= threshold from Settings) > Normal. */
    private fun computedStatus(item: InventoryItem): String {
        val threshold = AdminDemoData.getLowStockThreshold()
        return when {
            isExpiringSoon(item.expiry) -> "Expiring"
            item.stock <= threshold -> "Low"
            else -> "Normal"
        }
    }

    /** True if expiry string (YYYY-MM-DD) is within the next 30 days. */
    private fun isExpiringSoon(expiry: String): Boolean {
        if (expiry.isBlank()) return false
        return try {
            val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            val expiryCal = java.util.Calendar.getInstance().apply { time = fmt.parse(expiry)!! }
            val now = java.util.Calendar.getInstance()
            val limit = java.util.Calendar.getInstance().apply { add(java.util.Calendar.DAY_OF_YEAR, 30) }
            !expiryCal.before(now) && !expiryCal.after(limit)
        } catch (_: Exception) {
            false
        }
    }

    private fun setupAdherenceChart(view: View) {
        adherenceChart = view.findViewById(R.id.adherenceChart) ?: return
        val btnPrev = view.findViewById<MaterialButton>(R.id.btnChartPrev) ?: return
        val btnNext = view.findViewById<MaterialButton>(R.id.btnChartNext) ?: return
        val tvIndicator = view.findViewById<TextView>(R.id.tvChartPageIndicator) ?: return

        fun refreshChartAndIndicator() {
            val points = computeAdherenceData()
            adherenceChart?.data = points
            val (start, end) = getWeekRangeLabels()
            tvIndicator.text = "$start - $end"
            btnNext.isEnabled = weekOffset > 0
        }

        btnPrev.setOnClickListener {
            weekOffset++
            refreshChartAndIndicator()
        }
        btnNext.setOnClickListener {
            if (weekOffset > 0) {
                weekOffset--
                refreshChartAndIndicator()
            }
        }

        refreshChartAndIndicator()
    }

    private fun getWeekRangeLabels(): Pair<String, String> {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val firstDay = cal.clone() as Calendar
        firstDay.add(Calendar.DAY_OF_YEAR, -weekOffset * 7 - 6)
        val lastDay = firstDay.clone() as Calendar
        lastDay.add(Calendar.DAY_OF_YEAR, 6)
        val fmt = SimpleDateFormat("d MMM", Locale.getDefault())
        return fmt.format(firstDay.time) to fmt.format(lastDay.time)
    }

    /** Expected = sum of dosePerDay for all medicines (same each day). Taken = from AlertDb by date.
     * Until first alert is received, show static/dummy line (same as on opening). After first alert, show real adherence. */
    private fun computeAdherenceData(): List<AdherenceLineChartView.DayPoint> {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val firstDay = cal.clone() as Calendar
        firstDay.add(Calendar.DAY_OF_YEAR, -weekOffset * 7 - 6) // first of 7-day window
        val labelFormat = SimpleDateFormat("EEE", Locale.getDefault())

        fun makeDummyPoints(): List<AdherenceLineChartView.DayPoint> {
            val percents = AdminDemoData.adherencePercentByDay()
            val expected = AdminDemoData.expectedDosesPerDayDemo().coerceAtLeast(1)
            return (0 until 7).map { i ->
                val day = firstDay.clone() as Calendar
                day.add(Calendar.DAY_OF_YEAR, i)
                val dayStart = day.timeInMillis
                val pct = percents.getOrElse(i) { 0f }
                val taken = (expected * pct / 100f).toInt().coerceIn(0, expected)
                AdherenceLineChartView.DayPoint(
                    dateMillis = dayStart,
                    label = labelFormat.format(java.util.Date(dayStart)),
                    expectedDoses = expected,
                    takenDoses = taken,
                    adherencePercent = pct
                )
            }
        }

        // Before first connect, or when no alerts received yet: show static line so chart is never empty.
        if (!Prefs(requireContext()).hasEverConnected) {
            return makeDummyPoints()
        }
        val alerts = AlertDb(requireContext()).getAllAlerts()
        if (alerts.isEmpty()) {
            return makeDummyPoints()
        }

        val expectedPerDay = allItems.sumOf { it.totalDoseUnitsPerDay() }.coerceAtLeast(0)
        if (expectedPerDay == 0) {
            return makeDummyPoints()
        }

        return (0 until 7).map { i ->
            val day = firstDay.clone() as Calendar
            day.add(Calendar.DAY_OF_YEAR, i)
            val dayStart = day.timeInMillis
            val dayEndCal = day.clone() as Calendar
            dayEndCal.add(Calendar.DAY_OF_YEAR, 1)
            val dayEnd = dayEndCal.timeInMillis - 1
            val taken = alerts.count { a ->
                a.receivedAt in dayStart..dayEnd && (a.type.contains("taken", true) || a.message.contains("taken", true))
            }
            val adherence = (taken.toFloat() / expectedPerDay * 100f).coerceIn(0f, 100f)
            AdherenceLineChartView.DayPoint(
                dateMillis = dayStart,
                label = labelFormat.format(java.util.Date(dayStart)),
                expectedDoses = expectedPerDay,
                takenDoses = taken,
                adherencePercent = adherence
            )
        }
    }

    private fun updateTrendFromInventory(view: View) {
        val points = computeAdherenceData()
        adherenceChart?.data = points
        val tvIndicator = view.findViewById<TextView>(R.id.tvChartPageIndicator) ?: return
        val btnNext = view.findViewById<MaterialButton>(R.id.btnChartNext) ?: return
        val (start, end) = getWeekRangeLabels()
        tvIndicator.text = "$start - $end"
        btnNext.isEnabled = weekOffset > 0
    }

    private fun dpToPx(dp: Float): Int {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, resources.displayMetrics).toInt()
    }



    private fun setupKpiClicks(view: View) {
        view.findViewById<View>(R.id.cardKpiTotal)?.setOnClickListener {
            val items = allItems.sortedBy { it.box }
            showKpiDetailsDialog(
                title = "Total Medicines",
                subtitle = "All medicines currently present in boxes",
                lines = items.map { formatMedicineLine(it) }
            )
        }

        view.findViewById<View>(R.id.cardKpiLow)?.setOnClickListener {
            val items = allItems.filter { computedStatus(it) == "Low" }.sortedBy { it.box }
            showKpiDetailsDialog(
                title = "Low Stock Medicines",
                subtitle = "Medicines in the low-stock band (not expiring-within-30-days)",
                lines = items.map { formatMedicineLine(it) }
            )
        }

        view.findViewById<View>(R.id.cardKpiExpiring)?.setOnClickListener {
            val items = allItems.filter { isExpiringSoon(it.expiry) }.sortedBy { it.box }
            showKpiDetailsDialog(
                title = "Expiring Medicines",
                subtitle = "Medicines expiring within 30 days",
                lines = items.map { formatMedicineLine(it) }
            )
        }

        view.findViewById<View>(R.id.cardKpiRefill)?.setOnClickListener {
            val zeroStockLines = allItems.filter { it.stock == 0 }.sortedBy { it.box }.map { formatMedicineLine(it) }
            val lines = if (CareUi.effectiveStandaloneShell(requireContext())) {
                zeroStockLines
            } else {
                val used = allItems.map { it.box.uppercase() }.toSet()
                val emptyBoxes = listOf("B1", "B2", "B3", "B4", "B5", "B6").filter { it !in used }
                    .map { "Box: $it | Empty | Refill needed" }
                zeroStockLines + emptyBoxes
            }
            val subtitle = if (CareUi.effectiveStandaloneShell(requireContext())) {
                "Medicines on your list with stock at zero (not tied to six slots)"
            } else {
                "Zero-stock medicines and empty B1–B6 slots"
            }
            showKpiDetailsDialog(
                title = "Refill Required",
                subtitle = subtitle,
                lines = lines,
                emptyMessage = if (CareUi.effectiveStandaloneShell(requireContext())) {
                    "Refill is zero — no medicines on your list have stock at zero."
                } else {
                    "No refills needed: no empty boxes and no zero-stock medicines."
                },
            )
        }
    }

    private fun formatMedicineLine(item: InventoryItem): String {
        return "${item.name} | Box: ${item.box} | Stock: ${item.stock} | Dose: ${item.displayDoseCell()} (daily total ${item.totalDoseUnitsPerDay()}) | Times: ${item.displayTimesLabel()} | Expiry: ${item.expiry}"
    }

    private fun showKpiDetailsDialog(
        title: String,
        subtitle: String,
        lines: List<String>,
        emptyMessage: String = "No matching medicines",
    ) {
        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(12f), dpToPx(8f), dpToPx(12f), dpToPx(4f))
        }

        val subtitleView = TextView(requireContext()).apply {
            text = subtitle
            textSize = 13f
            setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
        }
        root.addView(subtitleView)

        val scroll = ScrollView(requireContext()).apply {
            val content = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dpToPx(8f), 0, 0)
            }

            if (lines.isEmpty()) {
                val empty = TextView(requireContext()).apply {
                    text = emptyMessage
                    textSize = 13f
                    setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
                }
                content.addView(empty)
            } else {
                lines.forEach { line ->
                    val row = TextView(requireContext()).apply {
                        text = line
                        textSize = 12f
                        setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
                        setBackgroundResource(R.drawable.bg_inventory_cell)
                        setPadding(dpToPx(10f), dpToPx(8f), dpToPx(10f), dpToPx(8f))
                    }
                    val lp = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dpToPx(8f) }
                    content.addView(row, lp)
                }
            }

            addView(content)
        }

        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dpToPx(320f)
        ))

        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setView(root)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun bindMedicineBoxes(view: View) {
        if (view.findViewById<TextView>(R.id.tvBoxB1Name) == null) return
        val byBox = allItems.associateBy { it.box.uppercase() }

        fun setBox(nameId: Int, qtyId: Int, box: String) {
            val item = byBox[box]
            view.findViewById<TextView>(nameId).text = item?.name ?: "Empty"
            view.findViewById<TextView>(qtyId).text = if (item != null) "Qty: ${item.stock}" else "Qty: 0"
        }

        setBox(R.id.tvBoxB1Name, R.id.tvBoxB1Qty, "B1")
        setBox(R.id.tvBoxB2Name, R.id.tvBoxB2Qty, "B2")
        setBox(R.id.tvBoxB3Name, R.id.tvBoxB3Qty, "B3")
        setBox(R.id.tvBoxB4Name, R.id.tvBoxB4Qty, "B4")
        setBox(R.id.tvBoxB5Name, R.id.tvBoxB5Qty, "B5")
        setBox(R.id.tvBoxB6Name, R.id.tvBoxB6Qty, "B6")
    }

    private fun saveMedicinesToApi() {
        if (isUserApp()) return
        val prefs = Prefs(requireContext())
        val accessCode = prefs.adminAccessCode.trim()
        val base = prefs.centralApiUrl.trim().removeSuffix("/")
        if (base.isEmpty() || accessCode.isEmpty()) return

        Thread {
            var success = false
            try {
                val bodyText = JSONObject().apply {
                    put("access_code", accessCode)
                    if (prefs.actAsUserId.isNotEmpty()) put("act_as_user_id", prefs.actAsUserId)
                    put("medicines", medicinesToJsonArray())
                }.toString()

                val urls = listOf(
                    "$base/admin/medicines",
                    "$base/admin/inventory"
                )

                for (url in urls) {
                    try {
                        val body = bodyText.toRequestBody("application/json".toMediaType())
                        val req = Request.Builder().url(url).put(body).build()
                        val res = http.newCall(req).execute()
                        if (res.isSuccessful) {
                            success = true
                            break
                        }
                    } catch (_: Exception) {}
                }

                if (!success) {
                    success = pushFullSyncFallback(base, accessCode, prefs.actAsUserId)
                }
            } catch (_: Exception) {
                success = pushFullSyncFallback(base, accessCode)
            }

            if (!success) {
                // Keep silent for transient network issues; next polling cycle will pull latest.
            }
        }.start()
    }

    private fun medicinesToJsonArray(): JSONArray {
        val arr = JSONArray()
        allItems.sortedBy { it.box }.forEach { item ->
            val eff = item.effectiveTimesForApi()
            val timesJa = JSONArray()
            for (t in eff) timesJa.put(MedicineSchedule.normalizeToHhMm(t))
            val totalDaily = item.totalDoseUnitsPerDay()
            val perTime = item.dosePerAdministrationAmount()
            val dosageStr =
                if (item.useMultipleTimesPerDay && eff.size > 1) {
                    "$perTime per time (${eff.size} times/day, $totalDaily total daily)"
                } else {
                    "$perTime per day"
                }
            val o = JSONObject().apply {
                put("name", item.name)
                put("box_id", item.box)
                put("quantity", item.stock)
                put("low_stock", 5)
                put("dosage", dosageStr)
                put("dose_per_day", totalDaily)
                put("exact_time", eff.first())
                put("instructions", dosageStr)
                put("times", timesJa)
                put("expiry", item.expiry)
            }
            arr.put(o)
        }
        return arr
    }
    private fun pushFullSyncFallback(base: String, accessCode: String, actAsUserId: String? = null): Boolean {
        if (isUserApp()) return false
        return try {
            val payload = JSONObject().apply {
                put("access_code", accessCode)
                if (!actAsUserId.isNullOrEmpty()) put("act_as_user_id", actAsUserId)
                put("medicine_boxes", medicinesToBoxesJsonObject())
                put("dose_log", JSONArray())
            }
            val body = payload.toString().toRequestBody("application/json".toMediaType())
            val req = Request.Builder().url("$base/admin/sync").post(body).build()
            val res = http.newCall(req).execute()
            res.isSuccessful
        } catch (_: Exception) {
            false
        }
    }

    private fun medicinesToBoxesJsonObject(): JSONObject {
        val boxes = JSONObject()
        allItems.sortedBy { it.box }.forEach { item ->
            val eff = item.effectiveTimesForApi()
            val timesJa = JSONArray()
            for (t in eff) timesJa.put(MedicineSchedule.normalizeToHhMm(t))
            val totalDaily = item.totalDoseUnitsPerDay()
            val perTime = item.dosePerAdministrationAmount()
            val dosageStr =
                if (item.useMultipleTimesPerDay && eff.size > 1) {
                    "$perTime per time (${eff.size} times/day, $totalDaily total daily)"
                } else {
                    "$perTime per day"
                }
            val med = JSONObject().apply {
                put("name", item.name)
                put("quantity", item.stock)
                put("low_stock", 5)
                put("dose_per_day", totalDaily)
                put("exact_time", eff.first())
                put("instructions", dosageStr)
                put("expiry", item.expiry)
                put("times", timesJa)
            }
            boxes.put(item.box, med)
        }
        return boxes
    }

    private fun syncIntoSharedDemoData() {
        AdminDemoData.replaceMedicines(
            requireContext(),
            allItems.map {
                val eff = it.effectiveTimesForApi()
                val multi = it.useMultipleTimesPerDay && eff.size > 1
                AdminDemoData.Medicine(
                    name = it.name,
                    stock = it.stock,
                    dosePerDay = it.dosePerDay,
                    expiry = it.expiry,
                    status = it.status,
                    box = it.box,
                    exactTime = eff.first(),
                    scheduleTimes = if (multi) eff else emptyList(),
                )
            },
        )
    }

    private fun dismissActiveHealthHubSheet() {
        try {
            activeHealthHubSheet?.dismiss()
        } catch (_: Exception) {
        }
        activeHealthHubSheet = null
    }

    private fun healthHubSheetAnchor(): View? = view?.findViewById(R.id.card_standalone_health_hub)

    /**
     * Applies fixed height + expanded offset so the sheet stays bottom-anchored. Call again after
     * large in-sheet visibility changes (e.g. Create plan) so [BottomSheetBehavior] does not shrink the sheet.
     */
    private fun applyHealthHubBottomSheetSizing(bottomSheet: View, anchor: View?): Boolean {
        if (!isAdded) return false
        bottomSheet.background =
            ContextCompat.getDrawable(requireContext(), R.drawable.bg_standalone_health_hub_sheet)
        val parent = bottomSheet.parent as? View ?: return false
        val ph = parent.height
        if (ph <= 0) return false
        val density = resources.displayMetrics.density

        val gapBelowHubPx = (6 * density).toInt()
        val extraHeightPx = (48 * density).toInt()

        // Keep sizing stable when the coordinator briefly reports a small height (staged layout /
        // IME resize). Never shrink the locked sheet height below the tallest parent we've seen.
        val prevMaxPh = bottomSheet.getTag(R.id.tag_health_hub_sheet_max_parent_h) as? Int ?: 0
        val rootH = (bottomSheet.rootView?.height ?: 0).coerceAtLeast(ph)
        val maxPhRecorded = maxOf(prevMaxPh, ph, rootH)
        bottomSheet.setTag(R.id.tag_health_hub_sheet_max_parent_h, maxPhRecorded)
        val capForSizing = maxPhRecorded

        val hubRect = Rect()
        val computed = if (anchor != null && anchor.isShown && anchor.getGlobalVisibleRect(hubRect) && hubRect.bottom > 80) {
            val pl = IntArray(2)
            parent.getLocationOnScreen(pl)
            val parentBottomOnScreen = pl[1] + ph
            val sheetTopOnScreen = hubRect.bottom + gapBelowHubPx
            val raw = parentBottomOnScreen - sheetTopOnScreen + extraHeightPx
            val minH = (232 * density).toInt()
            raw.coerceIn(minH, capForSizing)
        } else {
            (ph * 0.60f).toInt().coerceIn((300 * density).toInt(), capForSizing)
        }
        val lockedPrev = bottomSheet.getTag(R.id.tag_health_hub_sheet_locked_height) as? Int
        val h = maxOf(computed, lockedPrev ?: 0).coerceIn(1, capForSizing)
        bottomSheet.setTag(R.id.tag_health_hub_sheet_locked_height, h)

        val lp = bottomSheet.layoutParams
        lp.height = h
        if (lp is CoordinatorLayout.LayoutParams) {
            lp.gravity = Gravity.BOTTOM
        }
        bottomSheet.layoutParams = lp

        BottomSheetBehavior.from(bottomSheet).apply {
            isFitToContents = false
            skipCollapsed = true
            halfExpandedRatio = 0.999f
            // Close via X only; no drag handle / swipe-to-dismiss.
            isDraggable = false
            isHideable = false
            peekHeight = h
            setExpandedOffset((ph - h).coerceAtLeast(0))
            state = BottomSheetBehavior.STATE_EXPANDED
        }
        return true
    }

    /**
     * Sizes the sheet from the bottom up to just under the Health Hub card (same for all three).
     * Uses the bottom sheet parent's screen position so height/offset match [BottomSheetBehavior]
     * (mixing [DisplayMetrics.heightPixels] with [getGlobalVisibleRect] caused the sheet to sit too low,
     * e.g. with its top near "Your medicines" instead of under the hub).
     */
    private fun configureHealthHubSheetLayout(sheet: BottomSheetDialog, anchor: View?) {
        sheet.window?.setBackgroundDrawableResource(android.R.color.transparent)
        sheet.setOnShowListener {
            val bottomSheet = sheet.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                ?: return@setOnShowListener

            fun applyOnceStable() {
                if (!isAdded || !sheet.isShowing) return
                val parent = bottomSheet.parent as? View ?: return
                if (parent.height <= 0 || parent.width <= 0) return
                // One frame after layout so coordinator height is final — avoids triple resize/jerk.
                ViewCompat.postOnAnimation(bottomSheet) {
                    if (!isAdded || !sheet.isShowing) return@postOnAnimation
                    applyHealthHubBottomSheetSizing(bottomSheet, anchor)
                }
            }

            fun waitForParentLayout() {
                val parent = bottomSheet.parent as? View ?: return
                if (parent.height > 0 && parent.width > 0) {
                    bottomSheet.post { applyOnceStable() }
                    return
                }
                val vto = bottomSheet.viewTreeObserver
                val listener = object : ViewTreeObserver.OnGlobalLayoutListener {
                    override fun onGlobalLayout() {
                        val p = bottomSheet.parent as? View ?: return
                        if (!isAdded || !sheet.isShowing || !bottomSheet.isAttachedToWindow) {
                            if (vto.isAlive) vto.removeOnGlobalLayoutListener(this)
                            return
                        }
                        if (p.height <= 0 || p.width <= 0) return
                        if (vto.isAlive) vto.removeOnGlobalLayoutListener(this)
                        applyOnceStable()
                    }
                }
                vto.addOnGlobalLayoutListener(listener)
            }

            bottomSheet.post { waitForParentLayout() }
        }
    }

    private fun buildHealthHubAlertsReportText(): String {
        val ctx = requireContext()
        val meds = AdminDemoData.medicines
        val apiAlerts = AdminDemoData.getApiAlerts()
        val local = try {
            AlertDb(ctx).getAllAlerts()
        } catch (_: Exception) {
            emptyList()
        }
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
        val sb = StringBuilder()
        sb.appendLine("ACTIVE ALERTS REPORT")
        sb.appendLine("Generated: $now")
        sb.appendLine()
        sb.appendLine("SUMMARY")
        sb.appendLine("• Tracked medicines: ${meds.size}")
        sb.appendLine("• Care-team alerts (synced): ${apiAlerts.size}")
        sb.appendLine("• On-device inbox: ${local.size}")
        sb.appendLine()
        sb.appendLine("MEDICINES")
        if (meds.isEmpty()) {
            sb.appendLine("(none)")
        } else {
            meds.forEachIndexed { i, m ->
                sb.appendLine("${i + 1}. ${m.name} · ${m.box} · stock ${m.stock} · ${m.status}")
            }
        }
        sb.appendLine()
        sb.appendLine("CARE-TEAM ALERTS")
        if (apiAlerts.isEmpty()) {
            sb.appendLine("(none)")
        } else {
            apiAlerts.forEachIndexed { i, a ->
                val t = SimpleDateFormat("MMM d HH:mm", Locale.getDefault()).format(Date(a.receivedAt))
                sb.appendLine("${i + 1}. [$t] ${a.type}: ${a.message}")
            }
        }
        sb.appendLine()
        sb.appendLine("ON-DEVICE INBOX")
        if (local.isEmpty()) {
            sb.appendLine("(none)")
        } else {
            local.forEachIndexed { i, a ->
                val t = SimpleDateFormat("MMM d HH:mm", Locale.getDefault()).format(Date(a.receivedAt))
                sb.appendLine("${i + 1}. [$t] ${a.type}: ${a.message}")
            }
        }
        return sb.toString().trim()
    }

    private fun htmlEscapeForPrint(s: String): String = buildString(s.length + 16) {
        for (c in s) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                else -> append(c)
            }
        }
    }

    private fun printHealthHubAlertsReport(plain: String) {
        val esc = htmlEscapeForPrint(plain)
        val html =
            "<html><head><meta charset=\"utf-8\"/></head><body style=\"margin:16px;font-family:sans-serif;font-size:13px;\"><pre style=\"white-space:pre-wrap;word-wrap:break-word;\">$esc</pre></body></html>"
        val wv = WebView(requireContext())
        wv.settings.javaScriptEnabled = false
        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                val mgr = requireActivity().getSystemService(Context.PRINT_SERVICE) as PrintManager
                val adapter = view.createPrintDocumentAdapter("CuraXAlertsReport")
                mgr.print("CuraX — Alerts report", adapter, PrintAttributes.Builder().build())
            }
        }
        wv.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
    }

    private fun showHealthHubPlannedItemsBottomSheet() {
        val ctx = requireContext()
        dismissActiveHealthHubSheet()
        healthHubPlannedReloadCallback = null
        val shell = layoutInflater.inflate(R.layout.standalone_health_hub_bottom_sheet, null)
        shell.findViewById<TextView>(R.id.tv_health_hub_sheet_prompt).setText(R.string.health_hub_sheet_planned_prompt)
        shell.findViewById<MaterialButton>(R.id.btn_sheet_primary).visibility = View.GONE
        shell.findViewById<ImageButton>(R.id.btn_health_hub_sheet_print).visibility = View.GONE
        val btnAdd = shell.findViewById<ImageButton>(R.id.btn_health_hub_sheet_add)
        val canMutatePlans = StandaloneUserMutationGate.allowMutations(ctx)
        btnAdd.visibility = if (canMutatePlans) View.VISIBLE else View.GONE

        val flBody = shell.findViewById<FrameLayout>(R.id.fl_health_hub_sheet_body)
        layoutInflater.inflate(R.layout.sheet_body_planned_list, flBody, true)
        val rv = flBody.findViewById<RecyclerView>(R.id.rvPlannedList)
        val tvEmpty = flBody.findViewById<TextView>(R.id.tvPlannedListEmpty)
        rv.layoutManager = LinearLayoutManager(ctx)

        val sheet = BottomSheetDialog(ctx)
        activeHealthHubSheet = sheet

        lateinit var plansAdapter: HealthHubPlansAdapter

        fun reapplyPlannedSheetHeight() {
            if (!isAdded || !sheet.isShowing) return
            val bs = sheet.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) ?: return
            bs.post { applyHealthHubBottomSheetSizing(bs, healthHubSheetAnchor()) }
        }

        fun reloadPlansFromNetwork() {
            Thread {
                val (list, _) = UserPlansApi.fetchPlans(ctx.applicationContext)
                activity?.runOnUiThread {
                    val sorted = list.sortedWith(compareBy({ it.planDate }, { it.planTime }))
                    tvEmpty.visibility = if (sorted.isEmpty()) View.VISIBLE else View.GONE
                    plansAdapter.submitList(sorted)
                    reapplyPlannedSheetHeight()
                    healthHubPlanFetchSeq++
                    view?.findViewById<TextView>(R.id.tv_standalone_summary_total)?.text = list.size.toString()
                    StandaloneOfflineMirror.persistMergedSnapshot(ctx.applicationContext)
                    view?.let { HealthHubPlanInsights.bind(it, ctx.applicationContext) }
                    UserAlarmScheduler.rescheduleAll(ctx.applicationContext)
                }
            }.start()
        }

        fun toggleHealthHubPlanStatus(row: UserPlanRow) {
            val next = if (row.isDone) "pending" else "done"
            Thread {
                val (ok, err) = UserPlansApi.patchPlanStatus(ctx.applicationContext, row.id, next)
                activity?.runOnUiThread {
                    if (ok) {
                        reloadPlansFromNetwork()
                    } else {
                        CuraxFeedback.warn(
                            requireActivity(),
                            getString(R.string.plan_update_failed) + (err?.let { ": $it" } ?: ""),
                        )
                    }
                }
            }.start()
        }

        plansAdapter = HealthHubPlansAdapter()
        rv.adapter = plansAdapter

        if (canMutatePlans) {
            ItemTouchHelper(
                object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
                    override fun onMove(
                        recyclerView: RecyclerView,
                        viewHolder: RecyclerView.ViewHolder,
                        target: RecyclerView.ViewHolder,
                    ): Boolean = false

                    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                        val pos = viewHolder.bindingAdapterPosition
                        if (pos == RecyclerView.NO_POSITION) return
                        val row = plansAdapter.itemAt(pos) ?: return
                        plansAdapter.notifyItemChanged(pos)
                        when (direction) {
                            ItemTouchHelper.RIGHT -> {
                                if (!row.isDone) {
                                    Thread {
                                        val (ok, err) = UserPlansApi.patchPlanStatus(ctx.applicationContext, row.id, "done")
                                        activity?.runOnUiThread {
                                            if (ok) {
                                                reloadPlansFromNetwork()
                                            } else {
                                                CuraxFeedback.warn(
                                                    requireActivity(),
                                                    getString(R.string.plan_update_failed) + (err?.let { ": $it" } ?: ""),
                                                )
                                            }
                                        }
                                    }.start()
                                }
                            }
                            ItemTouchHelper.LEFT -> {
                                MaterialAlertDialogBuilder(ctx)
                                    .setTitle(R.string.plan_delete_title)
                                    .setNegativeButton(android.R.string.cancel, null)
                                    .setPositiveButton(R.string.plan_delete_confirm) { _, _ ->
                                        Thread {
                                            val (ok, err) = UserPlansApi.deletePlan(ctx.applicationContext, row.id)
                                            activity?.runOnUiThread {
                                                if (ok) {
                                                    reloadPlansFromNetwork()
                                                } else {
                                                    CuraxFeedback.warn(
                                                        requireActivity(),
                                                        getString(R.string.plan_delete_failed) + (err?.let { ": $it" } ?: ""),
                                                    )
                                                }
                                            }
                                        }.start()
                                    }
                                    .show()
                            }
                        }
                    }
                },
            ).attachToRecyclerView(rv)
        }

        fun openCreatePlanFullScreen() {
            healthHubCreatePlanLauncher.launch(Intent(ctx, CreateHealthHubPlanActivity::class.java))
        }

        btnAdd.setOnClickListener { openCreatePlanFullScreen() }

        val sortedLocal = UserPlansLocalStore.readCache(ctx.applicationContext)
            .sortedWith(compareBy({ it.planDate }, { it.planTime }))
        tvEmpty.visibility = if (sortedLocal.isEmpty()) View.VISIBLE else View.GONE
        plansAdapter.submitList(sortedLocal)

        shell.findViewById<ImageButton>(R.id.btn_health_hub_sheet_close).setOnClickListener { sheet.dismiss() }
        sheet.setContentView(shell)
        configureHealthHubSheetLayout(sheet, healthHubSheetAnchor())
        healthHubPlannedReloadCallback = { reloadPlansFromNetwork() }
        sheet.setOnDismissListener {
            if (activeHealthHubSheet === sheet) activeHealthHubSheet = null
            healthHubPlannedReloadCallback = null
        }
        sheet.show()
        reloadPlansFromNetwork()
    }

    private fun showHealthHubAlertsBottomSheet() {
        val ctx = requireContext()
        dismissActiveHealthHubSheet()
        val shell = layoutInflater.inflate(R.layout.standalone_health_hub_bottom_sheet, null)
        shell.findViewById<TextView>(R.id.tv_health_hub_sheet_prompt).setText(R.string.health_hub_sheet_alerts_prompt)
        shell.findViewById<MaterialButton>(R.id.btn_sheet_primary).visibility = View.GONE
        val btnPrint = shell.findViewById<ImageButton>(R.id.btn_health_hub_sheet_print)
        btnPrint.visibility = View.VISIBLE
        val flBody = shell.findViewById<FrameLayout>(R.id.fl_health_hub_sheet_body)
        layoutInflater.inflate(R.layout.sheet_body_alerts, flBody, true)
        val reportPlain = buildHealthHubAlertsReportText()
        flBody.findViewById<TextView>(R.id.tvReportBody).text = reportPlain

        val sheet = BottomSheetDialog(ctx)
        activeHealthHubSheet = sheet
        shell.findViewById<ImageButton>(R.id.btn_health_hub_sheet_close).setOnClickListener { sheet.dismiss() }
        btnPrint.setOnClickListener { printHealthHubAlertsReport(reportPlain) }
        sheet.setContentView(shell)
        configureHealthHubSheetLayout(sheet, healthHubSheetAnchor())
        sheet.setOnDismissListener {
            if (activeHealthHubSheet === sheet) activeHealthHubSheet = null
        }
        sheet.show()
    }

    private fun showHealthHubSyncHistoryBottomSheet() {
        val ctx = requireContext()
        dismissActiveHealthHubSheet()
        val shell = layoutInflater.inflate(R.layout.standalone_health_hub_bottom_sheet, null)
        shell.findViewById<TextView>(R.id.tv_health_hub_sheet_prompt).setText(R.string.health_hub_sheet_sync_prompt)
        shell.findViewById<MaterialButton>(R.id.btn_sheet_primary).visibility = View.GONE
        shell.findViewById<ImageButton>(R.id.btn_health_hub_sheet_print).visibility = View.GONE
        val flBody = shell.findViewById<FrameLayout>(R.id.fl_health_hub_sheet_body)
        layoutInflater.inflate(R.layout.sheet_body_sync, flBody, true)
        val rv = flBody.findViewById<RecyclerView>(R.id.rvSyncHistory)
        val rows = HealthHubHistoryStore.readSyncEvents(ctx)
        rv.layoutManager = LinearLayoutManager(ctx)
        rv.adapter = HealthHubSyncSheetAdapter(rows)

        val sheet = BottomSheetDialog(ctx)
        activeHealthHubSheet = sheet
        shell.findViewById<ImageButton>(R.id.btn_health_hub_sheet_close).setOnClickListener { sheet.dismiss() }
        sheet.setContentView(shell)
        configureHealthHubSheetLayout(sheet, healthHubSheetAnchor())
        sheet.setOnDismissListener {
            if (activeHealthHubSheet === sheet) activeHealthHubSheet = null
        }
        sheet.show()
    }

    private class HealthHubSyncSheetAdapter(
        private val items: List<Pair<Long, String>>,
    ) : RecyclerView.Adapter<HealthHubSyncSheetAdapter.VH>() {
        private val fmtServer = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
            timeZone = java.util.TimeZone.getDefault()
        }
        private val fmtOut = SimpleDateFormat("EEE, MMM d yyyy · HH:mm", Locale.getDefault())
        private val fmtRecorded = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val server: TextView = v.findViewById(R.id.tvServerTime)
            val recorded: TextView = v.findViewById(R.id.tvRecordedAt)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_sync_history_row, parent, false)
            return VH(v)
        }

        override fun getItemCount(): Int = items.size.coerceAtLeast(1)

        override fun onBindViewHolder(holder: VH, position: Int) {
            if (items.isEmpty()) {
                holder.server.text = holder.itemView.context.getString(R.string.sync_history_empty)
                holder.recorded.visibility = View.GONE
                return
            }
            holder.recorded.visibility = View.VISIBLE
            val (at, serverIso) = items[position]
            val label = try {
                val normalized = serverIso.replace(' ', 'T').substringBefore('Z').substringBefore('+').take(19)
                val d = fmtServer.parse(normalized) ?: Date(at)
                fmtOut.format(d)
            } catch (_: Exception) {
                serverIso
            }
            holder.server.text = label
            holder.recorded.text = "Recorded ${fmtRecorded.format(Date(at))}"
        }
    }

    private fun isUserApp(): Boolean = AppRole.isUser(requireContext())
}
