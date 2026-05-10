package com.curax.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

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
        itemView: View,
        private val onAccept: (PendingLinkRequestUi) -> Unit,
        private val onDecline: (PendingLinkRequestUi) -> Unit,
    ) : RecyclerView.ViewHolder(itemView) {
        private val btnAccept = itemView.findViewById<MaterialButton>(R.id.btnPendingAccept)
        private val btnDecline = itemView.findViewById<MaterialButton>(R.id.btnPendingDecline)
        private val tvMessage = itemView.findViewById<TextView>(R.id.tvPendingConnectMessage)
        private val tvName = itemView.findViewById<TextView>(R.id.tvPendingRequestName)
        private val card = itemView as MaterialCardView

        fun bind(row: PendingLinkRequestUi) {
            val ctx = itemView.context
            tvMessage.text = ctx.getString(R.string.admin_connection_request_message)
            val who = row.displayName.trim().ifEmpty { row.email.trim() }
            tvName.text = who.ifEmpty { ctx.getString(R.string.admin_user_display_fallback) }
            if (row.isDemo) {
                card.strokeWidth = (2 * ctx.resources.displayMetrics.density).toInt()
                card.strokeColor = ContextCompat.getColor(ctx, R.color.chart_status_monitor)
            } else {
                card.strokeWidth = (1 * ctx.resources.displayMetrics.density).toInt()
                card.strokeColor = ContextCompat.getColor(ctx, R.color.summary_stroke)
            }
            card.alpha = 1f
            btnAccept.setOnClickListener { onAccept(row) }
            btnDecline.setOnClickListener { onDecline(row) }
        }
    }
}
