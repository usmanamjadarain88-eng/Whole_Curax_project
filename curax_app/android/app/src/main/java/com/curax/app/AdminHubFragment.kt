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
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Admin home when not acting as a user: connection code, stats, and user cards with entry into per-user management.
 */
class AdminHubFragment : Fragment() {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private lateinit var prefs: Prefs
    private lateinit var store: LocalUserStore
    private lateinit var progressLoad: ProgressBar
    private lateinit var tvGreeting: TextView
    private lateinit var tvConnectionCode: TextView
    private lateinit var btnCopyCode: MaterialButton
    private lateinit var tvStatUsers: TextView
    private lateinit var tvStatRelay: TextView
    private lateinit var tvStatAlerts: TextView
    private lateinit var tvUsersHint: TextView
    private lateinit var btnRetry: MaterialButton
    private lateinit var usersContainer: LinearLayout
    private lateinit var tvEmpty: TextView

    private val loadGeneration = AtomicInteger(0)

    private var hubReceiverRegistered = false
    private val hubReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                AlertEvents.ACTION_ADMIN_DATA_SYNCED -> loadLinkedUsers()
                AlertEvents.ACTION_CONNECTION_STATE_CHANGED,
                AlertEvents.ACTION_ALERTS_UPDATED,
                -> refreshLocalStats()
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_admin_hub, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = Prefs(requireContext())
        store = LocalUserStore(requireContext())
        progressLoad = view.findViewById(R.id.progressAdminHubLoad)
        tvGreeting = view.findViewById(R.id.tvAdminHubGreeting)
        tvConnectionCode = view.findViewById(R.id.tvAdminHubConnectionCode)
        btnCopyCode = view.findViewById(R.id.btnAdminHubCopyCode)
        tvStatUsers = view.findViewById(R.id.tvStatUsersValue)
        tvStatRelay = view.findViewById(R.id.tvStatRelayValue)
        tvStatAlerts = view.findViewById(R.id.tvStatAlertsValue)
        tvUsersHint = view.findViewById(R.id.tvAdminHubUsersHint)
        btnRetry = view.findViewById(R.id.btnAdminHubRetry)
        usersContainer = view.findViewById(R.id.containerAdminHubUsers)
        tvEmpty = view.findViewById(R.id.tvAdminHubEmpty)

        val first = store.email.trim().substringBefore("@").ifEmpty { "" }.replaceFirstChar {
            if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
        }
        tvGreeting.text = if (first.isNotEmpty()) {
            getString(R.string.admin_hub_greeting, first)
        } else {
            getString(R.string.admin_hub_greeting_generic)
        }

        tvConnectionCode.text = prefs.connectionCode.trim().ifEmpty { "—" }
        btnCopyCode.setOnClickListener {
            val code = prefs.connectionCode.trim()
            if (code.isEmpty()) {
                CuraxFeedback.warn(this@AdminHubFragment, getString(R.string.connection_code_not_available), long = true)
                return@setOnClickListener
            }
            (requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("", code))
            CuraxFeedback.success(this@AdminHubFragment, getString(R.string.admin_hub_code_copied))
        }

        btnRetry.setOnClickListener { loadLinkedUsers() }

