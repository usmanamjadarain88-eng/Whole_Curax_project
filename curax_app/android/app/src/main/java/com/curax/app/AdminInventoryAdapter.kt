package com.curax.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

class AdminInventoryAdapter(
    private val useStandaloneCards: Boolean,
    private val onItemClick: (AdminOverviewFragment.InventoryItem) -> Unit,
    private val computedStatus: (AdminOverviewFragment.InventoryItem) -> String
) : RecyclerView.Adapter<AdminInventoryAdapter.ViewHolder>() {

    private val items = mutableListOf<AdminOverviewFragment.InventoryItem>()
    private var selectedId: Long? = null

    fun submitList(newItems: List<AdminOverviewFragment.InventoryItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun setSelectedId(id: Long?) {
        selectedId = id
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val layout = if (useStandaloneCards) {
            R.layout.item_admin_inventory_standalone
        } else {
            R.layout.item_admin_inventory
        }
        val view = LayoutInflater.from(parent.context).inflate(layout, parent, false)
        return ViewHolder(view, useStandaloneCards)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position], items[position].id == selectedId)
    }

    inner class ViewHolder(itemView: View, private val standalone: Boolean) : RecyclerView.ViewHolder(itemView) {
        private val tvMedicine: TextView = itemView.findViewById(R.id.tvRowMedicine)
        private val tvBox: TextView = itemView.findViewById(R.id.tvRowBox)
        private val tvStock: TextView = itemView.findViewById(R.id.tvRowStock)
        private val tvDose: TextView = itemView.findViewById(R.id.tvRowDose)
        private val tvTime: TextView = itemView.findViewById(R.id.tvRowTime)
        private val tvExpiry: TextView = itemView.findViewById(R.id.tvRowExpiry)
        private val tvStatus: TextView = itemView.findViewById(R.id.tvRowStatus)
        private val root: View = itemView.findViewById(R.id.layoutRowRoot)
        private val strip: View? = if (standalone) itemView.findViewById(R.id.viewInventoryStrip) else null

        fun bind(item: AdminOverviewFragment.InventoryItem, selected: Boolean) {
            val status = computedStatus(item)
            tvMedicine.text = item.name
            if (standalone) {
                tvBox.text = "Slot ${item.box}"
            } else {
                tvBox.text = item.box
            }
            tvStock.text = item.stock.toString()
            tvDose.text = item.dosePerDay.toString()
            tvTime.text = if (standalone) "Next · ${item.exactTime}" else item.exactTime
            tvExpiry.text = if (standalone) "Expires ${item.expiry}" else item.expiry
            tvStatus.text = status

            if (standalone) {
                val statusColor = when (status) {
                    "Expiring" -> ContextCompat.getColor(itemView.context, R.color.standalone_inventory_strip_exp)
                    "Low" -> ContextCompat.getColor(itemView.context, R.color.standalone_inventory_strip_low)
                    "Normal" -> ContextCompat.getColor(itemView.context, R.color.standalone_inventory_strip_normal)
                    else -> ContextCompat.getColor(itemView.context, R.color.text_secondary)
                }
                tvStatus.setTextColor(statusColor)
                strip?.setBackgroundColor(statusColor)
            } else {
                tvStatus.setTextColor(
                    when (status) {
                        "Expiring" -> 0xFFDC2626.toInt()
                        "Low" -> 0xFFD97706.toInt()
                        "Normal" -> 0xFF0E7A57.toInt()
                        else -> ContextCompat.getColor(itemView.context, R.color.text_secondary)
                    }
                )
            }

            if (standalone) {
                (root as? com.google.android.material.card.MaterialCardView)?.strokeWidth =
                    if (selected) (2 * itemView.resources.displayMetrics.density).toInt().coerceAtLeast(2) else 1
                (root as? com.google.android.material.card.MaterialCardView)?.strokeColor = ContextCompat.getColor(
                    itemView.context,
                    if (selected) R.color.standalone_tab_indicator else R.color.standalone_inventory_row_stroke
                )
            } else {
                root.background = if (selected) {
                    ContextCompat.getDrawable(itemView.context, R.drawable.bg_inventory_selected)
                } else {
                    null
                }
            }

            itemView.setOnClickListener { onItemClick(item) }
        }
    }
}
