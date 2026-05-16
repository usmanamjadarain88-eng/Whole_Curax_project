package com.curax.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class SettingsActivity : AppCompatActivity() {

    private lateinit var btnSetPin: MaterialButton
    private lateinit var btnAutoLock: MaterialButton

    private lateinit var prefs: Prefs
    /** Only needed for export; lazy avoids SQLite open on every Settings visit (standalone Alert settings path). */
    private val alertDb by lazy { AlertDb(this) }
    private lateinit var localUserStore: LocalUserStore
    /** Avoid rebuilding connection rows on every [onResume] (back from child screens feels sluggish). */
    private var lastConnectionSectionsSig: String = ""

    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    private val autoLockValues = intArrayOf(300, 600, 1800)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        prefs = Prefs(this)
        localUserStore = LocalUserStore(this)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.title = if (AppRole.isUser(this)) {
            getString(R.string.settings_screen_user_title)
        } else {
            getString(R.string.settings_screen_title)
        }
        toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        btnSetPin = findViewById(R.id.btnSetPin)
        btnAutoLock = findViewById(R.id.btnAutoLock)

        refreshConnectionSectionsIfNeeded()
        btnSetPin.setOnClickListener {
            startActivity(Intent(this, SetPinActivity::class.java))
        }
        btnAutoLock.setOnClickListener { showAutoLockDialog() }

        findViewById<MaterialButton>(R.id.btnExportHistory).setOnClickListener { exportChatHistory() }
        findViewById<MaterialButton>(R.id.btnAppInfo).setOnClickListener { showAppInfo() }
        findViewById<MaterialButton>(R.id.btnHelp).setOnClickListener {
            startActivity(Intent(this, HelpActivity::class.java))
        }
    }

    private fun refreshConnectionSectionsIfNeeded() {
        val sig = "${prefs.linkedAdminId}|${StandaloneUi.isUserStandalone(this)}|${localUserStore.role}"
        if (sig == lastConnectionSectionsSig) return
        lastConnectionSectionsSig = sig
        setupConnectionCodeSections()
    }

    private fun setupConnectionCodeSections() {
        val btnMyConnectionCode = findViewById<MaterialButton>(R.id.btnMyConnectionCode)
        val sectionConnectToAdmin = findViewById<android.widget.TextView>(R.id.sectionConnectToAdmin)
        val connectionCodeInputLayout = findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.connectionCodeInputLayout)
        val btnLinkToAdmin = findViewById<MaterialButton>(R.id.btnLinkToAdmin)
        val isAdmin = localUserStore.role == LocalUserStore.ROLE_ADMIN
        if (isAdmin) {
            btnMyConnectionCode.visibility = android.view.View.VISIBLE
            btnMyConnectionCode.setOnClickListener {
                val code = prefs.connectionCode.trim()
                if (code.isEmpty()) {
                    AlertDialog.Builder(this)
                        .setTitle(getString(R.string.my_connection_code))
                        .setMessage(getString(R.string.connection_code_not_available))
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                    return@setOnClickListener
                }
                val content = LayoutInflater.from(this).inflate(R.layout.dialog_my_connection_code, null, false)
                content.findViewById<TextView>(R.id.tvDialogConnectionCode).text = code
                val dlg = AlertDialog.Builder(this)
                    .setTitle(getString(R.string.my_connection_code))
                    .setView(content)
                    .setPositiveButton(android.R.string.ok, null)
                    .create()
                content.findViewById<MaterialButton>(R.id.btnDialogCopyConnectionCode).setOnClickListener {
                    (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText("", code))
                    CuraxFeedback.success(this@SettingsActivity, getString(R.string.admin_hub_code_copied))
                }
                dlg.show()
            }
            findViewById<MaterialButton>(R.id.btnDesktopLinkingCode).visibility = android.view.View.VISIBLE
            findViewById<MaterialButton>(R.id.btnDesktopLinkingCode).setOnClickListener { showDesktopLinkingCodeFlow() }
            sectionConnectToAdmin.visibility = android.view.View.GONE
            connectionCodeInputLayout.visibility = android.view.View.GONE
            btnLinkToAdmin.visibility = android.view.View.GONE
            findViewById<MaterialButton>(R.id.btnUserDesktopLinkCode).visibility = View.GONE
        } else {
            // Users link at sign-up via connection code; no need for Link to admin in Settings.
            btnMyConnectionCode.visibility = android.view.View.GONE
            sectionConnectToAdmin.visibility = android.view.View.GONE
            connectionCodeInputLayout.visibility = android.view.View.GONE
            btnLinkToAdmin.visibility = android.view.View.GONE
            val btnUserDesktopLink = findViewById<MaterialButton>(R.id.btnUserDesktopLinkCode)
            if (StandaloneUi.isUserStandalone(this)) {
                // Standalone mode only: on-device alert prefs (default Curax user app has no desktop linking).
                btnUserDesktopLink.visibility = View.VISIBLE
                btnUserDesktopLink.setText(R.string.standalone_alert_settings)
                btnUserDesktopLink.setOnClickListener {
                    startActivity(Intent(this, StandaloneLocalAlertSettingsActivity::class.java))
                    overridePendingTransition(0, 0)
                }
            } else {
                btnUserDesktopLink.visibility = View.GONE
            }
        }
    }

    private fun showAppInfo() {
        val botId = prefs.id.ifEmpty { "—" }
        val apiKey = prefs.apiKey.ifEmpty { "—" }
        val fcm = prefs.fcmToken.ifEmpty { "—" }
        val msg = buildString {
            append("Bot ID: ").append(botId).append("\n\n")
            append("API Key: ").append(apiKey).append("\n\n")
            append("FCM Token:\n").append(fcm)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.my_app_info_title))
            .setMessage(msg)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(R.string.copy) { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("app_info", msg))
                CuraxFeedback.success(this, getString(R.string.app_info_copied))
            }
            .show()
    }

    /** Admin: show instructions first, then on Create code call API and show code. */
    private fun showDesktopLinkingCodeFlow() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.desktop_linking_code))
            .setMessage(getString(R.string.desktop_linking_code_instructions))
            .setPositiveButton(getString(R.string.create_code)) { _, _ -> createAdminDesktopLinkCode() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun createAdminDesktopLinkCode() {
        val base = prefs.centralApiUrl.trim().removeSuffix("/")
        val accessCode = prefs.adminAccessCode.trim()
        if (base.isEmpty() || accessCode.isEmpty()) {
            CuraxFeedback.warn(this, "Not signed in as admin")
            return
        }
        val btn = findViewById<MaterialButton>(R.id.btnDesktopLinkingCode)
        btn.isEnabled = false
        Thread {
            try {
                val body = JSONObject().put("access_code", accessCode).toString()
                    .toRequestBody("application/json".toMediaType())
                val req = Request.Builder()
                    .url("$base/admin/create-desktop-link-code")
                    .post(body)
                    .build()
                val res = http.newCall(req).execute()
                runOnUiThread {
                    btn.isEnabled = true
                    if (!isFinishing) handleAdminDesktopLinkCodeResponse(res)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    btn.isEnabled = true
                    if (!isFinishing) CuraxFeedback.warn(this, "Error: ${e.message}")
                }
            }
        }.start()
    }

    private fun handleAdminDesktopLinkCodeResponse(response: okhttp3.Response) {
        if (response.isSuccessful) {
            val body = response.body?.string() ?: "{}"
            val data = try { JSONObject(body) } catch (_: Exception) { JSONObject() }
            val code = data.optString("code", "").trim()
            val expiresIn = data.optInt("expires_in", 600)
            val adminName = data.optString("admin_name", "").trim()
            if (code.isEmpty()) {
                CuraxFeedback.warn(this, "No code returned")
                return
            }
            val minutes = (expiresIn / 60).coerceAtLeast(1)
            val nameLine = if (adminName.isNotEmpty()) {
                getString(R.string.admin_desktop_link_result_admin_line, adminName)
            } else {
                ""
            }
            val msg = getString(R.string.admin_desktop_link_result_message_core, code, minutes) + nameLine
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.desktop_linking_code))
                .setMessage(msg)
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(R.string.copy) { _, _ ->
                    cm?.setPrimaryClip(ClipData.newPlainText("desktop_link_code", code))
                    CuraxFeedback.success(this, getString(R.string.admin_hub_code_copied))
                }
                .show()
        } else {
            val body = response.body?.string().orEmpty()
            val serverMsg = try {
                JSONObject(body).optString("message", "").trim()
            } catch (_: Exception) {
                ""
            }
            val detail = when (response.code) {
                404 -> serverMsg.ifEmpty {
                    getString(R.string.desktop_link_code_failed_404)
                }
                503 -> serverMsg.ifEmpty { getString(R.string.request_failed) }
                else -> serverMsg.ifEmpty {
                    getString(R.string.desktop_link_code_failed_http, response.code)
                }
            }
            CuraxFeedback.warn(this, detail, long = true)
        }
    }

    override fun onResume() {
        super.onResume()
        val hasPin = prefs.appPin.isNotEmpty() || (localUserStore.pinEnabled && localUserStore.pinCode.isNotEmpty())
        btnSetPin.text = if (hasPin) getString(R.string.change_pin) else getString(R.string.set_pin)
        btnAutoLock.text = getAutoLockButtonText(prefs.autoLockSeconds)
        refreshConnectionSectionsIfNeeded()
    }

    private fun showAutoLockDialog() {
        val labels = arrayOf(
            getString(R.string.auto_lock_5m_label),
            getString(R.string.auto_lock_10m_label),
            getString(R.string.auto_lock_30m_label)
        )
        val currentIndex = autoLockValues.indexOf(prefs.autoLockSeconds).takeIf { it >= 0 } ?: 0

        AlertDialog.Builder(this)
            .setTitle(R.string.auto_lock_title)
            .setSingleChoiceItems(labels, currentIndex) { dialog, which ->
                prefs.autoLockSeconds = autoLockValues[which]
                btnAutoLock.text = getAutoLockButtonText(prefs.autoLockSeconds)
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun getAutoLockButtonText(seconds: Int): String {
        return when (seconds) {
            300 -> getString(R.string.auto_lock_selected_5m)
            600 -> getString(R.string.auto_lock_selected_10m)
            1800 -> getString(R.string.auto_lock_selected_30m)
            else -> getString(R.string.auto_lock_selected_5m)
        }
    }

    private fun exportChatHistory() {
        val alerts = alertDb.getAllAlerts().sortedBy { it.receivedAt }
        if (alerts.isEmpty()) {
            CuraxFeedback.warn(this, getString(R.string.no_alerts_to_export))
            return
        }

        val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val lines = buildString {
            append("Curax Chat History")
            append("\n\n")
            alerts.forEach { alert ->
                append("[")
                append(format.format(Date(alert.receivedAt)))
                append("] ")
                append(alert.type.uppercase(Locale.getDefault()))
                append(": ")
                append(alert.message)
                append("\n")
            }
        }

        val exportDir = File(cacheDir, "exports").apply { mkdirs() }
        val file = File(exportDir, "curax_chat_history_${System.currentTimeMillis()}.txt")
        file.writeText(lines)

        val uri = FileProvider.getUriForFile(this, "${applicationContext.packageName}.fileprovider", file)
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.export_subject))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(sendIntent, getString(R.string.export_chat_history)))
    }
}
