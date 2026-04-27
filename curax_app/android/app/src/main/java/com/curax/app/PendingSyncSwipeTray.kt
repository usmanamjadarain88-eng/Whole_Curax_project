package com.curax.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
/**
 * Bottom swipe-dismiss cards for each queued local mutation (informational; queue still syncs in background).
 */
class PendingSyncSwipeTray(private val activity: AppCompatActivity) {

    private val rv: RecyclerView = activity.findViewById(R.id.rvPendingSyncSwipeCards)
    private val adapter = CardAdapter { updateVisibility() }

    init {
        rv.layoutManager = LinearLayoutManager(activity)
        rv.adapter = adapter
        rv.setHasFixedSize(false)
        ItemTouchHelper(
            object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
                override fun onMove(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder,
                    target: RecyclerView.ViewHolder,
                ): Boolean = false

                override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                    adapter.removeAt(viewHolder.bindingAdapterPosition)
                    updateVisibility()
                }
            },
        ).attachToRecyclerView(rv)
        updateVisibility()
    }

    fun push(title: String, subtitle: String) {
        activity.runOnUiThread {
            adapter.items.add(0, Card(title, subtitle))
            if (adapter.items.size > 6) {
                adapter.items.removeAt(adapter.items.lastIndex)
            }
            adapter.notifyItemInserted(0)
            rv.scrollToPosition(0)
            updateVisibility()
        }
    }

    private fun updateVisibility() {
        rv.visibility = if (adapter.items.isEmpty()) View.GONE else View.VISIBLE
    }

    private data class Card(val title: String, val subtitle: String)

    private class CardAdapter(
        private val onChange: () -> Unit,
    ) : RecyclerView.Adapter<CardAdapter.VH>() {
        val items = mutableListOf<Card>()

        fun removeAt(position: Int) {
            if (position in items.indices) {
                items.removeAt(position)
                notifyItemRemoved(position)
                onChange()
            }
        }

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_pending_sync_swipe_card, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(items[position])
        }

        class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val t = itemView.findViewById<TextView>(R.id.tvPendingSyncTitle)
            private val s = itemView.findViewById<TextView>(R.id.tvPendingSyncSubtitle)

            fun bind(c: Card) {
                t.text = c.title
                s.text = c.subtitle
            }
        }
    }
}
