package com.curax.app

import org.json.JSONArray
import org.json.JSONObject

/** Linked users for admin → user relay chat (from /admin/linked-users). */
object AdminChatUserStore {

    data class ChatUser(
        val userId: String,
        val displayName: String,
        val email: String,
        val botId: String,
        val apiKey: String,
    ) {
        val canMessage: Boolean get() = botId.isNotEmpty() && apiKey.isNotEmpty()
    }

    @Volatile
    private var users: List<ChatUser> = emptyList()

    fun ingestUsersArray(arr: JSONArray?) {
        if (arr == null) {
            users = emptyList()
            return
        }
        val list = mutableListOf<ChatUser>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id", "").trim()
            if (id.isEmpty()) continue
            val nameRaw = o.optString("name", "").trim()
            val email = o.optString("email", "").trim()
            val display = when {
                nameRaw.isNotEmpty() && !nameRaw.equals("null", ignoreCase = true) -> nameRaw
                email.contains("@") -> email.substringBefore("@").trim()
                else -> nameRaw.ifEmpty { "User" }
            }
            list.add(
                ChatUser(
                    userId = id,
                    displayName = display,
                    email = email,
                    botId = o.optString("bot_id", "").trim(),
                    apiKey = o.optString("api_key", "").trim(),
                ),
            )
        }
        users = list
    }

    fun snapshot(): List<ChatUser> = users

    fun find(userId: String): ChatUser? =
        users.find { it.userId == userId.trim() }
}
