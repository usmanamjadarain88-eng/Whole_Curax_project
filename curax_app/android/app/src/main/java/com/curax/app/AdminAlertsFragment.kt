package com.curax.app

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.textfield.TextInputEditText
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

class AdminAlertsFragment : Fragment() {

    companion object {
        private val http = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()

        private const val LINKED_DIR_MIN_INTERVAL_MS = 30_000L
        @Volatile private var linkedUsersDirLastFetchMs: Long = 0L
    }

    private enum class StatusFilter { BOTH, TAKEN, MISSED, SYSTEM }
    private enum class TimeMode { NEWEST, OLDEST, CUSTOM }
    private enum class FilterTab { TIME, STATUS }

    private lateinit var alertDb: AlertDb
    private lateinit var recycler: RecyclerView
    private lateinit var layoutEmpty: View
    private lateinit var tvEmpty: TextView
    private lateinit var tvPinSetupHint: TextView
    private var pinHintColorAnim: ValueAnimator? = null
    private lateinit var adapter: AdminAlertsAdapter

    private lateinit var cardFilter: View
    private lateinit var btnFiltersTrigger: TextView
    private lateinit var btnFilterTime: MaterialButton
    private lateinit var btnFilterStatus: MaterialButton
    private lateinit var panelTimeOptions: View
    private lateinit var panelStatusOptions: View
    private lateinit var panelCustomRange: View
    private lateinit var btnTimeNewest: MaterialButton
    private lateinit var btnTimeOldest: MaterialButton
    private lateinit var btnTimeCustom: MaterialButton
    private lateinit var btnFromDate: MaterialButton
    private lateinit var btnToDate: MaterialButton
    private lateinit var btnStatusAll: MaterialButton
    private lateinit var btnStatusTaken: MaterialButton
    private lateinit var btnStatusMissed: MaterialButton
    private lateinit var btnStatusSystem: MaterialButton
    private lateinit var btnApplyFilters: MaterialButton
    private lateinit var etSearch: TextInputEditText

    private lateinit var adminSelectionActionBar: View
    private lateinit var btnAdminSelectionCancel: MaterialButton
    private lateinit var btnAdminSelectionSelectAll: MaterialButton
    private lateinit var btnAdminSelectionDelete: MaterialButton

