package com.curax.app

import org.json.JSONArray
import java.util.Locale

/**
 * Latest linked-user roster for admin UI (name/email resolution for alerts, etc.).
 * Updated when Users, Hub, Reports, or Alerts refresh linked-users from the API.
 */
object AdminLinkedUserDirectory {

    data class Entry(
        val userId: String,
        val displayName: String,
        val email: String,
    )

    @Volatile
    private var entries: List<Entry> = emptyList()

    fun ingestUsersJsonArray(arr: JSONArray?) {
        if (arr == null) return
        val list = mutableListOf<Entry>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id", "").trim()
            if (id.isEmpty()) continue
            val nameRaw = o.optString("name", "").trim()
            val email = o.optString("email", "").trim()
            val display = linkedUserDisplayFromFields(nameRaw, email)
            list.add(Entry(userId = id, displayName = display, email = email))
        }
        entries = list
    }

    fun ingestFromUiModels(models: List<AdminLinkedUserUiModel>) {
        entries = models.map { m ->
            val dn = m.name.trim().ifEmpty { emailLocalPart(m.email) }
            Entry(
                userId = m.userId,
                displayName = dn.ifEmpty { m.name },
                email = m.email.trim(),
            )
        }
    }

    fun snapshot(): List<Entry> = entries

    /**
     * Prefer stored relay user line; else infer from roster (single linked user, or name/email substring in message).
     */
    fun resolveAlertUserLabel(storedUserName: String, message: String, type: String = ""): String {
        if (AlertDisplayRules.isAdminHubSystemEvent(type)) return ""
        val s = storedUserName.trim()
        val generic = s.equals("null", ignoreCase = true) || s.equals("user", ignoreCase = true)
        if (s.isNotEmpty() && !generic) return s
        val msgLower = message.lowercase(Locale.getDefault())
        val list = entries
        if (list.size == 1) {
            val dn = list[0].displayName.trim()
            if (dn.isNotEmpty()) return dn
        }
        for (e in list) {
            val dn = e.displayName.trim()
            if (dn.length >= 2 && msgLower.contains(dn.lowercase(Locale.getDefault()))) return dn
            val em = e.email.trim()
            if (em.contains("@")) {
                val local = em.substringBefore("@").trim()
                if (local.length >= 2 && msgLower.contains(local.lowercase(Locale.getDefault()))) {
                    return dn.ifEmpty { local }
                }
            }
        }
        return ""
    }

    private fun linkedUserDisplayFromFields(nameRaw: String, email: String): String {
        val n = nameRaw.trim()
        if (n.isNotEmpty() && !n.equals("null", ignoreCase = true)) return n
        return emailLocalPart(email)
    }

    private fun emailLocalPart(email: String): String {
        val e = email.trim()
        return if (e.contains("@")) e.substringBefore("@").trim() else ""
    }
}
