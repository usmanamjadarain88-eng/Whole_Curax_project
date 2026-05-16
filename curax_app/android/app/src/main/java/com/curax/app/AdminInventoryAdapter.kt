package com.curax.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

/** Table rows (same layout as admin/default mode) for all variants — matches inventory header columns. */
class AdminInventoryAdapter(
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
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_admin_inventory, parent, false)
        return ViewHolder(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position], items[position].id == selectedId)
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvMedicine: TextView = itemView.findViewById(R.id.tvRowMedicine)
        private val tvBox: TextView = itemView.findViewById(R.id.tvRowBox)
        private val tvStock: TextView = itemView.findViewById(R.id.tvRowStock)
        private val tvDose: TextView = itemView.findViewById(R.id.tvRowDose)
        private val tvTime: TextView = itemView.findViewById(R.id.tvRowTime)
        private val tvExpiry: TextView = itemView.findViewById(R.id.tvRowExpiry)
        private val tvStatus: TextView = itemView.findViewById(R.id.tvRowStatus)
        private val root: View = itemView.findViewById(R.id.layoutRowRoot)

        fun bind(item: AdminOverviewFragment.InventoryItem, selected: Boolean) {
            val status = computedStatus(item)
            tvMedicine.text = item.name.ifBlank { "—" }
            tvBox.text = item.box.ifBlank { "—" }
            tvStock.text = item.stock.toString()
            tvDose.text = item.displayDoseCell()
            tvTime.text = item.displayTimesLabel().ifBlank { "—" }
            tvExpiry.text = item.expiry.ifBlank { "—" }
            tvStatus.text = status

            tvStatus.setTextColor(
                when (status) {
                    "Expiring" -> ContextCompat.getColor(itemView.context, R.color.standalone_inventory_strip_exp)
                    "Low" -> ContextCompat.getColor(itemView.context, R.color.standalone_inventory_strip_low)
                    "Normal" -> ContextCompat.getColor(itemView.context, R.color.standalone_inventory_strip_normal)
                    else -> ContextCompat.getColor(itemView.context, R.color.text_secondary)
                },
            )

            root.background = if (selected) {
                ContextCompat.getDrawable(itemView.context, R.drawable.bg_inventory_selected)
            } else {
                null
            }

            itemView.setOnClickListener { onItemClick(item) }
        }
    }
}