        refreshLocalStats()
        loadLinkedUsers()
    }

    override fun onStart() {
        super.onStart()
        if (!hubReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(AlertEvents.ACTION_ADMIN_DATA_SYNCED)
                addAction(AlertEvents.ACTION_CONNECTION_STATE_CHANGED)
                addAction(AlertEvents.ACTION_ALERTS_UPDATED)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requireContext().registerReceiver(hubReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                requireContext().registerReceiver(hubReceiver, filter)
            }
            hubReceiverRegistered = true
        }
    }

    override fun onResume() {
        super.onResume()
        refreshLocalStats()
        loadLinkedUsers()
    }

    override fun onDestroyView() {
        if (hubReceiverRegistered) {
            try {
                requireContext().unregisterReceiver(hubReceiver)
            } catch (_: Exception) {
            }
            hubReceiverRegistered = false
        }
        super.onDestroyView()
    }

    private fun refreshLocalStats() {
        if (!this::tvStatAlerts.isInitialized) return
        val alertCount = AlertDb(requireContext()).getAllAlerts().size
        tvStatAlerts.text = alertCount.toString()
        val relayOn = (activity as? AdminDashboardActivity)?.isAdminConnected() == true
        tvStatRelay.text = if (relayOn) {
            getString(R.string.admin_hub_relay_on)
        } else {
            getString(R.string.admin_hub_relay_off)
        }
        tvStatRelay.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                if (relayOn) android.R.color.holo_green_dark else android.R.color.holo_red_dark,
            ),
        )
    }

    private fun loadLinkedUsers() {
        if (!this::progressLoad.isInitialized) return
        val accessCode = prefs.adminAccessCode.trim()
        val base = prefs.centralApiUrl.trim().removeSuffix("/")
        if (base.isEmpty() || accessCode.isEmpty()) {
            progressLoad.visibility = View.GONE
            btnRetry.visibility = View.GONE
            tvUsersHint.text = getString(R.string.admin_hub_need_sign_in)
            usersContainer.removeAllViews()
            tvEmpty.visibility = View.VISIBLE
            tvStatUsers.text = "0"
            return
        }

        val gen = loadGeneration.incrementAndGet()
        btnRetry.visibility = View.GONE
        progressLoad.visibility = View.VISIBLE

        Thread {
            try {
                val url = "$base/admin/linked-users?access_code=${java.net.URLEncoder.encode(accessCode, "UTF-8")}"
                val res = http.newCall(Request.Builder().url(url).get().build()).execute()
                val body = res.body?.string().orEmpty()
                val data = if (body.isNotBlank()) JSONObject(body) else JSONObject()
                val usersArr = data.optJSONArray("users") ?: org.json.JSONArray()

                activity?.runOnUiThread {
                    if (gen != loadGeneration.get()) return@runOnUiThread
                    progressLoad.visibility = View.GONE
                    if (!res.isSuccessful) {
                        tvStatUsers.text = "—"
                        tvUsersHint.text = getString(R.string.admin_hub_users_http_error)
                        btnRetry.visibility = View.VISIBLE
                        usersContainer.removeAllViews()
                        tvEmpty.visibility = View.GONE
                        refreshLocalStats()
                        return@runOnUiThread
                    }
                    usersContainer.removeAllViews()
                    tvStatUsers.text = usersArr.length().toString()
                    if (usersArr.length() == 0) {
                        tvUsersHint.text = getString(R.string.admin_hub_users_hint_none)
                        tvEmpty.visibility = View.VISIBLE
                    } else {
                        tvUsersHint.text = getString(R.string.admin_hub_users_hint_some)
                        tvEmpty.visibility = View.GONE
                        for (i in 0 until usersArr.length()) {
                            val u = usersArr.optJSONObject(i) ?: continue
                            val userId = u.optString("id", "").trim()
                            val email = u.optString("email", "").trim()
                            val name = u.optString("name", "").ifEmpty { getString(R.string.admin_user_display_fallback) }
                            val botId = u.optString("bot_id", "").trim()
                            val desktopLinked = botId.isNotEmpty()
                            usersContainer.addView(
                                inflateUserCard(userId, name, email, desktopLinked),
                            )
                        }
                    }
                    refreshLocalStats()
                }
            } catch (_: Exception) {
                activity?.runOnUiThread {
                    if (gen != loadGeneration.get()) return@runOnUiThread
                    progressLoad.visibility = View.GONE
                    tvUsersHint.text = getString(R.string.admin_hub_users_load_failed)
                    tvStatUsers.text = "—"
                    btnRetry.visibility = View.VISIBLE
                    refreshLocalStats()
                }
            }
        }.start()
    }

    private fun inflateUserCard(
        userId: String,
        name: String,
        email: String,
        desktopLinked: Boolean,
    ): View {
        val v = layoutInflater.inflate(R.layout.item_admin_hub_user, usersContainer, false)
        val initial = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
        v.findViewById<TextView>(R.id.tvHubUserInitial).text = initial
        v.findViewById<TextView>(R.id.tvHubUserName).text = name
        v.findViewById<TextView>(R.id.tvHubUserEmail).text = email.ifEmpty { getString(R.string.admin_hub_no_email) }
        val status = v.findViewById<TextView>(R.id.tvHubUserStatus)
        status.text = if (desktopLinked) {
            getString(R.string.admin_hub_status_ready)
        } else {
            getString(R.string.admin_hub_status_pending)
        }
        v.findViewById<MaterialButton>(R.id.btnHubUserManage).setOnClickListener {
            (activity as? AdminDashboardActivity)?.openLinkedUserForManagement(
                userId = userId,
                name = name,
                desktopLinked = desktopLinked,
            )
        }
        return v
    }
}
