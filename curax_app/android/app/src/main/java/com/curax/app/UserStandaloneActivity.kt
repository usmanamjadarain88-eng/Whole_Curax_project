package com.curax.app

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.os.PowerManager
import android.provider.Settings
import android.content.res.ColorStateList
import android.graphics.Color
import androidx.core.graphics.ColorUtils
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.content.BroadcastReceiver
import android.content.IntentFilter
import java.net.URLEncoder
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import android.widget.TextView
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import android.view.Menu
import android.view.MenuItem
import android.widget.PopupWindow
import android.graphics.drawable.ColorDrawable
import android.widget.CompoundButton
import android.widget.RadioGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.MenuItemCompat
import android.widget.LinearLayout
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.messaging.FirebaseMessaging
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import androidx.viewpager2.widget.ViewPager2
import android.content.res.Configuration

class UserStandaloneActivity : AppCompatActivity() {

    companion object {
        private const val STATE_VIEW_PAGER_TAB = "user_standalone_vp_tab"
        /** Cold start of this activity after mode toggle (avoids fragment restore from [recreate]). */
        private const val EXTRA_RELAUNCH_TAB = "user_standalone_relaunch_tab"
        /** Toolbar action order: lower = further left (Test → theme → mode → overflow). */
        private const val MENU_ORDER_DEV = 1
        private const val MENU_ORDER_THEME = 2
        private const val MENU_ORDER_MODE = 3
        private const val MENU_ORDER_SETTINGS = 100
        /** Let the home shell paint before the mandatory mode bottom sheet appears (~1–2s). */
        private const val DEFERRED_MODE_SHEET_DELAY_MS = 1_800L
        /** After the user qualifies for the PIN offer, wait briefly so it does not stack on the mode sheet / relaunch. */
        private const val DEFERRED_PIN_PROMPT_DELAY_MS = 1_200L
    }

    private lateinit var prefs: Prefs
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var tabLayout: TabLayout
    private lateinit var viewPager: ViewPager2
    private var tabMediator: TabLayoutMediator? = null
    private var overviewFragmentRef: AdminOverviewFragment? = null
    private lateinit var btnConnect: MaterialButton
    private var sidebarConnectShowsConnecting = false
    /** Avoids relaunch when [onPrepareOptionsMenu] syncs the dev switch from prefs (spurious callbacks). */
    private var suppressDevStandaloneSwitchCallback = false
    private var lastDevStandaloneRelaunchAt = 0L

    private val devModeSwitchListener = CompoundButton.OnCheckedChangeListener { _, isChecked ->
        if (suppressDevStandaloneSwitchCallback || isFinishing) return@OnCheckedChangeListener
        if (AppModeManager.isStandaloneMode(this) == isChecked) return@OnCheckedChangeListener
        AppModeManager.setStandaloneMode(this, isChecked)
        UserDisplayModeApi.postDisplayModeAsync(this, isChecked)
        val now = SystemClock.elapsedRealtime()
        if (now - lastDevStandaloneRelaunchAt < 450L) return@OnCheckedChangeListener
        lastDevStandaloneRelaunchAt = now
        relaunchAfterDisplayModeChange()
    }

