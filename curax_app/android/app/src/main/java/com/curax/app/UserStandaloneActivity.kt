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
import android.content.BroadcastReceiver
import android.content.IntentFilter
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import android.widget.TextView
import android.widget.Toast
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import android.view.Menu
import android.view.MenuItem
import android.widget.RadioGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
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
    private lateinit var toggleUserMode: RadioGroup
    private lateinit var btnModeDefault: com.google.android.material.radiobutton.MaterialRadioButton
    private lateinit var btnModeStandalone: com.google.android.material.radiobutton.MaterialRadioButton
    private lateinit var btnConnect: MaterialButton
    private lateinit var tvConnectionStatus: TextView
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
        prefs.userStandaloneMode = true

        // Bootstrap from the last saved standalone snapshot so the screen has data immediately on open.
        val restored = UserDataBusClient.restoreCachedUserData(this)

        setContentView(R.layout.activity_user_standalone)

        drawerLayout = findViewById(R.id.userStandaloneDrawer)
        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.userStandaloneToolbar)
        setSupportActionBar(toolbar)
        toolbar.setTitleTextColor(android.graphics.Color.WHITE)

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

        toggleUserMode = findViewById(R.id.toggleUserModeStandalone)
        btnModeDefault = findViewById(R.id.btnModeDefaultStandalone)
        btnModeStandalone = findViewById(R.id.btnModeStandaloneStandalone)
        btnConnect = findViewById(R.id.btnStandaloneConnect)
        tvConnectionStatus = findViewById(R.id.tvStandaloneConnectionStatus)
        toggleUserMode.check(btnModeStandalone.id)
        toggleUserMode.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId == btnModeDefault.id) {
                prefs.userStandaloneMode = false
                AppLockState.grantUnlock()
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            }
        }

        btnConnect.setOnClickListener {
            val id = prefs.id.trim()
            val apiKey = prefs.apiKey.trim()
            if (connectionService?.isConnected() == true) {
                disconnectService()
                Toast.makeText(this, "Disconnected", Toast.LENGTH_SHORT).show()
            } else if (id.isNotEmpty() && apiKey.isNotEmpty()) {
                Toast.makeText(this, "Registering FCM and connecting to relay...", Toast.LENGTH_SHORT).show()
                askNotificationPermission()
                if (!prefs.hasRequestedConnectWakePermissions) {
                    val didAskBattery = requestBatteryOptimizationExemption()
                    prefs.hasRequestedConnectWakePermissions = didAskBattery
                }
                connectWithLatestFcmToken(prefs.serverUrl, id, apiKey)
            }
        }

        showLoading(!restored)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.standalone_menu, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val isDark = isDarkModeEnabled()
        val themeItem = menu.findItem(R.id.action_toggle_theme)
        themeItem?.setIcon(if (isDark) R.drawable.ic_theme_sun else R.drawable.ic_theme_moon)
        themeItem?.title = if (isDark) getString(R.string.light_mode) else getString(R.string.dark_mode)
        findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.userStandaloneToolbar).overflowIcon?.setTint(android.graphics.Color.WHITE)
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
            else -> super.onOptionsItemSelected(item)
        }
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
                else -> "Settings"
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
                            if (!isFinishing && prefs.userStandaloneMode) connectDatabus(dc)
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
        ensureUserDataBusConnected()
        bootstrapStandaloneDataOnce()
        startService(Intent(this, AlertConnectionService::class.java).apply { action = AlertConnectionService.ACTION_RECONNECT_NOW })
    }

    override fun onStop() {
        // Same as admin: stay subscribed in background for real-time sync from admin/desktop.
        UserDataBusClient.setOnUserDataAppliedListener(null)
        if (dataSyncReceiverRegistered) {
            try { unregisterReceiver(dataSyncReceiver) } catch (_: Exception) {}
            dataSyncReceiverRegistered = false
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
        val intent = Intent(this, AlertConnectionService::class.java).apply {
            action = AlertConnectionService.ACTION_CONNECT
            putExtra(AlertConnectionService.EXTRA_SERVER_URL, serverUrl)
            putExtra(AlertConnectionService.EXTRA_BOT_ID, id)
            putExtra(AlertConnectionService.EXTRA_API_KEY, apiKey)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        tvConnectionStatus.text = getString(R.string.connecting)
        tvConnectionStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_orange_dark))
    }

    private fun connectWithLatestFcmToken(serverUrl: String, id: String, apiKey: String) {
        tvConnectionStatus.text = getString(R.string.connecting)
        tvConnectionStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_orange_dark))
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
        tvConnectionStatus.text = if (connected) getString(R.string.connected) else getString(R.string.disconnected)
        tvConnectionStatus.setTextColor(
            ContextCompat.getColor(
                this,
                if (connected) android.R.color.holo_green_dark else android.R.color.holo_red_dark
            )
        )
        btnConnect.text = if (connected) getString(R.string.disconnect) else getString(R.string.connect)
    }

    private fun disconnectService() {
        try {
            unbindService(serviceConnection)
        } catch (_: Exception) { }
        connectionService = null
        startService(Intent(this, AlertConnectionService::class.java).apply {
            action = AlertConnectionService.ACTION_DISCONNECT
        })
        tvConnectionStatus.text = getString(R.string.disconnected)
        tvConnectionStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
        btnConnect.text = getString(R.string.connect)
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
