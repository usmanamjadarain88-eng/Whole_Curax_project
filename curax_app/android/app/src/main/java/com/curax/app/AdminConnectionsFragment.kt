package com.curax.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.widget.AppCompatImageButton
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Last successful Connections tab pending-requests UI (process-wide).
 * Avoids re-hitting HTTP + progress every time the tab is recreated or [ACTION_ADMIN_DATA_SYNCED] fires often.
 */
private object AdminConnectionsPendingCache {
    const val MIN_REFRESH_INTERVAL_MS = 90_000L
    @Volatile var lastSuccessAtMs: Long = 0L
    @Volatile private var cacheValid: Boolean = false
    @Volatile private var cachedForUi: List<PendingLinkRequestUi> = emptyList()
    @Volatile private var cacheWasDemo: Boolean = false
    @Volatile private var lastPendingRealCount: Int = 0

    fun invalidate() {
        cacheValid = false
        cachedForUi = emptyList()
        cacheWasDemo = false
        lastPendingRealCount = 0
        lastSuccessAtMs = 0L
    }

    fun record(forUi: List<PendingLinkRequestUi>, showDemo: Boolean, realPendingCount: Int) {
        cachedForUi = forUi.toList()
        cacheWasDemo = showDemo
        lastPendingRealCount = realPendingCount
        lastSuccessAtMs = System.currentTimeMillis()
        cacheValid = true
    }

    fun shouldServeFromCache(now: Long, force: Boolean): Boolean {
        if (force || !cacheValid || lastSuccessAtMs <= 0L) return false
        return now - lastSuccessAtMs < MIN_REFRESH_INTERVAL_MS
    }

    /** True after at least one successful fetch; used to avoid flashing progress on repeat loads. */
    fun hasValidCache(): Boolean = cacheValid

    fun restoreInto(
        adapter: PendingLinkRequestsAdapter,
        tvEmpty: TextView,
        recycler: RecyclerView,
        emptyRes: String,
    ) {
        adapter.submit(cachedForUi)
        val showDemo = cacheWasDemo
        val pendingRowsEmpty = lastPendingRealCount == 0
        tvEmpty.visibility = if (pendingRowsEmpty && !showDemo) View.VISIBLE else View.GONE
        recycler.visibility = if (!pendingRowsEmpty || showDemo) View.VISIBLE else View.GONE
        if (pendingRowsEmpty && !showDemo) {
            tvEmpty.text = emptyRes
        }
    }
}

/** Pending directory link requests (accept / decline). */
class AdminConnectionsFragment : Fragment() {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private lateinit var prefs: Prefs
    private lateinit var progress: ProgressBar
    private lateinit var recycler: RecyclerView
    private lateinit var adapter: PendingLinkRequestsAdapter
    private lateinit var tvEmpty: TextView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var tvConnectionCode: TextView
    private lateinit var btnCopyCode: AppCompatImageButton

