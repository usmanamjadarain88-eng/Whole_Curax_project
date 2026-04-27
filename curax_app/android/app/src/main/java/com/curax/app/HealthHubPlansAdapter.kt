package com.curax.app

import android.graphics.Paint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView

class HealthHubPlansAdapter(
    private val onRowClick: (UserPlanRow) -> Unit,
) : RecyclerView.Adapter<HealthHubPlansAdapter.VH>() {

    private val items = mutableListOf<UserPlanRow>()

    fun submitList(newItems: List<UserPlanRow>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun itemAt(position: Int): UserPlanRow? = items.getOrNull(position)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_plan_row, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position], onRowClick)
    }

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val card: MaterialCardView = itemView as MaterialCardView
        private val tvStatus: TextView = itemView.findViewById(R.id.tvPlanStatus)
        private val tvHealth: TextView = itemView.findViewById(R.id.tvPlanHealthType)
        private val tvTitle: TextView = itemView.findViewById(R.id.tvPlanTitle)
        private val tvMeta: TextView = itemView.findViewById(R.id.tvPlanMeta)
        private val tvNotes: TextView = itemView.findViewById(R.id.tvPlanNotes)
        private val ivDone: ImageView = itemView.findViewById(R.id.ivPlanDone)

        fun bind(row: UserPlanRow, onRowClick: (UserPlanRow) -> Unit) {
            val ctx = itemView.context
            val done = row.isDone
            tvStatus.setText(if (done) R.string.plan_status_done else R.string.plan_status_pending)
            tvHealth.text = HealthHubPlanTemplates.labelForSlug(ctx, row.healthType)
            tvTitle.text = row.title
            tvTitle.paintFlags = tvTitle.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
            if (done) {
                tvTitle.paintFlags = tvTitle.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            }
            card.alpha = if (done) 0.58f else 1f
            ivDone.visibility = if (done) View.VISIBLE else View.GONE
            val meta = buildString {
                append(row.planDate)
                if (row.planTime.isNotBlank()) append(" · ").append(row.planTime.take(5))
                append(" · ").append(row.activityType)
            }
            tvMeta.text = meta
            if (row.notes.isNotBlank()) {
                tvNotes.visibility = View.VISIBLE
                tvNotes.text = row.notes
            } else {
                tvNotes.visibility = View.GONE
            }
            itemView.setOnClickListener { onRowClick(row) }
        }
    }
}
