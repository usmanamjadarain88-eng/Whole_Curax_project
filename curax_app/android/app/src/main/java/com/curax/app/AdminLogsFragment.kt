package com.curax.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.print.PrintAttributes
import android.print.PrintManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AdminLogsFragment : Fragment() {

    private lateinit var logAdapter: LogEntriesAdapter
    private lateinit var btnLogsPrev: MaterialButton
    private lateinit var btnLogsNext: MaterialButton
    private lateinit var tvLogsPageInfo: TextView
    private var pageIndex = 0
    private val pageSize = 20
    private var connectionReceiverRegistered = false
    private val connectionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                AlertEvents.ACTION_CONNECTION_STATE_CHANGED,
                AlertEvents.ACTION_ALERTS_UPDATED,
                AlertEvents.ACTION_ADMIN_DATA_SYNCED -> bindLogs()
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val layout = if (StandaloneUi.isUserStandalone(requireContext())) {
            R.layout.fragment_admin_logs_standalone
        } else {
            R.layout.fragment_admin_logs
        }
        return inflater.inflate(layout, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        logAdapter = LogEntriesAdapter(useTimelineLayout = false)
        view.findViewById<RecyclerView>(R.id.rvLogEntries).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = logAdapter
        }
        btnLogsPrev = view.findViewById(R.id.btnLogsPrev)
        btnLogsNext = view.findViewById(R.id.btnLogsNext)
        tvLogsPageInfo = view.findViewById(R.id.tvLogsPageInfo)
        setupPager()
        bindLogs()

        view.findViewById<MaterialButton>(R.id.btnPrintLogsReport).setOnClickListener {
            printLogsReport()
        }
    }

    override fun onStart() {
        super.onStart()
        if (!connectionReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(AlertEvents.ACTION_CONNECTION_STATE_CHANGED)
                addAction(AlertEvents.ACTION_ALERTS_UPDATED)
                addAction(AlertEvents.ACTION_ADMIN_DATA_SYNCED)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requireContext().registerReceiver(connectionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                requireContext().registerReceiver(connectionReceiver, filter)
            }
            connectionReceiverRegistered = true
        }
    }

    override fun onStop() {
        super.onStop()
    }

    override fun onDestroyView() {
        if (connectionReceiverRegistered) {
            try {
                requireContext().unregisterReceiver(connectionReceiver)
            } catch (_: Exception) {}
            connectionReceiverRegistered = false
        }
        super.onDestroyView()
    }

    override fun onResume() {
        super.onResume()
        bindLogs()
    }

    fun refresh() {
        bindLogs()
    }

    /** Logs = all alerts admin receives (API + local), sorted newest first; each row includes user when from API. */
    private fun bindLogs() {
        if (StandaloneUi.isUserStandalone(requireContext()) && AppRole.isUser(requireContext())) {
            AdminDemoData.seedStandaloneDemoLogsIfNeeded(requireContext())
        }
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        val alertDb = AlertDb(requireContext())
        val apiAlerts = AdminDemoData.getApiAlerts()
        val localAlerts = alertDb.getAllAlerts()
        val combined = (apiAlerts + localAlerts).distinctBy { it.id }.sortedByDescending { it.receivedAt }
        val entries = combined.map { alert ->
            alertToLogEntry(alert, dateFormat, timeFormat)
        }
        updatePager(entries.size)
        logAdapter.entries = getPagedEntries(entries)
    }

    private fun setupPager() {
        btnLogsPrev.setOnClickListener {
            if (pageIndex > 0) {
                pageIndex -= 1
                bindLogs()
            }
        }
        btnLogsNext.setOnClickListener {
            val totalPages = getTotalPages(getTotalCount())
            if (pageIndex < totalPages - 1) {
                pageIndex += 1
                bindLogs()
            }
        }
    }

    private fun getTotalCount(): Int {
        val alertDb = AlertDb(requireContext())
        val apiAlerts = AdminDemoData.getApiAlerts()
        val localAlerts = alertDb.getAllAlerts()
        return (apiAlerts + localAlerts).distinctBy { it.id }.size
    }

    private fun getTotalPages(totalItems: Int): Int {
        if (totalItems <= 0) return 1
        return ((totalItems - 1) / pageSize) + 1
    }

    private fun updatePager(totalItems: Int) {
        val totalPages = getTotalPages(totalItems)
        if (pageIndex >= totalPages) pageIndex = totalPages - 1
        val showPage = pageIndex + 1
        tvLogsPageInfo.text = "Page $showPage/$totalPages"
        btnLogsPrev.isEnabled = pageIndex > 0
        btnLogsNext.isEnabled = pageIndex < totalPages - 1
    }

    private fun getPagedEntries(entries: List<LogEntry>): List<LogEntry> {
        if (entries.isEmpty()) return entries
        val from = (pageIndex * pageSize).coerceAtLeast(0)
        if (from >= entries.size) return emptyList()
        val to = (from + pageSize).coerceAtMost(entries.size)
        return entries.subList(from, to)
    }

    private fun alertToLogEntry(alert: AlertItem, dateFormat: SimpleDateFormat, timeFormat: SimpleDateFormat): LogEntry {
        val date = dateFormat.format(Date(alert.receivedAt))
        val time = timeFormat.format(Date(alert.receivedAt))
        val medicine = extractMedicineFromMessage(alert.message)
        val status = when {
            alert.type.contains("taken", true) || alert.message.contains("taken", true) -> "Taken"
            alert.type.contains("missed", true) || alert.message.contains("missed", true) -> "Missed"
            else -> "Alert"
        }
        val userName = alert.userName.ifEmpty { "—" }
        return LogEntry(date = date, time = time, medicineName = medicine, status = status, source = "System", userName = userName)
    }

    private fun extractMedicineFromMessage(message: String): String {
        val t = message.indexOf(":")
        if (t >= 0) {
            val after = message.substring(t + 1).trim()
            val from = after.indexOf(" from ")
            return if (from > 0) after.substring(0, from).trim() else after.take(40)
        }
        return message.take(40)
    }

    private fun printLogsReport() {
        if (StandaloneUi.isUserStandalone(requireContext()) && AppRole.isUser(requireContext())) {
            AdminDemoData.seedStandaloneDemoLogsIfNeeded(requireContext())
        }
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        val apiAlerts = AdminDemoData.getApiAlerts()
        val localAlerts = AlertDb(requireContext()).getAllAlerts()
        val combined = (apiAlerts + localAlerts).distinctBy { it.id }.sortedByDescending { it.receivedAt }
        val entries: List<LogEntry> = combined.map { alertToLogEntry(it, dateFormat, timeFormat) }

        val title = "Curax Logs / Alert History"
        val html = buildString {
            append("<html><body style='font-family:sans-serif;padding:24px'>")
            append("<h2>$title</h2>")
            append("<h3>Log (Date, Time, Medicine, Status, Source, User)</h3>")
            append("<table border='1' cellpadding='8' cellspacing='0' style='border-collapse:collapse'>")
            append("<tr style='background:#eee'><th>Date</th><th>Time</th><th>Medicine</th><th>Status</th><th>Source</th><th>User</th></tr>")
            entries.forEach { e ->
                append("<tr><td>${e.date}</td><td>${e.time}</td><td>${e.medicineName}</td><td>${e.status}</td><td>${e.source}</td><td>${e.userName.ifEmpty { "—" }}</td></tr>")
            }
            append("</table></body></html>")
        }

        val webView = WebView(requireContext())
        webView.loadDataWithBaseURL(null, html, "text/HTML", "UTF-8", null)
        val printManager = requireContext().getSystemService(Context.PRINT_SERVICE) as PrintManager
        printManager.print("Curax Logs Report", webView.createPrintDocumentAdapter("Curax Logs Report"), PrintAttributes.Builder().build())
    }
}
