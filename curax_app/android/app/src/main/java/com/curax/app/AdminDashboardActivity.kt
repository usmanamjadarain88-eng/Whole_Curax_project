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
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.json.JSONArray
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
    private lateinit var tvSidebarStatRelay: TextView
    private lateinit var tvSidebarStatLinked: TextView
    private lateinit var tvSidebarStatPending: TextView
    private lateinit var tvSidebarStatAlerts: TextView
    private lateinit var tvSidebarStatSync: TextView
    private lateinit var btnSidebarOpenUsers: MaterialButton
    private var tabLayout: TabLayout? = null
    private var viewPager: ViewPager2? = null
    private var tabMediator: TabLayoutMediator? = null

    private var connectionService: AlertConnectionService? = null
    private var pendingRelayConnectAfterNotificationPermission = false
    private var alertsReceiverRegistered = false
    private var adminDataSyncReceiverRegistered = false
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()

    /** Throttle sidebar linked/pending HTTP: [ACTION_ADMIN_DATA_SYNCED] can fire very often over WebSocket. */
    private var lastSidebarLinkedOverviewFetchAtMs: Long = 0L

    private val adminDataSyncReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AlertEvents.ACTION_ADMIN_DATA_SYNCED) {
                runOnUiThread {
                    updateReturnToAdminBar()
                    fetchSidebarHealthOverview(force = false)
                }
            }
        }
    }

    private val alertsUpdatedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AlertEvents.ACTION_ALERTS_UPDATED) {
                supportFragmentManager.fragments
                    .filterIsInstance<AdminAlertsFragment>()
                    .forEach { it.refresh() }
                runOnUiThread { applySidebarLocalStats() }
            }
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            connectionService = (service as AlertConnectionService.LocalBinder).getService()
            connectionService?.onAlertReceived = { type, message, userName ->
                val storedUser = AlertDisplayRules.linkedUserLabelForAlert(type, userName)
                val alertId = alertDb.insertAlert(type, message, userName = storedUser)
                runOnUiThread {
                    if (!AppVisibility.isForeground) {
                        NotificationHelper.showAlertNotification(
                            this@AdminDashboardActivity,
                            notificationId = alertId.toInt(),
                            alertId = alertId,
                            type = type,
                            message = message,
                            userName = storedUser,
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
        tvSidebarStatRelay = findViewById(R.id.tvSidebarStatRelay)
        tvSidebarStatLinked = findViewById(R.id.tvSidebarStatLinked)
        tvSidebarStatPending = findViewById(R.id.tvSidebarStatPending)
        tvSidebarStatAlerts = findViewById(R.id.tvSidebarStatAlerts)
        tvSidebarStatSync = findViewById(R.id.tvSidebarStatSync)
        btnSidebarOpenUsers = findViewById(R.id.btnSidebarOpenUsers)

        btnSidebarOpenUsers.setOnClickListener {
            drawerLayout.closeDrawer(Gravity.START)
            if (prefs.actAsUserId.isNotEmpty()) {
                CuraxFeedback.info(this, getString(R.string.admin_sidebar_open_users_blocked))
                return@setOnClickListener
            }
            viewPager?.setCurrentItem(1, true)
        }

        updateReturnToAdminBar()

        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.adminToolbar)
        setSupportActionBar(toolbar)
        toolbar.title = getString(R.string.admin_dashboard_toolbar_title)
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
        val initialTab = resolveInitialHubTab(intent, savedInstanceState)
        refreshTabsForActAsUser(initialTab)

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

        fetchSidebarHealthOverview(force = true)
        btnAdminConnect.setOnClickListener {
            if (connectionService?.isConnected() == true) {
                disconnectService()
                CuraxFeedback.info(this, getString(R.string.admin_relay_disconnected_toast))
            } else {
                CuraxFeedback.info(this, getString(R.string.admin_relay_registering))
                if (ConnectRelaySetup.needsNotificationPrompt(this, prefs)) {
                    pendingRelayConnectAfterNotificationPermission = true
                    ConnectRelaySetup.requestNotificationPrompt(this)
                } else {
                    pendingRelayConnectAfterNotificationPermission = false
                    ConnectRelaySetup.runFirstConnectSystemPrompts(this, prefs)
                    prefs.relayAutoConnectEnabled = true
                    connectWithLatestFcmToken(prefs.serverUrl, id, apiKey)
                }
            }
        }
    }

    private fun applySidebarLocalStats() {
        if (!this::tvSidebarStatRelay.isInitialized) return
        val relayOn = RelayAutoConnect.isRelayLive(this, connectionService)
        val relayRestoring = RelayAutoConnect.shouldAutoRestore(prefs) && !relayOn
        tvSidebarStatRelay.text = when {
            relayOn -> getString(R.string.admin_hub_relay_on)
            relayRestoring -> getString(R.string.connecting)
            else -> getString(R.string.admin_hub_relay_off)
        }
        tvSidebarStatRelay.setTextColor(
            ContextCompat.getColor(
                this,
                when {
                    relayOn -> android.R.color.holo_green_dark
                    relayRestoring -> android.R.color.holo_orange_dark
                    else -> android.R.color.holo_red_dark
                },
            ),
        )
        tvSidebarStatAlerts.text = AdminDemoData.totalAdminAlertsVisibleCount(
            AppRole.isAdmin(this),
            alertDb.getAllAlerts().size,
        ).toString()
        val placeholder = getString(R.string.admin_sidebar_stat_placeholder)
        tvSidebarStatSync.text = prefs.lastSyncTime.trim().ifEmpty { placeholder }
    }

    /** Sidebar care overview: relay + local alerts/sync + linked/pending counts from API when signed in. */
    private fun fetchSidebarHealthOverview(force: Boolean = false) {
        applySidebarLocalStats()
        val accessCode = prefs.adminAccessCode.trim()
        val base = prefs.centralApiUrl.trim().removeSuffix("/")
        val placeholder = getString(R.string.admin_sidebar_stat_placeholder)
        if (base.isEmpty() || accessCode.isEmpty()) {
            tvSidebarStatLinked.text = placeholder
            tvSidebarStatPending.text = placeholder
            return
        }
        val now = System.currentTimeMillis()
        if (!force &&
            lastSidebarLinkedOverviewFetchAtMs > 0L &&
            now - lastSidebarLinkedOverviewFetchAtMs < SIDEBAR_LINKED_OVERVIEW_MIN_INTERVAL_MS
        ) {
            return
        }
        lastSidebarLinkedOverviewFetchAtMs = now
        Thread {
            var linked = placeholder
            var pending = placeholder
            try {
                val usersUrl = "$base/admin/linked-users?access_code=${URLEncoder.encode(accessCode, "UTF-8")}"
                val usersRes = http.newCall(Request.Builder().url(usersUrl).get().build()).execute()
                if (usersRes.isSuccessful) {
                    val usersBody = usersRes.body?.string().orEmpty()
                    val data = if (usersBody.isNotBlank()) JSONObject(usersBody) else JSONObject()
                    val usersArr = data.optJSONArray("users") ?: JSONArray()
                    linked = usersArr.length().toString()
                }

                val pendUrl = "$base/admin/pending-user-link-requests?access_code=${URLEncoder.encode(accessCode, "UTF-8")}"
                val pendRes = http.newCall(Request.Builder().url(pendUrl).get().build()).execute()
                if (pendRes.isSuccessful) {
                    val pendBody = pendRes.body?.string().orEmpty()
                    val jo = if (pendBody.isNotBlank()) JSONObject(pendBody) else JSONObject()
                    val arr = jo.optJSONArray("requests") ?: JSONArray()
                    pending = arr.length().toString()
                }
            } catch (_: Exception) {
            }
            runOnUiThread {
                tvSidebarStatLinked.text = linked
                tvSidebarStatPending.text = pending
                applySidebarLocalStats()
                updateReturnToAdminBar()
            }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        updateReturnToAdminBar()
        applySidebarLocalStats()
        fetchAdminSnapshotFromServer()
        restoreRelayOnHomeOpen()
    }

    private fun restoreRelayOnHomeOpen() {
        RelayAutoConnect.restoreOnAppOpen(
            activity = this,
            prefs = prefs,
            connectionService = connectionService,
            serviceConnection = serviceConnection,
            onConnecting = {
                tvAdminConnectionStatus.text = getString(R.string.connecting)
                tvAdminConnectionStatus.setTextColor(
                    ContextCompat.getColor(this, android.R.color.holo_orange_dark),
                )
            },
            onConnected = { updateConnectionUi(it) },
        )
        if (RelayAutoConnect.shouldAutoRestore(prefs) &&
            !RelayAutoConnect.isRelayLive(this, connectionService)
        ) {
            val id = prefs.id.trim()
            val apiKey = prefs.apiKey.trim()
            if (id.isNotEmpty() && apiKey.isNotEmpty()) {
                connectWithLatestFcmToken(prefs.serverUrl, id, apiKey)
            }
        }
        applySidebarLocalStats()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != ConnectRelaySetup.REQ_POST_NOTIFICATIONS || !pendingRelayConnectAfterNotificationPermission) {
            return
        }
        pendingRelayConnectAfterNotificationPermission = false
        val id = prefs.id.trim()
        val apiKey = prefs.apiKey.trim()
        if (id.isEmpty() || apiKey.isEmpty()) return
        ConnectRelaySetup.runFirstConnectSystemPrompts(this, prefs)
        prefs.relayAutoConnectEnabled = true
        connectWithLatestFcmToken(prefs.serverUrl, id, apiKey)
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
                if (actAsUserId.isEmpty()) prefs.actAsUserDisplayMode = ""
                refreshTabsForActAsUser()
                updateReturnToAdminBar()
                updateToolbarSubtitle()
            }
            return
        }
        AdminDataBusClient.fetchAdminSnapshotAsync(this) { result ->
            if (result == AdminDataBusClient.SnapshotResult.FAILED) {
                prefs.actAsUserId = ""
                prefs.actAsUserName = ""
                prefs.actAsUserDisplayMode = ""
            }
            refreshTabsForActAsUser()
            updateReturnToAdminBar()
            updateToolbarSubtitle()
            when (result) {
                AdminDataBusClient.SnapshotResult.APPLIED ->
                    CuraxFeedback.success(
                        this@AdminDashboardActivity,
                        if (actAsUserName.isNotEmpty()) {
                            getString(R.string.admin_loaded_user_care_data, actAsUserName)
                        } else {
                            getString(R.string.admin_loaded_user_care_data_generic)
                        },
                    )
                AdminDataBusClient.SnapshotResult.FAILED ->
                    CuraxFeedback.warn(this@AdminDashboardActivity, getString(R.string.admin_load_user_care_failed), long = true)
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

    /** Care mode: toolbar subtitle + overflow menu "Return to admin" (no sidebar duplicate). */
    private fun updateReturnToAdminBar() {
        updateToolbarSubtitle()
        invalidateOptionsMenu()
    }

    private fun performReturnToAdminHome() {
        prefs.actAsUserId = ""
        prefs.actAsUserName = ""
        prefs.actAsUserDisplayMode = ""
        DoseTrackingLocalStore.clear(applicationContext)
        refreshTabsForActAsUser()
        updateReturnToAdminBar()
        fetchSidebarHealthOverview(force = true)
        fetchAdminSnapshotFromServer()
    }

    private fun updateToolbarSubtitle() {
        val tb = findViewById<androidx.appcompat.widget.Toolbar>(R.id.adminToolbar)
        tb.subtitle = if (prefs.actAsUserId.isNotEmpty()) {
            getString(
                R.string.admin_toolbar_managing,
                prefs.actAsUserName.ifEmpty { getString(R.string.admin_user_display_fallback) },
            )
        } else {
            getString(R.string.admin_toolbar_home)
        }
        tb.setSubtitleTextColor(Color.argb(230, 255, 255, 255))
    }

    /** Call from child fragments after act-as-user changes so the tab strip updates. */
    fun applyActAsUserUiFromChild() {
        refreshTabsForActAsUser()
        updateReturnToAdminBar()
    }

    /**
     * Care mode: Overview, Reminders, optional temp adjustment, Logs, Settings — no Alerts/Dose/Reports
     * (admin home strip already has Alerts/Reports). Pure admin: hub · Users · Alerts · Reports · Connections.
     */
    private fun resolveInitialHubTab(intent: Intent?, savedInstanceState: Bundle?): Int {
        if (intent?.getBooleanExtra(AlertNavigation.EXTRA_OPEN_ALERTS_TAB, false) == true) {
            intent.removeExtra(AlertNavigation.EXTRA_OPEN_ALERTS_TAB)
            if (prefs.actAsUserId.isEmpty()) return 2
        }
        return savedInstanceState?.getInt(STATE_HUB_TAB, 0) ?: 0
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        viewPager?.let { outState.putInt(STATE_HUB_TAB, it.currentItem) }
    }

    private fun refreshTabsForActAsUser(initialTab: Int = 0) {
        val tl = tabLayout ?: return
        val vp = viewPager ?: return
        tabMediator?.detach()
        val actingAsUser = prefs.actAsUserId.isNotEmpty()
        val careStandalone =
            actingAsUser && prefs.actAsUserDisplayMode.trim().equals("standalone", ignoreCase = true)
        val count = when {
            !actingAsUser -> 5
            careStandalone -> 4
            else -> 5
        }
        vp.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount(): Int = count
            override fun createFragment(position: Int): androidx.fragment.app.Fragment {
                return if (actingAsUser) {
                    if (careStandalone) {
                        when (position) {
                            0 -> AdminOverviewFragment()
                            1 -> AdminMedicalRemindersFragment()
                            2 -> AdminLogsFragment()
                            else -> AdminSettingsFragment()
                        }
                    } else {
                        when (position) {
                            0 -> AdminOverviewFragment()
                            1 -> AdminMedicalRemindersFragment()
                            2 -> UserTempAdjustmentFragment()
                            3 -> AdminLogsFragment()
                            else -> AdminSettingsFragment()
                        }
                    }
                } else {
                    when (position) {
                        0 -> AdminHubFragment()
                        1 -> AdminUsersFragment()
                        2 -> AdminAlertsFragment()
                        3 -> AdminReportsFragment()
                        else -> AdminConnectionsFragment()
                    }
                }
            }
        }
        tabMediator = TabLayoutMediator(tl, vp) { tab, position ->
            tab.text = if (actingAsUser) {
                if (careStandalone) {
                    when (position) {
                        0 -> getString(R.string.admin_tab_overview)
                        1 -> getString(R.string.admin_tab_user_reminders)
                        2 -> getString(R.string.admin_logs_screen_title)
                        else -> getString(R.string.admin_tab_settings)
                    }
                } else {
                    when (position) {
                        0 -> getString(R.string.admin_tab_overview)
                        1 -> getString(R.string.admin_tab_user_reminders)
                        2 -> getString(R.string.tab_temp_adjustment)
                        3 -> getString(R.string.admin_logs_screen_title)
                        else -> getString(R.string.admin_tab_settings)
                    }
                }
            } else {
                when (position) {
                    0 -> getString(R.string.admin_tab_overview)
                    1 -> getString(R.string.admin_tab_users)
                    2 -> getString(R.string.admin_tab_alerts)
                    3 -> getString(R.string.admin_tab_reports)
                    else -> getString(R.string.admin_tab_connections)
                }
            }
        }.apply { attach() }
        val maxTab = (count - 1).coerceAtLeast(0)
        val start = if (actingAsUser) 0 else initialTab.coerceIn(0, maxTab)
        vp.setCurrentItem(start, false)
        updateToolbarSubtitle()
        invalidateOptionsMenu()
    }

    /** Home hub: jump to Users tab (ignored while in Care mode). */
    fun navigateAdminHomeToUsersTab() {
        if (prefs.actAsUserId.isNotEmpty()) return
        drawerLayout.closeDrawer(android.view.Gravity.START)
        viewPager?.setCurrentItem(1, true)
    }

    /** Home hub: jump to Alerts tab. */
    fun navigateAdminHomeToAlertsTab() {
        if (prefs.actAsUserId.isNotEmpty()) return
        drawerLayout.closeDrawer(android.view.Gravity.START)
        viewPager?.setCurrentItem(2, true)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyOpenAlertsTabFromIntent(intent)
    }

    private fun applyOpenAlertsTabFromIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(AlertNavigation.EXTRA_OPEN_ALERTS_TAB, false) != true) return
        intent.removeExtra(AlertNavigation.EXTRA_OPEN_ALERTS_TAB)
        if (prefs.actAsUserId.isNotEmpty()) return
        viewPager?.setCurrentItem(2, false)
    }

    /** Home hub: open drawer where relay Connect lives. */
    fun openAdminDrawerForRelay() {
        if (prefs.actAsUserId.isNotEmpty()) return
        drawerLayout.openDrawer(android.view.Gravity.START)
    }

    /** Open per-user management (same rules as drawer list). Callable from [AdminUsersFragment]. */
    fun openLinkedUserForManagement(
        userId: String,
        name: String,
        desktopLinked: Boolean,
        isDemo: Boolean = false,
        userDisplayMode: String = "",
    ) {
        if (isDemo || userId.startsWith("demo_")) {
            CuraxFeedback.info(this, getString(R.string.admin_demo_user_open_blocked))
            return
        }
        prefs.actAsUserId = userId
        prefs.actAsUserName = name
        prefs.actAsUserDisplayMode = when (userDisplayMode.trim().lowercase()) {
            "standalone", "default" -> userDisplayMode.trim().lowercase()
            else -> ""
        }
        drawerLayout.closeDrawer(android.view.Gravity.START)
        updateToolbarSubtitle()
        refreshTabsForActAsUser()
        updateReturnToAdminBar()
        CuraxFeedback.info(this, getString(R.string.admin_hub_loading_user_data, name))
        fetchActAsUserDataThenNotify()
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
        menu.findItem(R.id.action_return_to_admin)?.apply {
            isVisible = prefs.actAsUserId.isNotEmpty()
            if (isVisible) {
                icon?.mutate()?.setTint(Color.WHITE)
            }
        }
        findViewById<androidx.appcompat.widget.Toolbar>(R.id.adminToolbar).overflowIcon?.setTint(Color.WHITE)
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_return_to_admin -> {
                performReturnToAdminHome()
                true
            }
            R.id.action_toggle_theme -> {
                val newMode = if (isDarkModeEnabled()) AppCompatDelegate.MODE_NIGHT_NO else AppCompatDelegate.MODE_NIGHT_YES
                AppLockState.grantUnlock()
                prefs.themeMode = newMode
                AppCompatDelegate.setDefaultNightMode(newMode)
                window.decorView.post { recreate() }
                true
            }
            R.id.action_settings -> {
                if (prefs.actAsUserId.isNotEmpty()) {
                    val vp = viewPager
                    val n = vp?.adapter?.itemCount ?: 0
                    if (n > 0) vp?.setCurrentItem(n - 1, true)
                }
                else {
                    startActivity(Intent(this, SettingsActivity::class.java))
                }
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
                    CuraxFeedback.warn(this, "Enable Full-screen intent for CuraX to wake screen", long = true)
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
        applySidebarLocalStats()
        sendBroadcast(
            Intent(AlertEvents.ACTION_CONNECTION_STATE_CHANGED).apply {
                putExtra(AlertEvents.EXTRA_CONNECTED, false)
                setPackage(packageName)
            },
        )
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
        applySidebarLocalStats()
    }

    private fun copyToClipboard(text: String) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("", text))
        CuraxFeedback.success(this, getString(R.string.clipboard_generic_copied))
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

    companion object {
        private const val STATE_HUB_TAB = "admin_hub_vp_tab"
        /** Throttle sidebar linked/pending counts over frequent [ACTION_ADMIN_DATA_SYNCED] (WebSocket). */
        private const val SIDEBAR_LINKED_OVERVIEW_MIN_INTERVAL_MS = 90_000L
    }
}
