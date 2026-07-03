package com.curax.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.imageview.ShapeableImageView
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Main admin view: connected users. Admin taps a user to manage that user's desktop
 * (medicines, reminders, alerts). Use Dashboard, Reminders, Alerts tabs for the selected user.
 */
class AdminConnectedUsersFragment : Fragment() {

    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var scrollConnected: android.widget.ScrollView

    private var syncReceiverRegistered = false
    private val syncReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AlertEvents.ACTION_ADMIN_DATA_SYNCED) refresh()
        }
    }

    companion object {
        private val http = AdminNetwork.http
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_admin_connected_users, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        swipeRefresh = view.findViewById(R.id.swipeAdminConnectedUsers)
        scrollConnected = view.findViewById(R.id.scrollAdminConnectedUsers)
        val accent = ContextCompat.getColor(requireContext(), R.color.button_primary_bg)
        swipeRefresh.setColorSchemeColors(accent)
        swipeRefresh.setProgressBackgroundColorSchemeColor(
            ContextCompat.getColor(requireContext(), R.color.surface_bg),
        )
        swipeRefresh.setOnChildScrollUpCallback { _, _ -> scrollConnected.canScrollVertically(-1) }
        swipeRefresh.setOnRefreshListener { fetchLinkedUsers(fromPullToRefresh = true) }
        refresh()
        fetchLinkedUsers(fromPullToRefresh = false)
        view.findViewById<MaterialButton>(R.id.btnClearActingAs).setOnClickListener {
            Prefs(requireContext()).apply {
                actAsUserId = ""
                actAsUserName = ""
                actAsUserDisplayMode = ""
            }
            updateActingAsRow()
            (activity as? AdminDashboardActivity)?.applyActAsUserUiFromChild()
            AdminDataBusClient.fetchAdminSnapshotAsync(requireContext(), null)
        }
        updateActingAsRow()
    }

    override fun onStart() {
        super.onStart()
        if (!syncReceiverRegistered) {
            val filter = IntentFilter(AlertEvents.ACTION_ADMIN_DATA_SYNCED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requireContext().registerReceiver(syncReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                requireContext().registerReceiver(syncReceiver, filter)
            }
            syncReceiverRegistered = true
        }
    }

    override fun onStop() {
        if (syncReceiverRegistered) {
            try { requireContext().unregisterReceiver(syncReceiver) } catch (_: Exception) {}
            syncReceiverRegistered = false
        }
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        refresh()
        fetchLinkedUsers(fromPullToRefresh = false)
    }

    private fun refresh() {
        updateActingAsRow()
    }

    private fun updateActingAsRow() {
        if (!isAdded) return
        val prefs = Prefs(requireContext())
        val row = view?.findViewById<View>(R.id.actingAsRow)
        val tvActingAs = view?.findViewById<android.widget.TextView>(R.id.tvActingAs)
        if (prefs.actAsUserId.isNotEmpty()) {
            row?.visibility = View.VISIBLE
            tvActingAs?.text = "Managing: ${prefs.actAsUserName.ifEmpty { "User" }}'s desktop"
        } else {
            row?.visibility = View.GONE
        }
    }

    private fun stopConnectedSwipeRefresh() {
        if (!this::swipeRefresh.isInitialized) return
        swipeRefresh.isRefreshing = false
    }

    private fun fetchLinkedUsers(fromPullToRefresh: Boolean = false) {
        val prefs = Prefs(requireContext())
        val accessCode = prefs.adminAccessCode.trim()
        val base = prefs.centralApiUrl.trim().removeSuffix("/")
        if (base.isEmpty() || accessCode.isEmpty()) {
            view?.findViewById<android.widget.TextView>(R.id.tvConnectedUsersInfo)?.text =
                "Set API URL and sign in as admin in Settings."
            stopConnectedSwipeRefresh()
            return
        }
        if (!AdminNetwork.isOnline(requireContext())) {
            view?.findViewById<android.widget.TextView>(R.id.tvConnectedUsersInfo)?.text =
                getString(R.string.error_network_unreachable)
            stopConnectedSwipeRefresh()
            return
        }
        if (!fromPullToRefresh) {
            view?.findViewById<android.widget.TextView>(R.id.tvConnectedUsersInfo)?.text = "Loading…"
        }
        Thread {
            try {
                val url = "$base/admin/linked-users?access_code=${URLEncoder.encode(accessCode, "UTF-8")}"
                val req = Request.Builder().url(url).get().build()
                val res = http.newCall(req).execute()
                if (res.isSuccessful) {
                    val body = res.body?.string() ?: "{}"
                    val data = JSONObject(body)
                    val usersArr = data.optJSONArray("users") ?: org.json.JSONArray()
                    activity?.runOnUiThread {
                        try {
                            if (!isAdded) return@runOnUiThread
                            val infoTv = view?.findViewById<android.widget.TextView>(R.id.tvConnectedUsersInfo)
                            val listLayout = view?.findViewById<android.widget.LinearLayout>(R.id.llConnectedUsersList)
                            listLayout?.removeAllViews()
                            if (usersArr.length() == 0) {
                                infoTv?.text = "No users connected yet. Share your connection code so users can link to you on mobile."
                            } else {
                                infoTv?.text = "${usersArr.length()} user(s) — tap one to open Care mode and manage their data."
                                for (i in 0 until usersArr.length()) {
                                    val u = usersArr.optJSONObject(i) ?: continue
                                    val userId = u.optString("id", "").trim()
                                    val name = u.optString("name", "").ifEmpty { "Unknown" }
                                    val botId = u.optString("bot_id", "").trim()
                                    val row = layoutInflater.inflate(R.layout.item_admin_linked_user_row, listLayout, false)
                                    row.findViewById<android.widget.TextView>(R.id.tvLinkedUserName).text = name
                                    row.findViewById<android.widget.TextView>(R.id.tvLinkedUserSubtitle).text =
                                        if (botId.isNotEmpty()) "Tap for Care mode · $botId"
                                        else "Linked user"
                                    val iv = row.findViewById<ShapeableImageView>(R.id.ivLinkedUserAvatar)
                                    val pic = u.optString("profile_picture", "").trim()
                                    if (pic.isEmpty()) {
                                        iv.setImageResource(R.drawable.ic_avatar_placeholder)
                                    } else {
                                        Thread {
                                            val bmp = UserProfileImageCodec.bitmapFromDataUrl(pic)
                                            activity?.runOnUiThread avatarRow@{
                                                if (!isAdded) return@avatarRow
                                                if (bmp != null) iv.setImageBitmap(bmp) else iv.setImageResource(R.drawable.ic_avatar_placeholder)
                                            }
                                        }.start()
                                    }
                                    row.setOnClickListener {
                                        val p = Prefs(requireContext())
                                        p.actAsUserDisplayMode = ""
                                        p.actAsUserId = userId
                                        p.actAsUserName = name
                                        updateActingAsRow()
                                        CuraxFeedback.info(requireActivity(), "Loading $name's data…")
                                        AdminDataBusClient.fetchAdminSnapshotAsync(requireContext()) { result ->
                                            if (!isAdded) return@fetchAdminSnapshotAsync
                                            when (result) {
                                                AdminDataBusClient.SnapshotResult.APPLIED -> {
                                                    (activity as? AdminDashboardActivity)?.applyActAsUserUiFromChild()
                                                    CuraxFeedback.success(
                                                        this@AdminConnectedUsersFragment,
                                                        "Managing $name's desktop. Use Dashboard, Reminders, Settings tabs. Use \"Return to Admin\" in the drawer to go back.",
                                                    )
                                                }
                                                AdminDataBusClient.SnapshotResult.FAILED -> {
                                                    Prefs(requireContext()).apply {
                                                        actAsUserId = ""
                                                        actAsUserName = ""
                                                        actAsUserDisplayMode = ""
                                                    }
                                                    updateActingAsRow()
                                                    (activity as? AdminDashboardActivity)?.applyActAsUserUiFromChild()
                                                    CuraxFeedback.warn(
                                                        this@AdminConnectedUsersFragment,
                                                        "Could not load this user's data. Try again.",
                                                    )
                                                }
                                                AdminDataBusClient.SnapshotResult.SKIPPED_STALE -> { }
                                            }
                                        }
                                    }
                                    row.setOnLongClickListener {
                                        AlertDialog.Builder(requireContext())
                                            .setTitle("Remove user?")
                                            .setMessage("Remove \"$name\"? They will be informed on their app and desktop. Only you can remove users.")
                                            .setPositiveButton("Remove") { _, _ -> deleteUser(userId) }
                                            .setNegativeButton("Cancel", null)
                                            .show()
                                        true
                                    }
                                    listLayout?.addView(row)
                                }
                            }
                            updateActingAsRow()
                        } finally {
                            stopConnectedSwipeRefresh()
                        }
                    }
                } else {
                    activity?.runOnUiThread {
                        try {
                            if (!isAdded) return@runOnUiThread
                            view?.findViewById<android.widget.TextView>(R.id.tvConnectedUsersInfo)?.text =
                                "Could not load users. Pull to refresh."
                        } finally {
                            stopConnectedSwipeRefresh()
                        }
                    }
                }
            } catch (_: Exception) {
                activity?.runOnUiThread {
                    try {
                        if (!isAdded) return@runOnUiThread
                        view?.findViewById<android.widget.TextView>(R.id.tvConnectedUsersInfo)?.text =
                            "Could not load users. Pull to refresh."
                    } finally {
                        stopConnectedSwipeRefresh()
                    }
                }
            }
        }.start()
    }

    private fun deleteUser(userId: String) {
        val prefs = Prefs(requireContext())
        val accessCode = prefs.adminAccessCode.trim()
        val base = prefs.centralApiUrl.trim().removeSuffix("/")
        if (base.isEmpty() || accessCode.isEmpty()) {
            CuraxFeedback.warn(this, "Not signed in as admin")
            return
        }
        Thread {
            try {
                val body = JSONObject().put("access_code", accessCode).toString()
                    .toRequestBody("application/json".toMediaType())
                val req = Request.Builder()
                    .url("$base/admin/users/$userId")
                    .delete(body)
                    .build()
                val res = http.newCall(req).execute()
                activity?.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    if (res.code == 410 || res.isSuccessful) {
                        if (prefs.actAsUserId == userId) {
                            prefs.actAsUserId = ""
                            prefs.actAsUserName = ""
                            prefs.actAsUserDisplayMode = ""
                            updateActingAsRow()
                            requireContext().sendBroadcast(Intent(AlertEvents.ACTION_ADMIN_DATA_SYNCED))
                            requireContext().sendBroadcast(Intent(AlertEvents.ACTION_ADMIN_HUB_REFRESH_METRICS))
                        }
                        fetchLinkedUsers()
                        CuraxFeedback.success(this, "User removed. They will be informed on their app and desktop.")
                    } else {
                        val msg = try { JSONObject(res.body?.string() ?: "{}").optString("message", "Failed to remove user") } catch (_: Exception) { "Failed to remove user" }
                        CuraxFeedback.warn(this, msg)
                    }
                }
            } catch (e: Exception) {
                activity?.runOnUiThread {
                    if (isAdded) CuraxFeedback.warn(this, "Error: ${e.message}")
                }
            }
        }.start()
    }
}
