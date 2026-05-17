package com.curax.app

import android.Manifest
import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.firebase.messaging.FirebaseMessaging
import android.app.NotificationManager
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var alertDb: AlertDb
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var btnConnect: MaterialButton
    private lateinit var tvConnectionStatus: TextView
    private lateinit var recyclerAlerts: RecyclerView
    private lateinit var tvAlertsTitle: TextView
    private lateinit var tvNoAlerts: TextView
    private lateinit var tvSecurityLine: TextView
    private lateinit var tvNoAlertsFooter: TextView
    private lateinit var selectionActionBar: MaterialCardView
    private lateinit var btnSelectionCancel: MaterialButton
    private lateinit var btnSelectionSelectAll: MaterialButton
    private lateinit var btnSelectionDelete: MaterialButton
    private lateinit var alertsAdapter: AlertsAdapter
    private var connectionService: AlertConnectionService? = null
    private var securityShineAnimator: ValueAnimator? = null
    private var alertsReceiverRegistered = false
    private var pendingRelayConnectAfterNotificationPermission = false

    private val alertsUpdatedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AlertEvents.ACTION_ALERTS_UPDATED) {
                loadAlerts()
            }
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            connectionService = (service as AlertConnectionService.LocalBinder).getService()
            connectionService?.onAlertReceived = { type, message, userName ->
                val storedUser = AlertDisplayRules.linkedUserLabelForAlert(type, userName)
                alertDb.insertAlert(type, message, userName = storedUser)
                runOnUiThread { loadAlerts() }
            }
            connectionService?.onConnectionStateChanged = { connected ->
                runOnUiThread { updateConnectionUi(connected) }
            }
            runOnUiThread { updateConnectionUi(connectionService?.isConnected() == true) }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            connectionService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        val launcherLaunch = isLauncherLaunch(intent)
        

        val hasConfiguredPin = prefs.appPin.isNotEmpty() || LocalUserStore(this).pinEnabled
        if (hasConfiguredPin && launcherLaunch) {
            startActivity(Intent(this, PinEntryActivity::class.java))
            finish()
            return
        }
        if (shouldRequireLockOnEntry()) {
            startActivity(Intent(this, PinEntryActivity::class.java))
            finish()
            return
        }

        val store = LocalUserStore(this)
        if (store.role == LocalUserStore.ROLE_USER) {
            startActivity(Intent(this, UserStandaloneActivity::class.java))
            finish()
            return
        }

        AppLockState.clearBackgroundTimestamp()
        AppLockState.markProcessEntryHandled()
        prefs.lastBackgroundAtMs = 0L
        prefs.lastExitWasClose = false

        setContentView(R.layout.activity_main)

        alertDb = AlertDb(this)
        NotificationHelper.createChannel(this)

        drawerLayout = findViewById(R.id.drawerLayout)
        btnConnect = findViewById(R.id.btnConnect)
        tvConnectionStatus = findViewById(R.id.tvConnectionStatus)
        recyclerAlerts = findViewById(R.id.recyclerAlerts)
        tvAlertsTitle = findViewById(R.id.tvAlertsTitle)
        tvNoAlerts = findViewById(R.id.tvNoAlerts)
        tvSecurityLine = findViewById(R.id.tvSecurityLine)
        tvNoAlertsFooter = findViewById(R.id.tvNoAlertsFooter)
        selectionActionBar = findViewById(R.id.selectionActionBar)
        btnSelectionCancel = findViewById(R.id.btnSelectionCancel)
        btnSelectionSelectAll = findViewById(R.id.btnSelectionSelectAll)
        btnSelectionDelete = findViewById(R.id.btnSelectionDelete)

        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        val toggle = androidx.appcompat.app.ActionBarDrawerToggle(
            this,
            drawerLayout,
            toolbar,
            R.string.drawer_open,
            R.string.drawer_close
        )
        drawerLayout.addDrawerListener(toggle)
        toggle.syncState()
        toggle.drawerArrowDrawable.color = Color.WHITE
        toolbar.navigationIcon?.setTint(Color.WHITE)
        toolbar.overflowIcon?.setTint(Color.WHITE)

        var id = prefs.id
        if (id.isEmpty()) {
            id = UUID.randomUUID().toString().take(8)
            prefs.id = id
        }

        var apiKey = prefs.apiKey
        if (apiKey.isEmpty()) {
            apiKey = UUID.randomUUID().toString().replace("-", "").take(16)
            prefs.apiKey = apiKey
        }

        alertsAdapter = AlertsAdapter(
            onAlertClick = { alert -> openAlertDetail(alert) },
            onSelectionChanged = { count ->
                supportActionBar?.subtitle = if (count > 0) getString(R.string.selected_count, count) else null
                selectionActionBar.visibility = if (count > 0) View.VISIBLE else View.GONE
                btnSelectionSelectAll.text = if (alertsAdapter.areAllSelected()) {
                    getString(R.string.unselect_all)
                } else {
                    getString(R.string.select_all)
                }
                invalidateOptionsMenu()
            }
        )
        recyclerAlerts.layoutManager = LinearLayoutManager(this)
        recyclerAlerts.adapter = alertsAdapter
        attachSwipeToDelete()
        loadAlerts()

        btnSelectionCancel.setOnClickListener { alertsAdapter.clearSelection() }
        btnSelectionSelectAll.setOnClickListener { toggleSelectAll() }
        btnSelectionDelete.setOnClickListener { deleteSelectedAlerts() }

        tvSecurityLine.setOnClickListener {
            startActivity(Intent(this, SetPinActivity::class.java))
        }

        btnConnect.setOnClickListener {
            if (connectionService?.isConnected() == true) {
                disconnectService()
                CuraxFeedback.info(this, "Disconnected")
            } else {
                CuraxFeedback.info(this, "Registering FCM and connecting to relay…")
                if (ConnectRelaySetup.needsNotificationPrompt(this, prefs)) {
                    pendingRelayConnectAfterNotificationPermission = true
                    ConnectRelaySetup.requestNotificationPrompt(this)
                } else {
                    pendingRelayConnectAfterNotificationPermission = false
                    ConnectRelaySetup.runFirstConnectSystemPrompts(this, prefs)
                    runMainConnectWakeAndRelayFlow(id, apiKey)
                }
            }
        }

        val btnDrawerLogout = findViewById<MaterialButton>(R.id.btnDrawerLogout)
        if (store.role == LocalUserStore.ROLE_USER) {
            btnDrawerLogout.visibility = View.VISIBLE
            btnDrawerLogout.setOnClickListener {
                AlertDialog.Builder(this)
                    .setMessage(R.string.logout_confirm_message)
                    .setPositiveButton(R.string.logout) { _, _ ->
                        disconnectService()
                        UserLogoutHelper.clearLocalSession(this)
                        UserLogoutHelper.navigateToSignIn(this)
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        } else {
            btnDrawerLogout.visibility = View.GONE
        }
    }

    override fun onStart() {
        super.onStart()
        maybeAutoLockOnReturn()
        if (!alertsReceiverRegistered) {
            registerReceiver(alertsUpdatedReceiver, IntentFilter(AlertEvents.ACTION_ALERTS_UPDATED))
            alertsReceiverRegistered = true
        }
    }

    override fun onResume() {
        super.onResume()
        loadAlerts()
        if (prefs.linkedAdminId.isNotEmpty() && prefs.id.isNotEmpty()) {
            checkUserDeletedByAdmin()
        }
        ConnectionManager.ensureRelayLiveOnAppOpen(this)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == ConnectRelaySetup.REQ_POST_NOTIFICATIONS && pendingRelayConnectAfterNotificationPermission) {
            pendingRelayConnectAfterNotificationPermission = false
            val id = prefs.id.trim()
            val apiKey = prefs.apiKey.trim()
            if (id.isNotEmpty() && apiKey.isNotEmpty()) {
                ConnectRelaySetup.runFirstConnectSystemPrompts(this, prefs)
                runMainConnectWakeAndRelayFlow(id, apiKey)
            }
        }
    }

    private fun checkUserDeletedByAdmin() {
        val base = prefs.centralApiUrl.trim().removeSuffix("/")
        val id = prefs.id
        val apiKey = prefs.apiKey
        if (base.isEmpty() || id.isEmpty() || apiKey.isEmpty()) return
        Thread {
            try {
                val url = "$base/get-role?bot_id=${java.net.URLEncoder.encode(id, "UTF-8")}&api_key=${java.net.URLEncoder.encode(apiKey, "UTF-8")}"
                val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).build()
                val req = Request.Builder().url(url).get().build()
                val res = client.newCall(req).execute()
                if (res.code == 410) {
                    val msg = try {
                        val j = JSONObject(res.body?.string() ?: "{}")
                        val m = j.optString("message", "").trim()
                        if (m.isNotEmpty()) m else getString(R.string.account_removed_by_admin)
                    } catch (_: Exception) { getString(R.string.account_removed_by_admin) }
                    runOnUiThread { handleUserDeletedByAdmin(msg) }
                }
            } catch (_: Exception) { }
        }.start()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val isDark = isDarkModeEnabled()
        val themeItem = menu.findItem(R.id.action_toggle_theme)
        themeItem?.setIcon(if (isDark) R.drawable.ic_theme_sun else R.drawable.ic_theme_moon)
        themeItem?.title = if (isDark) getString(R.string.light_mode) else getString(R.string.dark_mode)
        findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar).overflowIcon?.setTint(Color.WHITE)
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_toggle_theme -> {
                val newMode = if (isDarkModeEnabled()) {
                    AppCompatDelegate.MODE_NIGHT_NO
                } else {
                    AppCompatDelegate.MODE_NIGHT_YES
                }
                AppLockState.grantUnlock()
                prefs.themeMode = newMode
                AppCompatDelegate.setDefaultNightMode(newMode)
                window.decorView.post { recreate() }
                true
            }
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    @Deprecated("Deprecated in API 33")
    override fun onBackPressed() {
        when {
            drawerLayout.isDrawerOpen(GravityCompat.START) -> drawerLayout.closeDrawer(GravityCompat.START)
            alertsAdapter.isSelectionMode() -> alertsAdapter.clearSelection()
            else -> {
                @Suppress("DEPRECATION")
                super.onBackPressed()
            }
        }
    }

    private fun isLauncherLaunch(sourceIntent: Intent?): Boolean {
        return sourceIntent?.action == Intent.ACTION_MAIN &&
            (sourceIntent.hasCategory(Intent.CATEGORY_LAUNCHER) || sourceIntent.hasCategory(Intent.CATEGORY_LEANBACK_LAUNCHER))
    }

    private fun isDarkModeEnabled(): Boolean {
        val mask = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return mask == Configuration.UI_MODE_NIGHT_YES
    }

    private fun shouldRequireLockOnEntry(): Boolean {
        return AppLockPolicy.shouldRequireLockOnEntry(this)
    }

    private fun clearPendingBackgroundLock() {
        AppLockState.clearBackgroundTimestamp()
        AppLockState.markProcessEntryHandled()
        prefs.lastBackgroundAtMs = 0L
        prefs.lastExitWasClose = false
    }

    private fun maybeAutoLockOnReturn() {
        val hasPin = prefs.appPin.isNotEmpty() || LocalUserStore(this).pinEnabled
        if (!hasPin) {
            clearPendingBackgroundLock()
            return
        }

        if (AppLockState.isUnlockValid()) {
            clearPendingBackgroundLock()
            return
        }

        val backgroundAt = AppLockState.getBackgroundTimestamp()
        if (backgroundAt <= 0L) return

        val seconds = prefs.autoLockSeconds
        val elapsedMs = System.currentTimeMillis() - backgroundAt
        val thresholdMs = seconds * 1_000L
        if (elapsedMs >= thresholdMs) {
            startActivity(Intent(this, PinEntryActivity::class.java))
            finish()
        } else {
            clearPendingBackgroundLock()
        }
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
        }
    }

    private fun ensureFullScreenIntentPermission() {
        if (Build.VERSION.SDK_INT >= 34) {
            val nm = getSystemService(NotificationManager::class.java)
            if (!nm.canUseFullScreenIntent()) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                    CuraxFeedback.warn(this, "Enable Full-screen intent for Curax to wake screen", long = true)
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun copyToClipboard(text: String) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("", text))
        CuraxFeedback.success(this, "Copied")
    }

    private fun openAlertDetail(item: AlertItem) {
        if (alertsAdapter.isSelectionMode()) return
        val user = AlertDisplayRules.linkedUserLabelForAlert(item.type, item.userName)
        AlertNavigation.launchDetailFromAlertsList(
            this,
            alertId = item.id,
            type = item.type,
            message = item.message,
            receivedAt = item.receivedAt,
            userName = user,
        )
    }

    private fun attachSwipeToDelete() {
        val callback = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean = false

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.bindingAdapterPosition
                if (position == RecyclerView.NO_POSITION) return

                if (alertsAdapter.isSelectionMode()) {
                    alertsAdapter.notifyItemChanged(position)
                    return
                }

                val item = alertsAdapter.currentList.getOrNull(position) ?: return
                deleteWithUndo(listOf(item), getString(R.string.deleted))
            }
        }
        ItemTouchHelper(callback).attachToRecyclerView(recyclerAlerts)
    }

    private fun toggleSelectAll() {
        if (alertsAdapter.currentList.isEmpty()) return
        if (alertsAdapter.areAllSelected()) {
            alertsAdapter.clearSelection()
        } else {
            alertsAdapter.selectAll()
        }
    }

    private fun deleteSelectedAlerts() {
        val ids = alertsAdapter.getSelectedIds()
        if (ids.isEmpty()) return
        val items = alertsAdapter.currentList.filter { ids.contains(it.id) }

        AlertDialog.Builder(this)
            .setTitle(R.string.delete_selected)
            .setMessage(getString(R.string.delete_selected_confirm, ids.size))
            .setPositiveButton(R.string.delete) { _, _ ->
                alertsAdapter.clearSelection()
                deleteWithUndo(items, getString(R.string.deleted_count, items.size))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun deleteWithUndo(items: List<AlertItem>, message: String) {
        if (items.isEmpty()) return
        items.forEach { alertDb.deleteAlert(it.id) }
        loadAlerts()
        CuraxFeedback.successWithUndo(this, message) {
            items.forEach { alertDb.insertAlert(it.type, it.message, it.receivedAt) }
            loadAlerts()
        }
    }

    private fun startConnectionService(serverUrl: String, id: String, apiKey: String) {
        ConnectionManager.requestConnectRelay(this, serverUrl, id, apiKey)
        tvConnectionStatus.text = getString(R.string.connecting)
        tvConnectionStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_orange_dark))
        bindService(Intent(this, AlertConnectionService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun runMainConnectWakeAndRelayFlow(id: String, apiKey: String) {
        prefs.relayAutoConnectEnabled = true
        connectWithLatestFcmToken(prefs.serverUrl, id, apiKey)
    }

    private fun connectWithLatestFcmToken(serverUrl: String, id: String, apiKey: String) {
        tvConnectionStatus.text = getString(R.string.connecting)
        tvConnectionStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_orange_dark))

        FirebaseMessaging.getInstance().token
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val token = task.result.orEmpty()
                    if (token.isNotEmpty()) {
                        prefs.fcmToken = token
                        saveFcmTokenToCentralApi(id, apiKey, token)
                        startService(Intent(this, AlertConnectionService::class.java).apply {
                            action = AlertConnectionService.ACTION_UPDATE_FCM
                            putExtra(AlertConnectionService.EXTRA_BOT_ID, id)
                            putExtra(AlertConnectionService.EXTRA_API_KEY, apiKey)
                            putExtra(AlertConnectionService.EXTRA_FCM_TOKEN, token)
                        })
                    }
                }
                startConnectionService(serverUrl, id, apiKey)
            }
    }

    private fun saveFcmTokenToCentralApi(botId: String, apiKey: String, fcmToken: String) {
        val base = prefs.centralApiUrl.trim().removeSuffix("/")
        val adminId = prefs.linkedAdminId.trim()
        if (base.isEmpty() || adminId.isEmpty()) return
        Thread {
            try {
                val body = JSONObject().apply {
                    put("bot_id", botId)
                    put("api_key", apiKey)
                    put("role", "user")
                    put("admin_id", adminId)
                    put("fcm_token", fcmToken)
                }
                val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).build()
                val req = Request.Builder()
                    .url("$base/save-credentials")
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                val res = client.newCall(req).execute()
                if (res.code == 410) {
                    val msg = try {
                        val j = JSONObject(res.body?.string() ?: "{}")
                        val m = j.optString("message", "").trim()
                        if (m.isNotEmpty()) m else getString(R.string.account_removed_by_admin)
                    } catch (_: Exception) { getString(R.string.account_removed_by_admin) }
                    runOnUiThread { handleUserDeletedByAdmin(msg) }
                } else if (res.isSuccessful) {
                }
            } catch (_: Exception) { }
        }.start()
    }

    private fun handleUserDeletedByAdmin(message: String) {
        prefs.id = ""
        prefs.apiKey = ""
        prefs.linkedAdminId = ""
        prefs.linkedAdminName = ""
        prefs.databusAccessCode = ""
        LocalUserStore(this).clearUser()
        val displayMessage = if (message.isNotBlank()) message else getString(R.string.account_removed_by_admin)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.account_removed_title))
            .setMessage("$displayMessage\n\n${getString(R.string.account_removed_sign_up_again)}")
            .setCancelable(false)
            .setPositiveButton("OK") { _, _ ->
                startActivity(Intent(this, LaunchActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                finish()
            }
            .show()
    }

    private fun updateConnectionUi(connected: Boolean) {
        tvConnectionStatus.text = if (connected) getString(R.string.connected) else getString(R.string.disconnected)
        tvConnectionStatus.setTextColor(
            ContextCompat.getColor(
                this,
                if (connected) android.R.color.holo_green_dark else android.R.color.holo_red_dark
            )
        )
        btnConnect.text = if (connected) getString(R.string.disconnect) else getString(R.string.connect)
        if (connected) prefs.hasEverConnected = true
    }

    private fun loadAlerts() {
        val listAll = alertDb.getAllAlerts()
        alertsAdapter.submitList(listAll)
        tvAlertsTitle.visibility = if (listAll.isEmpty()) View.GONE else View.VISIBLE

        if (listAll.isEmpty()) {
            tvNoAlertsFooter.visibility = View.VISIBLE
            tvNoAlerts.text = getString(R.string.no_alerts_onboarding)
            tvNoAlerts.visibility = View.VISIBLE

            val hasPin = prefs.appPin.isNotEmpty() || LocalUserStore(this).pinEnabled
            if (!hasPin) {
                tvSecurityLine.visibility = View.VISIBLE
                startSecurityLineAnimation()
            } else {
                tvSecurityLine.visibility = View.GONE
                stopSecurityLineAnimation()
            }
        } else {
            tvNoAlerts.visibility = View.GONE
            tvSecurityLine.visibility = View.GONE
            tvNoAlertsFooter.visibility = View.GONE
            stopSecurityLineAnimation()
        }
    }

    private fun startSecurityLineAnimation() {
        if (securityShineAnimator == null) {
            val base = Color.parseColor("#A32020")
            val highlight = Color.parseColor("#FF3B30")
            securityShineAnimator = ValueAnimator.ofArgb(base, highlight, base).apply {
                duration = 700L
                repeatCount = ValueAnimator.INFINITE
                addUpdateListener { animator ->
                    tvSecurityLine.setTextColor(animator.animatedValue as Int)
                }
            }
        }
        if (securityShineAnimator?.isStarted != true) {
            securityShineAnimator?.start()
        }
    }

    private fun stopSecurityLineAnimation() {
        securityShineAnimator?.cancel()
        securityShineAnimator = null
        if (::tvSecurityLine.isInitialized) {
            tvSecurityLine.setTextColor(Color.parseColor("#A32020"))
            tvSecurityLine.alpha = 1f
        }
    }

    override fun onStop() {
        if (alertsReceiverRegistered) {
            try {
                unregisterReceiver(alertsUpdatedReceiver)
            } catch (_: Exception) {
            }
            alertsReceiverRegistered = false
        }
        super.onStop()
    }

    override fun onDestroy() {
        stopSecurityLineAnimation()
        if (isFinishing && !isChangingConfigurations) {
            prefs.lastExitWasClose = true
        }
        super.onDestroy()
    }

    private fun disconnectService() {
        try {
            unbindService(serviceConnection)
        } catch (_: Exception) {
        }
        connectionService = null
        ConnectionManager.requestDisconnectRelay(this)
        tvConnectionStatus.text = getString(R.string.disconnected)
        tvConnectionStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
        btnConnect.text = getString(R.string.connect)
    }
}