    private fun isDebuggableBuild(): Boolean =
        (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
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
    private var mandatoryModeSheetLaunched = false
    private var firstAppModeBottomSheet: BottomSheetDialog? = null
    private var deferredHomeUiRunnable: Runnable? = null
    private var pinDeferredPromptRunnable: Runnable? = null
    private var appModeLabelPopup: PopupWindow? = null
    private var displayModeReceiverRegistered = false
    private val displayModeFromServerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AlertEvents.ACTION_USER_DISPLAY_MODE_FROM_SERVER && !isFinishing) {
                relaunchAfterDisplayModeChange()
            }
        }
    }
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
            when (intent?.action) {
                AlertEvents.ACTION_CONNECTION_STATE_CHANGED,
                AlertEvents.ACTION_USER_DATABUS_SOCKET_STATE,
                -> refreshUserSidebar()
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
        if (savedInstanceState == null) {
            prefs.userHomeColdStartCount = prefs.userHomeColdStartCount + 1
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
                    // Fade in/out so theme swap feels smoother than an instant cut (same activity tree).
                    overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
                    recreate()
                    overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
                    true
                }
                R.id.action_settings -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                    true
                }
                R.id.action_app_mode -> {
                    toolbar.post { showCurrentAppModeInfo() }
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
        val extraTab = intent.getIntExtra(EXTRA_RELAUNCH_TAB, -1)
        if (extraTab >= 0) {
            intent.removeExtra(EXTRA_RELAUNCH_TAB)
        }
        val restoreTab = if (extraTab >= 0) {
            extraTab.coerceIn(0, 5)
        } else {
            savedInstanceState?.getInt(STATE_VIEW_PAGER_TAB)?.coerceIn(0, 5) ?: 0
        }
        setupTabs(restoreTab)
        refreshUserShellChrome()

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

        scheduleDeferredHomeDialogs()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::viewPager.isInitialized) {
            outState.putInt(STATE_VIEW_PAGER_TAB, viewPager.currentItem)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshUserShellChrome()
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
        menu.clear()
        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.userStandaloneToolbar)
        if (isDebuggableBuild()) {
            val devSwitchLayout = layoutInflater.inflate(R.layout.toolbar_dev_mode_switch, toolbar, false)
            devSwitchLayout.findViewById<SwitchCompat>(R.id.switchDevStandalone).apply {
                suppressDevStandaloneSwitchCallback = true
                setOnCheckedChangeListener(null)
                isChecked = AppModeManager.isStandaloneMode(this@UserStandaloneActivity)
                setOnCheckedChangeListener(devModeSwitchListener)
                suppressDevStandaloneSwitchCallback = false
            }
            menu.add(Menu.NONE, R.id.action_dev_test_mode, MENU_ORDER_DEV, "")
                .setActionView(devSwitchLayout)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        menu.add(Menu.NONE, R.id.action_toggle_theme, MENU_ORDER_THEME, getString(R.string.dark_mode))
            .setIcon(R.drawable.ic_theme_moon_toolbar)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        menu.add(Menu.NONE, R.id.action_app_mode, MENU_ORDER_MODE, "")
            .setIcon(R.drawable.ic_app_mode_toolbar)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        menu.add(Menu.NONE, R.id.action_settings, MENU_ORDER_SETTINGS, getString(R.string.settings_screen_title))
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val result = super.onPrepareOptionsMenu(menu)
        val isDark = isDarkModeEnabled()
        val themeItem = menu.findItem(R.id.action_toggle_theme)
        themeItem?.setIcon(if (isDark) R.drawable.ic_theme_sun_toolbar else R.drawable.ic_theme_moon_toolbar)
        themeItem?.title = if (isDark) getString(R.string.light_mode) else getString(R.string.dark_mode)
        menu.findItem(R.id.action_app_mode)?.apply {
            title = ""
            MenuItemCompat.setTooltipText(this, getString(R.string.user_mode_section))
        }
        if (isDebuggableBuild()) {
            menu.findItem(R.id.action_dev_test_mode)?.actionView
                ?.findViewById<SwitchCompat>(R.id.switchDevStandalone)
                ?.let { sw ->
                    suppressDevStandaloneSwitchCallback = true
                    sw.setOnCheckedChangeListener(null)
                    sw.isChecked = AppModeManager.isStandaloneMode(this@UserStandaloneActivity)
                    sw.setOnCheckedChangeListener(devModeSwitchListener)
                    suppressDevStandaloneSwitchCallback = false
                }
        }
        findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.userStandaloneToolbar).overflowIcon?.setTint(android.graphics.Color.WHITE)
        return result
    }

    private fun isDarkModeEnabled(): Boolean {
        val mask = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return mask == Configuration.UI_MODE_NIGHT_YES
    }

    private fun shellDp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun scheduleDeferredHomeDialogs() {
        deferredHomeUiRunnable?.let { r ->
            try {
                window.decorView.removeCallbacks(r)
            } catch (_: Exception) {
            }
        }
        deferredHomeUiRunnable = Runnable {
            deferredHomeUiRunnable = null
            if (isFinishing) return@Runnable
            if (!prefs.userInitialAppModeSheetCompleted) {
                showMandatoryFirstAppModeSheetIfNeeded()
            } else {
                maybeOfferPinSecurityDialog()
            }
        }
        val delayMs = if (!prefs.userInitialAppModeSheetCompleted) {
            DEFERRED_MODE_SHEET_DELAY_MS
        } else {
            0L
        }
        if (delayMs > 0L) {
            window.decorView.postDelayed(deferredHomeUiRunnable!!, delayMs)
        } else {
            window.decorView.post(deferredHomeUiRunnable!!)
        }
    }

    /** Standalone vs default: shell gradient, tab strip, and pager surface. */
    private fun refreshUserShellChrome() {
        val main = findViewById<LinearLayout>(R.id.userStandaloneMainColumn)
        val tabCard = findViewById<MaterialCardView>(R.id.userStandaloneTabStripCard)
        val pagerCard = findViewById<MaterialCardView>(R.id.userStandalonePagerCard)
        val tabNavInner = findViewById<View>(R.id.userStandaloneTabNavInner)
        if (StandaloneUi.isUserStandalone(this)) {
            main.setBackgroundResource(R.drawable.bg_standalone_app_shell)
            tabCard.setCardBackgroundColor(ContextCompat.getColor(this, R.color.standalone_tab_strip_bg))
            tabCard.strokeWidth = 0
            tabCard.strokeColor = Color.TRANSPARENT
            tabCard.radius = shellDp(20).toFloat()
            pagerCard.setCardBackgroundColor(ContextCompat.getColor(this, R.color.standalone_tab_strip_bg))
            tabNavInner.setBackgroundResource(R.drawable.bg_standalone_tab_nav_container)
            tabLayout.setBackgroundColor(Color.TRANSPARENT)
            tabLayout.setSelectedTabIndicator(ContextCompat.getDrawable(this, R.drawable.tab_indicator_standalone))
            tabLayout.tabIndicatorAnimationMode = TabLayout.INDICATOR_ANIMATION_MODE_ELASTIC
            tabLayout.isTabIndicatorFullWidth = false
            tabLayout.setSelectedTabIndicatorHeight(shellDp(3))
            tabLayout.tabRippleColor = ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.standalone_tab_ripple),
            )
            tabLayout.setTabTextColors(
                ContextCompat.getColor(this, R.color.standalone_tab_text_normal),
                ContextCompat.getColor(this, R.color.standalone_tab_text_selected),
            )
            (tabCard.layoutParams as LinearLayout.LayoutParams).apply {
                marginStart = shellDp(8)
                marginEnd = shellDp(8)
                topMargin = shellDp(10)
            }
            (pagerCard.layoutParams as LinearLayout.LayoutParams).apply {
                marginStart = shellDp(8)
                marginEnd = shellDp(8)
                topMargin = shellDp(8)
                bottomMargin = shellDp(10)
            }
        } else {
            main.setBackgroundResource(R.drawable.bg_admin_dashboard_surface)
            tabCard.setCardBackgroundColor(ContextCompat.getColor(this, R.color.summary_card))
            tabCard.strokeWidth = shellDp(1)
            tabCard.strokeColor = ContextCompat.getColor(this, R.color.summary_stroke)
            tabCard.radius = shellDp(14).toFloat()
            pagerCard.setCardBackgroundColor(ContextCompat.getColor(this, R.color.summary_card))
            tabNavInner.background = null
            tabLayout.setBackgroundColor(Color.TRANSPARENT)
            // Admin-style “needle” strip: full-width underline on the summary card, not the standalone pill strip.
            tabLayout.setSelectedTabIndicator(ContextCompat.getDrawable(this, R.drawable.tab_indicator_default))
            tabLayout.tabIndicatorAnimationMode = TabLayout.INDICATOR_ANIMATION_MODE_LINEAR
            tabLayout.isTabIndicatorFullWidth = true
            tabLayout.setSelectedTabIndicatorHeight(shellDp(3))
            tabLayout.setSelectedTabIndicatorColor(ContextCompat.getColor(this, R.color.button_primary_bg))
            val ripple = ColorUtils.setAlphaComponent(
                ContextCompat.getColor(this, R.color.text_secondary),
                0x33,
            )
            tabLayout.tabRippleColor = ColorStateList.valueOf(ripple)
            tabLayout.setTabTextColors(
                ContextCompat.getColor(this, R.color.text_secondary),
                ContextCompat.getColor(this, R.color.connection_panel_title),
            )
            (tabCard.layoutParams as LinearLayout.LayoutParams).apply {
                marginStart = shellDp(14)
                marginEnd = shellDp(14)
                topMargin = shellDp(12)
            }
            (pagerCard.layoutParams as LinearLayout.LayoutParams).apply {
                marginStart = shellDp(14)
                marginEnd = shellDp(14)
                topMargin = shellDp(10)
                bottomMargin = shellDp(12)
            }
        }
        tabCard.requestLayout()
        pagerCard.requestLayout()
        syncSidebarConnectButtonStyleWithRelayState()
    }

    /** Sidebar Connect: Default mode = medicine-box green; Standalone = same gradient as Health hub hero. */
    private fun setSidebarConnectBackgroundDrawable(connected: Boolean, connecting: Boolean) {
        val resId = when {
            connected -> R.drawable.bg_sidebar_connect_connected
            connecting && !StandaloneUi.isUserStandalone(this) -> R.drawable.bg_sidebar_connect_connecting_default
            connecting -> R.drawable.bg_sidebar_connect_standalone
            StandaloneUi.isUserStandalone(this) -> R.drawable.bg_sidebar_connect_standalone
            else -> R.drawable.bg_sidebar_connect_default
        }
        btnConnect.background = ContextCompat.getDrawable(this, resId)
        btnConnect.backgroundTintList = null
    }

    private fun syncSidebarConnectButtonStyleWithRelayState() {
        if (!::btnConnect.isInitialized) return
        val connected = connectionService?.isConnected() == true
        if (sidebarConnectShowsConnecting) {
            setSidebarConnectBackgroundDrawable(connected = false, connecting = true)
        } else {
            setSidebarConnectBackgroundDrawable(connected = connected, connecting = false)
        }
    }

    private fun setupTabs(restoreTab: Int = 0) {
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
        viewPager.setCurrentItem(restoreTab.coerceIn(0, 5), false)
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
            val cf = IntentFilter().apply {
                addAction(AlertEvents.ACTION_CONNECTION_STATE_CHANGED)
                addAction(AlertEvents.ACTION_USER_DATABUS_SOCKET_STATE)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(relayConnectionReceiver, cf, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(relayConnectionReceiver, cf)
            }
            connectionBroadcastRegistered = true
        }
        if (!displayModeReceiverRegistered) {
            val df = IntentFilter(AlertEvents.ACTION_USER_DISPLAY_MODE_FROM_SERVER)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(displayModeFromServerReceiver, df, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(displayModeFromServerReceiver, df)
            }
            displayModeReceiverRegistered = true
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
        if (displayModeReceiverRegistered) {
            try { unregisterReceiver(displayModeFromServerReceiver) } catch (_: Exception) {}
            displayModeReceiverRegistered = false
        }
        super.onStop()
    }

    override fun onDestroy() {
        deferredHomeUiRunnable?.let { r ->
            try {
                window.decorView.removeCallbacks(r)
            } catch (_: Exception) {
            }
        }
        deferredHomeUiRunnable = null
        pinDeferredPromptRunnable?.let { r ->
            try {
                window.decorView.removeCallbacks(r)
            } catch (_: Exception) {
            }
        }
        pinDeferredPromptRunnable = null
        try {
            firstAppModeBottomSheet?.dismiss()
        } catch (_: Exception) {
        }
        firstAppModeBottomSheet = null
        dismissAppModeLabelPopup()
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
        sidebarConnectShowsConnecting = false
        btnConnect.text = if (connected) {
            getString(R.string.disconnect)
        } else {
            getString(R.string.user_sidebar_connect_for_alerts)
        }
        setSidebarConnectBackgroundDrawable(connected = connected, connecting = false)
        refreshUserSidebar()
    }

    private fun applyConnectionButtonConnectingUi() {
        sidebarConnectShowsConnecting = true
        btnConnect.text = getString(R.string.connecting)
        setSidebarConnectBackgroundDrawable(connected = false, connecting = true)
        refreshUserSidebar()
    }

    private fun disconnectService() {
        try {
            unbindService(serviceConnection)
        } catch (_: Exception) { }
        connectionService = null
        ConnectionManager.requestDisconnectRelay(this)
        sidebarConnectShowsConnecting = false
        btnConnect.text = getString(R.string.user_sidebar_connect_for_alerts)
        setSidebarConnectBackgroundDrawable(connected = false, connecting = false)
        refreshUserSidebar()
    }

    private fun setSidebarLine(tv: TextView, level: Int, message: String) {
        val prefix = when (level) {
            0 -> "🟢 "
            1 -> "🟡 "
            else -> "🔴 "
        }
        val full = prefix + message
        val bodyColor = ContextCompat.getColor(this, R.color.text_primary)
        val s = SpannableString(full)
        if (full.length > prefix.length) {
            s.setSpan(
                ForegroundColorSpan(bodyColor),
                prefix.length,
                full.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        tv.text = s
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
            val prefix = "⚪ "
            val inactive = getString(R.string.user_sidebar_alerts_inactive)
            val full = prefix + inactive
            val bodyColor = ContextCompat.getColor(this, R.color.text_primary)
            val s = SpannableString(full)
            s.setSpan(
                ForegroundColorSpan(bodyColor),
                prefix.length,
                full.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            tvUserSidebarAlertsStatus.text = s
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

    private fun showMandatoryFirstAppModeSheetIfNeeded() {
        if (prefs.userInitialAppModeSheetCompleted || mandatoryModeSheetLaunched) return
        mandatoryModeSheetLaunched = true
        val sheetView = layoutInflater.inflate(R.layout.bottom_sheet_first_app_mode, null)
        val rg = sheetView.findViewById<RadioGroup>(R.id.rgFirstAppMode)
        val btnContinue = sheetView.findViewById<MaterialButton>(R.id.btnFirstModeContinue)
        val sheet = BottomSheetDialog(this)
        firstAppModeBottomSheet = sheet
        sheet.setContentView(sheetView)
        sheet.behavior.isDraggable = false
        sheet.setCancelable(false)
        sheet.setCanceledOnTouchOutside(false)
        sheet.setOnDismissListener {
            firstAppModeBottomSheet = null
            if (!prefs.userInitialAppModeSheetCompleted) {
                mandatoryModeSheetLaunched = false
            }
        }
        rg.setOnCheckedChangeListener { _, _ ->
            btnContinue.isEnabled = rg.checkedRadioButtonId != View.NO_ID
        }
        btnContinue.setOnClickListener {
            val checked = rg.checkedRadioButtonId
            if (checked == View.NO_ID) return@setOnClickListener
            val standalone = checked == R.id.rbFirstModeStandalone
            AppModeManager.setStandaloneMode(this, standalone)
            prefs.userInitialAppModeSheetCompleted = true
            UserDisplayModeApi.postDisplayModeAsync(this, standalone)
            sheet.dismiss()
            // Overview layout is chosen once in onCreateView; without a fresh activity, Dashboard stays on
            // the wrong XML until a tab switch. Same fix as toolbar mode toggle (relaunchAfterDisplayModeChange).
            window.decorView.post {
                relaunchAfterDisplayModeChange()
            }
        }
        sheet.setOnShowListener {
            val bottomSheet = sheet.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                ?: return@setOnShowListener
            bottomSheet.alpha = 0f
            val lift = 20f * resources.displayMetrics.density
            bottomSheet.translationY = lift
            BottomSheetBehavior.from(bottomSheet).run {
                skipCollapsed = true
                state = BottomSheetBehavior.STATE_EXPANDED
            }
            bottomSheet.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(320)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
        sheet.show()
    }

    private fun maybeOfferPinSecurityDialog() {
        if (prefs.appPin.isNotEmpty()) return
        if (prefs.pinDeferredAutoPromptShown) return
        if (prefs.userHomeColdStartCount < 2) return
        pinDeferredPromptRunnable?.let { r ->
            try {
                window.decorView.removeCallbacks(r)
            } catch (_: Exception) {
            }
        }
        pinDeferredPromptRunnable = Runnable {
            pinDeferredPromptRunnable = null
            if (isFinishing) return@Runnable
            if (prefs.appPin.isNotEmpty()) return@Runnable
            if (prefs.pinDeferredAutoPromptShown) return@Runnable
            val dlg = MaterialAlertDialogBuilder(this)
                .setTitle(R.string.pin_deferred_dialog_title)
                .setMessage(R.string.pin_deferred_dialog_message)
                .setNegativeButton(R.string.pin_deferred_skip, null)
                .setPositiveButton(R.string.pin_deferred_set) { _, _ ->
                    startActivity(
                        Intent(this, PinSetupActivity::class.java).putExtra(
                            PinSetupActivity.EXTRA_NEXT_ROLE,
                            LocalUserStore.ROLE_USER,
                        ),
                    )
                }
                .create()
            dlg.setOnDismissListener { prefs.pinDeferredAutoPromptShown = true }
            dlg.show()
        }
        window.decorView.postDelayed(pinDeferredPromptRunnable!!, DEFERRED_PIN_PROMPT_DELAY_MS)
    }

    private fun dismissAppModeLabelPopup() {
        try {
            appModeLabelPopup?.dismiss()
        } catch (_: Exception) {
        }
        appModeLabelPopup = null
    }

    /** Breadth-first: menu action views live under [toolbar] with [android.view.View.id] == itemId. */
    private fun findToolbarMenuAnchor(toolbar: View, itemId: Int): View? {
        val queue = ArrayDeque<ViewGroup>()
        (toolbar as? ViewGroup)?.let { queue.add(it) }
        while (queue.isNotEmpty()) {
            val g = queue.removeFirst()
            for (i in 0 until g.childCount) {
                val c = g.getChildAt(i)
                if (c.id == itemId) return c
                if (c is ViewGroup) queue.add(c)
            }
        }
        return null
    }

    private fun showCurrentAppModeInfo() {
        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.userStandaloneToolbar)
        if (appModeLabelPopup?.isShowing == true) {
            dismissAppModeLabelPopup()
            return
        }
        dismissAppModeLabelPopup()
        val anchor = findToolbarMenuAnchor(toolbar, R.id.action_app_mode)
        if (anchor == null) {
            toolbar.post {
                findToolbarMenuAnchor(toolbar, R.id.action_app_mode)?.let { showCurrentAppModeInfoInternal(it) }
            }
            return
        }
        showCurrentAppModeInfoInternal(anchor)
    }

    private fun showCurrentAppModeInfoInternal(anchor: View) {
        val label = if (AppModeManager.isStandaloneMode(this)) {
            getString(R.string.user_mode_standalone)
        } else {
            getString(R.string.user_mode_default)
        }
        val content = layoutInflater.inflate(R.layout.popup_toolbar_mode_label, null, false) as TextView
        content.text = label
        val density = resources.displayMetrics.density
        val gap = (6 * density).toInt()
        val popup = PopupWindow(
            content,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            true,
        )
        popup.isOutsideTouchable = true
        popup.isFocusable = true
        popup.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            popup.elevation = 10f * density
        }
        popup.setOnDismissListener { appModeLabelPopup = null }
        appModeLabelPopup = popup
        content.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED),
            android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED),
        )
        val pw = content.measuredWidth
        val xOff = ((anchor.width - pw) / 2f).toInt()
        popup.showAsDropDown(anchor, xOff, gap)
    }

    /**
     * After Default ↔ Standalone save: [recreate] restores ViewPager fragments (wrong layouts until
     * tab switch). [super.onCreate(null)] to skip that restore has caused process crashes on some
     * devices. Starting a fresh instance + [finish] gives a clean fragment tree with no restore.
     */
    fun relaunchAfterDisplayModeChange() {
        val tab = if (::viewPager.isInitialized) viewPager.currentItem else 0
        AppLockState.grantUnlock(60_000L)
        AppLockState.clearBackgroundTimestamp()
        prefs.lastBackgroundAtMs = 0L
        val i = Intent(this, UserStandaloneActivity::class.java)
        i.putExtra(EXTRA_RELAUNCH_TAB, tab.coerceIn(0, 5))
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        startActivity(i)
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }

    /** Toolbar label + medicine box colors after mode popup saves [AppModeManager]. */
    fun notifyUserAppModePreferenceChanged() {
        refreshUserShellChrome()
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
