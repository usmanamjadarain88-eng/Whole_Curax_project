package com.curax.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class StandaloneHealthHubSyncHistoryActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_health_hub_sync_history)
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        val rv = findViewById<RecyclerView>(R.id.rvSyncHistory)
        val rows = HealthHubHistoryStore.readSyncEvents(this)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = SyncAdapter(rows)
    }

    private class SyncAdapter(private val items: List<Pair<Long, String>>) : RecyclerView.Adapter<SyncVH>() {
        private val fmtServer = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
            timeZone = java.util.TimeZone.getDefault()
        }
        private val fmtOut = SimpleDateFormat("EEE, MMM d yyyy · HH:mm", Locale.getDefault())
        private val fmtRecorded = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SyncVH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_sync_history_row, parent, false)
            return SyncVH(v)
        }

        override fun getItemCount(): Int = items.size.coerceAtLeast(1)

        override fun onBindViewHolder(holder: SyncVH, position: Int) {
            if (items.isEmpty()) {
                holder.server.text = holder.itemView.context.getString(R.string.sync_history_empty)
                holder.recorded.visibility = View.GONE
                return
            }
            holder.recorded.visibility = View.VISIBLE
            val (at, serverIso) = items[position]
            val label = try {
                val normalized = serverIso.replace(' ', 'T').substringBefore('Z').substringBefore('+').take(19)
                val d = fmtServer.parse(normalized) ?: Date(at)
                fmtOut.format(d)
            } catch (_: Exception) {
                serverIso
            }
            holder.server.text = label
            holder.recorded.text = "Recorded ${fmtRecorded.format(Date(at))}"
        }
    }

    private class SyncVH(v: View) : RecyclerView.ViewHolder(v) {
        val server: TextView = v.findViewById(R.id.tvServerTime)
        val recorded: TextView = v.findViewById(R.id.tvRecordedAt)
    }
}