    private var filterTab: FilterTab = FilterTab.TIME
    private var timeMode: TimeMode = TimeMode.NEWEST
    private var fromDateMillis: Long? = null
    private var toDateMillis: Long? = null
    private var statusFilter: StatusFilter = StatusFilter.BOTH

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    private var syncReceiverRegistered = false
    private val syncReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AlertEvents.ACTION_ADMIN_DATA_SYNCED) refresh()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val layout = if (CareUi.effectiveStandaloneShell(requireContext())) {
            R.layout.fragment_admin_alerts_standalone
        } else {
            R.layout.fragment_admin_alerts
        }
        return inflater.inflate(layout, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        alertDb = AlertDb(requireContext())

        recycler = view.findViewById(R.id.recyclerAdminAlerts)
        layoutEmpty = view.findViewById(R.id.layoutAdminAlertsEmpty)
        tvEmpty = view.findViewById(R.id.tvAdminNoAlerts)
        tvPinSetupHint = view.findViewById(R.id.tvPinSetupHint)
        tvPinSetupHint.setOnClickListener {
            if (!AppRole.isUser(requireContext())) return@setOnClickListener
            startActivity(
                Intent(requireContext(), PinSetupActivity::class.java).putExtra(
                    PinSetupActivity.EXTRA_NEXT_ROLE,
                    LocalUserStore.ROLE_USER,
                ),
            )
        }
        cardFilter = view.findViewById(R.id.cardFilterPanel)
        btnFiltersTrigger = view.findViewById(R.id.btnFiltersTrigger)
        btnFilterTime = view.findViewById(R.id.btnFilterTime)
        btnFilterStatus = view.findViewById(R.id.btnFilterStatus)
        panelTimeOptions = view.findViewById(R.id.panelTimeOptions)
        panelStatusOptions = view.findViewById(R.id.panelStatusOptions)
        panelCustomRange = view.findViewById(R.id.panelCustomRange)
        btnTimeNewest = view.findViewById(R.id.btnTimeNewest)
        btnTimeOldest = view.findViewById(R.id.btnTimeOldest)
        btnTimeCustom = view.findViewById(R.id.btnTimeCustom)
        btnFromDate = view.findViewById(R.id.btnFromDate)
        btnToDate = view.findViewById(R.id.btnToDate)
        btnStatusAll = view.findViewById(R.id.btnStatusAll)
        btnStatusTaken = view.findViewById(R.id.btnStatusTaken)
        btnStatusMissed = view.findViewById(R.id.btnStatusMissed)
        btnStatusSystem = view.findViewById(R.id.btnStatusSystem)
        btnApplyFilters = view.findViewById(R.id.btnApplyFilters)
        etSearch = view.findViewById(R.id.etAlertSearch)

        adminSelectionActionBar = view.findViewById(R.id.adminSelectionActionBar)
        btnAdminSelectionCancel = view.findViewById(R.id.btnAdminSelectionCancel)
        btnAdminSelectionSelectAll = view.findViewById(R.id.btnAdminSelectionSelectAll)
        btnAdminSelectionDelete = view.findViewById(R.id.btnAdminSelectionDelete)

        val allowAlertMutations = StandaloneUserMutationGate.allowMutations(requireContext())
        adapter = AdminAlertsAdapter(
            useStandaloneCards = CareUi.effectiveStandaloneShell(requireContext()),
            allowBulkMutations = allowAlertMutations,
            onBulkMutationBlocked = if (allowAlertMutations) null else {
                { CuraxFeedback.warn(this, R.string.standalone_connect_admin_first) }
            },
            onClick = { item ->
                val user = AlertDisplayRules.linkedUserLabelForAlert(item.type, item.userName)
                AlertNavigation.launchDetailFromAlertsList(
                    requireContext(),
                    alertId = item.id,
                    type = item.type,
                    message = item.message,
                    receivedAt = item.receivedAt,
                    userName = user,
                )
            },
            onSelectionChanged = { count -> updateSelectionUi(count) }
        )

        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter

        btnAdminSelectionCancel.setOnClickListener { adapter.clearSelection() }
        btnAdminSelectionSelectAll.setOnClickListener {
            if (adapter.areAllSelected()) {
                adapter.clearSelection()
            } else {
                adapter.selectAll()
            }
        }
        btnAdminSelectionDelete.setOnClickListener { confirmDeleteSelected() }

        setupFilterUi(view)
        refresh()
    }

    override fun onStart() {
        super.onStart()
        if (!syncReceiverRegistered) {
            val filter = IntentFilter(AlertEvents.ACTION_ADMIN_DATA_SYNCED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requireContext().registerReceiver(syncReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                requireContext().registerReceiver(syncReceiver, filter)
            }
            syncReceiverRegistered = true
        }
    }

    override fun onStop() {
        super.onStop()
    }

    override fun onPause() {
        stopPinHintColorAnimation()
        super.onPause()
    }

    override fun onDestroyView() {
        stopPinHintColorAnimation()
        if (syncReceiverRegistered) {
            try { requireContext().unregisterReceiver(syncReceiver) } catch (_: Exception) {}
            syncReceiverRegistered = false
        }
        super.onDestroyView()
    }

    override fun onResume() {
        super.onResume()
        refresh()
        updateApplyButton()
    }

    private fun setupFilterUi(view: View) {
        btnFiltersTrigger.setOnClickListener {
            val show = cardFilter.visibility != View.VISIBLE
            if (show) openFilterPanel() else closeFilterPanel()
        }

        btnFilterTime.setOnClickListener {
            filterTab = FilterTab.TIME
            panelTimeOptions.visibility = View.VISIBLE
            panelStatusOptions.visibility = View.GONE
            setFilterTabStyle(FilterTab.TIME)
        }

        btnFilterStatus.setOnClickListener {
            filterTab = FilterTab.STATUS
            panelTimeOptions.visibility = View.GONE
            panelStatusOptions.visibility = View.VISIBLE
            setFilterTabStyle(FilterTab.STATUS)
        }

        btnTimeNewest.setOnClickListener {
            timeMode = TimeMode.NEWEST
            panelCustomRange.visibility = View.GONE
            setTimeModeStyle(TimeMode.NEWEST)
            updateApplyButton()
        }

        btnTimeOldest.setOnClickListener {
            timeMode = TimeMode.OLDEST
            panelCustomRange.visibility = View.GONE
            setTimeModeStyle(TimeMode.OLDEST)
            updateApplyButton()
        }

        btnTimeCustom.setOnClickListener {
            timeMode = TimeMode.CUSTOM
            panelCustomRange.visibility = View.VISIBLE
            setTimeModeStyle(TimeMode.CUSTOM)
            updateApplyButton()
        }

        btnFromDate.setOnClickListener { pickDate(true) }
        btnToDate.setOnClickListener { pickDate(false) }

        btnStatusAll.setOnClickListener {
            statusFilter = StatusFilter.BOTH
            setStatusStyle(StatusFilter.BOTH)
            updateApplyButton()
        }

        btnStatusTaken.setOnClickListener {
            statusFilter = StatusFilter.TAKEN
            setStatusStyle(StatusFilter.TAKEN)
            updateApplyButton()
        }

        btnStatusMissed.setOnClickListener {
            statusFilter = StatusFilter.MISSED
            setStatusStyle(StatusFilter.MISSED)
            updateApplyButton()
        }

        btnStatusSystem.setOnClickListener {
            statusFilter = StatusFilter.SYSTEM
            setStatusStyle(StatusFilter.SYSTEM)
            updateApplyButton()
        }

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                updateApplyButton()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        btnApplyFilters.setOnClickListener {
            refresh()
            closeFilterPanel()
        }

        view.findViewById<MaterialButton>(R.id.btnResetFilters).setOnClickListener {
            timeMode = TimeMode.NEWEST
            fromDateMillis = null
            toDateMillis = null
            statusFilter = StatusFilter.BOTH
            etSearch.setText("")
            panelCustomRange.visibility = View.GONE
            setTimeModeStyle(TimeMode.NEWEST)
            setStatusStyle(StatusFilter.BOTH)
            updateFromToButtonLabels()
            refresh()
            updateApplyButton()
        }

        setFilterTabStyle(filterTab)
        setTimeModeStyle(timeMode)
        setStatusStyle(statusFilter)
        if (filterTab == FilterTab.TIME) {
            panelTimeOptions.visibility = View.VISIBLE
            panelStatusOptions.visibility = View.GONE
        } else {
            panelTimeOptions.visibility = View.GONE
            panelStatusOptions.visibility = View.VISIBLE
        }
        panelCustomRange.visibility = if (timeMode == TimeMode.CUSTOM) View.VISIBLE else View.GONE
        updateFromToButtonLabels()
        updateApplyButton()
    }

    private fun setFilterTabStyle(tab: FilterTab) {
        val ctx = requireContext()
        btnFilterTime.setTextColor(ContextCompat.getColor(ctx, if (tab == FilterTab.TIME) R.color.connection_panel_title else R.color.text_secondary))
        btnFilterStatus.setTextColor(ContextCompat.getColor(ctx, if (tab == FilterTab.STATUS) R.color.connection_panel_title else R.color.text_secondary))
    }

    private fun setTimeModeStyle(mode: TimeMode) {
        val ctx = requireContext()
        val selectedTint = ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.button_primary_bg))
        val unselectedTint = ColorStateList.valueOf(ContextCompat.getColor(ctx, android.R.color.transparent))
        btnTimeNewest.backgroundTintList = if (mode == TimeMode.NEWEST) selectedTint else unselectedTint
        btnTimeOldest.backgroundTintList = if (mode == TimeMode.OLDEST) selectedTint else unselectedTint
        btnTimeCustom.backgroundTintList = if (mode == TimeMode.CUSTOM) selectedTint else unselectedTint
    }

    private fun setStatusStyle(status: StatusFilter) {
        val ctx = requireContext()
        val selectedTint = ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.button_primary_bg))
        val unselectedTint = ColorStateList.valueOf(ContextCompat.getColor(ctx, android.R.color.transparent))
        btnStatusAll.backgroundTintList = if (status == StatusFilter.BOTH) selectedTint else unselectedTint
        btnStatusTaken.backgroundTintList = if (status == StatusFilter.TAKEN) selectedTint else unselectedTint
        btnStatusMissed.backgroundTintList = if (status == StatusFilter.MISSED) selectedTint else unselectedTint
        btnStatusSystem.backgroundTintList = if (status == StatusFilter.SYSTEM) selectedTint else unselectedTint
    }

    private fun openFilterPanel() {
        cardFilter.visibility = View.VISIBLE
        cardFilter.alpha = 0f
        cardFilter.translationY = -12f
        cardFilter.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(180)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun closeFilterPanel() {
        cardFilter.animate()
            .alpha(0f)
            .translationY(-8f)
            .setDuration(120)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                cardFilter.visibility = View.GONE
                cardFilter.alpha = 1f
                cardFilter.translationY = 0f
            }
            .start()
    }

    private fun updateFromToButtonLabels() {
        btnFromDate.text = fromDateMillis?.let { dateFormat.format(Date(it)) } ?: "From"
        btnToDate.text = toDateMillis?.let { dateFormat.format(Date(it)) } ?: "To"
    }

    private fun pickDate(isFrom: Boolean) {
        val picker = MaterialDatePicker.Builder.datePicker()
            .setTitleText(if (isFrom) "Start date" else "End date")
            .build()

        picker.addOnPositiveButtonClickListener { selection ->
            val cal = Calendar.getInstance().apply {
                timeInMillis = selection
                set(Calendar.HOUR_OF_DAY, if (isFrom) 0 else 23)
                set(Calendar.MINUTE, if (isFrom) 0 else 59)
                set(Calendar.SECOND, if (isFrom) 0 else 59)
                set(Calendar.MILLISECOND, if (isFrom) 0 else 999)
            }
            if (isFrom) fromDateMillis = cal.timeInMillis else toDateMillis = cal.timeInMillis
            updateFromToButtonLabels()
            updateApplyButton()
        }

        picker.show(parentFragmentManager, if (isFrom) "from_date" else "to_date")
    }

    private fun getFilteredAlerts(): List<AlertItem> {
        val query = etSearch.text?.toString()?.trim()?.lowercase().orEmpty()
        val api = AdminDemoData.getApiAlerts()
        val db = alertDb.getAllAlerts()
        val apiForUi = if (AppRole.isAdmin(requireContext()) && api.isEmpty() && db.isEmpty()) {
            AdminDemoData.adminPreviewAlerts()
        } else {
            api
        }
        val combined = (apiForUi + db).let { raw ->
            if (!AppRole.isAdmin(requireContext())) raw
            else raw.map { item ->
                val r = AdminLinkedUserDirectory.resolveAlertUserLabel(
                    item.userName,
                    item.message,
                    item.type,
                )
                when {
                    r.isEmpty() -> item
                    r == item.userName -> item
                    else -> item.copy(userName = r)
                }
            }
        }

        var list = combined.filter { item ->
            val useCustomRange = timeMode == TimeMode.CUSTOM
            val inDate = when {
                !useCustomRange -> true
                fromDateMillis == null && toDateMillis == null -> true
                else -> (fromDateMillis == null || item.receivedAt >= fromDateMillis!!) &&
                    (toDateMillis == null || item.receivedAt <= toDateMillis!!)
            }

            val lowerType = item.type.lowercase()
            val lowerMsg = item.message.lowercase()
            val isTaken = lowerType.contains("taken") || lowerMsg.contains("taken")
            val isMissed = lowerType.contains("missed") || lowerMsg.contains("missed")
            val isSystem = lowerType == "system_started" ||
                lowerType == "system_unlocked" ||
                lowerType == "admin_login" ||
                lowerType.contains("test") ||
                lowerMsg.contains("curax started") ||
                lowerMsg.contains("system unlocked") ||
                lowerMsg.contains("admin panel logged in") ||
                lowerMsg.contains("test alert")
            val byStatus = when (statusFilter) {
                StatusFilter.BOTH -> true
                StatusFilter.MISSED -> isMissed
                StatusFilter.TAKEN -> isTaken
                StatusFilter.SYSTEM -> isSystem
            }

            val bySearch = query.isBlank() || lowerMsg.contains(query) || lowerType.contains(query)

            inDate && byStatus && bySearch
        }

        list = when (timeMode) {
            TimeMode.NEWEST -> list.sortedByDescending { it.receivedAt }
            TimeMode.OLDEST -> list.sortedBy { it.receivedAt }
            TimeMode.CUSTOM -> list.sortedByDescending { it.receivedAt }
        }

        return list
    }

    private fun updateApplyButton() {
        val count = getFilteredAlerts().size
        btnApplyFilters.text = "Apply ($count)"
    }

    private fun updateSelectionUi(count: Int) {
        adminSelectionActionBar.visibility = if (count > 0) View.VISIBLE else View.GONE
        btnAdminSelectionSelectAll.text = if (adapter.areAllSelected()) getString(R.string.unselect_all) else getString(R.string.select_all)
        if (count > 0) {
            btnFiltersTrigger.text = getString(R.string.selected_count, count)
        } else {
            btnFiltersTrigger.text = "Filters"
        }
    }

    private fun confirmDeleteSelected() {
        if (!StandaloneUserMutationGate.warnIfBlocked(this)) return
        val ids = adapter.getSelectedIds()
        if (ids.isEmpty()) return
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.delete_selected)
            .setMessage(getString(R.string.delete_selected_confirm, ids.size))
            .setPositiveButton(R.string.delete) { _, _ -> performDelete(ids) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun performDelete(ids: Set<Long>) {
        if (ids.isEmpty()) return
        if (!StandaloneUserMutationGate.allowMutations(requireContext())) return
        val removed = getFilteredAlerts().filter { it.id in ids }
        if (removed.isEmpty()) return

        // Optimistic UI: remove from list immediately; sync server in background.
        applyLocalDelete(removed, ids)

        if (!AppRole.isAdmin(requireContext())) return
        val prefs = Prefs(requireContext())
        val base = prefs.centralApiUrl.trim().removeSuffix("/")
        val accessCode = prefs.adminAccessCode.trim()
        val serverIds = removed.mapNotNull { it.serverId?.trim()?.takeIf { id -> id.isNotEmpty() } }.distinct()
        if (base.isEmpty() || accessCode.isEmpty() || serverIds.isEmpty()) return

        Thread {
            val ok = try {
                AdminAlertsApi.deleteAlerts(base, accessCode, serverIds, http)
            } catch (_: Exception) {
                false
            }
            activity?.runOnUiThread {
                if (!isAdded || ok) return@runOnUiThread
                CuraxFeedback.warn(
                    this,
                    getString(R.string.admin_alerts_delete_server_failed),
                    long = true,
                )
            }
        }.start()
    }

    private fun applyLocalDelete(removed: List<AlertItem>, ids: Set<Long>) {
        removed.forEach { item ->
            if (item.id > 0L) {
                alertDb.deleteAlert(item.id)
            }
        }
        AdminDemoData.removeApiAlertsByIds(ids)

        adapter.clearSelection()
        refresh()
        if (AppRole.isUser(requireContext()) && StandaloneUi.isUserStandalone(requireContext())) {
            StandaloneOfflineMirror.persistMergedSnapshot(requireContext())
        }

        CuraxFeedback.successWithUndo(
            this,
            getString(R.string.deleted_count, removed.size),
        ) { undoDelete(removed) }
    }

    private fun undoDelete(items: List<AlertItem>) {
        if (items.isEmpty()) return
        val apiItems = mutableListOf<AlertItem>()
        items.forEach { item ->
            if (item.id > 0L) {
                alertDb.insertAlert(item.type, item.message, item.receivedAt, item.userName)
            } else {
                apiItems.add(item)
            }
        }
        if (apiItems.isNotEmpty()) {
            AdminDemoData.appendApiAlerts(apiItems)
        }
        refresh()
        if (AppRole.isUser(requireContext()) && StandaloneUi.isUserStandalone(requireContext())) {
            StandaloneOfflineMirror.persistMergedSnapshot(requireContext())
        }
    }

    fun refresh() {
        if (!isAdded) return
        if (StandaloneUi.isUserStandalone(requireContext()) && AppRole.isUser(requireContext())) {
            AdminDemoData.seedStandaloneDemoLogsIfNeeded(requireContext())
        }
        applyAlertsListToUi()
        maybeRefreshLinkedDirectoryForAdminAlerts()
    }

    private fun maybeRefreshLinkedDirectoryForAdminAlerts() {
        if (!AppRole.isAdmin(requireContext())) return
        val prefs = Prefs(requireContext())
        val base = prefs.centralApiUrl.trim().removeSuffix("/")
        val accessCode = prefs.adminAccessCode.trim()
        if (base.isEmpty() || accessCode.isEmpty()) return
        val now = System.currentTimeMillis()
        synchronized(AdminAlertsFragment::class.java) {
            val last = linkedUsersDirLastFetchMs
            if (last != 0L && now - last < LINKED_DIR_MIN_INTERVAL_MS) return
            linkedUsersDirLastFetchMs = now
        }
        Thread {
            try {
                val url = "$base/admin/linked-users?access_code=${URLEncoder.encode(accessCode, "UTF-8")}"
                val res = http.newCall(Request.Builder().url(url).get().build()).execute()
                if (!res.isSuccessful) return@Thread
                val body = res.body?.string() ?: "{}"
                val data = JSONObject(body)
                val arr = data.optJSONArray("users") ?: org.json.JSONArray()
                AdminLinkedUserDirectory.ingestUsersJsonArray(arr)
            } catch (_: Exception) {
            }
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                applyAlertsListToUi()
            }
        }.start()
    }

    private fun applyAlertsListToUi() {
        val listAll = getFilteredAlerts()
        adapter.submitList(listAll)

        val db = alertDb.getAllAlerts()
        val totalAvailable = AdminDemoData.totalAdminAlertsVisibleCount(
            AppRole.isAdmin(requireContext()),
            db.size,
        )
        if (listAll.isEmpty()) {
            tvEmpty.text = if (totalAvailable == 0) {
                "No alerts yet"
            } else {
                "No alerts match current filters"
            }
            layoutEmpty.visibility = View.VISIBLE
            val showPinLink = AppRole.isUser(requireContext()) &&
                Prefs(requireContext()).appPin.isEmpty() &&
                totalAvailable == 0
            if (showPinLink) {
                tvPinSetupHint.visibility = View.VISIBLE
                startPinHintColorAnimation()
            } else {
                tvPinSetupHint.visibility = View.GONE
                stopPinHintColorAnimation()
            }
        } else {
            layoutEmpty.visibility = View.GONE
            stopPinHintColorAnimation()
        }
        updateApplyButton()
    }

    private fun startPinHintColorAnimation() {
        if (pinHintColorAnim?.isRunning == true) return
        val c1 = ContextCompat.getColor(requireContext(), R.color.standalone_tab_indicator)
        val c2 = ContextCompat.getColor(requireContext(), R.color.connect_button_bg)
        val eval = ArgbEvaluator()
        pinHintColorAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1600L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener { a ->
                val t = a.animatedValue as Float
                tvPinSetupHint.setTextColor(eval.evaluate(t, c1, c2) as Int)
            }
            start()
        }
    }

    private fun stopPinHintColorAnimation() {
        pinHintColorAnim?.cancel()
        pinHintColorAnim = null
        if (::tvPinSetupHint.isInitialized && isAdded) {
            tvPinSetupHint.setTextColor(ContextCompat.getColor(requireContext(), R.color.standalone_tab_indicator))
        }
    }
}
