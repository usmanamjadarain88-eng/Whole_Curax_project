package com.curax.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

class LogEntriesAdapter : RecyclerView.Adapter<LogEntriesAdapter.ViewHolder>() {

    var entries: List<LogEntry> = emptyList()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_log_row, parent, false)
        return ViewHolder(v)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(entries[position])
    }

    override fun getItemCount(): Int = entries.size

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvDate: TextView = itemView.findViewById(R.id.tvLogDate)
        private val tvTime: TextView = itemView.findViewById(R.id.tvLogTime)
        private val tvMedicine: TextView = itemView.findViewById(R.id.tvLogMedicine)
        private val tvStatus: TextView = itemView.findViewById(R.id.tvLogStatus)
        private val tvSource: TextView = itemView.findViewById(R.id.tvLogSource)
        private val tvUser: TextView = itemView.findViewById(R.id.tvLogUser)

        fun bind(entry: LogEntry) {
            tvDate.text = entry.date
            tvTime.text = entry.time
            tvMedicine.text = entry.medicineName
            tvStatus.text = entry.status
            tvSource.text = entry.source
            tvUser.text = entry.userName.ifEmpty { "—" }
            tvStatus.setTextColor(
                when (entry.status) {
                    "Taken" -> ContextCompat.getColor(itemView.context, android.R.color.holo_green_dark)
                    "Missed" -> ContextCompat.getColor(itemView.context, android.R.color.holo_red_dark)
                    else -> ContextCompat.getColor(itemView.context, R.color.text_primary)
                }
            )
        }
    }
}
