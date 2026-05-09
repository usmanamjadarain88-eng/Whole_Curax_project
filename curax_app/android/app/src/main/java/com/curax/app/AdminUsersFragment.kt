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
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
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
 * Invites + incoming directory link requests + linked roster (Care mode).
 */
class AdminUsersFragment : Fragment() {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private lateinit var prefs: Prefs
    private lateinit var progress: ProgressBar
    private lateinit var recyclerLinked: RecyclerView
    private lateinit var recyclerPending: RecyclerView
    private lateinit var linkedAdapter: AdminUsersAdapter
    private lateinit var pendingAdapter: PendingLinkRequestsAdapter
    private lateinit var tvEmptyLinked: TextView
    private lateinit var tvError: TextView
    private lateinit var btnRetry: MaterialButton
    private lateinit var btnRefresh: MaterialButton
    private lateinit var cardManaging: MaterialCardView
    private lateinit var tvManagingName: TextView
    private lateinit var btnExitCare: MaterialButton
    private lateinit var tvConnectionCode: TextView
    private lateinit var btnCopyCode: MaterialButton
    private lateinit var tvPendingEmpty: TextView

    private val loadGen = AtomicInteger(0)

    private var receiverRegistered = false
    private val syncReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AlertEvents.ACTION_ADMIN_DATA_SYNCED) refreshAll()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_admin_users, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = Prefs(requireContext())
        progress = view.findViewById(R.id.progressAdminUsers)
        recyclerLinked = view.findViewById(R.id.recyclerAdminUsers)
        recyclerPending = view.findViewById(R.id.recyclerPendingLinkRequests)
        tvEmptyLinked = view.findViewById(R.id.tvAdminUsersEmpty)
        tvError = view.findViewById(R.id.tvAdminUsersError)
        btnRetry = view.findViewById(R.id.btnAdminUsersRetry)
        btnRefresh = view.findViewById(R.id.btnAdminUsersRefresh)
        cardManaging = view.findViewById(R.id.cardAdminUsersManaging)
        tvManagingName = view.findViewById(R.id.tvAdminUsersManagingName)
        btnExitCare = view.findViewById(R.id.btnAdminUsersExitCare)
        tvConnectionCode = view.findViewById(R.id.tvAdminUsersConnectionCode)
        btnCopyCode = view.findViewById(R.id.btnAdminUsersCopyCode)
        tvPendingEmpty = view.findViewById(R.id.tvPendingRequestsEmpty)

        linkedAdapter = AdminUsersAdapter(
            onCareMode = { row ->
                (activity as? AdminDashboardActivity)?.openLinkedUserForManagement(
                    userId = row.userId,
                    name = row.name,
                    desktopLinked = row.desktopLinked,
                    isDemo = row.isDemo,
                )
            },
            onDisplayModeSelected = { row, mode -> postUserDisplayMode(row, mode) },
        )
        recyclerLinked.layoutManager = LinearLayoutManager(requireContext())
        recyclerLinked.isNestedScrollingEnabled = false
        recyclerLinked.adapter = linkedAdapter

        pendingAdapter = PendingLinkRequestsAdapter(
            onAccept = { row -> postDecision(row, accept = true) },
            onDecline = { row -> postDecision(row, accept = false) },
        )
        recyclerPending.layoutManager = LinearLayoutManager(requireContext())
        recyclerPending.isNestedScrollingEnabled = false
        recyclerPending.adapter = pendingAdapter

        bindConnectionCode()

        btnRefresh.setOnClickListener { refreshAll() }
        btnRetry.setOnClickListener { refreshAll() }
        btnExitCare.setOnClickListener {
            prefs.actAsUserId = ""
            prefs.actAsUserName = ""
            (activity as? AdminDashboardActivity)?.applyActAsUserUiFromChild()
            updateManagingBanner()
            refreshAll()
        }

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

        updateManagingBanner()
        refreshAll()
    }

    private fun bindConnectionCode() {
        tvConnectionCode.text = prefs.connectionCode.trim().ifEmpty { "—" }
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
        updateManagingBanner()
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

    private fun updateManagingBanner() {
        if (!this::cardManaging.isInitialized) return
        val id = prefs.actAsUserId.trim()
        linkedAdapter.managingUserId = id
        linkedAdapter.notifyDataSetChanged()
        if (id.isEmpty()) {
            cardManaging.visibility = View.GONE
        } else {
            cardManaging.visibility = View.VISIBLE
            tvManagingName.text = prefs.actAsUserName.ifEmpty {
                getString(R.string.admin_user_display_fallback)
            }
        }
    }

    private fun refreshAll() {
        loadLinkedAndPending()
    }

    private fun loadLinkedAndPending() {
        if (!this::progress.isInitialized) return
        val accessCode = prefs.adminAccessCode.trim()
        val base = prefs.centralApiUrl.trim().removeSuffix("/")
        tvError.visibility = View.GONE
        btnRetry.visibility = View.GONE

        if (base.isEmpty() || accessCode.isEmpty()) {
            progress.visibility = View.GONE
            linkedAdapter.submit(emptyList())
            pendingAdapter.submit(emptyList())
            tvPendingEmpty.visibility = View.VISIBLE
            recyclerPending.visibility = View.GONE
            tvEmptyLinked.visibility = View.VISIBLE
            tvEmptyLinked.text = getString(R.string.admin_hub_need_sign_in)
            bindConnectionCode()
            return
        }

        val gen = loadGen.incrementAndGet()
        progress.visibility = View.VISIBLE

        Thread {
            var pendingRows = emptyList<PendingLinkRequestUi>()
            var linkedRows = emptyList<AdminLinkedUserUiModel>()
            var httpErr: String? = null

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
                }

                val usersUrl = "$base/admin/linked-users?access_code=${URLEncoder.encode(accessCode, "UTF-8")}"
                val usersRes = http.newCall(Request.Builder().url(usersUrl).get().build()).execute()
                val usersBody = usersRes.body?.string().orEmpty()
                if (!usersRes.isSuccessful) {
                    httpErr = getString(R.string.admin_hub_users_http_error)
                } else {
                    val data = if (usersBody.isNotBlank()) JSONObject(usersBody) else JSONObject()
                    val usersArr = data.optJSONArray("users") ?: JSONArray()
                    val lr = mutableListOf<AdminLinkedUserUiModel>()
                    for (i in 0 until usersArr.length()) {
                        val u = usersArr.optJSONObject(i) ?: continue
                        val dmRaw = u.optString("user_display_mode", "").trim().lowercase()
                        val dm = if (dmRaw == "standalone") "standalone" else if (dmRaw == "default") "default" else ""
                        lr.add(
                            AdminLinkedUserUiModel(
                                userId = u.optString("id", "").trim(),
                                name = u.optString("name", "").ifEmpty {
                                    getString(R.string.admin_user_display_fallback)
                                },
                                email = u.optString("email", "").trim(),
                                desktopLinked = u.optString("bot_id", "").trim().isNotEmpty(),
                                profilePictureDataUrl = u.optString("profile_picture", "").trim(),
                                displayMode = dm,
                            ),
                        )
                    }
                    linkedRows = lr
                }
            } catch (_: Exception) {
                httpErr = getString(R.string.admin_users_load_failed)
            }

            activity?.runOnUiThread {
                if (gen != loadGen.get()) return@runOnUiThread
                progress.visibility = View.GONE
                bindConnectionCode()

                if (httpErr != null) {
                    tvError.visibility = View.VISIBLE
                    tvError.text = httpErr
                    btnRetry.visibility = View.VISIBLE
                    linkedAdapter.submit(emptyList())
                    pendingAdapter.submit(emptyList())
                    tvPendingEmpty.visibility = View.VISIBLE
                    recyclerPending.visibility = View.GONE
                    tvEmptyLinked.visibility = View.GONE
                    updateManagingBanner()
                    return@runOnUiThread
                }

                val showDemoPending = pendingRows.isEmpty()
                val pendingForUi = if (showDemoPending) {
                    listOf(
                        PendingLinkRequestUi(
                            requestId = "",
                            email = getString(R.string.admin_users_demo_email),
                            displayName = getString(R.string.admin_users_demo_display_name),
                            createdAtIso = "",
                            isDemo = true,
                        ),
                    )
                } else {
                    pendingRows
                }
                pendingAdapter.submit(pendingForUi)
                tvPendingEmpty.visibility = if (pendingRows.isEmpty() && !showDemoPending) View.VISIBLE else View.GONE
                recyclerPending.visibility = if (pendingRows.isNotEmpty() || showDemoPending) View.VISIBLE else View.GONE

                val linkedForUi = if (linkedRows.isEmpty()) demoLinkedUsers() else linkedRows
                linkedAdapter.submit(linkedForUi)
                tvEmptyLinked.visibility = View.GONE
                updateManagingBanner()
            }
        }.start()
    }

    private fun demoLinkedUsers(): List<AdminLinkedUserUiModel> = listOf(
        AdminLinkedUserUiModel(
            userId = "demo_usman",
            name = getString(R.string.admin_demo_name_usman),
            email = "usman.preview@example.com",
            desktopLinked = true,
            displayMode = "default",
            isDemo = true,
        ),
        AdminLinkedUserUiModel(
            userId = "demo_hamad",
            name = getString(R.string.admin_demo_name_hamad),
            email = "hamad.preview@example.com",
            desktopLinked = true,
            displayMode = "standalone",
            isDemo = true,
        ),
        AdminLinkedUserUiModel(
            userId = "demo_abdullah",
            name = getString(R.string.admin_demo_name_abdullah),
            email = "abdullah.preview@example.com",
            desktopLinked = true,
            displayMode = "default",
            isDemo = true,
        ),
        AdminLinkedUserUiModel(
            userId = "demo_zara",
            name = getString(R.string.admin_demo_name_zara),
            email = "zara.preview@example.com",
            desktopLinked = true,
            displayMode = "default",
            isDemo = true,
        ),
    )

    private fun postUserDisplayMode(row: AdminLinkedUserUiModel, mode: String) {
        if (row.isDemo) return
        val base = prefs.centralApiUrl.trim().removeSuffix("/")
        val accessCode = prefs.adminAccessCode.trim()
        if (base.isEmpty() || accessCode.isEmpty() || row.userId.isEmpty()) return
        val url = "$base/admin/set-user-display-mode"
        val json = JSONObject().apply {
            put("access_code", accessCode)
            put("user_id", row.userId)
            put("display_mode", mode)
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
                    CuraxFeedback.success(this, getString(R.string.admin_users_display_mode_saved))
                    requireContext().sendBroadcast(Intent(AlertEvents.ACTION_ADMIN_DATA_SYNCED))
                    refreshAll()
                } else {
                    CuraxFeedback.warn(this, getString(R.string.admin_users_display_mode_failed), long = true)
                    refreshAll()
                }
            }
        }.start()
    }

    private fun postDecision(row: PendingLinkRequestUi, accept: Boolean) {
        if (row.isDemo) return
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
                    refreshAll()
                } else {
                    CuraxFeedback.warn(this, getString(R.string.admin_users_action_failed), long = true)
                }
            }
        }.start()
    }
}
