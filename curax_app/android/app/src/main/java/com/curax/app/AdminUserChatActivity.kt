package com.curax.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton

/**
 * Admin → user messages via relay (tests user-side WebSocket + popup).
 * User must have relay connected; dose times stay on local alarms.
 */
class AdminUserChatActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var spinner: Spinner
    private lateinit var rv: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var tvRelayHint: TextView
    private lateinit var etMessage: EditText
    private lateinit var btnSend: MaterialButton

    private var users: List<AdminChatUserStore.ChatUser> = emptyList()
    private var selectedUserId: String = ""
    private val adapter = ChatAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_admin_user_chat)
        prefs = Prefs(this)

        findViewById<MaterialToolbar>(R.id.toolbarChat).apply {
            setNavigationOnClickListener { finish() }
        }
        spinner = findViewById(R.id.spinnerChatUser)
        rv = findViewById(R.id.rvChatMessages)
        tvEmpty = findViewById(R.id.tvChatEmpty)
        tvRelayHint = findViewById(R.id.tvChatRelayHint)
        etMessage = findViewById(R.id.etChatMessage)
        btnSend = findViewById(R.id.btnChatSend)

        rv.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        rv.adapter = adapter

        updateRelayHint()
        loadUsersFromApi()
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedUserId = users.getOrNull(position)?.userId.orEmpty()
                refreshMessages()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                selectedUserId = ""
                refreshMessages()
            }
        }

        btnSend.setOnClickListener { sendMessage() }
    }

    override fun onResume() {
        super.onResume()
        updateRelayHint()
    }

    private fun updateRelayHint() {
        val relayOn = ConnectionManager.isRelayConnectedHint()
        tvRelayHint.text = if (relayOn) {
            getString(R.string.admin_chat_relay_live)
        } else {
            getString(R.string.admin_chat_relay_hint)
        }
        tvRelayHint.setTextColor(
            ContextCompat.getColor(
                this,
                if (relayOn) android.R.color.holo_green_dark else R.color.text_secondary,
            ),
        )
    }

    private fun loadUsersFromApi() {
        val base = prefs.centralApiUrl.trim().removeSuffix("/")
        val accessCode = prefs.adminAccessCode.trim()
        if (base.isEmpty() || accessCode.isEmpty()) {
            bindUsers(emptyList())
            tvEmpty.text = getString(R.string.admin_hub_need_sign_in)
            tvEmpty.visibility = View.VISIBLE
            rv.visibility = View.GONE
            return
        }
        btnSend.isEnabled = false
        Thread {
            var list = AdminChatUserStore.snapshot()
            if (list.isEmpty()) {
                try {
                    val url =
                        "$base/admin/linked-users?access_code=${java.net.URLEncoder.encode(accessCode, "UTF-8")}"
                    val client = okhttp3.OkHttpClient()
                    val res = client.newCall(okhttp3.Request.Builder().url(url).get().build()).execute()
                    val body = res.body?.string().orEmpty()
                    if (res.isSuccessful && body.isNotBlank()) {
                        val arr = org.json.JSONObject(body).optJSONArray("users")
                        AdminChatUserStore.ingestUsersArray(arr)
                        list = AdminChatUserStore.snapshot()
                    }
                } catch (_: Exception) {
                }
            }
            runOnUiThread {
                bindUsers(list)
                btnSend.isEnabled = list.any { it.canMessage }
            }
        }.start()
    }

    private fun bindUsers(list: List<AdminChatUserStore.ChatUser>) {
        users = list
        val labels = list.map { u ->
            if (u.canMessage) u.displayName else "${u.displayName} (${getString(R.string.admin_chat_no_app)})"
        }
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        if (list.isNotEmpty()) {
            selectedUserId = list[0].userId
            refreshMessages()
        } else {
            selectedUserId = ""
            tvEmpty.text = getString(R.string.admin_chat_no_users)
            tvEmpty.visibility = View.VISIBLE
            rv.visibility = View.GONE
        }
    }

    private fun refreshMessages() {
        if (selectedUserId.isEmpty()) {
            adapter.submit(emptyList())
            tvEmpty.visibility = View.VISIBLE
            rv.visibility = View.GONE
            return
        }
        val lines = AdminChatHistoryStore.lines(this, selectedUserId)
        adapter.submit(lines)
        val empty = lines.isEmpty()
        tvEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        rv.visibility = if (empty) View.GONE else View.VISIBLE
        if (!empty) {
            rv.scrollToPosition(lines.size - 1)
        }
    }

    private fun sendMessage() {
        val user = users.find { it.userId == selectedUserId } ?: return
        val msg = etMessage.text?.toString()?.trim().orEmpty()
        if (msg.isEmpty()) {
            CuraxFeedback.warn(this, getString(R.string.admin_chat_empty_message))
            return
        }
        if (!user.canMessage) {
            CuraxFeedback.warn(this, getString(R.string.admin_chat_no_app))
            return
        }
        val base = prefs.centralApiUrl.trim().removeSuffix("/")
        val code = prefs.adminAccessCode.trim()
        if (base.isEmpty() || code.isEmpty()) {
            CuraxFeedback.warn(this, getString(R.string.admin_hub_need_sign_in))
            return
        }
        btnSend.isEnabled = false
        Thread {
            val result = AdminSendUserMessageApi.sendSync(code, user.userId, msg, base)
            runOnUiThread {
                btnSend.isEnabled = true
                if (result.ok) {
                    AdminChatHistoryStore.append(this, user.userId, msg, getString(R.string.admin_chat_delivered))
                    etMessage.text?.clear()
                    refreshMessages()
                    CuraxFeedback.success(this, getString(R.string.admin_chat_sent_toast))
                } else {
                    AdminChatHistoryStore.append(
                        this,
                        user.userId,
                        msg,
                        getString(R.string.admin_chat_failed, result.detail),
                    )
                    refreshMessages()
                    CuraxFeedback.warn(this, getString(R.string.admin_chat_failed, result.detail))
                }
            }
        }.start()
    }

    companion object {
        fun intent(context: Context): Intent =
            Intent(context, AdminUserChatActivity::class.java)
    }

    private class ChatAdapter : RecyclerView.Adapter<ChatAdapter.VH>() {
        private val items = mutableListOf<AdminChatHistoryStore.Line>()

        fun submit(list: List<AdminChatHistoryStore.Line>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_admin_chat_message, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(items[position])
        }

        class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val tvMsg = itemView.findViewById<TextView>(R.id.tvChatMessage)
            private val tvMeta = itemView.findViewById<TextView>(R.id.tvChatMeta)

            fun bind(line: AdminChatHistoryStore.Line) {
                tvMsg.text = line.text
                val meta = buildString {
                    append(line.at)
                    if (line.delivery.isNotBlank()) {
                        append(" · ")
                        append(line.delivery)
                    }
                }
                tvMeta.text = meta
            }
        }
    }
}
