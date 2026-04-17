package com.curax.app

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

class LogEntriesAdapter(
    private val useTimelineLayout: Boolean = false
) : RecyclerView.Adapter<LogEntriesAdapter.ViewHolder>() {

    var entries: List<LogEntry> = emptyList()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val layout = if (useTimelineLayout) R.layout.item_log_timeline else R.layout.item_log_row
        val v = LayoutInflater.from(parent.context).inflate(layout, parent, false)
        return ViewHolder(v, useTimelineLayout)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(entries[position])
    }

    override fun getItemCount(): Int = entries.size

    class ViewHolder(itemView: View, private val timeline: Boolean) : RecyclerView.ViewHolder(itemView) {
        private val tvDate: TextView = itemView.findViewById(R.id.tvLogDate)
        private val tvTime: TextView = itemView.findViewById(R.id.tvLogTime)
        private val tvMedicine: TextView = itemView.findViewById(R.id.tvLogMedicine)
        private val tvStatus: TextView = itemView.findViewById(R.id.tvLogStatus)
        private val tvSource: TextView = itemView.findViewById(R.id.tvLogSource)
        private val tvUser: TextView = itemView.findViewById(R.id.tvLogUser)
        private val dot: View? = if (timeline) itemView.findViewById(R.id.viewLogTimelineDot) else null

        fun bind(entry: LogEntry) {
            tvDate.text = entry.date
            tvTime.text = entry.time
            tvMedicine.text = entry.medicineName
            tvStatus.text = entry.status
            tvSource.text = entry.source
            tvUser.text = entry.userName.ifEmpty { "—" }

            val ctx = itemView.context
            if (timeline) {
                val color = statusColor(ctx, entry.status)
                tvStatus.setTextColor(color)
                dot?.let { applyOvalColor(it, color) }
            } else {
                val legacy = when (entry.status) {
                    "Taken" -> ContextCompat.getColor(ctx, android.R.color.holo_green_dark)
                    "Missed" -> ContextCompat.getColor(ctx, android.R.color.holo_red_dark)
                    else -> ContextCompat.getColor(ctx, R.color.text_primary)
                }
                tvStatus.setTextColor(legacy)
            }
        }

        private fun statusColor(ctx: Context, status: String): Int = when (status) {
            "Taken" -> ContextCompat.getColor(ctx, R.color.standalone_log_success)
            "Missed" -> ContextCompat.getColor(ctx, R.color.standalone_log_error)
            "Alert" -> ContextCompat.getColor(ctx, R.color.standalone_log_info)
            else -> {
                val s = status.lowercase()
                when {
                    s.contains("warn") -> ContextCompat.getColor(ctx, R.color.standalone_log_warning)
                    s.contains("error") || s.contains("fail") -> ContextCompat.getColor(ctx, R.color.standalone_log_error)
                    s.contains("success") || s.contains("ok") || s.contains("complete") ->
                        ContextCompat.getColor(ctx, R.color.standalone_log_success)
                    else -> ContextCompat.getColor(ctx, R.color.standalone_log_info)
                }
            }
        }

        private fun applyOvalColor(view: View, color: Int) {
            view.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
            }
        }
    }
}
