package com.curax.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.net.URLEncoder
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/** Admin hub tab: pick a linked user, then open chat. */
class AdminMessagesFragment : Fragment() {

    private lateinit var rv: RecyclerView
    private lateinit var tvEmpty: TextView
    private var users: List<AdminChatUserStore.ChatUser> = emptyList()
    private val adapter = UserAdapter { user ->
        startActivity(
            AdminUserChatActivity.intent(requireContext(), user.userId, user.displayName),
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_admin_messages, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        rv = view.findViewById(R.id.rvAdminMessageUsers)
        tvEmpty = view.findViewById(R.id.tvAdminMessagesEmpty)
        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = adapter
        loadUsers()
    }

    override fun onResume() {
        super.onResume()
        loadUsers()
    }

    private fun loadUsers() {
        val prefs = Prefs(requireContext())
        val base = prefs.centralApiUrl.trim().removeSuffix("/")
        val accessCode = prefs.adminAccessCode.trim()
        if (base.isEmpty() || accessCode.isEmpty()) {
            bindUsers(emptyList())
            return
        }
        Thread {
            var list = AdminChatUserStore.snapshot()
            if (list.isEmpty()) {
                try {
                    val url =
                        "$base/admin/linked-users?access_code=${URLEncoder.encode(accessCode, "UTF-8")}"
                    val client = OkHttpClient()
                    val res = client.newCall(Request.Builder().url(url).get().build()).execute()
                    val body = res.body?.string().orEmpty()
                    if (res.isSuccessful && body.isNotBlank()) {
                        AdminChatUserStore.ingestUsersArray(JSONObject(body).optJSONArray("users"))
                        list = AdminChatUserStore.snapshot()
                    }
                } catch (_: Exception) {
                }
            }
            activity?.runOnUiThread {
                if (isAdded) bindUsers(list)
            }
        }.start()
    }

    private fun bindUsers(list: List<AdminChatUserStore.ChatUser>) {
        users = list
        adapter.submit(list)
        val empty = list.isEmpty()
        tvEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        rv.visibility = if (empty) View.GONE else View.VISIBLE
    }

    private class UserAdapter(
        private val onClick: (AdminChatUserStore.ChatUser) -> Unit,
    ) : RecyclerView.Adapter<UserAdapter.VH>() {

        private val items = mutableListOf<AdminChatUserStore.ChatUser>()

        fun submit(list: List<AdminChatUserStore.ChatUser>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_admin_message_user, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(items[position], onClick)
        }

        class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val tvName = itemView.findViewById<TextView>(R.id.tvMessageUserName)
            private val tvHint = itemView.findViewById<TextView>(R.id.tvMessageUserHint)

            fun bind(user: AdminChatUserStore.ChatUser, onClick: (AdminChatUserStore.ChatUser) -> Unit) {
                tvName.text = user.displayName
                tvHint.text = if (user.canMessage) {
                    itemView.context.getString(R.string.admin_messages_tap_to_chat)
                } else {
                    itemView.context.getString(R.string.admin_chat_no_app)
                }
                itemView.setOnClickListener {
                    if (user.canMessage) onClick(user)
                }
                itemView.alpha = if (user.canMessage) 1f else 0.55f
            }
        }
    }
}