    private val loadGen = AtomicInteger(0)
    private var receiverRegistered = false
    private val syncReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != AlertEvents.ACTION_ADMIN_DATA_SYNCED) return
            val host = this@AdminConnectionsFragment
            if (!host.isAdded) return
            // Pending requests do not auto-refresh on every socket/tab (avoids progress bar churn).
            // Pull-to-refresh or accept/decline still loads fresh data.
            host.view?.post { host.bindConnectionCode() }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_admin_connections, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = Prefs(requireContext())
        progress = view.findViewById(R.id.progressAdminConnections)
        recycler = view.findViewById(R.id.recyclerAdminConnectionsRequests)
        tvEmpty = view.findViewById(R.id.tvAdminConnectionsEmpty)
        swipeRefresh = view.findViewById(R.id.swipeAdminConnections)
        val accent = ContextCompat.getColor(requireContext(), R.color.button_primary_bg)
        swipeRefresh.setColorSchemeColors(accent)
        swipeRefresh.setProgressBackgroundColorSchemeColor(
            ContextCompat.getColor(requireContext(), R.color.surface_bg),
        )
        swipeRefresh.setOnChildScrollUpCallback { _, child -> child?.canScrollVertically(-1) == true }
        swipeRefresh.setOnRefreshListener { loadPending(force = true, fromPullToRefresh = true) }
        view.findViewById<NestedScrollView>(R.id.scrollAdminConnections)
            .attachHorizontalScrollNestedHandoff(immediateDisallowOnDown = false)
        tvConnectionCode = view.findViewById(R.id.tvAdminConnectionsConnectionCode)
        btnCopyCode = view.findViewById(R.id.btnAdminConnectionsCopyCode)

        adapter = PendingLinkRequestsAdapter(
            onAccept = { row -> postDecision(row, accept = true) },
            onDecline = { row -> postDecision(row, accept = false) },
        )
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.isNestedScrollingEnabled = false
        recycler.adapter = adapter

        btnCopyCode.setOnClickListener {
            val code = prefs.connectionCode.trim()
            if (code.isEmpty()) {
                CuraxFeedback.warn(this, getString(R.string.connection_code_not_available), long = true)
                return@setOnClickListener
            }
            (requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("", code))
            CuraxFeedback.success(this, getString(R.string.admin_hub_code_copied))
        }
        bindConnectionCode()
        loadPending(force = false, fromPullToRefresh = false)
    }

    private fun bindConnectionCode() {
        if (!this::tvConnectionCode.isInitialized) return
        tvConnectionCode.text = prefs.connectionCode.trim().ifEmpty { "—" }
    }

    private fun stopConnectionsSwipeRefresh() {
        if (!this::swipeRefresh.isInitialized) return
        swipeRefresh.isRefreshing = false
    }

    override fun onStart() {
        super.onStart()
        if (!receiverRegistered) {
            val filter = IntentFilter(AlertEvents.ACTION_ADMIN_DATA_SYNCED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requireContext().registerReceiver(syncReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                requireContext().registerReceiver(syncReceiver, filter)
            }
            receiverRegistered = true
        }
    }

    override fun onResume() {
        super.onResume()
        bindConnectionCode()
    }

    override fun onDestroyView() {
        if (receiverRegistered) {
            try {
                requireContext().unregisterReceiver(syncReceiver)
            } catch (_: Exception) {
            }
            receiverRegistered = false
        }
        super.onDestroyView()
    }

    private fun loadPending(force: Boolean = false, fromPullToRefresh: Boolean = false) {
        if (!this::progress.isInitialized) return
        val accessCode = prefs.adminAccessCode.trim()
        val base = prefs.centralApiUrl.trim().removeSuffix("/")
        if (base.isEmpty() || accessCode.isEmpty()) {
            AdminConnectionsPendingCache.invalidate()
            progress.visibility = View.GONE
            adapter.submit(emptyList())
            tvEmpty.visibility = View.VISIBLE
            tvEmpty.text = getString(R.string.admin_hub_need_sign_in)
            recycler.visibility = View.GONE
            bindConnectionCode()
            stopConnectionsSwipeRefresh()
            return
        }

        val now = System.currentTimeMillis()
        if (AdminConnectionsPendingCache.shouldServeFromCache(now, force)) {
            progress.visibility = View.GONE
            AdminConnectionsPendingCache.restoreInto(
                adapter = adapter,
                tvEmpty = tvEmpty,
                recycler = recycler,
                emptyRes = getString(R.string.admin_connections_empty),
            )
            bindConnectionCode()
            stopConnectionsSwipeRefresh()
            return
        }

        val gen = loadGen.incrementAndGet()
        progress.visibility = when {
            fromPullToRefresh -> View.GONE
            force -> View.VISIBLE
            AdminConnectionsPendingCache.hasValidCache() -> View.GONE
            else -> View.VISIBLE
        }

        Thread {
            var pendingRows = emptyList<PendingLinkRequestUi>()
            var httpErr = false
            try {
                val pendUrl = "$base/admin/pending-user-link-requests?access_code=${URLEncoder.encode(accessCode, "UTF-8")}"
                val pendRes = http.newCall(Request.Builder().url(pendUrl).get().build()).execute()
                val pendBody = pendRes.body?.string().orEmpty()
                if (pendRes.isSuccessful) {
                    val jo = if (pendBody.isNotBlank()) JSONObject(pendBody) else JSONObject()
                    val arr = jo.optJSONArray("requests") ?: JSONArray()
                    val list = mutableListOf<PendingLinkRequestUi>()
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        list.add(
                            PendingLinkRequestUi(
                                requestId = o.optString("request_id", "").trim(),
                                email = o.optString("email", "").trim(),
                                displayName = o.optString("display_name", "").trim(),
                                createdAtIso = o.optString("created_at", "").trim(),
                            ),
                        )
                    }
                    pendingRows = list.filter { it.requestId.isNotEmpty() }
                } else {
                    httpErr = true
                }
            } catch (_: Exception) {
                httpErr = true
            }

            activity?.runOnUiThread {
                try {
                    if (gen != loadGen.get()) return@runOnUiThread
                    progress.visibility = View.GONE
                    bindConnectionCode()
                    if (httpErr) {
                        AdminConnectionsPendingCache.invalidate()
                        adapter.submit(emptyList())
                        tvEmpty.visibility = View.VISIBLE
                        tvEmpty.text = getString(R.string.admin_users_load_failed)
                        recycler.visibility = View.GONE
                        return@runOnUiThread
                    }

                    val showDemo = pendingRows.isEmpty()
                    val forUi = if (showDemo) {
                        val c = requireContext()
                        listOf(
                            PendingLinkRequestUi(
                                "demo_preview_1",
                                "usman.preview@example.com",
                                c.getString(R.string.admin_demo_name_usman),
                                "",
                                isDemo = true,
                            ),
                            PendingLinkRequestUi(
                                "demo_preview_2",
                                "zara.preview@example.com",
                                c.getString(R.string.admin_demo_name_zara),
                                "",
                                isDemo = true,
                            ),
                        )
                    } else {
                        pendingRows
                    }
                    adapter.submit(forUi)
                    tvEmpty.visibility = if (pendingRows.isEmpty() && !showDemo) View.VISIBLE else View.GONE
                    recycler.visibility = if (pendingRows.isNotEmpty() || showDemo) View.VISIBLE else View.GONE
                    if (pendingRows.isEmpty() && !showDemo) {
                        tvEmpty.text = getString(R.string.admin_connections_empty)
                    }
                    AdminConnectionsPendingCache.record(
                        forUi = forUi,
                        showDemo = showDemo,
                        realPendingCount = pendingRows.size,
                    )
                } finally {
                    stopConnectionsSwipeRefresh()
                }
            }
        }.start()
    }

    private fun postDecision(row: PendingLinkRequestUi, accept: Boolean) {
        if (row.isDemo) {
            CuraxFeedback.info(this, getString(R.string.admin_users_demo_preview_action))
            return
        }
        val base = prefs.centralApiUrl.trim().removeSuffix("/")
        val accessCode = prefs.adminAccessCode.trim()
        if (base.isEmpty() || accessCode.isEmpty()) return

        val url = if (accept) {
            "$base/admin/accept-user-link-request"
        } else {
            "$base/admin/reject-user-link-request"
        }
        val json = JSONObject().apply {
            put("access_code", accessCode)
            put("request_id", row.requestId)
        }.toString()
        val body = json.toRequestBody("application/json".toMediaType())

        Thread {
            var ok = false
            try {
                val res = http.newCall(Request.Builder().url(url).post(body).build()).execute()
                ok = res.isSuccessful
            } catch (_: Exception) {
                ok = false
            }
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                if (ok) {
                    CuraxFeedback.success(
                        this,
                        if (accept) getString(R.string.admin_users_accept_ok) else getString(R.string.admin_users_decline_ok),
                    )
                    requireContext().sendBroadcast(Intent(AlertEvents.ACTION_ADMIN_DATA_SYNCED))
                    AdminDataBusClient.fetchAdminSnapshotAsync(requireContext(), null)
                    loadPending(force = true)
                } else {
                    CuraxFeedback.warn(this, getString(R.string.admin_users_action_failed), long = true)
                }
            }
        }.start()
    }
}
