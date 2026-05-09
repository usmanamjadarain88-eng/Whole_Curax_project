package com.curax.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class PendingLinkRequestUi(
    val requestId: String,
    val email: String,
    val displayName: String,
    val createdAtIso: String,
    val isDemo: Boolean = false,
)

class PendingLinkRequestsAdapter(
    private val onAccept: (PendingLinkRequestUi) -> Unit,
    private val onDecline: (PendingLinkRequestUi) -> Unit,
) : RecyclerView.Adapter<PendingLinkRequestsAdapter.VH>() {

    private val items = mutableListOf<PendingLinkRequestUi>()

    fun submit(list: List<PendingLinkRequestUi>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_admin_pending_link_request, parent, false)
        return VH(v, onAccept, onDecline)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    class VH(
        itemView: android.view.View,
        private val onAccept: (PendingLinkRequestUi) -> Unit,
        private val onDecline: (PendingLinkRequestUi) -> Unit,
    ) : RecyclerView.ViewHolder(itemView) {
        private val tvName = itemView.findViewById<android.widget.TextView>(R.id.tvPendingDisplayName)
        private val tvEmail = itemView.findViewById<android.widget.TextView>(R.id.tvPendingEmail)
        private val tvMeta = itemView.findViewById<android.widget.TextView>(R.id.tvPendingMeta)
        private val tvSampleNote = itemView.findViewById<android.widget.TextView>(R.id.tvPendingSampleNote)
        private val rowActions = itemView.findViewById<View>(R.id.rowPendingActions)
        private val btnAccept = itemView.findViewById<MaterialButton>(R.id.btnPendingAccept)
        private val btnDecline = itemView.findViewById<MaterialButton>(R.id.btnPendingDecline)
        private val card = itemView as MaterialCardView

        fun bind(row: PendingLinkRequestUi) {
            val ctx = itemView.context
            val label = row.displayName.trim().ifEmpty { row.email.ifEmpty { ctx.getString(R.string.admin_hub_no_email) } }
            tvName.text = label
            tvEmail.text = row.email.ifEmpty { ctx.getString(R.string.admin_hub_no_email) }
            if (row.isDemo) {
                btnAccept.setOnClickListener(null)
                btnDecline.setOnClickListener(null)
                tvMeta.text = ctx.getString(R.string.admin_users_demo_meta)
                tvSampleNote.visibility = View.VISIBLE
                tvSampleNote.text = ctx.getString(R.string.admin_users_demo_note)
                rowActions.visibility = View.GONE
                card.strokeWidth = (2 * ctx.resources.displayMetrics.density).toInt()
                card.strokeColor = ContextCompat.getColor(ctx, R.color.chart_status_monitor)
                card.alpha = 0.96f
            } else {
                tvMeta.text = ctx.getString(R.string.admin_users_pending_requested, formatWhen(row.createdAtIso))
                tvSampleNote.visibility = View.GONE
                rowActions.visibility = View.VISIBLE
                card.strokeWidth = (1 * ctx.resources.displayMetrics.density).toInt()
                card.strokeColor = ContextCompat.getColor(ctx, R.color.summary_stroke)
                card.alpha = 1f
                btnAccept.setOnClickListener { onAccept(row) }
                btnDecline.setOnClickListener { onDecline(row) }
            }
        }

        private fun formatWhen(iso: String): String {
            val raw = iso.trim()
            if (raw.isEmpty()) return "—"
            val parsed = parseIso(raw) ?: return raw.take(19).replace('T', ' ')
            val localFmt = SimpleDateFormat("MMM d, yyyy · HH:mm", Locale.getDefault())
            return localFmt.format(parsed)
        }

        private fun parseIso(s: String): Date? {
            val patterns = listOf(
                "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
                "yyyy-MM-dd'T'HH:mm:ssXXX",
                "yyyy-MM-dd'T'HH:mm:ss'Z'",
                "yyyy-MM-dd'T'HH:mm:ss.SSSSSSXXX",
            )
            for (p in patterns) {
                try {
                    val fmt = SimpleDateFormat(p, Locale.US)
                    fmt.isLenient = false
                    val pos = ParsePosition(0)
                    val d = fmt.parse(s, pos)
                    if (d != null && pos.index == s.length) return d
                } catch (_: Exception) {
                }
            }
            try {
                val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
                fmt.timeZone = TimeZone.getTimeZone("UTC")
                return fmt.parse(s.take(19))
            } catch (_: Exception) {
            }
            return null
        }
    }
}
