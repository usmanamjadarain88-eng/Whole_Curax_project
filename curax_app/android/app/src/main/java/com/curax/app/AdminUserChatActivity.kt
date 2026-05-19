package com.curax.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton

/** Admin → linked user chat (relay popup on user device). */
class AdminUserChatActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var spinner: Spinner
    private lateinit var rv: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var etMessage: EditText
    private lateinit var btnSend: MaterialButton

    private var users: List<AdminChatUserStore.ChatUser> = emptyList()
    private var selectedUserId: String = ""
    private val adapter = ChatAdapter { line -> confirmDeleteMessage(line) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_admin_user_chat)
        prefs = Prefs(this)

        val presetUserId = intent.getStringExtra(EXTRA_USER_ID).orEmpty().trim()
        val presetUserName = intent.getStringExtra(EXTRA_USER_NAME).orEmpty().trim()

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbarChat)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }
        if (presetUserName.isNotEmpty()) toolbar.title = presetUserName

        findViewById<View>(R.id.tvChatRelayHint).visibility = View.GONE

        spinner = findViewById(R.id.spinnerChatUser)
        rv = findViewById(R.id.rvChatMessages)
        tvEmpty = findViewById(R.id.tvChatEmpty)
        etMessage = findViewById(R.id.etChatMessage)
        btnSend = findViewById(R.id.btnChatSend)

        rv.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        rv.adapter = adapter

        if (presetUserId.isNotEmpty()) {
            spinner.visibility = View.GONE
            selectedUserId = presetUserId
            loadUsersThenSelect(presetUserId)
        } else {
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
        }

        btnSend.setOnClickListener { sendMessage() }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_admin_chat, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_clear_chat -> {
                confirmClearAllMessages()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun loadUsersThenSelect(userId: String) {
        loadUsersFromApi {
            selectedUserId = userId
            refreshMessages()
        }
    }

    private fun loadUsersFromApi(onReady: (() -> Unit)? = null) {
        val base = prefs.centralApiUrl.trim().removeSuffix("/")
        val accessCode = prefs.adminAccessCode.trim()
        if (base.isEmpty() || accessCode.isEmpty()) {
            bindUsers(emptyList())
            tvEmpty.text = getString(R.string.admin_hub_need_sign_in)
            tvEmpty.visibility = View.VISIBLE
            rv.visibility = View.GONE
            onReady?.invoke()
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
                        AdminChatUserStore.ingestUsersArray(org.json.JSONObject(body).optJSONArray("users"))
                        list = AdminChatUserStore.snapshot()
                    }
                } catch (_: Exception) {
                }
            }
            runOnUiThread {
                bindUsers(list)
                btnSend.isEnabled = list.any { it.userId == selectedUserId && it.canMessage }
                onReady?.invoke()
            }
        }.start()
    }

    private fun bindUsers(list: List<AdminChatUserStore.ChatUser>) {
        users = list
        val labels = list.map { u ->
            if (u.canMessage) u.displayName else "${u.displayName} (${getString(R.string.admin_chat_no_app)})"
        }
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        if (selectedUserId.isEmpty() && list.isNotEmpty()) {
            selectedUserId = list[0].userId
            refreshMessages()
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
        if (!empty) rv.scrollToPosition(lines.size - 1)
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
                    AdminChatHistoryStore.append(this, user.userId, msg, "sent")
                    etMessage.text?.clear()
                    refreshMessages()
                    CuraxFeedback.success(this, getString(R.string.admin_chat_sent_toast))
                } else {
                    CuraxFeedback.warn(this, getString(R.string.admin_chat_failed, result.detail))
                }
            }
        }.start()
    }

    private fun confirmDeleteMessage(line: AdminChatHistoryStore.Line) {
        if (selectedUserId.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle(R.string.delete)
            .setMessage(R.string.admin_chat_delete_message_confirm)
            .setPositiveButton(R.string.delete) { _, _ ->
                if (AdminChatHistoryStore.deleteLine(this, selectedUserId, line.at, line.text)) {
                    refreshMessages()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmClearAllMessages() {
        if (selectedUserId.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle(R.string.clear_all)
            .setMessage(R.string.admin_chat_clear_confirm)
            .setPositiveButton(R.string.clear_all) { _, _ ->
                AdminChatHistoryStore.clearUser(this, selectedUserId)
                refreshMessages()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    companion object {
        private const val EXTRA_USER_ID = "extra_chat_user_id"
        private const val EXTRA_USER_NAME = "extra_chat_user_name"

        fun intent(context: Context): Intent =
            Intent(context, AdminUserChatActivity::class.java)

        fun intent(context: Context, userId: String, displayName: String): Intent =
            Intent(context, AdminUserChatActivity::class.java).apply {
                putExtra(EXTRA_USER_ID, userId.trim())
                putExtra(EXTRA_USER_NAME, displayName.trim())
            }
    }

    private class ChatAdapter(
        private val onLongPressDelete: (AdminChatHistoryStore.Line) -> Unit,
    ) : RecyclerView.Adapter<ChatAdapter.VH>() {

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
            return VH(v, onLongPressDelete)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(items[position])
        }

        class VH(
            itemView: View,
            private val onLongPressDelete: (AdminChatHistoryStore.Line) -> Unit,
        ) : RecyclerView.ViewHolder(itemView) {
            private val tvMsg = itemView.findViewById<TextView>(R.id.tvChatMessage)
            private val tvMeta = itemView.findViewById<TextView>(R.id.tvChatMeta)

            fun bind(line: AdminChatHistoryStore.Line) {
                tvMsg.text = line.text
                val delivery = line.delivery.trim().lowercase()
                tvMeta.text = when {
                    delivery == "sent" -> itemView.context.getString(R.string.admin_chat_sent_label)
                    delivery.contains("fail") ->
                        itemView.context.getString(R.string.admin_chat_failed_short)
                    else -> ""
                }
                tvMeta.visibility = if (tvMeta.text.isNullOrBlank()) View.GONE else View.VISIBLE
                itemView.setOnLongClickListener {
                    onLongPressDelete(line)
                    true
                }
            }
        }
    }
}
