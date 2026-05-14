package com.curax.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class AdminMedicalRemindersFragment : Fragment() {

    companion object {
        private val REMINDER_KEYS = listOf("appointments", "prescriptions", "lab_tests", "custom")
        private val REMINDER_LABELS = listOf("Appointments", "Prescriptions", "Lab Tests", "Custom")
        private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    }

    private var containerAppointments: LinearLayout? = null
    private var containerPrescriptions: LinearLayout? = null
    private var containerLabTests: LinearLayout? = null
    private var containerCustom: LinearLayout? = null
    private var lastRefreshSignature: String? = null

    private var syncReceiverRegistered = false
    private val syncReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AlertEvents.ACTION_ADMIN_DATA_SYNCED) {
                lastRefreshSignature = null
                refresh()
            }
        }
    }

    private fun isUserApp(): Boolean = AppRole.isUser(requireContext())

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val layout = if (CareUi.effectiveStandaloneShell(requireContext())) {
            R.layout.fragment_admin_medical_reminders_standalone
        } else {
            R.layout.fragment_admin_medical_reminders
        }
        return inflater.inflate(layout, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        containerAppointments = view.findViewById(R.id.containerAppointments)
        containerPrescriptions = view.findViewById(R.id.containerPrescriptions)
        containerLabTests = view.findViewById(R.id.containerLabTests)
        containerCustom = view.findViewById(R.id.containerCustom)

        refresh()

        val fab = view.findViewById<FloatingActionButton>(R.id.fabAddReminder)
        if (isUserApp()) {
            fab.visibility = View.GONE
        } else {
            fab.setOnClickListener { showAddReminderDialog() }
        }
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

    override fun onResume() {
        super.onResume()
        // Standalone updates come from DataBus sync; avoid refetching on every tab switch.
        if (isUserApp()) return
        loadRemindersFromServer()
    }

    override fun onStop() {
        super.onStop()
    }

    override fun onDestroyView() {
        // Unregister here (not onStop): ViewPager2 stops off-screen tabs, so onStop would
        // drop ACTION_ADMIN_DATA_SYNCED from UserDataBus while user is on Dashboard etc.
        if (syncReceiverRegistered) {
            try { requireContext().unregisterReceiver(syncReceiver) } catch (_: Exception) {}
            syncReceiverRegistered = false
        }
        super.onDestroyView()
    }

    private fun loadRemindersFromServer() {
        val prefs = Prefs(requireContext())
        val accessCode = prefs.adminAccessCode.trim()
        val base = prefs.centralApiUrl.trim().removeSuffix("/")
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
                        val medicalRemindersObj = data.optJSONObject("medical_reminders")
                        val incoming = AdminDemoData.fromApiMedicalReminders(medicalRemindersObj)
                        val currentSig = buildReminderSignature(AdminDemoData.getMedicalReminders())
                        val newSig = buildReminderSignature(incoming)
                        if (currentSig != newSig) {
                            AdminDemoData.replaceMedicalReminders(incoming)
                        }
                        // Always repaint: socket may have updated AdminDemoData while this tab was off-screen;
                        // then currentSig == newSig and UI would stay stale without refresh().
                        lastRefreshSignature = null
                        activity?.runOnUiThread { refresh() }
                    } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
        }.start()
    }

    fun refresh() {
        if (!isAdded) return
        val reminders = AdminDemoData.getMedicalReminders()
        val signature = buildReminderSignature(reminders)
        if (signature == lastRefreshSignature) return
        lastRefreshSignature = signature

        fillSection(containerAppointments, reminders["appointments"].orEmpty(), "appointments") { r ->
            listOf(
                r["doctor"]?.toString() ?: r["title"]?.toString() ?: "-",
                r["date"]?.toString() ?: "",
                r["time"]?.toString() ?: "",
                r["location"]?.toString() ?: ""
            )
        }
        fillSection(containerPrescriptions, reminders["prescriptions"].orEmpty(), "prescriptions") { r ->
            listOf(
                r["medicine"]?.toString() ?: r["title"]?.toString() ?: "-",
                r["expiry_date"]?.toString() ?: r["date"]?.toString() ?: "",
                r["pharmacy"]?.toString() ?: r["location"]?.toString() ?: ""
            )
        }
        fillSection(containerLabTests, reminders["lab_tests"].orEmpty(), "lab_tests") { r ->
            listOf(
                r["test_name"]?.toString() ?: r["title"]?.toString() ?: "-",
                r["date"]?.toString() ?: "",
                r["time"]?.toString() ?: "",
                r["location"]?.toString() ?: ""
            )
        }
        fillSection(containerCustom, reminders["custom"].orEmpty(), "custom") { r ->
            listOf(
                r["title"]?.toString() ?: "-",
                r["date"]?.toString() ?: "",
                r["time"]?.toString() ?: "",
                r["description"]?.toString() ?: ""
            )
        }
    }

    private fun fillSection(
        container: LinearLayout?,
        items: List<Map<String, Any?>>,
        category: String,
        lineBuilder: (Map<String, Any?>) -> List<String>
    ) {
        container ?: return
        container.removeAllViews()
        for ((index, r) in items.withIndex()) {
            val lines = lineBuilder(r)
            val card = layoutInflater.inflate(R.layout.item_medical_reminder_card, container, false) as MaterialCardView
            val tv = card.findViewById<TextView>(R.id.tvReminderCardText)
            tv.text = lines.filter { it.isNotBlank() }.joinToString(" - ")
            if (tv.text.isBlank()) tv.text = "-"
            card.setOnClickListener {
                if (isUserApp()) {
                    showReminderDetailsDialog(r)
                } else {
                    showReminderOptionsDialog(category, index, r)
                }
            }
            container.addView(card)
        }
    }

    private fun showReminderDetailsDialog(item: Map<String, Any?>) {
        val title = item["title"]?.toString()
            ?: item["doctor"]?.toString()
            ?: item["medicine"]?.toString()
            ?: item["test_name"]?.toString()
            ?: "Reminder"
        val details = buildString {
            fun add(label: String, value: Any?) {
                val v = value?.toString()?.trim().orEmpty()
                if (v.isNotEmpty()) append("$label: $v\n")
            }
            add("Date", item["date"] ?: item["expiry_date"])
            add("Time", item["time"])
            add("Location", item["location"] ?: item["pharmacy"])
            add("Details", item["description"] ?: item["specialty"])
        }.trim()

        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setMessage(if (details.isNotBlank()) details else "No details")
            .setPositiveButton("Close", null)
            .show()
    }

    private fun showReminderOptionsDialog(category: String, index: Int, item: Map<String, Any?>) {
        val title = item["title"]?.toString()
            ?: item["doctor"]?.toString()
            ?: item["medicine"]?.toString()
            ?: item["test_name"]?.toString()
            ?: "Reminder"
        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setItems(arrayOf("Edit", "Delete")) { _, which ->
                when (which) {
                    0 -> showEditReminderDialog(category, index, item)
                    1 -> confirmDeleteReminder(category, index, title)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDeleteReminder(category: String, index: Int, title: String) {
        AlertDialog.Builder(requireContext())
            .setTitle("Delete reminder")
            .setMessage("Are you sure you want to delete \"$title\"? This will also remove it from the desktop.")
            .setPositiveButton("Delete") { _, _ ->
                val current = AdminDemoData.getMedicalReminders()
                    .mapValues { (_, v) -> v.toMutableList() }
                    .toMutableMap()
                REMINDER_KEYS.forEach { k -> if (k !in current) current[k] = mutableListOf() }
                val list = current[category]
                if (list != null && index in list.indices) {
                    list.removeAt(index)
                }
                val finalMap = current.mapValues { it.value.toList() }
                AdminDemoData.replaceMedicalReminders(finalMap)
                saveRemindersToApi(finalMap)
                lastRefreshSignature = null
                refresh()
                CuraxFeedback.success(this, "\"$title\" deleted")
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showAddReminderDialog() {
        showReminderDialog(null, null) { newItem ->
            val category = REMINDER_KEYS[newItem.first]
            val map = newItem.second
            val current = AdminDemoData.getMedicalReminders().toMutableMap()
            val list = (current[category] ?: emptyList()).toMutableList()
            list.add(map)
            current[category] = list
            AdminDemoData.replaceMedicalReminders(current)
            saveRemindersToApi(current)
            refresh()
        }
    }

    private fun showEditReminderDialog(category: String, index: Int, item: Map<String, Any?>) {
        val keyIndex = REMINDER_KEYS.indexOf(category).takeIf { it >= 0 } ?: 0
        showReminderDialog(keyIndex, item) { newItem ->
            val cat = REMINDER_KEYS[newItem.first]
            val map = newItem.second
            val current = AdminDemoData.getMedicalReminders().mapValues { (_, v) -> v.toMutableList() }.toMutableMap()
            REMINDER_KEYS.forEach { k -> if (k !in current) current[k] = mutableListOf() }
            if (cat == category && index in (current[cat]?.indices ?: emptySet())) {
                current[cat]!![index] = map
            } else {
                val oldList = current[category] ?: mutableListOf()
                if (index in oldList.indices) oldList.removeAt(index)
                current[category] = oldList
                current[cat] = (current[cat] ?: mutableListOf()).apply { add(map) }
            }
            val finalMap = current.mapValues { it.value.toList() }
            AdminDemoData.replaceMedicalReminders(finalMap)
            saveRemindersToApi(finalMap)
            refresh()
        }
    }

    private fun showReminderDialog(
        typeIndex: Int?,
        existing: Map<String, Any?>?,
        onSave: (Pair<Int, Map<String, Any?>>) -> Unit
    ) {
        val context = requireContext()
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
        }
        fun addLabel(text: String) {
            val label = TextView(context).apply {
                setText(text)
                setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                textSize = 12f
                setPadding(0, 12, 0, 4)
            }
            layout.addView(label)
        }

        fun existingRemindersMap(): Map<*, *> {
            val raw = existing?.get("reminders")
            return if (raw is Map<*, *>) raw else emptyMap<String, Any?>()
        }

        fun styleLabel(tv: TextView, text: String) {
            tv.text = text
            tv.setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            tv.textSize = 12f
            tv.setPadding(0, 12, 0, 4)
        }

        addLabel("Type")
        val typeSpinner = Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, REMINDER_LABELS)
            layout.addView(this)
        }
        if (typeIndex != null && typeIndex in REMINDER_KEYS.indices) typeSpinner.setSelection(typeIndex)

        val titleLabel = TextView(context)
        layout.addView(titleLabel)
        val titleEt = EditText(context).apply {
            setText(existing?.get("title")?.toString()
                ?: existing?.get("doctor")?.toString()
                ?: existing?.get("medicine")?.toString()
                ?: existing?.get("test_name")?.toString())
            layout.addView(this)
        }

        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        var selectedDateStr = (existing?.get("date") ?: existing?.get("expiry_date"))?.toString()?.trim().orEmpty()
        if (selectedDateStr.isEmpty()) {
            selectedDateStr = dateFormat.format(Calendar.getInstance(Locale.US).time)
        }
        var selectedTimeStr = existing?.get("time")?.toString()?.trim().orEmpty()
        if (selectedTimeStr.isEmpty()) selectedTimeStr = "08:00"

        val dateLabel = TextView(context)
        layout.addView(dateLabel)
        val tvDate = TextView(context).apply {
            text = "Date: $selectedDateStr"
            setPadding(0, 8, 0, 4)
        }
        val btnPickDate = MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Calendar"
            setOnClickListener {
                val picker = MaterialDatePicker.Builder.datePicker()
                    .setSelection(Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                        val parsed = try { dateFormat.parse(selectedDateStr) } catch (_: Exception) { null }
                        time = parsed ?: java.util.Date()
                    }.timeInMillis)
                    .build()
                picker.addOnPositiveButtonClickListener { millis ->
                    val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = millis }
                    selectedDateStr = "${cal.get(Calendar.YEAR)}-${(cal.get(Calendar.MONTH) + 1).toString().padStart(2, '0')}-${cal.get(Calendar.DAY_OF_MONTH).toString().padStart(2, '0')}"
                    val idx = typeSpinner.selectedItemPosition.coerceIn(0, REMINDER_KEYS.size - 1)
                    val k = REMINDER_KEYS[idx]
                    tvDate.text = if (k == "prescriptions") "Expiry: $selectedDateStr" else "Date: $selectedDateStr"
                }
                picker.show(parentFragmentManager, "reminder_date_${System.nanoTime()}")
            }
        }
        val dateRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(tvDate, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(btnPickDate)
        }
        layout.addView(dateRow)

        val timeLabel = TextView(context)
        layout.addView(timeLabel)
        val tvTimeDisplay = TextView(context).apply {
            text = "Time: $selectedTimeStr"
            setPadding(0, 8, 0, 4)
        }
        val btnPickTime = MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Set time"
            setOnClickListener {
                val parts = selectedTimeStr.split(":")
                val hour = parts.getOrNull(0)?.toIntOrNull() ?: 8
                val minute = parts.getOrNull(1)?.toIntOrNull() ?: 0
                val picker = MaterialTimePicker.Builder()
                    .setTimeFormat(TimeFormat.CLOCK_24H)
                    .setHour(hour)
                    .setMinute(minute)
                    .build()
                picker.addOnPositiveButtonClickListener {
                    selectedTimeStr = "${picker.hour.toString().padStart(2, '0')}:${picker.minute.toString().padStart(2, '0')}"
                    tvTimeDisplay.text = "Time: $selectedTimeStr"
                }
                picker.show(parentFragmentManager, "reminder_time_${System.nanoTime()}")
            }
        }
        val timeRowPickers = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(tvTimeDisplay, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(btnPickTime)
        }
        layout.addView(timeRowPickers)

        val detailsLabel = TextView(context)
        layout.addView(detailsLabel)
        val detailsEt = EditText(context).apply {
            setText(existing?.get("location")?.toString()
                ?: existing?.get("pharmacy")?.toString()
                ?: existing?.get("description")?.toString())
            layout.addView(this)
        }

        addLabel("Reminder options")
        val cb24h = CheckBox(context).apply {
            text = "24h before"
            isChecked = (existingRemindersMap()["24h"] as? Boolean) ?: true
            layout.addView(this)
        }
        val cb2h = CheckBox(context).apply {
            text = "2h before"
            isChecked = (existingRemindersMap()["2h"] as? Boolean) ?: true
            layout.addView(this)
        }
        val cb7d = CheckBox(context).apply {
            text = "7 days before"
            isChecked = (existingRemindersMap()["7d"] as? Boolean) ?: true
            layout.addView(this)
        }
        val cb3d = CheckBox(context).apply {
            text = "3 days before"
            isChecked = (existingRemindersMap()["3d"] as? Boolean) ?: true
            layout.addView(this)
        }
        val cb1d = CheckBox(context).apply {
            text = "1 day before"
            isChecked = (existingRemindersMap()["1d"] as? Boolean) ?: true
            layout.addView(this)
        }
        val cbAlert = CheckBox(context).apply {
            text = "Send alert"
            isChecked = (existingRemindersMap()["alert"] as? Boolean) ?: true
            layout.addView(this)
        }

        fun applyTypeUi(typeIdx: Int) {
            val key = REMINDER_KEYS[typeIdx.coerceIn(0, REMINDER_KEYS.size - 1)]
            when (key) {
                "appointments" -> {
                    styleLabel(titleLabel, "Doctor")
                    titleEt.hint = "e.g. Dr. Usman"
                    styleLabel(dateLabel, "Date")
                    tvDate.text = "Date: $selectedDateStr"
                    styleLabel(timeLabel, "Time")
                    tvTimeDisplay.text = "Time: $selectedTimeStr"
                    timeRowPickers.visibility = View.VISIBLE
                    timeLabel.visibility = View.VISIBLE
                    styleLabel(detailsLabel, "Location")
                    detailsEt.hint = "e.g. Clinic name"
                    cb24h.visibility = View.VISIBLE
                    cb2h.visibility = View.VISIBLE
                    cb7d.visibility = View.GONE
                    cb3d.visibility = View.GONE
                    cb1d.visibility = View.GONE
                }
                "prescriptions" -> {
                    styleLabel(titleLabel, "Medicine")
                    titleEt.hint = "e.g. Panadol"
                    styleLabel(dateLabel, "Expiry date")
                    tvDate.text = "Expiry: $selectedDateStr"
                    styleLabel(timeLabel, "Time")
                    tvTimeDisplay.text = "Time: $selectedTimeStr"
                    timeRowPickers.visibility = View.VISIBLE
                    timeLabel.visibility = View.VISIBLE
                    styleLabel(detailsLabel, "Pharmacy")
                    detailsEt.hint = "e.g. City Pharmacy"
                    cb24h.visibility = View.GONE
                    cb2h.visibility = View.GONE
                    cb7d.visibility = View.VISIBLE
                    cb3d.visibility = View.VISIBLE
                    cb1d.visibility = View.VISIBLE
                }
                "lab_tests" -> {
                    styleLabel(titleLabel, "Test name")
                    titleEt.hint = "e.g. CBC"
                    styleLabel(dateLabel, "Date")
                    tvDate.text = "Date: $selectedDateStr"
                    styleLabel(timeLabel, "Time")
                    tvTimeDisplay.text = "Time: $selectedTimeStr"
                    timeRowPickers.visibility = View.VISIBLE
                    timeLabel.visibility = View.VISIBLE
                    styleLabel(detailsLabel, "Location")
                    detailsEt.hint = "e.g. Lab center"
                    cb24h.visibility = View.VISIBLE
                    cb2h.visibility = View.VISIBLE
                    cb7d.visibility = View.GONE
                    cb3d.visibility = View.GONE
                    cb1d.visibility = View.GONE
                }
                else -> {
                    styleLabel(titleLabel, "Title")
                    titleEt.hint = "e.g. Drink water"
                    styleLabel(dateLabel, "Date")
                    tvDate.text = "Date: $selectedDateStr"
                    styleLabel(timeLabel, "Time")
                    tvTimeDisplay.text = "Time: $selectedTimeStr"
                    timeRowPickers.visibility = View.VISIBLE
                    timeLabel.visibility = View.VISIBLE
                    styleLabel(detailsLabel, "Description")
                    detailsEt.hint = "e.g. Notes"
                    cb24h.visibility = View.VISIBLE
                    cb2h.visibility = View.VISIBLE
                    cb7d.visibility = View.GONE
                    cb3d.visibility = View.GONE
                    cb1d.visibility = View.GONE
                }
            }
        }
        applyTypeUi(typeIndex ?: 0)

        typeSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                applyTypeUi(position)
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }

        AlertDialog.Builder(context)
            .setTitle(if (existing != null) "Edit reminder" else "Add reminder")
            .setView(layout)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val typeIdx = typeSpinner.selectedItemPosition.coerceIn(0, REMINDER_KEYS.size - 1)
                val key = REMINDER_KEYS[typeIdx]
                val title = titleEt.text?.toString()?.trim().orEmpty()
                val date = selectedDateStr.trim()
                val time = selectedTimeStr.trim()
                val details = detailsEt.text?.toString()?.trim().orEmpty()
                val map = mutableMapOf<String, Any?>(
                    "title" to title.ifEmpty { null },
                    "date" to date.ifEmpty { null },
                    "time" to time.ifEmpty { null },
                    "status" to "scheduled"
                )
                when (key) {
                    "appointments" -> {
                        map["doctor"] = title.ifEmpty { null }
                        map["location"] = details.ifEmpty { null }
                        map["specialty"] = details.ifEmpty { null }
                        map["description"] = details.ifEmpty { null }
                        map["reminders"] = mapOf(
                            "24h" to cb24h.isChecked,
                            "2h" to cb2h.isChecked,
                            "alert" to cbAlert.isChecked
                        )
                    }
                    "prescriptions" -> {
                        map["medicine"] = title.ifEmpty { null }
                        map["expiry_date"] = date.ifEmpty { null }
                        map["pharmacy"] = details.ifEmpty { null }
                        map["location"] = details.ifEmpty { null }
                        map["description"] = details.ifEmpty { null }
                        map["reminders"] = mapOf(
                            "7d" to cb7d.isChecked,
                            "3d" to cb3d.isChecked,
                            "1d" to cb1d.isChecked,
                            "alert" to cbAlert.isChecked
                        )
                    }
                    "lab_tests" -> {
                        map["test_name"] = title.ifEmpty { null }
                        map["location"] = details.ifEmpty { null }
                        map["description"] = details.ifEmpty { null }
                        map["reminders"] = mapOf(
                            "24h" to cb24h.isChecked,
                            "2h" to cb2h.isChecked,
                            "alert" to cbAlert.isChecked
                        )
                    }
                    "custom" -> {
                        map["description"] = details.ifEmpty { null }
                        map["priority"] = details.ifEmpty { null }
                        map["location"] = details.ifEmpty { null }
                        map["reminders"] = mapOf(
                            "24h" to cb24h.isChecked,
                            "2h" to cb2h.isChecked,
                            "alert" to cbAlert.isChecked
                        )
                    }
                }
                onSave(typeIdx to map)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun buildReminderSignature(reminders: Map<String, List<Map<String, Any?>>>): String {
        return REMINDER_KEYS.joinToString("|") { key ->
            val list = reminders[key].orEmpty()
            val normalizedItems = list.map { item ->
                item.entries
                    .sortedBy { it.key }
                    .joinToString(";") { entry -> "${entry.key}=${entry.value?.toString().orEmpty()}" }
            }
            "$key#${normalizedItems.size}#${normalizedItems.joinToString("||")}"
        }
    }

    private fun saveRemindersToApi(reminders: Map<String, List<Map<String, Any?>>>) {
        if (isUserApp()) {
            if (StandaloneUi.isUserStandalone(requireContext())) {
                StandaloneOfflineMirror.persistMergedSnapshot(requireContext())
                StandaloneUserMutationSink.notifyLocalChange(
                    activity,
                    requireContext(),
                    PendingSyncQueueStore.TYPE_REMINDER,
                    getString(R.string.pending_sync_title_reminder),
                    getString(R.string.pending_sync_subtitle_not_synced),
                )
            }
            return
        }
        val prefs = Prefs(requireContext())
        val accessCode = prefs.adminAccessCode.trim()
        val base = prefs.centralApiUrl.trim().removeSuffix("/")
        if (base.isEmpty() || accessCode.isEmpty()) {
            CuraxFeedback.warn(this, "Not signed in as admin")
            return
        }
        Thread {
            try {
                val bodyObj = JSONObject().apply {
                    put("access_code", accessCode)
                    if (prefs.actAsUserId.isNotEmpty()) put("act_as_user_id", prefs.actAsUserId)
                    put("medical_reminders", remindersToJson(reminders))
                }
                val body = bodyObj.toString().toRequestBody("application/json".toMediaType())
                val req = Request.Builder()
                    .url("$base/admin/medical_reminders")
                    .put(body)
                    .build()
                val res = http.newCall(req).execute()
                activity?.runOnUiThread {
                    if (res.isSuccessful) {
                        CuraxFeedback.success(this, "Reminders saved")
                    } else {
                        CuraxFeedback.warn(this, "Failed to save reminders")
                    }
                }
            } catch (e: Exception) {
                activity?.runOnUiThread {
                    CuraxFeedback.warn(this, "Error: ${e.message}")
                }
            }
        }.start()
    }

    private fun remindersToJson(reminders: Map<String, List<Map<String, Any?>>>): JSONObject {
        val out = JSONObject()
        for (key in REMINDER_KEYS) {
            val list = reminders[key] ?: emptyList()
            val arr = JSONArray()
            for (item in list) {
                arr.put(mapToJsonObject(item))
            }
            out.put(key, arr)
        }
        return out
    }

    private fun mapToJsonObject(m: Map<String, Any?>): JSONObject {
        val o = JSONObject()
        for ((k, v) in m) {
            when (v) {
                null -> o.put(k, JSONObject.NULL)
                is Number -> o.put(k, v)
                is Boolean -> o.put(k, v)
                is String -> o.put(k, v)
                is Map<*, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    o.put(k, mapToJsonObject(v as Map<String, Any?>))
                }
                is List<*> -> {
                    val arr = JSONArray()
                    for (e in v) {
                        when (e) {
                            is Map<*, *> -> @Suppress("UNCHECKED_CAST") arr.put(mapToJsonObject(e as Map<String, Any?>))
                            else -> arr.put(e ?: JSONObject.NULL)
                        }
                    }
                    o.put(k, arr)
                }
                else -> o.put(k, v.toString())
            }
        }
        return o
    }
}
