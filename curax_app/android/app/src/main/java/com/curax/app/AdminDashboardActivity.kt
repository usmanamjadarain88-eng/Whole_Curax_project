package com.curax.app

import android.Manifest
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
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.button.MaterialButton
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.google.firebase.messaging.FirebaseMessaging
import android.app.NotificationManager
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class AdminDashboardActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var alertDb: AlertDb
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var tvAdminConnectionStatus: TextView
    private lateinit var btnAdminConnect: MaterialButton
    private lateinit var sidebarUsersList: android.widget.LinearLayout
    private lateinit var tvSidebarUsersHint: TextView
    private var tabLayout: TabLayout? = null
    private var viewPager: ViewPager2? = null
    private var tabMediator: TabLayoutMediator? = null

    private var connectionService: AlertConnectionService? = null
    private var alertsReceiverRegistered = false
    private var adminDataSyncReceiverRegistered = false
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()

    private val adminDataSyncReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AlertEvents.ACTION_ADMIN_DATA_SYNCED) {
                runOnUiThread { updateReturnToAdminBar() }
            }
        }
    }

    private val alertsUpdatedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AlertEvents.ACTION_ALERTS_UPDATED) {
                supportFragmentManager.fragments
                    .filterIsInstance<AdminAlertsFragment>()
                    .forEach { it.refresh() }
            }
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            connectionService = (service as AlertConnectionService.LocalBinder).getService()
            connectionService?.onAlertReceived = { type, message ->
                val alertId = alertDb.insertAlert(type, message)
                runOnUiThread {
                    if (!AppVisibility.isForeground) {
                        NotificationHelper.showAlertNotification(
                            this@AdminDashboardActivity,
                            notificationId = alertId.toInt(),
                            alertId = alertId,
                            type = type,
                            message = message
                        )
                    }
                    supportFragmentManager.fragments
                        .filterIsInstance<AdminAlertsFragment>()
                        .forEach { it.refresh() }
                    sendBroadcast(Intent(AlertEvents.ACTION_ALERTS_UPDATED))
                }
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
        if (AppLockPolicy.shouldRequireLockOnEntry(this)) {
            startActivity(Intent(this, PinEntryActivity::class.java))
            finish()
            return
        }
        setContentView(R.layout.activity_admin_dashboard)

        alertDb = AlertDb(this)
        NotificationHelper.createChannel(this)

        drawerLayout = findViewById(R.id.adminDrawerLayout)
        tvAdminConnectionStatus = findViewById(R.id.tvAdminConnectionStatus)
        btnAdminConnect = findViewById(R.id.btnAdminConnect)
        sidebarUsersList = findViewById(R.id.sidebarConnectedUsersList)
        tvSidebarUsersHint = findViewById(R.id.tvSidebarUsersHint)

        findViewById<MaterialButton>(R.id.btnSidebarReturnToAdmin).setOnClickListener {
            prefs.actAsUserId = ""
            prefs.actAsUserName = ""
            refreshTabsForActAsUser()
            updateReturnToAdminBar()
            fetchSidebarConnectedUsers()
            fetchAdminSnapshotFromServer()
        }
        updateReturnToAdminBar()

        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.adminToolbar)
        setSupportActionBar(toolbar)
        toolbar.title = "Curax"
        toolbar.setTitleTextColor(Color.WHITE)
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

        tabLayout = findViewById(R.id.tabLayout)
        viewPager = findViewById(R.id.viewPager)
        refreshTabsForActAsUser()

        tabLayout?.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                tab?.let { tabLayout?.isTabIndicatorFullWidth = (it.position != 0) }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
        tabLayout?.post { tabLayout?.isTabIndicatorFullWidth = (tabLayout?.selectedTabPosition != 0) }

        var id = prefs.id
        if (id.isEmpty()) {
            id = java.util.UUID.randomUUID().toString().take(8)
            prefs.id = id
        }
        var apiKey = prefs.apiKey
        if (apiKey.isEmpty()) {
            apiKey = java.util.UUID.randomUUID().toString().replace("-", "").take(16)
            prefs.apiKey = apiKey
        }

        fetchSidebarConnectedUsers()

        btnAdminConnect.setOnClickListener {
            if (connectionService?.isConnected() == true) {
                disconnectService()
                CuraxFeedback.info(this, "Disconnected")
            } else {
                CuraxFeedback.info(this, "Registering FCM and connecting to relay…")
                askNotificationPermission()
                if (!prefs.hasRequestedConnectWakePermissions) {
                    ensureFullScreenIntentPermission()
                    requestBatteryOptimizationExemption()
                    prefs.hasRequestedConnectWakePermissions = true
                }
                connectWithLatestFcmToken(prefs.serverUrl, id, apiKey)
            }
        }
    }

    private fun fetchSidebarConnectedUsers() {
        val accessCode = prefs.adminAccessCode.trim()
        val base = prefs.centralApiUrl.trim().removeSuffix("/")
        if (base.isEmpty() || accessCode.isEmpty()) {
            tvSidebarUsersHint.text = "Sign in as admin to see users"
            return
        }
        Thread {
            try {
                val url = "$base/admin/linked-users?access_code=${java.net.URLEncoder.encode(accessCode, "UTF-8")}"
                val req = Request.Builder().url(url).get().build()
                val res = http.newCall(req).execute()
                if (res.isSuccessful) {
                    val body = res.body?.string() ?: "{}"
                    val data = JSONObject(body)
                    val usersArr = data.optJSONArray("users") ?: org.json.JSONArray()
                    runOnUiThread {
                        sidebarUsersList.removeAllViews()
                        if (usersArr.length() == 0) {
                            tvSidebarUsersHint.text = "No users yet. Share your connection code."
                        } else {
                            tvSidebarUsersHint.text = "Tap a user to load their data"
                            for (i in 0 until usersArr.length()) {
                                val u = usersArr.optJSONObject(i) ?: continue
                                val userId = u.optString("id", "").trim()
                                val email = u.optString("email", "").trim()
                                val name = u.optString("name", "").ifEmpty { "User" }
                                val botId = u.optString("bot_id", "").trim()
                                val desktopLinked = botId.isNotEmpty()
                                val isManaging = userId == prefs.actAsUserId
                                val tv = android.widget.TextView(this).apply {
                                    tag = userId
                                    text = "• $email" + if (!desktopLinked) " (desktop not linked)" else "" + if (isManaging) " ★" else ""
                                    setTextColor(ContextCompat.getColor(this@AdminDashboardActivity, R.color.text_primary))
                                    textSize = 14f
                                    setPadding(0, 12, 0, 12)
                                    isClickable = true
                                    isFocusable = true
                                    setBackgroundResource(android.R.drawable.list_selector_background)
                                }
                                tv.setOnClickListener {
                                    if (!desktopLinked) {
                                        CuraxFeedback.warn(this@AdminDashboardActivity, getString(R.string.user_link_desktop_first_title), long = true)
                                        androidx.appcompat.app.AlertDialog.Builder(this@AdminDashboardActivity)
                                            .setTitle(getString(R.string.user_link_desktop_first_title))
                                            .setMessage(getString(R.string.user_link_desktop_first_message))
                                            .setPositiveButton(android.R.string.ok, null)
                                            .show()
                                        drawerLayout.closeDrawer(android.view.Gravity.START)
                                        return@setOnClickListener
                                    }
                                    prefs.actAsUserId = userId
                                    prefs.actAsUserName = name
                                    drawerLayout.closeDrawer(android.view.Gravity.START)
                                    CuraxFeedback.info(this@AdminDashboardActivity, "Loading $name's data…")
                                    fetchActAsUserDataThenNotify()
                                }
                                sidebarUsersList.addView(tv)
                            }
                        }
                        updateReturnToAdminBar()
                    }
                } else {
                    runOnUiThread { tvSidebarUsersHint.text = "Could not load users" }
                }
            } catch (_: Exception) {
                runOnUiThread { tvSidebarUsersHint.text = "Could not load users" }
            }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        updateReturnToAdminBar()
        fetchSidebarConnectedUsers()
        fetchAdminSnapshotFromServer()
        ConnectionManager.requestReconnectRelayNow(this)
    }

    private fun fetchAdminSnapshotFromServer() {
        AdminDataBusClient.fetchAdminSnapshotAsync(this, null)
    }

    /** After setting actAsUserId, fetch that user's data from backend, apply to AdminDemoData, then broadcast so UI shows existing medicines (no overwrite with stale/empty). */
    private fun fetchActAsUserDataThenNotify() {
        val actAsUserName = prefs.actAsUserName
        val base = prefs.centralApiUrl.trim().removeSuffix("/")
        val accessCode = prefs.adminAccessCode.trim()
        val actAsUserId = prefs.actAsUserId
        if (base.isEmpty() || accessCode.isEmpty() || actAsUserId.isEmpty()) {
            runOnUiThread {
                refreshTabsForActAsUser()
                updateReturnToAdminBar()
            }
            return
        }
        AdminDataBusClient.fetchAdminSnapshotAsync(this) { result ->
            if (result == AdminDataBusClient.SnapshotResult.FAILED) {
                prefs.actAsUserId = ""
                prefs.actAsUserName = ""
            }
            refreshTabsForActAsUser()
            updateReturnToAdminBar()
            when (result) {
                AdminDataBusClient.SnapshotResult.APPLIED ->
                    CuraxFeedback.success(
                        this@AdminDashboardActivity,
                        if (actAsUserName.isNotEmpty()) "Loaded $actAsUserName's data" else "Loaded user data",
                    )
                AdminDataBusClient.SnapshotResult.FAILED ->
                    CuraxFeedback.warn(this@AdminDashboardActivity, "Could not load user data")
                AdminDataBusClient.SnapshotResult.SKIPPED_STALE -> { /* newer selection or return-to-admin */ }
            }
        }
    }

    private fun maybeAutoLockOnReturn() {
        val hasPin = prefs.appPin.isNotEmpty() || LocalUserStore(this).pinEnabled
        if (!hasPin) {
            AppLockState.clearBackgroundTimestamp()
            prefs.lastBackgroundAtMs = 0L
            return
        }
        if (AppLockState.isUnlockValid()) {
            AppLockState.clearBackgroundTimestamp()
            prefs.lastBackgroundAtMs = 0L
            return
        }
        val backgroundAt = kotlin.math.max(AppLockState.getBackgroundTimestamp(), prefs.lastBackgroundAtMs)
        if (backgroundAt <= 0L) return
        val elapsedMs = System.currentTimeMillis() - backgroundAt
        if (elapsedMs >= prefs.autoLockSeconds * 1_000L) {
            startActivity(Intent(this, PinEntryActivity::class.java))
            finish()
        } else {
            AppLockState.clearBackgroundTimestamp()
            prefs.lastBackgroundAtMs = 0L
        }
    }

    /** Shows "Return to Admin" in sidebar and highlights the managed user in the list. Main content shows only tabs. */
    private fun updateReturnToAdminBar() {
        val container = findViewById<android.view.View>(R.id.sidebarReturnToAdminContainer)
        if (prefs.actAsUserId.isNotEmpty()) {
            container?.visibility = android.view.View.VISIBLE
        } else {
            container?.visibility = android.view.View.GONE
        }
        for (i in 0 until sidebarUsersList.childCount) {
            val child = sidebarUsersList.getChildAt(i)
            if (child is android.widget.TextView) {
                val userId = child.tag?.toString() ?: ""
                val highlight = userId == prefs.actAsUserId
                if (highlight) {
                    child.setBackgroundColor(ContextCompat.getColor(this, R.color.inventory_row_selected_bg))
                } else {
                    child.setBackgroundResource(android.R.drawable.list_selector_background)
                }
            }
        }
    }

    /**
     * Call from Connected Users tab after loading a user's data so the tab strip matches drawer behavior
     * (3 tabs while managing a user).
     */
    fun applyActAsUserUiFromChild() {
        refreshTabsForActAsUser()
        updateReturnToAdminBar()
    }

    /** When acting as a user: only 3 tabs (Dashboard, Reminders, Settings). When admin: all 6 tabs. */
    private fun refreshTabsForActAsUser() {
        val tl = tabLayout ?: return
        val vp = viewPager ?: return
        tabMediator?.detach()
        val actingAsUser = prefs.actAsUserId.isNotEmpty()
        val count = if (actingAsUser) 3 else 6
        vp.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount(): Int = count
            override fun createFragment(position: Int): androidx.fragment.app.Fragment {
                return if (actingAsUser) {
                    when (position) {
                        0 -> AdminOverviewFragment()
                        1 -> AdminMedicalRemindersFragment()
                        else -> AdminSettingsFragment()
                    }
                } else {
                    when (position) {
                        0 -> AdminOverviewFragment()
                        1 -> AdminAlertsFragment()
                        2 -> AdminMedicalRemindersFragment()
                        3 -> AdminLogsFragment()
                        4 -> AdminReportsFragment()
                        else -> AdminSettingsFragment()
                    }
                }
            }
        }
        tabMediator = TabLayoutMediator(tl, vp) { tab, position ->
            tab.text = if (actingAsUser) {
                when (position) {
                    0 -> "Dashboard"
                    1 -> "Reminders"
                    else -> "Settings"
                }
            } else {
                when (position) {
                    0 -> "Dashboard"
                    1 -> "Alerts"
                    2 -> "Reminders"
                    3 -> "Logs"
                    4 -> "Reports"
                    else -> "Settings"
                }
            }
        }.apply { attach() }
        vp.setCurrentItem(0, false)
    }

    override fun onStart() {
        super.onStart()
        maybeAutoLockOnReturn()
        if (!alertsReceiverRegistered) {
            registerReceiver(alertsUpdatedReceiver, IntentFilter(AlertEvents.ACTION_ALERTS_UPDATED))
            alertsReceiverRegistered = true
        }
        if (!adminDataSyncReceiverRegistered) {
            registerReceiver(adminDataSyncReceiver, IntentFilter(AlertEvents.ACTION_ADMIN_DATA_SYNCED))
            adminDataSyncReceiverRegistered = true
        }
        // Real-time admin data sync from Data Bus WebSocket (desktop/app changes).
        val accessCode = prefs.adminAccessCode.trim()
        if (accessCode.isNotEmpty()) {
            AdminDataBusClient.start(this, accessCode, prefs.dataBusUrl)
        }
    }

    override fun onStop() {
        AdminDataBusClient.stop()
        if (alertsReceiverRegistered) {
            try {
                unregisterReceiver(alertsUpdatedReceiver)
            } catch (_: Exception) {
            }
            alertsReceiverRegistered = false
        }
        if (adminDataSyncReceiverRegistered) {
            try {
                unregisterReceiver(adminDataSyncReceiver)
            } catch (_: Exception) {
            }
            adminDataSyncReceiverRegistered = false
        }
        super.onStop()
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
        findViewById<androidx.appcompat.widget.Toolbar>(R.id.adminToolbar).overflowIcon?.setTint(Color.WHITE)
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_toggle_theme -> {
                val newMode = if (isDarkModeEnabled()) AppCompatDelegate.MODE_NIGHT_NO else AppCompatDelegate.MODE_NIGHT_YES
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

    private fun isDarkModeEnabled(): Boolean {
        val mask = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return mask == Configuration.UI_MODE_NIGHT_YES
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

    private fun connectWithLatestFcmToken(serverUrl: String, id: String, apiKey: String) {
        tvAdminConnectionStatus.text = getString(R.string.connecting)
        tvAdminConnectionStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_orange_dark))

        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val token = task.result.orEmpty()
                    if (token.isNotEmpty()) {
                        prefs.fcmToken = token
                        saveFcmTokenToCentralApi(id, apiKey, token, role = "admin")
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

    private fun saveFcmTokenToCentralApi(botId: String, apiKey: String, fcmToken: String, role: String) {
        val base = prefs.centralApiUrl.trim().removeSuffix("/")
        if (base.isEmpty()) return
        Thread {
            try {
                val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).build()
                // 1) save-credentials so backend stores FCM in the correct admin/user row (use access_code for admin)
                val body = JSONObject().apply {
                    put("bot_id", botId)
                    put("api_key", apiKey)
                    put("role", role)
                    put("fcm_token", fcmToken)
                    if (role == "admin") {
                        val ac = prefs.adminAccessCode.trim()
                        if (ac.isNotEmpty()) put("access_code", ac)
                    }
                }
                var req = Request.Builder()
                    .url("$base/save-credentials")
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                val res = client.newCall(req).execute()
                if (res.isSuccessful) { }
                // 2) Also update by access_code so admin's fcm_token is set for GET /admin/connection etc.
                val accessCode = prefs.adminAccessCode.trim()
                if (accessCode.isNotEmpty() && fcmToken.isNotEmpty()) {
                    val fcmBody = JSONObject().apply {
                        put("access_code", accessCode)
                        put("fcm_token", fcmToken)
                    }
                    req = Request.Builder()
                        .url("$base/admin/fcm-token")
                        .put(fcmBody.toString().toRequestBody("application/json".toMediaType()))
                        .build()
                    client.newCall(req).execute()
                }
            } catch (_: Exception) { }
        }.start()
    }

    private fun startConnectionService(serverUrl: String, id: String, apiKey: String) {
        ConnectionManager.requestConnectRelay(this, serverUrl, id, apiKey)
        tvAdminConnectionStatus.text = getString(R.string.connecting)
        tvAdminConnectionStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_orange_dark))
        bindService(Intent(this, AlertConnectionService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        try {
            val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).setData(Uri.parse("package:$packageName"))
            startActivity(i)
        } catch (_: Exception) { }
    }

    private fun disconnectService() {
        try {
            unbindService(serviceConnection)
        } catch (_: Exception) {
        }
        connectionService = null
        ConnectionManager.requestDisconnectRelay(this)
        tvAdminConnectionStatus.text = getString(R.string.disconnected)
        tvAdminConnectionStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
        btnAdminConnect.text = getString(R.string.connect)
    }

    fun isAdminConnected(): Boolean = connectionService?.isConnected() == true

    private fun updateConnectionUi(connected: Boolean) {
        if (connected) prefs.hasEverConnected = true
        tvAdminConnectionStatus.text = if (connected) getString(R.string.connected) else getString(R.string.disconnected)
        tvAdminConnectionStatus.setTextColor(
            ContextCompat.getColor(
                this,
                if (connected) android.R.color.holo_green_dark else android.R.color.holo_red_dark
            )
        )
        btnAdminConnect.text = if (connected) getString(R.string.disconnect) else getString(R.string.connect)
    }

    private fun copyToClipboard(text: String) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("", text))
        CuraxFeedback.success(this, "Copied")
    }

    override fun onDestroy() {
        try {
            unbindService(serviceConnection)
        } catch (_: Exception) {
        }
        if (isFinishing && !isChangingConfigurations) {
            AdminDataBusClient.stop()
        }
        super.onDestroy()
    }
}
