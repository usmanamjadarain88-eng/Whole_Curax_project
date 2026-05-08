package com.curax.app

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AdminAlertsAdapter(
    private val useStandaloneCards: Boolean,
    private val allowBulkMutations: Boolean = true,
    private val onBulkMutationBlocked: (() -> Unit)? = null,
    private val onClick: (AlertItem) -> Unit,
    private val onSelectionChanged: (Int) -> Unit,
) : RecyclerView.Adapter<AdminAlertsAdapter.Holder>() {

    private val items = mutableListOf<AlertItem>()
    private val selectedIds = mutableSetOf<Long>()
    private val timeFormat = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
    private var selectionMode = false

    fun submitList(list: List<AlertItem>) {
        items.clear()
        items.addAll(list)

        val validIds = items.map { it.id }.toSet()
        selectedIds.retainAll(validIds)
        if (selectedIds.isEmpty()) selectionMode = false
        onSelectionChanged(selectedIds.size)

        notifyDataSetChanged()
    }

    fun getSelectedIds(): Set<Long> = selectedIds.toSet()

    fun isSelectionMode(): Boolean = selectionMode

    fun areAllSelected(): Boolean = items.isNotEmpty() && selectedIds.size == items.size

    fun selectAll() {
        if (items.isEmpty()) return
        selectionMode = true
        selectedIds.clear()
        selectedIds.addAll(items.map { it.id })
        onSelectionChanged(selectedIds.size)
        notifyDataSetChanged()
    }

    fun clearSelection() {
        if (!selectionMode && selectedIds.isEmpty()) return
        selectionMode = false
        selectedIds.clear()
        onSelectionChanged(0)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val layout = if (useStandaloneCards) {
            R.layout.item_admin_alert_standalone
        } else {
            R.layout.item_admin_alert
        }
        val view = LayoutInflater.from(parent.context).inflate(layout, parent, false)
        return Holder(view, useStandaloneCards)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        val selected = selectedIds.contains(item.id)

        holder.tvType.text = item.type.uppercase(Locale.getDefault())
        holder.tvMessage.text = item.message
        holder.tvUser.text = item.userName.ifEmpty { "User" }
        holder.tvTime.text = timeFormat.format(Date(item.receivedAt))
        holder.cbSelect.visibility = if (selectionMode) View.VISIBLE else View.GONE
        holder.cbSelect.isChecked = selected

        if (useStandaloneCards) {
            val accent = alertAccentColor(holder.itemView.context, item)
            holder.strip?.setBackgroundColor(accent)
            holder.dot?.let { applyOvalColor(it, accent) }
        }

        holder.itemView.setOnClickListener {
            if (selectionMode) {
                toggleSelection(item)
            } else {
                onClick(item)
            }
        }

        holder.itemView.setOnLongClickListener {
            if (!allowBulkMutations) {
                onBulkMutationBlocked?.invoke()
                return@setOnLongClickListener true
            }
            if (!selectionMode) selectionMode = true
            toggleSelection(item)
            true
        }

        holder.cbSelect.setOnClickListener { toggleSelection(item) }
    }

    private fun toggleSelection(item: AlertItem) {
        if (selectedIds.contains(item.id)) {
            selectedIds.remove(item.id)
        } else {
            selectedIds.add(item.id)
        }
        if (selectedIds.isEmpty()) selectionMode = false
        onSelectionChanged(selectedIds.size)
        notifyDataSetChanged()
    }

    private fun alertAccentColor(context: Context, item: AlertItem): Int {
        val msg = item.message.lowercase(Locale.ROOT)
        val typ = item.type.lowercase(Locale.ROOT)
        return when {
            typ.contains("missed") || msg.contains("missed") ->
                ContextCompat.getColor(context, R.color.standalone_alert_missed)
            typ.contains("taken") || msg.contains("taken") || typ.contains("sent") || msg.contains("sent") ->
                ContextCompat.getColor(context, R.color.standalone_alert_sent)
            else -> ContextCompat.getColor(context, R.color.standalone_alert_pending)
        }
    }

    private fun applyOvalColor(view: View, color: Int) {
        val d = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
        view.background = d
    }

    class Holder(view: View, useStandalone: Boolean) : RecyclerView.ViewHolder(view) {
        val tvType: TextView = view.findViewById(R.id.tvAdminItemType)
        val tvMessage: TextView = view.findViewById(R.id.tvAdminItemMessage)
        val tvUser: TextView = view.findViewById(R.id.tvAdminItemUser)
        val tvTime: TextView = view.findViewById(R.id.tvAdminItemTime)
        val cbSelect: CheckBox = view.findViewById(R.id.cbAdminSelect)
        val strip: View? = if (useStandalone) view.findViewById(R.id.viewAlertStrip) else null
        val dot: View? = if (useStandalone) view.findViewById(R.id.viewAlertStatusDot) else null
    }
}
