package com.curax.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AlertsAdapter(
    private val onAlertClick: (AlertItem) -> Unit,
    private val onSelectionChanged: (Int) -> Unit
) : ListAdapter<AlertItem, AlertsAdapter.Holder>(DiffCallback()) {

    private var selectionMode = false
    private val selectedIds = mutableSetOf<Long>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_alert, parent, false)
        return Holder(v)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = getItem(position)
        holder.bind(item, selectionMode, selectedIds.contains(item.id))

        holder.itemView.setOnClickListener {
            if (selectionMode) {
                toggleSelection(item)
            } else {
                onAlertClick(item)
            }
        }

        holder.itemView.setOnLongClickListener {
            if (!selectionMode) {
                selectionMode = true
            }
            toggleSelection(item)
            true
        }

        holder.cbSelect.setOnClickListener {
            toggleSelection(item)
        }
    }

    override fun onCurrentListChanged(
        previousList: MutableList<AlertItem>,
        currentList: MutableList<AlertItem>
    ) {
        super.onCurrentListChanged(previousList, currentList)
        val validIds = currentList.map { it.id }.toSet()
        if (selectedIds.retainAll(validIds)) {
            if (selectedIds.isEmpty()) {
                selectionMode = false
            }
            onSelectionChanged(selectedIds.size)
            notifyDataSetChanged()
        }
    }

    fun isSelectionMode(): Boolean = selectionMode

    fun selectedCount(): Int = selectedIds.size

    fun getSelectedIds(): Set<Long> = selectedIds.toSet()

    fun areAllSelected(): Boolean = currentList.isNotEmpty() && selectedIds.size == currentList.size

    fun selectAll() {
        if (currentList.isEmpty()) return
        selectionMode = true
        selectedIds.clear()
        selectedIds.addAll(currentList.map { it.id })
        onSelectionChanged(selectedIds.size)
        notifyDataSetChanged()
    }

    fun clearSelection() {
        if (selectionMode || selectedIds.isNotEmpty()) {
            selectionMode = false
            selectedIds.clear()
            onSelectionChanged(0)
            notifyDataSetChanged()
        }
    }

    private fun toggleSelection(item: AlertItem) {
        if (selectedIds.contains(item.id)) {
            selectedIds.remove(item.id)
        } else {
            selectedIds.add(item.id)
        }
        if (selectedIds.isEmpty()) {
            selectionMode = false
        }
        onSelectionChanged(selectedIds.size)
        notifyDataSetChanged()
    }

    class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvType: TextView = itemView.findViewById(R.id.tvAlertType)
        private val tvMessage: TextView = itemView.findViewById(R.id.tvAlertMessage)
        private val tvTime: TextView = itemView.findViewById(R.id.tvAlertTime)
        val cbSelect: CheckBox = itemView.findViewById(R.id.cbSelect)
        private val format = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())

        fun bind(item: AlertItem, selectionMode: Boolean, selected: Boolean) {
            tvType.text = item.type
            tvMessage.text = item.message
            tvTime.text = format.format(Date(item.receivedAt))
            cbSelect.visibility = if (selectionMode) View.VISIBLE else View.GONE
            cbSelect.isChecked = selected
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<AlertItem>() {
        override fun areItemsTheSame(a: AlertItem, b: AlertItem) = a.id == b.id
        override fun areContentsTheSame(a: AlertItem, b: AlertItem) = a == b
    }
}
