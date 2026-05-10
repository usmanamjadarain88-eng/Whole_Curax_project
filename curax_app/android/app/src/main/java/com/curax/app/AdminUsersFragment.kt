package com.curax.app

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
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Linked roster; invite code lives under Connections. */
class AdminUsersFragment : Fragment() {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private lateinit var prefs: Prefs
    private lateinit var progress: ProgressBar
    private lateinit var recyclerLinked: RecyclerView
    private lateinit var linkedAdapter: AdminUsersAdapter
    private lateinit var tvEmptyLinked: TextView
    private lateinit var tvError: TextView
    private lateinit var btnRetry: MaterialButton
    private lateinit var btnRefresh: MaterialButton
    private lateinit var cardManaging: MaterialCardView
    private lateinit var tvManagingName: TextView
    private lateinit var btnExitCare: MaterialButton
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
        tvEmptyLinked = view.findViewById(R.id.tvAdminUsersEmpty)
        tvError = view.findViewById(R.id.tvAdminUsersError)
        btnRetry = view.findViewById(R.id.btnAdminUsersRetry)
        btnRefresh = view.findViewById(R.id.btnAdminUsersRefresh)
        cardManaging = view.findViewById(R.id.cardAdminUsersManaging)
        tvManagingName = view.findViewById(R.id.tvAdminUsersManagingName)
        btnExitCare = view.findViewById(R.id.btnAdminUsersExitCare)

        linkedAdapter = AdminUsersAdapter(
            onCareMode = { row ->
                (activity as? AdminDashboardActivity)?.openLinkedUserForManagement(
                    userId = row.userId,
                    name = row.name,
                    desktopLinked = row.desktopLinked,
                    isDemo = row.isDemo,
                )
            },
        )
        recyclerLinked.layoutManager = LinearLayoutManager(requireContext())
        recyclerLinked.isNestedScrollingEnabled = false
        recyclerLinked.adapter = linkedAdapter

        btnRefresh.setOnClickListener { refreshAll() }
        btnRetry.setOnClickListener { refreshAll() }
        btnExitCare.setOnClickListener {
            prefs.actAsUserId = ""
            prefs.actAsUserName = ""
            (activity as? AdminDashboardActivity)?.applyActAsUserUiFromChild()
            updateManagingBanner()
            refreshAll()
        }

        updateManagingBanner()
        refreshAll()
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
        loadLinkedUsers()
    }

    private fun loadLinkedUsers() {
        if (!this::progress.isInitialized) return
        val accessCode = prefs.adminAccessCode.trim()
        val base = prefs.centralApiUrl.trim().removeSuffix("/")
        tvError.visibility = View.GONE
        btnRetry.visibility = View.GONE

        if (base.isEmpty() || accessCode.isEmpty()) {
            progress.visibility = View.GONE
            linkedAdapter.submit(emptyList())
            tvEmptyLinked.visibility = View.VISIBLE
            tvEmptyLinked.text = getString(R.string.admin_hub_need_sign_in)
            return
        }

        val gen = loadGen.incrementAndGet()
        progress.visibility = View.VISIBLE

        Thread {
            var linkedRows = emptyList<AdminLinkedUserUiModel>()
            var httpErr: String? = null

            try {
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
                        val nameRaw = u.optString("name", "").trim()
                        val email = u.optString("email", "").trim()
                        val displayName = when {
                            nameRaw.isNotEmpty() && !nameRaw.equals("null", ignoreCase = true) -> nameRaw
                            email.contains("@") -> email.substringBefore("@").trim()
                            else -> nameRaw
                        }
                        lr.add(
                            AdminLinkedUserUiModel(
                                userId = u.optString("id", "").trim(),
                                name = displayName,
                                email = email,
                                desktopLinked = u.optString("bot_id", "").trim().isNotEmpty(),
                                profilePictureDataUrl = u.optString("profile_picture", "").trim(),
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

                if (httpErr != null) {
                    tvError.visibility = View.VISIBLE
                    tvError.text = httpErr
                    btnRetry.visibility = View.VISIBLE
                    linkedAdapter.submit(emptyList())
                    tvEmptyLinked.visibility = View.GONE
                    updateManagingBanner()
                    return@runOnUiThread
                }

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
            isDemo = true,
        ),
        AdminLinkedUserUiModel(
            userId = "demo_hamad",
            name = getString(R.string.admin_demo_name_hamad),
            email = "hamad.preview@example.com",
            desktopLinked = true,
            isDemo = true,
        ),
        AdminLinkedUserUiModel(
            userId = "demo_abdullah",
            name = getString(R.string.admin_demo_name_abdullah),
            email = "abdullah.preview@example.com",
            desktopLinked = true,
            isDemo = true,
        ),
        AdminLinkedUserUiModel(
            userId = "demo_zara",
            name = getString(R.string.admin_demo_name_zara),
            email = "zara.preview@example.com",
            desktopLinked = true,
            isDemo = true,
        ),
    )
}
