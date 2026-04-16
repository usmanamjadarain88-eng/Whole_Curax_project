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
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
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

    private var syncReceiverRegistered = false
    private val syncReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AlertEvents.ACTION_ADMIN_DATA_SYNCED) refresh()
        }
    }

    companion object {
        private val http = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_admin_connected_users, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        refresh()
        fetchLinkedUsers()
        view.findViewById<MaterialButton>(R.id.btnClearActingAs).setOnClickListener {
            Prefs(requireContext()).apply {
                actAsUserId = ""
                actAsUserName = ""
            }
            updateActingAsRow()
            (activity as? AdminDashboardActivity)?.applyActAsUserUiFromChild()
            AdminDataBusClient.fetchAdminSnapshotAsync(requireContext(), null)
        }
        view.findViewById<MaterialButton>(R.id.btnRefreshUsers).setOnClickListener {
            fetchLinkedUsers()
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
        fetchLinkedUsers()
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

    private fun fetchLinkedUsers() {
        val prefs = Prefs(requireContext())
        val accessCode = prefs.adminAccessCode.trim()
        val base = prefs.centralApiUrl.trim().removeSuffix("/")
        if (base.isEmpty() || accessCode.isEmpty()) {
            view?.findViewById<android.widget.TextView>(R.id.tvConnectedUsersInfo)?.text =
                "Set API URL and sign in as admin in Settings."
            return
        }
        view?.findViewById<android.widget.TextView>(R.id.tvConnectedUsersInfo)?.text = "Loading…"
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
                        if (!isAdded) return@runOnUiThread
                        val infoTv = view?.findViewById<android.widget.TextView>(R.id.tvConnectedUsersInfo)
                        val listLayout = view?.findViewById<android.widget.LinearLayout>(R.id.llConnectedUsersList)
                        listLayout?.removeAllViews()
                        if (usersArr.length() == 0) {
                            infoTv?.text = "No users connected yet. Share your connection code so users can link to you. Then they can link their desktop; you manage each user's desktop here."
                        } else {
                            infoTv?.text = "${usersArr.length()} user(s) — tap one to manage their desktop."
                            for (i in 0 until usersArr.length()) {
                                val u = usersArr.optJSONObject(i) ?: continue
                                val userId = u.optString("id", "").trim()
                                val name = u.optString("name", "").ifEmpty { "Unknown" }
                                val botId = u.optString("bot_id", "").trim()
                                val desktopLinked = u.optBoolean("desktop_linked", false)
                                val tv = android.widget.TextView(requireContext()).apply {
                                    text = "• $name — Manage desktop" + if (desktopLinked) "  (ID: $botId)" else "  (user must login to desktop first)"
                                    setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.text_primary))
                                    textSize = 15f
                                    setPadding(32, 16, 32, 16)
                                    isClickable = true
                                    isFocusable = true
                                    setBackgroundResource(android.R.drawable.list_selector_background)
                                }
                                tv.setOnClickListener {
                                    if (!desktopLinked) {
                                        AlertDialog.Builder(requireContext())
                                            .setTitle(getString(R.string.user_link_desktop_first_title))
                                            .setMessage(getString(R.string.user_link_desktop_first_message))
                                            .setPositiveButton(android.R.string.ok, null)
                                            .show()
                                        return@setOnClickListener
                                    }
                                    val p = Prefs(requireContext())
                                    p.actAsUserId = userId
                                    p.actAsUserName = name
                                    updateActingAsRow()
                                    CuraxFeedback.info(requireActivity(), "Loading $name's data…")
                                    // Same as drawer: load user's medicines/reminders from API (broadcast alone left admin's data on screen).
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
                                tv.setOnLongClickListener {
                                    AlertDialog.Builder(requireContext())
                                        .setTitle("Remove user?")
                                        .setMessage("Remove \"$name\"? They will be informed on their app and desktop. Only you can remove users.")
                                        .setPositiveButton("Remove") { _, _ -> deleteUser(userId, name) }
                                        .setNegativeButton("Cancel", null)
                                        .show()
                                    true
                                }
                                listLayout?.addView(tv)
                            }
                        }
                        updateActingAsRow()
                    }
                } else {
                    activity?.runOnUiThread {
                        if (!isAdded) return@runOnUiThread
                        view?.findViewById<android.widget.TextView>(R.id.tvConnectedUsersInfo)?.text =
                            "Could not load users. Pull to refresh."
                    }
                }
            } catch (_: Exception) {
                activity?.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    view?.findViewById<android.widget.TextView>(R.id.tvConnectedUsersInfo)?.text =
                        "Could not load users. Tap Refresh."
                }
            }
        }.start()
    }

    private fun deleteUser(userId: String, userName: String) {
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
                            updateActingAsRow()
                            requireContext().sendBroadcast(Intent(AlertEvents.ACTION_ADMIN_DATA_SYNCED))
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
