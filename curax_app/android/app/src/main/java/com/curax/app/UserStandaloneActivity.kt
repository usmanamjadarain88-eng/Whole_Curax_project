package com.curax.app

import android.app.Activity
import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.content.BroadcastReceiver
import android.content.IntentFilter
import java.io.File
import java.net.URLEncoder
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import android.widget.FrameLayout
import android.widget.ImageView
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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.MenuItemCompat
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.firebase.messaging.FirebaseMessaging
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import androidx.viewpager2.widget.ViewPager2
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import android.content.res.Configuration
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes

class UserStandaloneActivity : AppCompatActivity() {

    companion object {
        private const val STATE_VIEW_PAGER_TAB = "user_standalone_vp_tab"
        /** Cold start of this activity after mode toggle (avoids fragment restore from [recreate]). */
        private const val EXTRA_RELAUNCH_TAB = "user_standalone_relaunch_tab"
        /** Toolbar action order: lower = further left (theme → mode → overflow). */
        private const val MENU_ORDER_THEME = 1
        private const val MENU_ORDER_MODE = 2
        private const val MENU_ORDER_SETTINGS = 100
        /** Let the home shell paint before the mandatory mode bottom sheet appears (~1–2s). */
        private const val DEFERRED_MODE_SHEET_DELAY_MS = 1_800L
        private const val REQ_CAMERA_PROFILE = 19
    }

