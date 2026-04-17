package com.curax.app

import android.content.Context
import android.os.Bundle
import android.print.PrintAttributes
import android.print.PrintManager
import android.view.Menu
import android.view.MenuItem
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Full-page active alerts + medicines summary from Health Hub pill tap. */
class StandaloneHealthHubAlertsReportActivity : AppCompatActivity() {

    private lateinit var bodyView: TextView
    private var reportPlain: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_health_hub_alerts_report)
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        bodyView = findViewById(R.id.tvReportBody)
        reportPlain = buildReportText()
        bodyView.text = reportPlain
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.health_hub_report_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_print_report) {
            printReport()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun buildReportText(): String {
        val meds = AdminDemoData.medicines
        val apiAlerts = AdminDemoData.getApiAlerts()
        val local = try {
            AlertDb(this).getAllAlerts()
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
        sb.appendLine("• Local inbox messages: ${local.size}")
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
        sb.appendLine("LOCAL INBOX")
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

    private fun printReport() {
        val esc = htmlEscape(reportPlain)
        val html = "<html><head><meta charset=\"utf-8\"/></head><body style=\"margin:16px;font-family:sans-serif;font-size:13px;\"><pre style=\"white-space:pre-wrap;word-wrap:break-word;\">$esc</pre></body></html>"
        val wv = WebView(this)
        wv.settings.javaScriptEnabled = false
        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                val mgr = getSystemService(Context.PRINT_SERVICE) as PrintManager
                val adapter = view.createPrintDocumentAdapter("CuraxAlertsReport")
                mgr.print("Curax — Alerts report", adapter, PrintAttributes.Builder().build())
            }
        }
        wv.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
    }

    private fun htmlEscape(s: String): String = buildString(s.length + 16) {
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
}
