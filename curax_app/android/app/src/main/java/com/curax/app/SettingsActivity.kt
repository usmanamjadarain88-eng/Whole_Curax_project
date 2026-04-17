package com.curax.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
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
    private lateinit var alertDb: AlertDb
    private lateinit var localUserStore: LocalUserStore

    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    private val autoLockValues = intArrayOf(300, 600, 1800)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        prefs = Prefs(this)
        alertDb = AlertDb(this)
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

        val cardPinSecurityBanner = findViewById<MaterialCardView>(R.id.cardPinSecurityBanner)
        val btnPinBannerAction = findViewById<MaterialButton>(R.id.btnPinBannerAction)
        val btnPinBannerDismiss = findViewById<MaterialButton>(R.id.btnPinBannerDismiss)
        fun refreshPinSecurityBanner() {
            val show = AppRole.isUser(this) && prefs.appPin.isEmpty() && !prefs.pinSettingsBannerDismissed
            cardPinSecurityBanner.visibility = if (show) View.VISIBLE else View.GONE
        }
        refreshPinSecurityBanner()
        btnPinBannerAction.setOnClickListener {
            startActivity(Intent(this, SetPinActivity::class.java))
        }
        btnPinBannerDismiss.setOnClickListener {
            prefs.pinSettingsBannerDismissed = true
            refreshPinSecurityBanner()
        }

        setupConnectionCodeSections()
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

    private fun setupConnectionCodeSections() {
        val btnMyConnectionCode = findViewById<MaterialButton>(R.id.btnMyConnectionCode)
        val sectionConnectToAdmin = findViewById<android.widget.TextView>(R.id.sectionConnectToAdmin)
        val connectionCodeInputLayout = findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.connectionCodeInputLayout)
        val btnLinkToAdmin = findViewById<MaterialButton>(R.id.btnLinkToAdmin)
        val tvConnectedToAdmin = findViewById<android.widget.TextView>(R.id.tvConnectedToAdmin)

        val isAdmin = localUserStore.role == LocalUserStore.ROLE_ADMIN
        if (isAdmin) {
            btnMyConnectionCode.visibility = android.view.View.VISIBLE
            btnMyConnectionCode.setOnClickListener {
                val code = prefs.connectionCode.trim()
                val message = if (code.isNotEmpty()) code else getString(R.string.connection_code_not_available)
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.my_connection_code))
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
            findViewById<MaterialButton>(R.id.btnDesktopLinkingCode).visibility = android.view.View.VISIBLE
            findViewById<MaterialButton>(R.id.btnDesktopLinkingCode).setOnClickListener { showDesktopLinkingCodeFlow() }
            sectionConnectToAdmin.visibility = android.view.View.GONE
            connectionCodeInputLayout.visibility = android.view.View.GONE
            btnLinkToAdmin.visibility = android.view.View.GONE
            tvConnectedToAdmin.visibility = android.view.View.GONE
            findViewById<MaterialButton>(R.id.btnUserDesktopLinkCode).visibility = View.GONE
        } else {
            // Users link at sign-up via connection code; no need for Link to admin in Settings.
            btnMyConnectionCode.visibility = android.view.View.GONE
            sectionConnectToAdmin.visibility = android.view.View.GONE
            connectionCodeInputLayout.visibility = android.view.View.GONE
            btnLinkToAdmin.visibility = android.view.View.GONE
            if (prefs.linkedAdminId.isNotEmpty()) {
                val adminName = prefs.linkedAdminName
                tvConnectedToAdmin.text = if (adminName.isNotEmpty()) "Connected to admin: $adminName" else getString(R.string.connected_to_admin)
                tvConnectedToAdmin.visibility = android.view.View.VISIBLE
                val btnUserDesktopLink = findViewById<MaterialButton>(R.id.btnUserDesktopLinkCode)
                btnUserDesktopLink.visibility = View.VISIBLE
                btnUserDesktopLink.setOnClickListener { showUserDesktopLinkCodeDialog() }
            } else {
                tvConnectedToAdmin.visibility = android.view.View.GONE
                findViewById<MaterialButton>(R.id.btnUserDesktopLinkCode).visibility = View.GONE
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
            .setTitle("My App Info")
            .setMessage(msg)
            .setPositiveButton("Copy") { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("app_info", msg))
                CuraxFeedback.success(this, "Copied")
            }
            .setNegativeButton(android.R.string.ok, null)
            .show()
    }

    /** User: show description dialog; on Confirm generate code. */
    private fun showUserDesktopLinkCodeDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.desktop_linking_code))
            .setMessage(getString(R.string.desktop_link_code_user_instructions))
            .setPositiveButton(getString(R.string.create_code)) { _, _ -> createUserDesktopLinkCode() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun createUserDesktopLinkCode() {
        val base = prefs.centralApiUrl.trim().removeSuffix("/")
        if (base.isEmpty()) {
            CuraxFeedback.warn(this, "Server URL not set")
            return
        }
        val botId = prefs.id.trim()
        val apiKey = prefs.apiKey.trim()
        if (botId.isEmpty() || apiKey.isEmpty()) {
            CuraxFeedback.warn(this, "Not linked to an admin")
            return
        }
        val btn = findViewById<MaterialButton>(R.id.btnUserDesktopLinkCode)
        btn.isEnabled = false
        Thread {
            try {
                val body = JSONObject().apply {
                    put("bot_id", botId)
                    put("api_key", apiKey)
                }.toString().toRequestBody("application/json".toMediaType())
                val req = Request.Builder()
                    .url("$base/user/create-desktop-link-code")
                    .post(body)
                    .build()
                val res = http.newCall(req).execute()
                runOnUiThread {
                    btn.isEnabled = true
                    if (!isFinishing) handleUserDesktopLinkCodeResponse(res)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    btn.isEnabled = true
                    if (!isFinishing) CuraxFeedback.warn(this, "Error: ${e.message}")
                }
            }
        }.start()
    }

    private fun handleUserDesktopLinkCodeResponse(response: okhttp3.Response) {
        if (response.isSuccessful) {
            val body = response.body?.string() ?: "{}"
            val data = try { JSONObject(body) } catch (_: Exception) { JSONObject() }
            val code = data.optString("code", "").trim()
            val expiresIn = data.optInt("expires_in", 300)
            val userName = data.optString("user_name", "").trim()
            if (code.isEmpty()) {
                CuraxFeedback.warn(this, "No code returned")
                return
            }
            val msg = buildString {
                append("Give this code to enter in the desktop app:\n\n")
                append("Settings → System → Link this desktop to you\n\n")
                append("Code: ")
                append(code)
                append("\n\nExpires in ")
                append(expiresIn / 60)
                append(" minutes. One-time use.")
                if (userName.isNotEmpty()) append("\n\nUser: $userName")
            }
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            AlertDialog.Builder(this)
                .setTitle("Desktop link code")
                .setMessage(msg)
                .setPositiveButton("Copy code") { _, _ ->
                    cm?.setPrimaryClip(ClipData.newPlainText("desktop_link_code", code))
                    CuraxFeedback.success(this, "Copied")
                }
                .setNegativeButton(android.R.string.ok, null)
                .show()
        } else {
            CuraxFeedback.warn(this, "Failed to create code")
        }
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
            val msg = buildString {
                append("Give this code to the user to enter in the desktop app:\n\n")
                append("• Settings → Admin Panel → Use existing admin → enter code\n\n")
                append("Code: ")
                append(code)
                append("\n\nExpires in ")
                append(expiresIn / 60)
                append(" minutes. One-time use.")
                if (adminName.isNotEmpty()) append("\n\nAdmin: $adminName")
            }
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.desktop_linking_code))
                .setMessage(msg)
                .setPositiveButton("Copy code") { _, _ ->
                    cm?.setPrimaryClip(ClipData.newPlainText("desktop_link_code", code))
                    CuraxFeedback.success(this, "Copied")
                }
                .setNegativeButton(android.R.string.ok, null)
                .show()
        } else {
            CuraxFeedback.warn(this, "Failed to create code")
        }
    }

    override fun onResume() {
        super.onResume()
        val hasPin = prefs.appPin.isNotEmpty() || (localUserStore.pinEnabled && localUserStore.pinCode.isNotEmpty())
        btnSetPin.text = if (hasPin) getString(R.string.change_pin) else getString(R.string.set_pin)
        btnAutoLock.text = getAutoLockButtonText(prefs.autoLockSeconds)
        setupConnectionCodeSections()
        val cardPinSecurityBanner = findViewById<MaterialCardView>(R.id.cardPinSecurityBanner)
        val showBanner = AppRole.isUser(this) && prefs.appPin.isEmpty() && !prefs.pinSettingsBannerDismissed
        cardPinSecurityBanner.visibility = if (showBanner) View.VISIBLE else View.GONE
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