    private val pickGalleryLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { runProfileUploadFromUri(it) }
    }

    private val takePictureLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok && cameraCaptureUri != null) runProfileUploadFromUri(cameraCaptureUri!!)
    }

    private val esp32BlePickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            val addr = res.data?.getStringExtra(Esp32BlePickerActivity.EXTRA_MAC) ?: return@registerForActivityResult
            val name = res.data?.getStringExtra(Esp32BlePickerActivity.EXTRA_NAME).orEmpty()
            CuraxEsp32BleLink.connect(this, addr, name)
        }
    }

    private var cameraCaptureUri: Uri? = null

    private lateinit var prefs: Prefs
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var tabLayout: TabLayout
    private lateinit var viewPager: ViewPager2
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private val userShellSwipePageCallback = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageSelected(position: Int) {
            applyUserShellSwipeForTab(position)
        }
    }
    private var tabMediator: TabLayoutMediator? = null
    private var overviewFragmentRef: AdminOverviewFragment? = null
    private lateinit var btnConnect: MaterialButton
    private var sidebarConnectShowsConnecting = false
    /** After Connect tap, run relay registration only once notification permission dialog returns (Android 13+). */
    private var pendingRelayConnectAfterNotificationPermission = false
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
    private lateinit var tvUserSidebarFullName: TextView
    private lateinit var tvSidebarEsp32BleStatus: TextView
    private val mainHandler = Handler(Looper.getMainLooper())
    private var ambientPreviewTickSeq = 0
    private val ambientSidebarPreviewRunnable = object : Runnable {
        override fun run() {
            if (isFinishing || StandaloneUi.isUserStandalone(this@UserStandaloneActivity)) return
            ambientPreviewTickSeq += 1
            val f = AmbientDemoReadout.format(this@UserStandaloneActivity, ambientPreviewTickSeq)
            findViewById<TextView>(R.id.tvSidebarAmbientTemp1)?.text = f.tempZone1
            findViewById<TextView>(R.id.tvSidebarAmbientTemp2)?.text = f.tempZone2
            findViewById<TextView>(R.id.tvSidebarAmbientHumidity)?.text = f.humidity
            mainHandler.postDelayed(this, 2000L)
        }
    }
    private val sidebarSectionExpanded = BooleanArray(4)
    private lateinit var loadingOverlay: View
    /** Debounced: [LocalAlertsController.reschedule] + [DoseAutoMissedMarker.run] are heavy on the main thread. */
    private val standaloneHeavyResumeRunnable = Runnable {
        if (isFinishing || !StandaloneUi.isUserStandalone(this@UserStandaloneActivity)) return@Runnable
        LocalAlertsController.reschedule(this@UserStandaloneActivity)
        DoseAutoMissedMarker.run(this@UserStandaloneActivity)
    }
    private var connectionService: AlertConnectionService? = null
    @Volatile
    private var databusResolveInFlight = false
    @Volatile
    private var bootstrapFetchRequested = false
    private var mandatoryModeSheetLaunched = false
    private var firstAppModeBottomSheet: BottomSheetDialog? = null
    private var deferredHomeUiRunnable: Runnable? = null
    private var appModeLabelPopup: PopupWindow? = null
    private var pendingSyncSwipeTray: PendingSyncSwipeTray? = null
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
                refreshAllUserShellFragments()
                refreshUserSidebar()
                mainHandler.removeCallbacks(standaloneHeavyResumeRunnable)
                mainHandler.post(standaloneHeavyResumeRunnable)
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
    private var esp32BleReceiverRegistered = false
    private val esp32BleConnectionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == CuraxEsp32BleLink.ACTION_CONNECTION_STATE && !isFinishing) {
                refreshSidebarEsp32BleUi()
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
        if (StandaloneUi.isUserStandalone(this)) {
            // Plans live in UserPlansLocalStore; warm from network when possible so cold start matches server.
            Thread { UserPlansApi.fetchPlans(applicationContext) }.start()
        }

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
                    // Apply night mode in-place when possible (avoids full activity recreate + ViewPager rebuild).
                    // Nested post: TabLayout / cards re-read XML after applyDayNight(); refresh must run after that pass
                    // so default-mode tab strip does not briefly show standalone colors or a stale elevation shadow.
                    window.decorView.post themeApply@{
                        if (isFinishing) return@themeApply
                        delegate.applyDayNight()
                        invalidateOptionsMenu()
                        window.decorView.post chromeRefresh@{
                            if (isFinishing) return@chromeRefresh
                            refreshUserShellChrome()
                        }
                    }
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
        val maxTabIndex = userShellTabCount() - 1
        val restoreTab = if (extraTab >= 0) {
            extraTab.coerceIn(0, maxTabIndex)
        } else {
            savedInstanceState?.getInt(STATE_VIEW_PAGER_TAB)?.coerceIn(0, maxTabIndex) ?: 0
        }
        setupTabs(restoreTab)
        swipeRefresh = findViewById(R.id.userStandaloneSwipeRefresh)
        val refreshAccent = ContextCompat.getColor(this, R.color.button_primary_bg)
        swipeRefresh.setColorSchemeColors(refreshAccent)
        swipeRefresh.setProgressBackgroundColorSchemeColor(
            ContextCompat.getColor(this, R.color.surface_bg),
        )
        swipeRefresh.setOnChildScrollUpCallback { _, _ -> userShellPagerChildCanScrollUp() }
        swipeRefresh.setOnRefreshListener {
            val botId = prefs.id.trim()
            val apiKey = prefs.apiKey.trim()
            val base = prefs.centralApiUrl.trim().removeSuffix("/")
            if (botId.isEmpty() || apiKey.isEmpty() || base.isEmpty()) {
                swipeRefresh.isRefreshing = false
                return@setOnRefreshListener
            }
            UserDataBusClient.fetchAndApplyUserData(
                this,
                base,
                botId,
                apiKey,
                onFetchFinished = { swipeRefresh.isRefreshing = false },
            )
        }
        viewPager.registerOnPageChangeCallback(userShellSwipePageCallback)
        applyUserShellSwipeForTab(viewPager.currentItem)
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
        tvUserSidebarFullName = findViewById(R.id.tvUserSidebarFullName)

        findViewById<ShapeableImageView>(R.id.ivUserSidebarProfile)?.setOnClickListener {
            if (prefs.userProfilePictureDataUrl.trim().isNotEmpty()) {
                showProfilePhotoPreview()
            } else {
                showProfilePictureSourceDialog()
            }
        }
        findViewById<ImageButton>(R.id.btnUserSidebarProfileAdd)?.setOnClickListener {
            showProfilePictureSourceDialog()
        }
        bindSidebarProfileAvatar()
        findViewById<ImageButton>(R.id.btnUserSidebarProfileAdd)?.bringToFront()
        applyProfileChromeNoShadow()

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
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notificationsChannelReady()) {
                    pendingRelayConnectAfterNotificationPermission = true
                    ActivityCompat.requestPermissions(
                        this,
                        arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                        1,
                    )
                } else {
                    pendingRelayConnectAfterNotificationPermission = false
                    runConnectWakeAndRelayFlow()
                }
            }
        }

        findViewById<MaterialCardView>(R.id.cardUserSidebarAmbient)?.setOnClickListener {
            startActivity(Intent(this, UserAmbientMonitorActivity::class.java))
        }

        tvSidebarEsp32BleStatus = findViewById(R.id.tvSidebarEsp32BleStatus)
        findViewById<MaterialButton>(R.id.btnSidebarEsp32BleConnect).setOnClickListener {
            esp32BlePickerLauncher.launch(Intent(this, Esp32BlePickerActivity::class.java))
        }
        findViewById<MaterialButton>(R.id.btnSidebarEsp32BleDisconnect).setOnClickListener {
            CuraxEsp32BleLink.disconnect()
            refreshSidebarEsp32BleUi()
        }

        updateConnectionUi(false)
        // No cloud snapshot yet while waiting for admin approval — avoid blocking overlay + bogus /user/data logout.
        showLoading(!restored && !prefs.awaitingAdminLinkApproval)

        pendingSyncSwipeTray = PendingSyncSwipeTray(this)
        StandaloneUserMutationSink.swipeCardPresenter = { title, subtitle ->
            pendingSyncSwipeTray?.push(title, subtitle)
        }

        scheduleDeferredHomeDialogs()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::viewPager.isInitialized) {
            outState.putInt(STATE_VIEW_PAGER_TAB, viewPager.currentItem)
        }
    }

    override fun onPause() {
        window.decorView.removeCallbacks(standaloneHeavyResumeRunnable)
        super.onPause()
    }

    private fun scheduleStandaloneHeavyResumeDebounced() {
        val decor = window.decorView
        decor.removeCallbacks(standaloneHeavyResumeRunnable)
        val delayMs =
            if (StandaloneUserMutationGate.isStandaloneUserWithoutAdminLink(this)) 450L else 180L
        decor.postDelayed(standaloneHeavyResumeRunnable, delayMs)
    }

    override fun onResume() {
        super.onResume()
        refreshUserShellChrome()
        if (!StandaloneUi.isUserStandalone(this)) {
            CuraxEsp32BleLink.init(this)
            CuraxEsp32BleLink.connectSavedDevice(this)
        }
        refreshUserSidebar()
        AwaitingAdminLinkCoordinator.pollIfNeeded(this)
        if (StandaloneUi.isUserStandalone(this)) {
            scheduleStandaloneHeavyResumeDebounced()
            DoseNudgeController.tickDailyAdherenceIfNeeded(this)
            PendingSyncCoordinator.requestFlush(this)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1) {
            if (pendingRelayConnectAfterNotificationPermission) {
                pendingRelayConnectAfterNotificationPermission = false
                runConnectWakeAndRelayFlow()
            } else {
                refreshUserSidebar()
            }
            return
        }
        if (requestCode == REQ_CAMERA_PROFILE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                openCameraForProfile()
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.clear()
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
        findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.userStandaloneToolbar).overflowIcon?.setTint(android.graphics.Color.WHITE)
        return result
    }

    private fun isDarkModeEnabled(): Boolean {
        val mask = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return mask == Configuration.UI_MODE_NIGHT_YES
    }

    /**
     * Right after [AppCompatDelegate.applyDayNight], [resources.configuration] can still report the previous
     * UI mode for one frame, so [ContextCompat.getColor] on the Activity resolves the wrong values/values-night
     * bucket (wrong tab strip / pager chrome until recreate).
     */
    private fun isAppNightPalette(): Boolean {
        return when (prefs.themeMode) {
            AppCompatDelegate.MODE_NIGHT_YES -> true
            AppCompatDelegate.MODE_NIGHT_NO -> false
            else -> isDarkModeEnabled()
        }
    }

    private fun paletteContext(): Context {
        val cfg = Configuration(resources.configuration)
        cfg.uiMode = cfg.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv() or
            if (isAppNightPalette()) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        return createConfigurationContext(cfg)
    }

    private fun paletteColor(@ColorRes id: Int): Int =
        ContextCompat.getColor(paletteContext(), id)

    private fun paletteDrawable(@DrawableRes id: Int): android.graphics.drawable.Drawable? =
        ContextCompat.getDrawable(paletteContext(), id)

    /** M3 tab slots keep a themed ripple/surface behind labels unless stripped after theme changes. */
    private fun clearTabSlotBackgrounds() {
        if (tabLayout.childCount == 0) return
        val strip = tabLayout.getChildAt(0) as? ViewGroup ?: return
        for (i in 0 until strip.childCount) {
            val tabSlot = strip.getChildAt(i)
            tabSlot?.background = null
            tabSlot?.setBackgroundColor(Color.TRANSPARENT)
        }
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
            tabCard.setCardBackgroundColor(paletteColor(R.color.standalone_tab_strip_bg))
            tabCard.strokeWidth = 0
            tabCard.strokeColor = Color.TRANSPARENT
            tabCard.radius = shellDp(20).toFloat()
            tabCard.cardElevation = shellDp(5).toFloat()
            pagerCard.setCardBackgroundColor(paletteColor(R.color.standalone_tab_strip_bg))
            tabNavInner.setBackgroundResource(R.drawable.bg_standalone_tab_nav_container)
            tabLayout.setBackgroundColor(Color.TRANSPARENT)
            tabLayout.setSelectedTabIndicator(paletteDrawable(R.drawable.tab_indicator_standalone))
            tabLayout.tabIndicatorAnimationMode = TabLayout.INDICATOR_ANIMATION_MODE_ELASTIC
            tabLayout.isTabIndicatorFullWidth = false
            tabLayout.setSelectedTabIndicatorHeight(shellDp(3))
            tabLayout.tabRippleColor = ColorStateList.valueOf(
                paletteColor(R.color.standalone_tab_ripple),
            )
            tabLayout.setTabTextColors(
                paletteColor(R.color.standalone_tab_text_normal),
                paletteColor(R.color.standalone_tab_text_selected),
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
            val shellBg = paletteColor(R.color.surface_bg)
            tabCard.setCardBackgroundColor(shellBg)
            tabCard.strokeWidth = shellDp(1)
            tabCard.strokeColor = paletteColor(R.color.summary_stroke)
            tabCard.radius = shellDp(14).toFloat()
            tabCard.cardElevation = 0f
            pagerCard.setCardBackgroundColor(shellBg)
            tabNavInner.background = null
            tabLayout.setBackgroundColor(Color.TRANSPARENT)
            // Admin-style “needle” strip: full-width underline on the summary card, not the standalone pill strip.
            tabLayout.setSelectedTabIndicator(paletteDrawable(R.drawable.tab_indicator_default))
            tabLayout.tabIndicatorAnimationMode = TabLayout.INDICATOR_ANIMATION_MODE_LINEAR
            tabLayout.isTabIndicatorFullWidth = true
            tabLayout.setSelectedTabIndicatorHeight(shellDp(3))
            tabLayout.setSelectedTabIndicatorColor(paletteColor(R.color.button_primary_bg))
            tabLayout.tabRippleColor = ColorStateList.valueOf(
                paletteColor(R.color.smart_shell_tab_ripple),
            )
            tabLayout.setTabTextColors(
                paletteColor(R.color.text_secondary),
                paletteColor(R.color.connection_panel_title),
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
        viewPager.setBackgroundColor(
            if (StandaloneUi.isUserStandalone(this)) {
                paletteColor(R.color.standalone_tab_strip_bg)
            } else {
                paletteColor(R.color.surface_bg)
            },
        )
        clearTabSlotBackgrounds()
        tabLayout.post { clearTabSlotBackgrounds() }
        tabCard.requestLayout()
        pagerCard.requestLayout()
        syncSidebarConnectButtonStyleWithRelayState()
        applySidebarAmbientVisibility()
    }

    /** Default (Smart System) mode: ESP32 bridge + hardware ambient above Connect; hidden in Personal Health standalone. */
    private fun applySidebarAmbientVisibility() {
        val show = !StandaloneUi.isUserStandalone(this)
        findViewById<View>(R.id.cardUserSidebarAmbient)?.visibility = if (show) View.VISIBLE else View.GONE
        findViewById<View>(R.id.cardUserSidebarEsp32Ble)?.visibility = if (show) View.VISIBLE else View.GONE
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

    /** Standalone: 7 tabs (includes Dose). Default (Smart System): 8 tabs (Dose + T Adjustment + ambient sidebar). */
    private fun userShellTabCount(): Int = if (StandaloneUi.isUserStandalone(this)) 7 else 8

    private fun setupTabs(restoreTab: Int = 0) {
        val standalone = StandaloneUi.isUserStandalone(this)
        val tabCount = userShellTabCount()
        tabMediator?.detach()
        viewPager.adapter = object : androidx.viewpager2.adapter.FragmentStateAdapter(this) {
            override fun getItemCount(): Int = tabCount
            override fun createFragment(position: Int): androidx.fragment.app.Fragment {
                return if (standalone) {
                    when (position) {
                        0 -> AdminOverviewFragment().also { overviewFragmentRef = it }
                        1 -> AdminAlertsFragment()
                        2 -> DoseTrackingFragment()
                        3 -> AdminMedicalRemindersFragment()
                        4 -> AdminLogsFragment()
                        5 -> AdminReportsFragment()
                        else -> AdminSettingsFragment()
                    }
                } else {
                    when (position) {
                        0 -> AdminOverviewFragment().also { overviewFragmentRef = it }
                        1 -> AdminAlertsFragment()
                        2 -> DoseTrackingFragment()
                        3 -> AdminMedicalRemindersFragment()
                        4 -> UserTempAdjustmentFragment()
                        5 -> AdminLogsFragment()
                        6 -> AdminReportsFragment()
                        else -> AdminSettingsFragment()
                    }
                }
            }
        }
        tabMediator = TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = if (standalone) {
                when (position) {
                    0 -> "Dashboard"
                    1 -> "Alerts"
                    2 -> getString(R.string.tab_dose_tracking)
                    3 -> "Reminders"
                    4 -> "Logs"
                    5 -> "Reports"
                    else -> getString(R.string.tab_system_view)
                }
            } else {
                when (position) {
                    0 -> "Dashboard"
                    1 -> "Alerts"
                    2 -> getString(R.string.tab_dose_tracking)
                    3 -> "Reminders"
                    4 -> getString(R.string.tab_temp_adjustment)
                    5 -> "Logs"
                    6 -> "Reports"
                    else -> getString(R.string.tab_system_view)
                }
            }
        }.apply { attach() }
        viewPager.setCurrentItem(restoreTab.coerceIn(0, tabCount - 1), false)
        viewPager.attachSwipeRefreshNestedHandoff()
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
                refreshAllUserShellFragments()
                refreshUserSidebar()
                mainHandler.removeCallbacks(standaloneHeavyResumeRunnable)
                mainHandler.post(standaloneHeavyResumeRunnable)
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
        if (!esp32BleReceiverRegistered) {
            val ef = IntentFilter(CuraxEsp32BleLink.ACTION_CONNECTION_STATE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(esp32BleConnectionReceiver, ef, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(esp32BleConnectionReceiver, ef)
            }
            esp32BleReceiverRegistered = true
        }
        ensureUserDataBusConnected()
        UserDataBusClient.scheduleApiFallbackIfDataBusOffline(this)
        bootstrapStandaloneDataOnce()
        ConnectionManager.requestReconnectRelayNow(this)
        window.decorView.postDelayed({ refreshUserSidebar() }, 900L)
        window.decorView.postDelayed({ refreshUserSidebar() }, 2800L)
        startAmbientSidebarPreviewIfNeeded()
    }

    private fun startAmbientSidebarPreviewIfNeeded() {
        mainHandler.removeCallbacks(ambientSidebarPreviewRunnable)
        if (StandaloneUi.isUserStandalone(this)) return
        mainHandler.post(ambientSidebarPreviewRunnable)
    }

    private fun stopAmbientSidebarPreview() {
        mainHandler.removeCallbacks(ambientSidebarPreviewRunnable)
    }

    override fun onStop() {
        stopAmbientSidebarPreview()
        if (StandaloneUi.isUserStandalone(this)) {
            StandaloneOfflineMirror.persistMergedSnapshot(applicationContext)
        }
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
        if (esp32BleReceiverRegistered) {
            try { unregisterReceiver(esp32BleConnectionReceiver) } catch (_: Exception) {}
            esp32BleReceiverRegistered = false
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
        if (::viewPager.isInitialized) {
            viewPager.unregisterOnPageChangeCallback(userShellSwipePageCallback)
        }
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
            StandaloneUserMutationSink.swipeCardPresenter = null
        }
        pendingSyncSwipeTray = null
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

    /** Battery-optimization system prompt when still optimizing this app, then relay + FCM. Notification prompt is handled before this runs (or skipped on older Android). */
    private fun runConnectWakeAndRelayFlow() {
        val id = prefs.id.trim()
        val apiKey = prefs.apiKey.trim()
        if (id.isEmpty() || apiKey.isEmpty()) return
        requestBatteryOptimizationExemption()
        connectWithLatestFcmToken(prefs.serverUrl, id, apiKey)
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

    private fun applyProfileChromeNoShadow() {
        val add = findViewById<ImageButton>(R.id.btnUserSidebarProfileAdd) ?: return
        add.stateListAnimator = null
        add.elevation = 0f
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            add.outlineSpotShadowColor = Color.TRANSPARENT
            add.outlineAmbientShadowColor = Color.TRANSPARENT
        }
        val iv = findViewById<ShapeableImageView>(R.id.ivUserSidebarProfile) ?: return
        iv.elevation = 0f
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            iv.outlineSpotShadowColor = Color.TRANSPARENT
            iv.outlineAmbientShadowColor = Color.TRANSPARENT
        }
    }

    private fun bindSidebarProfileAvatar() {
        val iv = findViewById<ShapeableImageView>(R.id.ivUserSidebarProfile) ?: return
        val raw = prefs.userProfilePictureDataUrl.trim()
        if (raw.isEmpty()) {
            iv.setImageResource(R.drawable.ic_avatar_placeholder)
            findViewById<ImageButton>(R.id.btnUserSidebarProfileAdd)?.bringToFront()
            return
        }
        Thread {
            val bmp = UserProfileImageCodec.bitmapFromDataUrl(raw)
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (bmp != null) iv.setImageBitmap(bmp) else iv.setImageResource(R.drawable.ic_avatar_placeholder)
                findViewById<ImageButton>(R.id.btnUserSidebarProfileAdd)?.bringToFront()
            }
        }.start()
    }

    private fun showProfilePhotoPreview() {
        val raw = prefs.userProfilePictureDataUrl.trim()
        if (raw.isEmpty()) return
        Thread {
            val bmp: Bitmap? = UserProfileImageCodec.bitmapFromDataUrl(raw)
            runOnUiThread {
                if (isFinishing || bmp == null) return@runOnUiThread
                val dm = resources.displayMetrics
                val pad = (16f * dm.density).toInt()
                val maxH = (dm.heightPixels * 0.55f).toInt()
                val maxW = dm.widthPixels - (32f * dm.density).toInt()
                val iv = ImageView(this).apply {
                    setImageBitmap(bmp)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    adjustViewBounds = true
                    maxHeight = maxH
                    maxWidth = maxW
                }
                val wrap = FrameLayout(this).apply {
                    setPadding(pad, pad, pad, pad)
                    addView(
                        iv,
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ),
                    )
                }
                AlertDialog.Builder(this)
                    .setView(wrap)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }.start()
    }

    private fun showProfilePictureSourceDialog() {
        val dm = resources.displayMetrics
        val padH = (20f * dm.density).toInt()
        val padV = (12f * dm.density).toInt()
        val gap = (8f * dm.density).toInt()
        val panelBg = ContextCompat.getColor(this, R.color.summary_card)
        val labelColor = ContextCompat.getColor(this, R.color.text_primary)
        val outlineCol = ContextCompat.getColor(this, R.color.summary_stroke)
        val rippleCol = ContextCompat.getColor(this, R.color.smart_shell_tab_ripple)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padH, padV, padH, padV)
            background = ColorDrawable(panelBg)
        }
        val rowLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        val btnCamera = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            layoutParams = LinearLayout.LayoutParams(rowLp)
            text = getString(R.string.user_profile_pick_camera)
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            setTextColor(labelColor)
            strokeColor = ColorStateList.valueOf(outlineCol)
            rippleColor = ColorStateList.valueOf(rippleCol)
        }
        val btnGallery = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            layoutParams = LinearLayout.LayoutParams(rowLp).apply { topMargin = gap }
            text = getString(R.string.user_profile_pick_gallery)
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            setTextColor(labelColor)
            strokeColor = ColorStateList.valueOf(outlineCol)
            rippleColor = ColorStateList.valueOf(rippleCol)
        }
        root.addView(btnCamera)
        root.addView(btnGallery)
        val dialog = AlertDialog.Builder(this).setView(root).create()
        btnCamera.setOnClickListener {
            dialog.dismiss()
            requestCameraThenCapture()
        }
        btnGallery.setOnClickListener {
            dialog.dismiss()
            pickGalleryLauncher.launch("image/*")
        }
        dialog.show()
    }

    private fun requestCameraThenCapture() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQ_CAMERA_PROFILE)
            return
        }
        openCameraForProfile()
    }

    private fun openCameraForProfile() {
        val dir = File(cacheDir, "profile_snapshots").apply { mkdirs() }
        val f = File(dir, "cap_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", f)
        cameraCaptureUri = uri
        try {
            takePictureLauncher.launch(uri)
        } catch (_: Exception) {
            CuraxFeedback.warn(this, getString(R.string.user_profile_upload_fail))
        }
    }

    private fun runProfileUploadFromUri(uri: Uri) {
        CuraxFeedback.info(this, getString(R.string.user_profile_uploading))
        Thread {
            val bmp = UserProfileImageCodec.loadAndDownscale(this, uri)
            if (bmp == null) {
                runOnUiThread { CuraxFeedback.warn(this@UserStandaloneActivity, getString(R.string.user_profile_upload_fail)) }
                return@Thread
            }
            val dataUrl = UserProfileImageCodec.toJpegDataUrl(bmp)
            if (!bmp.isRecycled) bmp.recycle()
            val (ok, err) = UserProfilePictureApi.uploadProfilePicture(applicationContext, dataUrl)
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (ok) {
                    prefs.userProfilePictureDataUrl = dataUrl
                    bindSidebarProfileAvatar()
                    StandaloneOfflineMirror.persistMergedSnapshot(applicationContext)
                    CuraxFeedback.success(this, getString(R.string.user_profile_upload_ok))
                } else {
                    CuraxFeedback.warn(
                        this,
                        getString(R.string.user_profile_upload_fail) + (err?.let { ": $it" } ?: ""),
                    )
                }
            }
        }.start()
    }

    private fun refreshUserSidebar() {
        if (!::tvUserSidebarAdminStatus.isInitialized) return
        bindSidebarProfileAvatar()

        val displayFullName = prefs.userHubUsername.trim()
            .ifEmpty { prefs.userHubFullName.trim() }
            .ifEmpty { prefs.userHubFirstName.trim() }
        if (displayFullName.isNotEmpty()) {
            tvUserSidebarFullName.text = displayFullName
            tvUserSidebarFullName.visibility = View.VISIBLE
        } else {
            tvUserSidebarFullName.text = ""
            tvUserSidebarFullName.visibility = View.GONE
        }

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

        val awaitingAdmin = prefs.awaitingAdminLinkApproval
        val pendingAdminLabel = prefs.awaitingAdminChosenDisplayName.trim()

        if (awaitingAdmin) {
            val pendingMsg = if (pendingAdminLabel.isNotEmpty()) {
                getString(R.string.user_sidebar_admin_request_pending_named, pendingAdminLabel)
            } else {
                getString(R.string.user_sidebar_admin_request_pending_generic)
            }
            setSidebarLine(tvUserSidebarAdminStatus, 1, pendingMsg)
        } else if (!adminLinked) {
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
            awaitingAdmin -> setSidebarLine(
                tvUserSidebarHealthStatus,
                1,
                getString(R.string.user_sidebar_health_local_until_admin),
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

        // Alerts are "active" only when relay is connected (Connect for alerts completed), not merely when FCM token exists.
        val alertsRelayReady = relayOk &&
            prefs.fcmToken.trim().isNotEmpty() &&
            notificationsChannelReady()
        if (alertsRelayReady) {
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
        tvUserSidebarAdminDetail.text = when {
            adminLinked -> getString(R.string.user_sidebar_detail_admin_linked, displayAdmin)
            awaitingAdmin -> if (pendingAdminLabel.isNotEmpty()) {
                getString(R.string.user_sidebar_detail_admin_pending_named, pendingAdminLabel)
            } else {
                getString(R.string.user_sidebar_detail_admin_pending_generic)
            }
            else -> getString(R.string.user_sidebar_detail_admin_not_linked)
        }

        val healthDetailRes = when {
            !configOk -> R.string.user_sidebar_detail_health_config
            awaitingAdmin -> R.string.user_sidebar_detail_health_until_admin_detail
            !adminLinked -> R.string.user_sidebar_detail_health_no_admin
            databusOk && relayOk -> R.string.user_sidebar_detail_health_ok
            databusOk || relayOk -> R.string.user_sidebar_detail_health_partial
            snapshotOk -> R.string.user_sidebar_detail_health_offline
            else -> R.string.user_sidebar_detail_health_issues
        }
        tvUserSidebarHealthDetail.setText(healthDetailRes)

        tvUserSidebarAlertsDetail.text = if (alertsRelayReady) {
            getString(R.string.user_sidebar_detail_alerts_active)
        } else {
            getString(R.string.user_sidebar_detail_alerts_inactive)
        }

        tvUserSidebarRealtimeDetail.text = when (rtState) {
            0 -> getString(R.string.user_sidebar_detail_realtime_connected)
            1 -> getString(R.string.user_sidebar_detail_realtime_reconnecting)
            else -> getString(R.string.user_sidebar_detail_realtime_disconnected)
        }

        refreshSidebarEsp32BleUi()
    }

    private fun refreshSidebarEsp32BleUi() {
        if (!::tvSidebarEsp32BleStatus.isInitialized) return
        if (StandaloneUi.isUserStandalone(this)) return
        if (CuraxEsp32BleLink.isConnected()) {
            val name = CuraxEsp32BleLink.connectedDeviceName().ifEmpty {
                prefs.esp32BleDeviceAddress
            }
            tvSidebarEsp32BleStatus.text = getString(
                R.string.dose_ble_status_connected,
                name.ifEmpty { "ESP32" },
            )
        } else {
            tvSidebarEsp32BleStatus.text = getString(R.string.dose_ble_status_disconnected)
        }
    }

    internal fun applyAdminLinkAcceptedUiRefresh() {
        runOnUiThread {
            refreshUserShellChrome()
            refreshUserSidebar()
        }
    }

    private fun bootstrapStandaloneDataOnce() {
        if (bootstrapFetchRequested) return
        bootstrapFetchRequested = true
        if (prefs.awaitingAdminLinkApproval) {
            showLoading(false)
            return
        }
        val botId = prefs.id.trim()
        val apiKey = prefs.apiKey.trim()
        val base = prefs.centralApiUrl.trim().removeSuffix("/")
        if (botId.isEmpty() || apiKey.isEmpty() || base.isEmpty()) {
            showLoading(false)
            return
        }
        // One-time startup refresh: keeps the screen accurate without polling or tab-switch fetches.
        UserDataBusClient.fetchAndApplyUserData(
            this,
            base,
            botId,
            apiKey,
            onFetchFinished = { showLoading(false) },
        )
    }

    private fun showMandatoryFirstAppModeSheetIfNeeded() {
        if (prefs.userInitialAppModeSheetCompleted || mandatoryModeSheetLaunched) return
        mandatoryModeSheetLaunched = true
        val sheetView = layoutInflater.inflate(R.layout.bottom_sheet_first_app_mode, null)
        val cardSmart = sheetView.findViewById<MaterialCardView>(R.id.card_first_mode_smart)
        val cardPersonal = sheetView.findViewById<MaterialCardView>(R.id.card_first_mode_personal)
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
        /** false = Smart System (server default), true = Personal Health (server standalone). */
        var wantStandalone = false
        fun applyFirstModeCardSelection(smartSelected: Boolean) {
            wantStandalone = !smartSelected
            val d = resources.displayMetrics.density
            val thin = (1f * d).toInt().coerceAtLeast(1)
            val thick = (2f * d).toInt().coerceAtLeast(thin + 1)
            val accent = ContextCompat.getColor(this, R.color.connect_button_bg)
            val muted = ContextCompat.getColor(this, R.color.summary_stroke)
            cardSmart.strokeWidth = if (smartSelected) thick else thin
            cardSmart.setStrokeColor(ColorStateList.valueOf(if (smartSelected) accent else muted))
            cardPersonal.strokeWidth = if (!smartSelected) thick else thin
            cardPersonal.setStrokeColor(ColorStateList.valueOf(if (!smartSelected) accent else muted))
            cardSmart.alpha = if (smartSelected) 1f else 0.9f
            cardPersonal.alpha = if (!smartSelected) 1f else 0.9f
        }
        applyFirstModeCardSelection(smartSelected = true)
        cardSmart.setOnClickListener { applyFirstModeCardSelection(smartSelected = true) }
        cardPersonal.setOnClickListener { applyFirstModeCardSelection(smartSelected = false) }
        btnContinue.setOnClickListener {
            AppModeManager.setStandaloneMode(this, wantStandalone)
            prefs.userInitialAppModeSheetCompleted = true
            UserDisplayModeApi.postDisplayModeAsync(this, wantStandalone)
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
        val cap = ((if (::viewPager.isInitialized) viewPager.adapter?.itemCount else null)
            ?: userShellTabCount()) - 1
        AppLockState.grantUnlock(60_000L)
        AppLockState.clearBackgroundTimestamp()
        prefs.lastBackgroundAtMs = 0L
        val i = Intent(this, UserStandaloneActivity::class.java)
        i.putExtra(EXTRA_RELAUNCH_TAB, tab.coerceIn(0, cap.coerceAtLeast(0)))
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

    /**
     * Pull-to-refresh: Dashboard, Reminders, Settings. Disabled on Alerts, Dose, T adjustment, Logs, Reports
     * (nested scroll / BLE screen — T adjustment uses per-Peltier icon refresh instead).
     */
    private fun userShellIsPullToRefreshDisabledTab(position: Int): Boolean {
        val standalone = StandaloneUi.isUserStandalone(this)
        return if (standalone) {
            when (position) {
                1, 2, 4, 5 -> true // Alerts, Dose, Logs, Reports
                else -> false
            }
        } else {
            when (position) {
                1, 2, 4, 5, 6 -> true // Alerts, Dose, T adjustment, Logs, Reports
                else -> false
            }
        }
    }

    private fun applyUserShellSwipeForTab(position: Int) {
        if (!::swipeRefresh.isInitialized) return
        val allow = !userShellIsPullToRefreshDisabledTab(position)
        swipeRefresh.isEnabled = allow
        if (!allow) swipeRefresh.isRefreshing = false
    }

    /**
     * After WebSocket/user/data applies [AdminDemoData], refresh every user-shell tab that currently has a view.
     * ViewPager2 destroys far off-screen fragments; those repaint from memory when opened. Tabs that stay alive must not depend on tab switches.
     */
    private fun firstVerticalScrollableIn(view: View?): View? {
        if (view == null) return null
        if (view is androidx.core.widget.NestedScrollView || view is android.widget.ScrollView) return view
        if (view is androidx.recyclerview.widget.RecyclerView) {
            val lm = view.layoutManager
            if (lm is androidx.recyclerview.widget.LinearLayoutManager &&
                lm.orientation == androidx.recyclerview.widget.RecyclerView.VERTICAL
            ) {
                return view
            }
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                firstVerticalScrollableIn(view.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    private fun userShellPagerChildCanScrollUp(): Boolean {
        if (!::viewPager.isInitialized) return false
        if (!swipeRefresh.isEnabled) return false
        val fsa = viewPager.adapter as? FragmentStateAdapter ?: return false
        val tag = "f${fsa.getItemId(viewPager.currentItem)}"
        val frag = supportFragmentManager.findFragmentByTag(tag) ?: return false
        val scrollable = firstVerticalScrollableIn(frag.view)
        return scrollable?.canScrollVertically(-1) == true
    }

    private fun refreshAllUserShellFragments() {
        supportFragmentManager.executePendingTransactions()
        val seen = mutableSetOf<androidx.fragment.app.Fragment>()
        fun notifyFragment(f: androidx.fragment.app.Fragment) {
            if (!seen.add(f)) return
            when (f) {
                is AdminOverviewFragment ->
                    if (f.isAdded && f.view != null) {
                        f.refreshStandaloneFromMemory()
                        f.view?.requestLayout()
                        f.view?.invalidate()
                        f.view?.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvInventory)?.apply {
                            requestLayout()
                            invalidate()
                            adapter?.notifyDataSetChanged()
                        }
                    }
                is DoseTrackingFragment -> f.applyRemoteUserDataSync()
                is AdminMedicalRemindersFragment ->
                    if (f.isAdded && f.view != null) {
                        f.view?.post { f.refresh() }
                    }
                is AdminSettingsFragment ->
                    if (f.isAdded && f.view != null) {
                        f.view?.post { f.refresh() }
                    }
                is AdminAlertsFragment ->
                    if (f.isAdded && f.view != null) {
                        f.view?.post { f.refresh() }
                    }
                is AdminLogsFragment ->
                    if (f.isAdded && f.view != null) {
                        f.view?.post { f.refresh() }
                    }
                is AdminReportsFragment ->
                    if (f.isAdded && f.view != null) {
                        f.view?.post { f.refresh() }
                    }
                else -> { }
            }
            for (c in f.childFragmentManager.fragments) {
                notifyFragment(c)
            }
        }
        for (top in supportFragmentManager.fragments) {
            notifyFragment(top)
        }
        if (::viewPager.isInitialized) {
            val fsa = viewPager.adapter as? FragmentStateAdapter
            if (fsa != null) {
                for (i in 0 until fsa.itemCount) {
                    val tag = "f${fsa.getItemId(i)}"
                    supportFragmentManager.findFragmentByTag(tag)?.let { notifyFragment(it) }
                }
            }
        }
    }
}
