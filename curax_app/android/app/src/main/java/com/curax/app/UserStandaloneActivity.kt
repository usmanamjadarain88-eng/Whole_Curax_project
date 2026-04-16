package com.curax.app

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.content.res.ColorStateList
import android.content.BroadcastReceiver
import android.content.IntentFilter
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import android.widget.TextView
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.MenuItemCompat
import android.widget.LinearLayout
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.button.MaterialButton
import com.google.firebase.messaging.FirebaseMessaging
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import androidx.viewpager2.widget.ViewPager2
import android.content.res.Configuration

class UserStandaloneActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var tabLayout: TabLayout
    private lateinit var viewPager: ViewPager2
    private var tabMediator: TabLayoutMediator? = null
    private var overviewFragmentRef: AdminOverviewFragment? = null
    private lateinit var btnConnect: MaterialButton
    private lateinit var tvUserSidebarAdminStatus: TextView
    private lateinit var tvUserSidebarHealthStatus: TextView
    private lateinit var tvUserSidebarAlertsStatus: TextView
    private lateinit var tvUserSidebarRealtimeStatus: TextView
    private lateinit var tvUserSidebarAdminDetail: TextView
    private lateinit var tvUserSidebarHealthDetail: TextView
    private lateinit var tvUserSidebarAlertsDetail: TextView
    private lateinit var tvUserSidebarRealtimeDetail: TextView
    private lateinit var tvChevronAdmin: TextView
    private lateinit var tvChevronHealth: TextView
    private lateinit var tvChevronAlerts: TextView
    private lateinit var tvChevronRealtime: TextView
    private val sidebarSectionExpanded = BooleanArray(4)
    private lateinit var loadingOverlay: View
    private var connectionService: AlertConnectionService? = null
    @Volatile
    private var databusResolveInFlight = false
    @Volatile
    private var bootstrapFetchRequested = false
    private var dataSyncReceiverRegistered = false
    private val dataSyncReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            if (intent?.action == AlertEvents.ACTION_ADMIN_DATA_SYNCED) {
                showLoading(false)
                refreshVisibleDashboard()
                refreshUserSidebar()
            }
        }
    }
    private var connectionBroadcastRegistered = false
    private val relayConnectionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            if (intent?.action == AlertEvents.ACTION_CONNECTION_STATE_CHANGED) {
                refreshUserSidebar()
            }
        }
    }
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            connectionService = (service as AlertConnectionService.LocalBinder).getService()
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
        val store = LocalUserStore(this)
        if (store.role != LocalUserStore.ROLE_USER) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }
        // Bootstrap from the last saved standalone snapshot so the screen has data immediately on open.
        val restored = UserDataBusClient.restoreCachedUserData(this)

        setContentView(R.layout.activity_user_standalone)

        drawerLayout = findViewById(R.id.userStandaloneDrawer)
        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.userStandaloneToolbar)
        setSupportActionBar(toolbar)
        toolbar.setTitleTextColor(android.graphics.Color.WHITE)
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
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
                R.id.action_app_mode -> {
                    toolbar.post {
                        UserModePopup.show(
                            this@UserStandaloneActivity,
                            UserModePopup.anchorForModeIcon(toolbar),
                        )
                    }
                    true
                }
                else -> false
            }
        }

        val toggle = androidx.appcompat.app.ActionBarDrawerToggle(
            this,
            drawerLayout,
            toolbar,
            R.string.drawer_open,
            R.string.drawer_close
        )
        drawerLayout.addDrawerListener(toggle)
        toggle.syncState()
        toggle.drawerArrowDrawable.color = android.graphics.Color.WHITE
        toolbar.navigationIcon?.setTint(android.graphics.Color.WHITE)

        tabLayout = findViewById(R.id.userStandaloneTabs)
        viewPager = findViewById(R.id.userStandalonePager)
        loadingOverlay = findViewById(R.id.loadingOverlay)
        setupTabs()

        btnConnect = findViewById(R.id.btnStandaloneConnect)
        tvUserSidebarAdminStatus = findViewById(R.id.tvUserSidebarAdminStatus)
        tvUserSidebarHealthStatus = findViewById(R.id.tvUserSidebarHealthStatus)
        tvUserSidebarAlertsStatus = findViewById(R.id.tvUserSidebarAlertsStatus)
        tvUserSidebarRealtimeStatus = findViewById(R.id.tvUserSidebarRealtimeStatus)
        tvUserSidebarAdminDetail = findViewById(R.id.tvUserSidebarAdminDetail)
        tvUserSidebarHealthDetail = findViewById(R.id.tvUserSidebarHealthDetail)
        tvUserSidebarAlertsDetail = findViewById(R.id.tvUserSidebarAlertsDetail)
        tvUserSidebarRealtimeDetail = findViewById(R.id.tvUserSidebarRealtimeDetail)
        tvChevronAdmin = findViewById(R.id.tvChevronAdmin)
        tvChevronHealth = findViewById(R.id.tvChevronHealth)
        tvChevronAlerts = findViewById(R.id.tvChevronAlerts)
        tvChevronRealtime = findViewById(R.id.tvChevronRealtime)

        findViewById<LinearLayout>(R.id.sidebarSectionAdmin).setOnClickListener { toggleSidebarSection(0) }
        findViewById<LinearLayout>(R.id.sidebarSectionHealth).setOnClickListener { toggleSidebarSection(1) }
        findViewById<LinearLayout>(R.id.sidebarSectionAlerts).setOnClickListener { toggleSidebarSection(2) }
        findViewById<LinearLayout>(R.id.sidebarSectionRealtime).setOnClickListener { toggleSidebarSection(3) }
        listOf(
            tvUserSidebarAdminDetail,
            tvUserSidebarHealthDetail,
            tvUserSidebarAlertsDetail,
            tvUserSidebarRealtimeDetail,
        ).forEach { detail -> detail.setOnClickListener { } }
        applySidebarExpandUi()

        drawerLayout.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerOpened(drawerView: View) {
                refreshUserSidebar()
            }
        })

        btnConnect.setOnClickListener {
            val id = prefs.id.trim()
            val apiKey = prefs.apiKey.trim()
            if (connectionService?.isConnected() == true) {
                disconnectService()
                CuraxFeedback.info(this, "Disconnected")
            } else if (id.isNotEmpty() && apiKey.isNotEmpty()) {
                CuraxFeedback.info(this, "Registering FCM and connecting to relay...")
                askNotificationPermission()
                if (!prefs.hasRequestedConnectWakePermissions) {
                    val didAskBattery = requestBatteryOptimizationExemption()
                    prefs.hasRequestedConnectWakePermissions = didAskBattery
                }
                connectWithLatestFcmToken(prefs.serverUrl, id, apiKey)
            }
        }

        updateConnectionUi(false)
        showLoading(!restored)
    }

    override fun onResume() {
        super.onResume()
        refreshUserSidebar()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1) {
            refreshUserSidebar()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.user_home_menu, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val result = super.onPrepareOptionsMenu(menu)
        val isDark = isDarkModeEnabled()
        val themeItem = menu.findItem(R.id.action_toggle_theme)
        themeItem?.setIcon(if (isDark) R.drawable.ic_theme_sun_toolbar else R.drawable.ic_theme_moon_toolbar)
        themeItem?.title = if (isDark) getString(R.string.light_mode) else getString(R.string.dark_mode)
        menu.findItem(R.id.action_app_mode)?.apply {
            val standalone = AppModeManager.isStandaloneMode(this@UserStandaloneActivity)
            val label = if (standalone) {
                getString(R.string.user_mode_standalone)
            } else {
                getString(R.string.user_mode_default)
            }
            title = label
            MenuItemCompat.setTooltipText(this, "${getString(R.string.user_mode_section)} · $label")
        }
        findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.userStandaloneToolbar).overflowIcon?.setTint(android.graphics.Color.WHITE)
        return result
    }

    private fun isDarkModeEnabled(): Boolean {
        val mask = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return mask == Configuration.UI_MODE_NIGHT_YES
    }

    private fun setupTabs() {
        tabMediator?.detach()
        viewPager.adapter = object : androidx.viewpager2.adapter.FragmentStateAdapter(this) {
            override fun getItemCount(): Int = 6
            override fun createFragment(position: Int): androidx.fragment.app.Fragment {
                return when (position) {
                    0 -> AdminOverviewFragment().also { overviewFragmentRef = it }
                    1 -> AdminAlertsFragment()
                    2 -> AdminMedicalRemindersFragment()
                    3 -> AdminLogsFragment()
                    4 -> AdminReportsFragment()
                    else -> AdminSettingsFragment()
                }
            }
        }
        tabMediator = TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> "Dashboard"
                1 -> "Alerts"
                2 -> "Reminders"
                3 -> "Logs"
                4 -> "Reports"
                else -> getString(R.string.tab_system_view)
            }
        }.apply { attach() }
        viewPager.setCurrentItem(0, false)
    }

    /**
     * Join the admin databus room (WebSocket) so data_sync → GET /user/data applies medicines, reminders, settings.
     * Called from onStart and onResume so returning to the app reconnects if the socket dropped.
     */
    private fun ensureUserDataBusConnected() {
        val botId = prefs.id.trim()
        val apiKey = prefs.apiKey.trim()
        val base = prefs.centralApiUrl.trim().removeSuffix("/")
        if (botId.isEmpty() || apiKey.isEmpty() || base.isEmpty()) return

        fun connectDatabus(room: String) {
            if (room.isEmpty()) return
            UserDataBusClient.start(
                this, room, prefs.dataBusUrl, base, botId, apiKey
            )
        }

        val room = prefs.databusAccessCode.trim()
        if (room.isNotEmpty()) {
            connectDatabus(room)
            return
        }
        if (databusResolveInFlight) return
        databusResolveInFlight = true
        // Existing installs linked before databus_access_code existed — resolve room from API.
        Thread {
            try {
                val url = "$base/user/databus-room?bot_id=${URLEncoder.encode(botId, "UTF-8")}&api_key=${URLEncoder.encode(apiKey, "UTF-8")}"
                val client = OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(15, TimeUnit.SECONDS)
                    .build()
                val res = client.newCall(Request.Builder().url(url).get().build()).execute()
                if (res.isSuccessful) {
                    val j = JSONObject(res.body?.string() ?: "{}")
                    val dc = j.optString("databus_access_code", "").trim()
                    if (dc.isNotEmpty()) {
                        prefs.databusAccessCode = dc
                        runOnUiThread {
                            if (!isFinishing) connectDatabus(dc)
                        }
                    }
                }
            } catch (_: Exception) { }
            finally {
                databusResolveInFlight = false
            }
        }.start()
    }

    override fun onStart() {
        super.onStart()
        UserDataBusClient.setOnUserDataAppliedListener {
            runOnUiThread {
                showLoading(false)
                refreshVisibleDashboard()
                refreshUserSidebar()
            }
        }
        if (!dataSyncReceiverRegistered) {
            val filter = IntentFilter(AlertEvents.ACTION_ADMIN_DATA_SYNCED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(dataSyncReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(dataSyncReceiver, filter)
            }
            dataSyncReceiverRegistered = true
        }
        if (!connectionBroadcastRegistered) {
            val cf = IntentFilter(AlertEvents.ACTION_CONNECTION_STATE_CHANGED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(relayConnectionReceiver, cf, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(relayConnectionReceiver, cf)
            }
            connectionBroadcastRegistered = true
        }
        ensureUserDataBusConnected()
        UserDataBusClient.scheduleApiFallbackIfDataBusOffline(this)
        bootstrapStandaloneDataOnce()
        ConnectionManager.requestReconnectRelayNow(this)
        window.decorView.postDelayed({ refreshUserSidebar() }, 900L)
        window.decorView.postDelayed({ refreshUserSidebar() }, 2800L)
    }

    override fun onStop() {
        // Same as admin: stay subscribed in background for real-time sync from admin/desktop.
        UserDataBusClient.setOnUserDataAppliedListener(null)
        if (dataSyncReceiverRegistered) {
            try { unregisterReceiver(dataSyncReceiver) } catch (_: Exception) {}
            dataSyncReceiverRegistered = false
        }
        if (connectionBroadcastRegistered) {
            try { unregisterReceiver(relayConnectionReceiver) } catch (_: Exception) {}
            connectionBroadcastRegistered = false
        }
        super.onStop()
    }

    override fun onDestroy() {
        tabMediator?.detach()
        overviewFragmentRef = null
        try {
            unbindService(serviceConnection)
        } catch (_: Exception) { }
        if (isFinishing && !isChangingConfigurations) {
            UserDataBusClient.stop()
        }
        super.onDestroy()
    }

    private fun showLoading(show: Boolean) {
        if (::loadingOverlay.isInitialized) {
            loadingOverlay.visibility = if (show) View.VISIBLE else View.GONE
        }
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
        }
    }

    private fun requestBatteryOptimizationExemption(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) return false
        return try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).setData(android.net.Uri.parse("package:$packageName")))
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun startConnectionService(serverUrl: String, id: String, apiKey: String) {
        ConnectionManager.requestConnectRelay(this, serverUrl, id, apiKey)
        applyConnectionButtonConnectingUi()
    }

    private fun connectWithLatestFcmToken(serverUrl: String, id: String, apiKey: String) {
        applyConnectionButtonConnectingUi()
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
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
            bindService(Intent(this, AlertConnectionService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)
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
                client.newCall(req).execute().close()
            } catch (_: Exception) { }
        }.start()
    }

    private fun updateConnectionUi(connected: Boolean) {
        btnConnect.text = if (connected) {
            getString(R.string.disconnect)
        } else {
            getString(R.string.user_sidebar_connect_for_alerts)
        }
        val tint = if (connected) {
            ContextCompat.getColor(this, R.color.sidebar_connect_connected)
        } else {
            ContextCompat.getColor(this, R.color.sidebar_connect_disconnected)
        }
        btnConnect.backgroundTintList = ColorStateList.valueOf(tint)
        refreshUserSidebar()
    }

    private fun applyConnectionButtonConnectingUi() {
        btnConnect.text = getString(R.string.connecting)
        btnConnect.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, R.color.sidebar_connect_connecting),
        )
        refreshUserSidebar()
    }

    private fun disconnectService() {
        try {
            unbindService(serviceConnection)
        } catch (_: Exception) { }
        connectionService = null
        ConnectionManager.requestDisconnectRelay(this)
        btnConnect.text = getString(R.string.user_sidebar_connect_for_alerts)
        btnConnect.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, R.color.sidebar_connect_disconnected),
        )
        refreshUserSidebar()
    }

    private fun sidebarStatusColor(level: Int): Int = when (level) {
        0 -> ContextCompat.getColor(this, R.color.sidebar_status_ok)
        1 -> ContextCompat.getColor(this, R.color.sidebar_status_warn)
        else -> ContextCompat.getColor(this, R.color.sidebar_status_err)
    }

    private fun setSidebarLine(tv: TextView, level: Int, message: String) {
        val prefix = when (level) {
            0 -> "🟢 "
            1 -> "🟡 "
            else -> "🔴 "
        }
        tv.text = "$prefix$message"
        tv.setTextColor(sidebarStatusColor(level))
    }

    private fun notificationsChannelReady(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun toggleSidebarSection(index: Int) {
        sidebarSectionExpanded[index] = !sidebarSectionExpanded[index]
        applySidebarExpandUi()
    }

    private fun applySidebarExpandUi() {
        if (!::tvUserSidebarAdminDetail.isInitialized) return
        val details = arrayOf(
            tvUserSidebarAdminDetail,
            tvUserSidebarHealthDetail,
            tvUserSidebarAlertsDetail,
            tvUserSidebarRealtimeDetail,
        )
        val chevs = arrayOf(tvChevronAdmin, tvChevronHealth, tvChevronAlerts, tvChevronRealtime)
        for (i in 0 until 4) {
            details[i].visibility = if (sidebarSectionExpanded[i]) View.VISIBLE else View.GONE
            chevs[i].text = if (sidebarSectionExpanded[i]) "▲" else "▼"
        }
    }

    private fun refreshUserSidebar() {
        if (!::tvUserSidebarAdminStatus.isInitialized) return

        val base = prefs.centralApiUrl.trim().removeSuffix("/")
        val botId = prefs.id.trim()
        val apiKey = prefs.apiKey.trim()
        val configOk = base.isNotEmpty() && botId.isNotEmpty() && apiKey.isNotEmpty()
        val adminId = prefs.linkedAdminId.trim()
        val adminName = prefs.linkedAdminName.trim()
        val adminLinked = adminId.isNotEmpty()
        val relayOk = connectionService?.isConnected() == true || ConnectionManager.isRelayConnectedHint()
        val databusOk = UserDataBusClient.isSocketConnected()
        val snapshotOk = prefs.userStandaloneDataReady

        if (!adminLinked) {
            setSidebarLine(tvUserSidebarAdminStatus, 2, getString(R.string.user_sidebar_admin_not_linked))
        } else {
            val label = if (adminName.isNotEmpty()) {
                getString(R.string.user_sidebar_admin_connected, adminName)
            } else {
                getString(R.string.user_sidebar_admin_connected_generic)
            }
            setSidebarLine(tvUserSidebarAdminStatus, 0, label)
        }

        when {
            !configOk -> setSidebarLine(
                tvUserSidebarHealthStatus,
                2,
                getString(R.string.user_sidebar_health_config),
            )
            !adminLinked -> setSidebarLine(
                tvUserSidebarHealthStatus,
                1,
                getString(R.string.user_sidebar_health_no_admin),
            )
            databusOk && relayOk -> setSidebarLine(
                tvUserSidebarHealthStatus,
                0,
                getString(R.string.user_sidebar_health_ok),
            )
            databusOk || relayOk -> setSidebarLine(
                tvUserSidebarHealthStatus,
                1,
                getString(R.string.user_sidebar_health_partial),
            )
            snapshotOk -> setSidebarLine(
                tvUserSidebarHealthStatus,
                1,
                getString(R.string.user_sidebar_health_offline),
            )
            else -> setSidebarLine(
                tvUserSidebarHealthStatus,
                2,
                getString(R.string.user_sidebar_health_issues),
            )
        }

        val fcmOk = prefs.fcmToken.trim().isNotEmpty() && notificationsChannelReady()
        if (fcmOk) {
            setSidebarLine(tvUserSidebarAlertsStatus, 0, getString(R.string.user_sidebar_alerts_active))
        } else {
            tvUserSidebarAlertsStatus.text = "⚪ ${getString(R.string.user_sidebar_alerts_inactive)}"
            tvUserSidebarAlertsStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        }

        val rtState = UserDataBusClient.getRealtimeConnectionState()
        when (rtState) {
            0 -> setSidebarLine(
                tvUserSidebarRealtimeStatus,
                0,
                getString(R.string.user_sidebar_realtime_connected),
            )
            1 -> setSidebarLine(
                tvUserSidebarRealtimeStatus,
                1,
                getString(R.string.user_sidebar_realtime_reconnecting),
            )
            else -> setSidebarLine(
                tvUserSidebarRealtimeStatus,
                2,
                getString(R.string.user_sidebar_realtime_disconnected),
            )
        }

        val displayAdmin = if (adminName.isNotEmpty()) adminName else getString(R.string.user_sidebar_admin_connected_generic)
        tvUserSidebarAdminDetail.text = if (adminLinked) {
            getString(R.string.user_sidebar_detail_admin_linked, displayAdmin)
        } else {
            getString(R.string.user_sidebar_detail_admin_not_linked)
        }

        val healthDetailRes = when {
            !configOk -> R.string.user_sidebar_detail_health_config
            !adminLinked -> R.string.user_sidebar_detail_health_no_admin
            databusOk && relayOk -> R.string.user_sidebar_detail_health_ok
            databusOk || relayOk -> R.string.user_sidebar_detail_health_partial
            snapshotOk -> R.string.user_sidebar_detail_health_offline
            else -> R.string.user_sidebar_detail_health_issues
        }
        tvUserSidebarHealthDetail.setText(healthDetailRes)

        tvUserSidebarAlertsDetail.text = if (fcmOk) {
            getString(R.string.user_sidebar_detail_alerts_active)
        } else {
            getString(R.string.user_sidebar_detail_alerts_inactive)
        }

        tvUserSidebarRealtimeDetail.text = when (rtState) {
            0 -> getString(R.string.user_sidebar_detail_realtime_connected)
            1 -> getString(R.string.user_sidebar_detail_realtime_reconnecting)
            else -> getString(R.string.user_sidebar_detail_realtime_disconnected)
        }
    }

    private fun bootstrapStandaloneDataOnce() {
        if (bootstrapFetchRequested) return
        bootstrapFetchRequested = true
        val botId = prefs.id.trim()
        val apiKey = prefs.apiKey.trim()
        val base = prefs.centralApiUrl.trim().removeSuffix("/")
        if (botId.isEmpty() || apiKey.isEmpty() || base.isEmpty()) {
            showLoading(false)
            return
        }
        // One-time startup refresh: keeps the screen accurate without polling or tab-switch fetches.
        UserDataBusClient.fetchAndApplyUserData(this, base, botId, apiKey) {
            showLoading(false)
        }
    }

    /** Toolbar label + medicine box colors after mode popup saves [AppModeManager]. */
    fun notifyUserAppModePreferenceChanged() {
        invalidateOptionsMenu()
        val targets = linkedSetOf<AdminOverviewFragment>()
        overviewFragmentRef?.let { targets.add(it) }
        (supportFragmentManager.findFragmentByTag("f0") as? AdminOverviewFragment)?.let { targets.add(it) }
        supportFragmentManager.fragments
            .filterIsInstance<AdminOverviewFragment>()
            .forEach { targets.add(it) }
        targets.forEach { fragment ->
            if (fragment.isAdded && fragment.view != null) {
                fragment.refreshMedBoxThemeForUserMode()
            }
        }
    }

    private fun refreshVisibleDashboard() {
        overviewFragmentRef?.let { fragment ->
            if (fragment.isAdded && fragment.view != null) {
                fragment.refreshStandaloneFromMemory()
                fragment.view?.requestLayout()
                fragment.view?.invalidate()
                fragment.view?.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvInventory)?.apply {
                    requestLayout()
                    invalidate()
                    adapter?.notifyDataSetChanged()
                }
                return
            }
        }
        val targets = linkedSetOf<AdminOverviewFragment>()
        (supportFragmentManager.findFragmentByTag("f0") as? AdminOverviewFragment)?.let { targets.add(it) }
        supportFragmentManager.fragments
            .filterIsInstance<AdminOverviewFragment>()
            .filter { it.isAdded && it.view != null }
            .forEach { targets.add(it) }

        targets.forEach { fragment ->
            fragment.refreshStandaloneFromMemory()
            fragment.view?.requestLayout()
            fragment.view?.invalidate()
            fragment.view?.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvInventory)?.apply {
                requestLayout()
                invalidate()
                adapter?.notifyDataSetChanged()
            }
        }
    }
}
