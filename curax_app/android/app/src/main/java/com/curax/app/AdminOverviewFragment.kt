package com.curax.app

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.animation.OvershootInterpolator
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.curax.app.AdherenceLineChartView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.datepicker.MaterialDatePicker
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
        var addedAt: Long
    )

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
    private val dataSyncReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                AlertEvents.ACTION_ALERTS_UPDATED -> {
                    if (Prefs(requireContext()).hasEverConnected) {
                        view?.post { refreshAdherenceChartFromAlerts() }
                    }
                }
                AlertEvents.ACTION_ADMIN_DATA_SYNCED -> {
                    view?.post {
                        refreshStandaloneFromMemory()
                        // ViewPager/fragment rendering can lag one frame behind the data-bus apply.
                        // Rebind once more on the next loop so medicine cards and inventory repaint immediately.
                        view?.post {
                            refreshStandaloneFromMemory()
                        }
                    }
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_admin_overview, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // In user standalone mode, don't seed until bootstrap fetch completes (prevents showing dummy data).
        // For admin mode, seed immediately (admin data is loaded separately).
        ensureInventorySeeded()
        setupInventory(view)
        setupBoxClicks(view)
        applyStandaloneMedicineBoxGoldTheme(view)
        setupAdherenceChart(view)
        refreshDashboard(view)
        refreshInventoryList(view)
        setupKpiClicks(view)
        // Socket-only sync: no periodic HTTP polling.
        startAdminPollingIfNeeded(view)
    }

    override fun onStart() {
        super.onStart()
        if (!alertsReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(AlertEvents.ACTION_ALERTS_UPDATED)
                addAction(AlertEvents.ACTION_ADMIN_DATA_SYNCED)
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
            // Standalone should not refetch on every tab switch; live changes arrive via DataBus sync.
            refreshStandaloneFromMemory()
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
                        // Use AdminDataBusClient to apply all data (medicines, alerts, medical_reminders, alert_settings)
                        // This ensures medical reminders and settings are also loaded when dashboard is shown
                        AdminDataBusClient.applyAdminDataJson(requireContext(), data)
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
                            AdminDemoData.replaceMedicines(medicinesList)
                            AdminDemoData.replaceApiAlerts(apiAlerts)
                            val n = AdminDemoData.medicines.size
                            allItems.clear()
                            seedInventory()
                            refreshInventoryList(v)
                            refreshDashboard(v)
                            adherenceChart?.data = computeAdherenceData()
                            if (n > 0) {
                                CuraxFeedback.success(requireActivity(), "Imported $n medicines from desktop.")
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
            val tvIndicator = v.findViewById<TextView>(R.id.tvChartPageIndicator)
            val btnNext = v.findViewById<MaterialButton>(R.id.btnChartNext)
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
                    exactTime = m.exactTime,
                    expiry = m.expiry,
                    status = m.status,
                    box = m.box,
                    addedAt = System.currentTimeMillis() - (10000L - order * 10)
                )
            )
            order++
        }
    }

    private fun ensureInventorySeeded() {
        if (isUserApp() && !Prefs(requireContext()).userStandaloneDataReady) {
            return
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
    }

    private fun setupInventory(view: View) {
        adapter = AdminInventoryAdapter(
            onItemClick = { item ->
                selectedItemId = item.id
                adapter.setSelectedId(selectedItemId)
            },
            computedStatus = { computedStatus(it) }
        )

        view.findViewById<RecyclerView>(R.id.rvInventory).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@AdminOverviewFragment.adapter
        }

        view.findViewById<MaterialButton>(R.id.btnAddMedicine).setOnClickListener {
            showAddDialog(view)
        }

        view.findViewById<MaterialButton>(R.id.btnEditMedicine).setOnClickListener {
            val selected = allItems.find { it.id == selectedItemId }
            if (selected == null) {
                CuraxFeedback.warn(this, "Select a medicine first")
            } else {
                showEditDialog(view, selected)
            }
        }

        view.findViewById<MaterialButton>(R.id.btnRemoveMedicine).setOnClickListener {
            val index = allItems.indexOfFirst { it.id == selectedItemId }
            if (index < 0) {
                CuraxFeedback.warn(this, "Select a medicine first")
            } else {
                allItems.removeAt(index)
                selectedItemId = null
                adapter.setSelectedId(null)
                syncIntoSharedDemoData()
                saveMedicinesToApi()
                refreshDashboard(view)
                refreshInventoryList(view)
                CuraxFeedback.success(this, "Box cleared")
            }
        }

        if (isUserApp()) {
            view.findViewById<MaterialButton>(R.id.btnAddMedicine).visibility = View.GONE
            view.findViewById<MaterialButton>(R.id.btnEditMedicine).visibility = View.GONE
            view.findViewById<MaterialButton>(R.id.btnRemoveMedicine).visibility = View.GONE
        }
    }

    /** Green boxes in Default mode; gold only when [AppModeManager] is Standalone (user app only). */
    private fun applyStandaloneMedicineBoxGoldTheme(view: View) {
        if (!isUserApp()) return
        if (!AppModeManager.isStandaloneMode(requireContext())) {
            restoreUserMedBoxColors(view)
            return
        }
        val ctx = requireContext()
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
        if (!isUserApp()) return
        val ctx = requireContext()
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
                CuraxFeedback.warn(this, "$box is empty")
            } else {
                showBoxDetailsDialog(item)
            }
        }
    }

    private fun showBoxDetailsDialog(item: InventoryItem) {
        val status = computedStatus(item)
        val detail = """
            Name: ${item.name}
            Quantity: ${item.stock}
            Dose/day: ${item.dosePerDay}
            Time: ${item.exactTime}
            Status: $status
            Expiry: ${item.expiry}
            Box: ${item.box}
        """.trimIndent()

        AlertDialog.Builder(requireContext())
            .setTitle("${item.box} Details")
            .setMessage(detail)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun showAddDialog(view: View) {
        val available = availableBoxes()
        if (available.isEmpty()) {
            CuraxFeedback.warn(this, "All 6 boxes are filled")
            return
        }

        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 20, 36, 0)
        }

        val etName = EditText(requireContext()).apply { hint = "Medicine name" }
        val etStock = EditText(requireContext()).apply {
            hint = "Stock"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val etDose = EditText(requireContext()).apply {
            hint = "Dose/day (1-4)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val expiryFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        var selectedExpiry = expiryFormat.format(Calendar.getInstance(Locale.US).time)
        val tvExpiry = TextView(requireContext()).apply {
            text = "Expiry: $selectedExpiry"
            setPadding(0, 32, 0, 8)
        }
        val btnExpiry = MaterialButton(requireContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Calendar"
            setOnClickListener {
                val picker = MaterialDatePicker.Builder.datePicker()
                    .setSelection(Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                        val parsed = try { expiryFormat.parse(selectedExpiry) } catch (_: Exception) { null }
                        time = parsed ?: java.util.Date()
                    }.timeInMillis)
                    .build()
                picker.addOnPositiveButtonClickListener { millis ->
                    val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = millis }
                    selectedExpiry = "${cal.get(Calendar.YEAR)}-${(cal.get(Calendar.MONTH) + 1).toString().padStart(2, '0')}-${cal.get(Calendar.DAY_OF_MONTH).toString().padStart(2, '0')}"
                    tvExpiry.text = "Expiry: $selectedExpiry"
                }
                picker.show(parentFragmentManager, "expiry_picker")
            }
        }
        var selectedTime = "08:00"
        val tvTime = TextView(requireContext()).apply {
            setPadding(0, 24, 0, 8)
            text = "Time: $selectedTime"
        }
        val btnTime = MaterialButton(requireContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Set time"
            setOnClickListener {
                val parts = selectedTime.split(":")
                val hour = parts.getOrNull(0)?.toIntOrNull() ?: 8
                val minute = parts.getOrNull(1)?.toIntOrNull() ?: 0
                val picker = MaterialTimePicker.Builder()
                    .setTimeFormat(TimeFormat.CLOCK_24H)
                    .setHour(hour)
                    .setMinute(minute)
                    .build()
                picker.addOnPositiveButtonClickListener {
                    selectedTime = "${picker.hour.toString().padStart(2, '0')}:${picker.minute.toString().padStart(2, '0')}"
                    tvTime.text = "Time: $selectedTime"
                }
                picker.show(parentFragmentManager, "time_picker_add")
            }
        }
        val timeRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(tvTime, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(btnTime)
        }
        val expiryRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(tvExpiry, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(btnExpiry)
        }
        val actvBox = android.widget.AutoCompleteTextView(requireContext()).apply {
            hint = "Select empty box"
            setAdapter(ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, available))
            setText(available.first(), false)
        }

        container.addView(etName)
        container.addView(etStock)
        container.addView(etDose)
        container.addView(timeRow)
        container.addView(expiryRow)
        container.addView(actvBox)

        val addDialog = AlertDialog.Builder(requireContext())
            .setTitle("Add Medicine")
            .setView(container)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Add") { _, _ ->
                val name = etName.text.toString().trim()
                val stock = etStock.text.toString().trim().toIntOrNull() ?: -1
                val dosePerDay = etDose.text.toString().trim().toIntOrNull() ?: -1
                val expiry = selectedExpiry
                val box = actvBox.text.toString().trim().uppercase()

                if (name.isBlank() || stock < 0 || dosePerDay !in 1..4 || expiry.isBlank() || box !in availableBoxes()) {
                    CuraxFeedback.warn(this, "Use valid values. Dose/day must be 1..4 and box must be empty")
                    return@setPositiveButton
                }

                val newItem = InventoryItem(
                    id = System.nanoTime(),
                    name = name,
                    stock = stock,
                    dosePerDay = dosePerDay,
                    exactTime = selectedTime,
                    expiry = expiry,
                    status = "Normal",
                    box = box,
                    addedAt = System.currentTimeMillis()
                )
                newItem.status = computedStatus(newItem)
                allItems.add(newItem)
                selectedItemId = newItem.id
                adapter.setSelectedId(selectedItemId)
                syncIntoSharedDemoData()
                saveMedicinesToApi()
                refreshDashboard(view)
                refreshInventoryList(view)
                CuraxFeedback.success(this, "Medicine added to $box")
            }
            .create()

        addDialog.setOnShowListener {
            addDialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(ContextCompat.getColor(requireContext(), R.color.connection_panel_title))
            addDialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
        }
        addDialog.show()
    }

    private fun showEditDialog(view: View, existing: InventoryItem) {
        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 20, 36, 0)
        }

        val etName = EditText(requireContext()).apply {
            hint = "Medicine name"
            setText(existing.name)
        }
        val etStock = EditText(requireContext()).apply {
            hint = "Stock"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(existing.stock.toString())
        }
        val etDose = EditText(requireContext()).apply {
            hint = "Dose/day (1-4)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(existing.dosePerDay.toString())
        }
        var selectedTime = existing.exactTime.ifBlank { "08:00" }
        val tvTime = TextView(requireContext()).apply {
            setPadding(0, 24, 0, 8)
            text = "Time: $selectedTime"
        }
        val btnTime = MaterialButton(requireContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Set time"
            setOnClickListener {
                val parts = selectedTime.split(":")
                val hour = parts.getOrNull(0)?.toIntOrNull() ?: 8
                val minute = parts.getOrNull(1)?.toIntOrNull() ?: 0
                val picker = MaterialTimePicker.Builder()
                    .setTimeFormat(TimeFormat.CLOCK_24H)
                    .setHour(hour)
                    .setMinute(minute)
                    .build()
                picker.addOnPositiveButtonClickListener {
                    selectedTime = "${picker.hour.toString().padStart(2, '0')}:${picker.minute.toString().padStart(2, '0')}"
                    tvTime.text = "Time: $selectedTime"
                }
                picker.show(parentFragmentManager, "time_picker_edit")
            }
        }
        val timeRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(tvTime, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(btnTime)
        }
        val expiryFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        var selectedExpiry = existing.expiry.ifBlank { expiryFormat.format(Calendar.getInstance(Locale.US).time) }
        val tvExpiry = TextView(requireContext()).apply {
            setPadding(0, 32, 0, 8)
            text = "Expiry: $selectedExpiry"
        }
        val btnExpiry = MaterialButton(requireContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Calendar"
            setOnClickListener {
                val cal = try {
                    Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                        time = expiryFormat.parse(selectedExpiry) ?: java.util.Date()
                    }
                } catch (_: Exception) { Calendar.getInstance(TimeZone.getTimeZone("UTC")) }
                val picker = MaterialDatePicker.Builder.datePicker()
                    .setSelection(cal.timeInMillis)
                    .build()
                picker.addOnPositiveButtonClickListener { millis ->
                    val c = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = millis }
                    selectedExpiry = "${c.get(Calendar.YEAR)}-${(c.get(Calendar.MONTH) + 1).toString().padStart(2, '0')}-${c.get(Calendar.DAY_OF_MONTH).toString().padStart(2, '0')}"
                    tvExpiry.text = "Expiry: $selectedExpiry"
                }
                picker.show(parentFragmentManager, "expiry_picker_edit")
            }
        }
        val expiryRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(tvExpiry, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(btnExpiry)
        }
        val etBox = EditText(requireContext()).apply {
            hint = "Box (B1-B6)"
            setText(existing.box)
        }

        container.addView(etName)
        container.addView(etStock)
        container.addView(etDose)
        container.addView(timeRow)
        container.addView(expiryRow)
        container.addView(etBox)

        val editDialog = AlertDialog.Builder(requireContext())
            .setTitle("Edit Medicine")
            .setView(container)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val name = etName.text.toString().trim()
                val stock = etStock.text.toString().trim().toIntOrNull() ?: -1
                val dosePerDay = etDose.text.toString().trim().toIntOrNull() ?: -1
                val expiry = selectedExpiry
                val box = etBox.text.toString().trim().uppercase()

                val validBox = box in setOf("B1", "B2", "B3", "B4", "B5", "B6")
                val occupiedByOther = allItems.any { it.id != existing.id && it.box.equals(box, true) }

                if (name.isBlank() || stock < 0 || dosePerDay !in 1..4 || expiry.isBlank() || !validBox || occupiedByOther) {
                    CuraxFeedback.warn(this, "Use valid data. Dose/day 1..4 and unique box B1-B6")
                    return@setPositiveButton
                }

                existing.name = name
                existing.stock = stock
                existing.dosePerDay = dosePerDay
                existing.exactTime = selectedTime
                existing.expiry = expiry
                existing.box = box
                existing.status = computedStatus(existing)
                selectedItemId = existing.id

                adapter.setSelectedId(selectedItemId)
                syncIntoSharedDemoData()
                saveMedicinesToApi()
                refreshDashboard(view)
                refreshInventoryList(view)
                CuraxFeedback.success(this, "Medicine updated")
            }
            .create()

        editDialog.setOnShowListener {
            editDialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(ContextCompat.getColor(requireContext(), R.color.connection_panel_title))
            editDialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
        }
        editDialog.show()
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
        view.findViewById<TextView>(R.id.tvInventoryHint).text = if (avail.isEmpty()) {
            "6/6 boxes filled. Tap row to Edit or Clear Box"
        } else {
            "${working.size}/6 filled. Empty: ${avail.joinToString(", ")}" 
        }

        if (isUserApp()) {
            view.findViewById<MaterialButton>(R.id.btnAddMedicine).visibility = View.GONE
        } else {
            view.findViewById<MaterialButton>(R.id.btnAddMedicine).visibility = if (avail.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    private fun availableBoxes(): List<String> {
        val all = listOf("B1", "B2", "B3", "B4", "B5", "B6")
        val used = allItems.map { it.box.uppercase() }.toSet()
        return all.filter { it !in used }
    }

    private fun refreshDashboard(view: View) {
        ensureInventorySeeded()
        val total = allItems.size
        val boxesWithMedicine = allItems.count { it.stock > 0 }
        val threshold = AdminDemoData.getLowStockThreshold()
        val low = allItems.count { it.stock in 1..threshold }
        val emptyBoxes = 6 - allItems.size
        val zeroStockItems = allItems.count { it.stock == 0 }
        val refill = emptyBoxes + zeroStockItems
        val exp = allItems.count { isExpiringSoon(it.expiry) }
        val normal = allItems.count { computedStatus(it) == "Normal" }

        bindMedicineBoxes(view)

        animateDonutSection(view, boxesWithMedicine)

        view.findViewById<TextView>(R.id.tvDistNormal).text = "Normal: $normal"
        view.findViewById<TextView>(R.id.tvDistLow).text = "Low: $low"
        view.findViewById<TextView>(R.id.tvDistExpiring).text = "Expiring: $exp"

        view.findViewById<TextView>(R.id.tvKpiTotal).text = total.toString()
        view.findViewById<TextView>(R.id.tvKpiLow).text = low.toString()
        view.findViewById<TextView>(R.id.tvKpiExpiring).text = exp.toString()
        view.findViewById<TextView>(R.id.tvKpiRefill).text = refill.toString()

        updateTrendFromInventory(view)
        if (isUserApp()) applyStandaloneMedicineBoxGoldTheme(view)
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
        adherenceChart = view.findViewById(R.id.adherenceChart)
        val btnPrev = view.findViewById<MaterialButton>(R.id.btnChartPrev)
        val btnNext = view.findViewById<MaterialButton>(R.id.btnChartNext)
        val tvIndicator = view.findViewById<TextView>(R.id.tvChartPageIndicator)

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

        val expectedPerDay = allItems.sumOf { it.dosePerDay }.coerceAtLeast(0)
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
        val tvIndicator = view.findViewById<TextView>(R.id.tvChartPageIndicator)
        val btnNext = view.findViewById<MaterialButton>(R.id.btnChartNext)
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
            val threshold = AdminDemoData.getLowStockThreshold()
            val items = allItems.filter { it.stock in 1..threshold }.sortedBy { it.box }
            showKpiDetailsDialog(
                title = "Low Stock Medicines",
                subtitle = "Medicines with stock between 1 and $threshold",
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
            val zeroStock = allItems.filter { it.stock == 0 }.sortedBy { it.box }.map { formatMedicineLine(it) }
            val used = allItems.map { it.box.uppercase() }.toSet()
            val emptyBoxes = listOf("B1", "B2", "B3", "B4", "B5", "B6").filter { it !in used }
                .map { "Box: $it | Empty | Refill needed" }
            showKpiDetailsDialog(
                title = "Refill Required",
                subtitle = "Zero-stock and empty boxes",
                lines = zeroStock + emptyBoxes
            )
        }
    }

    private fun formatMedicineLine(item: InventoryItem): String {
        return "${item.name} | Box: ${item.box} | Stock: ${item.stock} | Dose/day: ${item.dosePerDay} | Time: ${item.exactTime} | Expiry: ${item.expiry}"
    }

    private fun showKpiDetailsDialog(title: String, subtitle: String, lines: List<String>) {
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
                    text = "No matching medicines"
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
            val o = JSONObject().apply {
                put("name", item.name)
                put("box_id", item.box)
                put("quantity", item.stock)
                put("low_stock", 5)
                put("dosage", "${item.dosePerDay} per day")
                put("dose_per_day", item.dosePerDay)
                put("exact_time", item.exactTime)
                put("instructions", "${item.dosePerDay} per day")
                put("times", JSONArray().put(item.exactTime))
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
            val med = JSONObject().apply {
                put("name", item.name)
                put("quantity", item.stock)
                put("low_stock", 5)
                put("dose_per_day", item.dosePerDay)
                put("exact_time", item.exactTime)
                put("instructions", "${item.dosePerDay} per day")
                put("expiry", item.expiry)
            }
            boxes.put(item.box, med)
        }
        return boxes
    }

    private fun syncIntoSharedDemoData() {
        AdminDemoData.replaceMedicines(
            allItems.map {
                AdminDemoData.Medicine(
                    name = it.name,
                    stock = it.stock,
                    dosePerDay = it.dosePerDay,
                    expiry = it.expiry,
                    status = it.status,
                    box = it.box,
                    exactTime = it.exactTime
                )
            }
        )
    }
    private fun isUserApp(): Boolean = AppRole.isUser(requireContext())
}
