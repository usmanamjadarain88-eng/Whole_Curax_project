package com.curax.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class AdminReportsFragment : Fragment() {

    private var connectionReceiverRegistered = false
    private val connectionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                AlertEvents.ACTION_CONNECTION_STATE_CHANGED,
                AlertEvents.ACTION_ALERTS_UPDATED,
                AlertEvents.ACTION_ADMIN_DATA_SYNCED -> view?.let { bindMetrics(it) }
            }
        }
    }

    /** Linked users (id, name) for per-user reports. When empty, report shows dashboard data. */
    private var linkedUsers = listOf<Pair<String, String>>()
    private var selectedReportIndex = 0
    /** When linkedUsers non-empty: report data for selected user. */
    private var reportMedicines = listOf<AdminDemoData.Medicine>()
    private var reportAlerts = listOf<AlertItem>()
    private var reportUserName = ""

    companion object {
        private val http = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val layout = if (StandaloneUi.isUserStandalone(requireContext())) {
            R.layout.fragment_admin_reports_standalone
        } else {
            R.layout.fragment_admin_reports
        }
        return inflater.inflate(layout, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindMetrics(view)
        fetchLinkedUsersAndMaybeSelectFirst()

        if (!isUserApp()) {
            view.findViewById<MaterialButton>(R.id.btnReportPrev).setOnClickListener {
                if (linkedUsers.isEmpty()) return@setOnClickListener
                selectedReportIndex = (selectedReportIndex - 1).let { if (it < 0) linkedUsers.size - 1 else it }
                fetchUserReportData(linkedUsers[selectedReportIndex].first, linkedUsers[selectedReportIndex].second)
            }
            view.findViewById<MaterialButton>(R.id.btnReportNext).setOnClickListener {
                if (linkedUsers.isEmpty()) return@setOnClickListener
                selectedReportIndex = (selectedReportIndex + 1) % linkedUsers.size
                fetchUserReportData(linkedUsers[selectedReportIndex].first, linkedUsers[selectedReportIndex].second)
            }
        }

        view.findViewById<MaterialButton>(R.id.btnExportCsv).setOnClickListener {
            try {
                shareFile(writeCsv(), "text/csv")
            } catch (e: Exception) {
                CuraxFeedback.warn(this, "CSV export failed")
            }
        }
        view.findViewById<MaterialButton>(R.id.btnExportPdf).setOnClickListener {
            try {
                val name = if (reportUserName.isNotEmpty()) "curax_report_${reportUserName.replace(" ", "_")}.pdf" else "curax_admin_report.pdf"
                shareFile(writePdf(name, buildReportText()), "application/pdf")
            } catch (e: Exception) {
                CuraxFeedback.warn(this, "PDF export failed")
            }
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
            try { requireContext().unregisterReceiver(connectionReceiver) } catch (_: Exception) {}
            connectionReceiverRegistered = false
        }
        super.onDestroyView()
    }

    override fun onResume() {
        super.onResume()
        view?.let { bindMetrics(it) }
    }

    fun refresh() {
        view?.let { bindMetrics(it) }
        fetchLinkedUsersAndMaybeSelectFirst()
    }

    private fun fetchLinkedUsersAndMaybeSelectFirst() {
        if (isUserApp()) {
            view?.findViewById<View>(R.id.panelReportUserNav)?.visibility = View.GONE
            reportMedicines = emptyList()
            reportAlerts = emptyList()
            view?.let { bindMetrics(it) }
            return
        }
        val prefs = Prefs(requireContext())
        val base = prefs.centralApiUrl.trim().removeSuffix("/")
        val accessCode = prefs.adminAccessCode.trim()
        if (base.isEmpty() || accessCode.isEmpty()) return
        Thread {
            try {
                val url = "$base/admin/linked-users?access_code=${URLEncoder.encode(accessCode, "UTF-8")}"
                val res = http.newCall(Request.Builder().url(url).get().build()).execute()
                if (!res.isSuccessful) return@Thread
                val body = res.body?.string() ?: "{}"
                val data = JSONObject(body)
                val arr = data.optJSONArray("users") ?: org.json.JSONArray()
                val list = mutableListOf<Pair<String, String>>()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optString("id", "").trim()
                    val nameRaw = o.optString("name", "").trim()
                    val email = o.optString("email", "").trim()
                    val display = when {
                        nameRaw.isNotEmpty() && !nameRaw.equals("null", ignoreCase = true) -> nameRaw
                        email.contains("@") -> email.substringBefore("@").trim()
                        else -> nameRaw
                    }.ifEmpty { "User" }
                    if (id.isNotEmpty()) list.add(id to display)
                }
                if (list.isNotEmpty()) {
                    AdminLinkedUserDirectory.ingestUsersJsonArray(arr)
                } else {
                    AdminLinkedUserDirectory.ingestFromUiModels(
                        demoReportUserPairs().mapIndexed { index, (id, name) ->
                            AdminLinkedUserUiModel(
                                userId = id,
                                name = name,
                                email = "",
                                desktopLinked = true,
                                isDemo = true,
                                userDisplayMode = if (index % 2 == 0) "default" else "standalone",
                            )
                        },
                    )
                }
                activity?.runOnUiThread {
                    val finalList = if (list.isEmpty()) demoReportUserPairs() else list
                    linkedUsers = finalList
                    view?.findViewById<View>(R.id.panelReportUserNav)?.visibility =
                        if (finalList.isEmpty()) View.GONE else View.VISIBLE
                    if (finalList.isNotEmpty()) {
                        selectedReportIndex = 0
                        view?.findViewById<TextView>(R.id.tvReportForUser)?.text =
                            "Report for: Loading… (1 of ${finalList.size})"
                        fetchUserReportData(finalList[0].first, finalList[0].second)
                    } else {
                        bindMetrics(view!!)
                    }
                }
            } catch (_: Exception) {}
        }.start()
    }

    private fun demoReportUserPairs(): List<Pair<String, String>> = listOf(
        "demo_usman" to getString(R.string.admin_demo_name_usman),
        "demo_hamad" to getString(R.string.admin_demo_name_hamad),
        "demo_abdullah" to getString(R.string.admin_demo_name_abdullah),
        "demo_zara" to getString(R.string.admin_demo_name_zara),
    )

    private fun fetchUserReportData(userId: String, userName: String) {
        if (userId.startsWith("demo_")) {
            val meds = AdminDemoData.adminPreviewMedicinesForReport(userId.hashCode())
            val alerts = AdminDemoData.adminPreviewAlertsForReport(userName)
            activity?.runOnUiThread {
                reportMedicines = meds
                reportAlerts = alerts
                reportUserName = userName
                val idx = selectedReportIndex + 1
                val total = linkedUsers.size.coerceAtLeast(1)
                view?.findViewById<TextView>(R.id.tvReportForUser)?.text =
                    "Report for: $userName ($idx of $total)"
                view?.let { bindMetrics(it) }
            }
            return
        }
        val prefs = Prefs(requireContext())
        val base = prefs.centralApiUrl.trim().removeSuffix("/")
        val accessCode = prefs.adminAccessCode.trim()
        if (base.isEmpty() || accessCode.isEmpty()) return
        Thread {
            try {
                val url = "$base/admin/data?access_code=${URLEncoder.encode(accessCode, "UTF-8")}&act_as_user_id=${URLEncoder.encode(userId, "UTF-8")}"
                val res = http.newCall(Request.Builder().url(url).get().build()).execute()
                if (!res.isSuccessful) return@Thread
                val body = res.body?.string() ?: "{}"
                val data = JSONObject(body)
                val medicinesArr = data.optJSONArray("medicines") ?: org.json.JSONArray()
                val alertsArr = data.optJSONArray("alerts") ?: org.json.JSONArray()
                val medicinesList = mutableListOf<Map<String, Any?>>()
                for (i in 0 until medicinesArr.length()) {
                    val o = medicinesArr.optJSONObject(i) ?: continue
                    val m = mutableMapOf<String, Any?>()
                    m["name"] = o.optString("name", "")
                    m["box_id"] = o.optString("box_id", "B1")
                    m["quantity"] = o.optInt("quantity", 0)
                    m["times"] = try { o.optJSONArray("times")?.let { (0 until it.length()).map { j -> it.optString(j) } } ?: emptyList<String>() } catch (_: Exception) { emptyList<String>() }
                    m["exact_time"] = o.optString("exact_time", "08:00")
                    m["dose_per_day"] = o.optInt("dose_per_day", 1)
                    m["expiry"] = o.optString("expiry", "")
                    m["low_stock"] = o.optInt("low_stock", 5)
                    medicinesList.add(m)
                }
                val meds = AdminDemoData.fromApiMedicines(medicinesList)
                val alerts = AdminDemoData.fromApiAlerts(alertsArr)
                activity?.runOnUiThread {
                    reportMedicines = meds
                    reportAlerts = alerts
                    reportUserName = userName
                    val idx = selectedReportIndex + 1
                    val total = linkedUsers.size
                    view?.findViewById<TextView>(R.id.tvReportForUser)?.text = "Report for: $userName ($idx of $total)"
                    bindMetrics(view!!)
                }
            } catch (_: Exception) {
                activity?.runOnUiThread {
                    reportMedicines = emptyList()
                    reportAlerts = emptyList()
                    reportUserName = userName
                    bindMetrics(view!!)
                }
            }
        }.start()
    }

    // ── Data binding ────────────────────────────────────────────────

    private fun bindMetrics(view: View) {
        val useReportData = (linkedUsers.isNotEmpty() && reportMedicines.isNotEmpty()) ||
            (isUserApp() && reportMedicines.isNotEmpty())
        val medicines = if (useReportData) reportMedicines else AdminDemoData.medicines
        val alerts = if ((linkedUsers.isNotEmpty() && reportAlerts.isNotEmpty()) ||
            (isUserApp() && reportAlerts.isNotEmpty())
        ) reportAlerts else {
            val alertDb = AlertDb(requireContext())
            AdminDemoData.getApiAlerts() + alertDb.getAllAlerts()
        }

        val takenAlerts = alerts.filter { it.type.contains("taken", true) || it.message.contains("taken", true) }
        val missedAlerts = alerts.filter { it.type.contains("missed", true) || it.message.contains("missed", true) }
        val taken = takenAlerts.size
        val missed = missedAlerts.size
        val total = taken + missed
        val adherencePct = if (total > 0) (taken * 100 / total) else 0

        val todayStart = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val todayAlerts = alerts.filter { it.receivedAt >= todayStart }
        val todayTaken = todayAlerts.count { it.type.contains("taken", true) || it.message.contains("taken", true) }
        val todayMissed = todayAlerts.count { it.type.contains("missed", true) || it.message.contains("missed", true) }

        // Subtitle
        val sub = if (reportUserName.isNotEmpty()) "Report for $reportUserName" else if (isUserApp()) "User dashboard" else "Admin dashboard"
        view.findViewById<TextView>(R.id.tvReportSubtitle).text =
            "$sub  |  ${medicines.size} medicines  |  Today: $todayTaken taken, $todayMissed missed"

        // Adherence hero
        view.findViewById<TextView>(R.id.tvMetricAdherence).text =
            if (total > 0) "${adherencePct}%" else "--"
        view.findViewById<TextView>(R.id.tvAdherenceDetail).text =
            if (total > 0) "$taken taken out of $total doses" else "No dose data yet"
        view.findViewById<ProgressBar>(R.id.progressAdherence).progress = adherencePct

        // Taken/missed bar weights
        val takenW = if (total > 0) taken.toFloat() else 1f
        val missedW = if (total > 0) missed.toFloat().coerceAtLeast(0.05f) else 1f
        val barTaken = view.findViewById<View>(R.id.barTakenSegment)
        val barMissed = view.findViewById<View>(R.id.barMissedSegment)
        (barTaken.layoutParams as LinearLayout.LayoutParams).weight = takenW
        (barMissed.layoutParams as LinearLayout.LayoutParams).weight = missedW
        barTaken.requestLayout()

        view.findViewById<TextView>(R.id.tvMetricTaken).text = "Taken: $taken"
        view.findViewById<TextView>(R.id.tvMetricMissed).text = "Missed: $missed"

        // Most used + total alerts
        view.findViewById<TextView>(R.id.tvMetricMostUsed).text = findMostFrequentMedicine(takenAlerts, medicines)
        view.findViewById<TextView>(R.id.tvMetricTotalAlerts).text = total.toString()

        // Time distribution bars
        val (mPct, aPct, nPct) = computeTimeDistribution(alerts)
        setBarPercent(view.findViewById(R.id.barMorning), mPct)
        setBarPercent(view.findViewById(R.id.barAfternoon), aPct)
        setBarPercent(view.findViewById(R.id.barNight), nPct)
        view.findViewById<TextView>(R.id.tvMorningPct).text = "${mPct}%"
        view.findViewById<TextView>(R.id.tvAfternoonPct).text = "${aPct}%"
        view.findViewById<TextView>(R.id.tvNightPct).text = "${nPct}%"
    }

    private fun setBarPercent(bar: View, pct: Int) {
        val parent = bar.parent as FrameLayout
        bar.post {
            val params = bar.layoutParams
            params.width = (parent.width * pct.coerceIn(0, 100) / 100f).toInt().coerceAtLeast(if (pct > 0) 4 else 0)
            bar.layoutParams = params
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private fun findMostFrequentMedicine(takenAlerts: List<AlertItem>, medicines: List<AdminDemoData.Medicine> = AdminDemoData.medicines): String {
        if (takenAlerts.isEmpty()) {
            return if (medicines.isNotEmpty()) medicines.maxByOrNull { it.dosePerDay }?.name ?: "-" else "-"
        }
        val freq = mutableMapOf<String, Int>()
        for (alert in takenAlerts) {
            val name = extractMedNameFromAlert(alert.message)
            if (name.isNotEmpty()) freq[name] = (freq[name] ?: 0) + 1
        }
        return freq.maxByOrNull { it.value }?.key ?: "-"
    }

    private fun extractMedNameFromAlert(message: String): String {
        val colonIdx = message.indexOf(":")
        if (colonIdx >= 0) {
            val after = message.substring(colonIdx + 1).trim()
            val fromIdx = after.indexOf(" from ", ignoreCase = true)
            return if (fromIdx > 0) after.substring(0, fromIdx).trim() else after.take(40).trim()
        }
        return message.take(40).trim()
    }

    private fun computeTimeDistribution(alerts: List<AlertItem>): Triple<Int, Int, Int> {
        if (alerts.isEmpty()) return Triple(0, 0, 0)
        var morning = 0; var afternoon = 0; var night = 0
        val cal = Calendar.getInstance()
        for (alert in alerts) {
            cal.timeInMillis = alert.receivedAt
            when (cal.get(Calendar.HOUR_OF_DAY)) {
                in 5..11 -> morning++
                in 12..17 -> afternoon++
                else -> night++
            }
        }
        val t = (morning + afternoon + night).coerceAtLeast(1)
        return Triple(morning * 100 / t, afternoon * 100 / t, night * 100 / t)
    }

    // ── Export ───────────────────────────────────────────────────────

    private fun buildReportText(): String {
        val medicines = if ((linkedUsers.isNotEmpty() && reportMedicines.isNotEmpty()) ||
            (isUserApp() && reportMedicines.isNotEmpty())
        ) reportMedicines else AdminDemoData.medicines
        val alerts = if ((linkedUsers.isNotEmpty() && reportAlerts.isNotEmpty()) ||
            (isUserApp() && reportAlerts.isNotEmpty())
        ) reportAlerts else {
            AlertDb(requireContext()).getAllAlerts() + AdminDemoData.getApiAlerts()
        }
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        val takenAlerts = alerts.filter { it.type.contains("taken", true) || it.message.contains("taken", true) }
        val taken = takenAlerts.size
        val missed = alerts.count { it.type.contains("missed", true) || it.message.contains("missed", true) }
        val total = taken + missed
        val adherencePct = if (total > 0) (taken * 100 / total) else 0
        val mostUsedName = findMostFrequentMedicine(takenAlerts, medicines)
        val (mPct, aPct, nPct) = computeTimeDistribution(alerts)

        val medLines = medicines.joinToString("\n") {
            "- ${it.name} | stock ${it.stock} | dose/day ${it.dosePerDay} | time ${it.exactTime} | ${it.status} | ${it.box}"
        }
        val logLines = alerts.take(100).joinToString("\n") { alert ->
            val time = dateFormat.format(Date(alert.receivedAt))
            "[$time] ${alert.type}: ${alert.message}"
        }
        val title = if (reportUserName.isNotEmpty()) "Curax Report: $reportUserName" else "Curax Admin Report"
        return """
            $title
            Generated: ${dateFormat.format(Date())}

            Key Metrics
            - Adherence: ${adherencePct}% ($taken taken / $total total)
            - Most used: $mostUsedName
            - Time distribution: Morning ${mPct}% | Afternoon ${aPct}% | Night ${nPct}%

            Medicine Inventory
            $medLines

            Recent Activity (last ${alerts.take(100).size} alerts)
            $logLines
        """.trimIndent()
    }

    private fun writeCsv(): File {
        val exportsDir = File(requireContext().cacheDir, "exports")
        if (!exportsDir.exists()) exportsDir.mkdirs()
        val name = if (reportUserName.isNotEmpty()) "curax_report_${reportUserName.replace(" ", "_")}.csv" else "curax_admin_report.csv"
        val file = File(exportsDir, name)
        val alerts = if ((linkedUsers.isNotEmpty() && reportAlerts.isNotEmpty()) ||
            (isUserApp() && reportAlerts.isNotEmpty())
        ) reportAlerts else {
            AlertDb(requireContext()).getAllAlerts() + AdminDemoData.getApiAlerts()
        }
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        val content = "ReceivedAt,Type,Message,User\n" + alerts.joinToString("\n") {
            "${dateFormat.format(Date(it.receivedAt))},${it.type},${it.message.replace(",", " ")},${it.userName.ifEmpty { "—" }}"
        }
        file.writeText(content)
        return file
    }

    private fun writePdf(name: String, text: String): File {
        val exportsDir = File(requireContext().cacheDir, "exports")
        if (!exportsDir.exists()) exportsDir.mkdirs()
        val file = File(exportsDir, name)
        val pdf = PdfDocument()
        val paint = Paint().apply { textSize = 12f }
        val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create()
        val page = pdf.startPage(pageInfo)
        var y = 40f
        text.lines().forEach { line ->
            page.canvas.drawText(line, 32f, y, paint)
            y += 18f
            if (y > 800f) return@forEach
        }
        pdf.finishPage(page)
        FileOutputStream(file).use { pdf.writeTo(it) }
        pdf.close()
        return file
    }

    private fun shareFile(file: File, mime: String) {
        val uri: Uri = FileProvider.getUriForFile(
            requireContext(), "${requireContext().packageName}.fileprovider", file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share report"))
    }

    private fun isUserApp(): Boolean = AppRole.isUser(requireContext())
}
