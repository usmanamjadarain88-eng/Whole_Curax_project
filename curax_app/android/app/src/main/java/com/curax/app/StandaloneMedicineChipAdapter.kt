package com.curax.app

import android.content.Context
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView

class StandaloneMedicineChipAdapter(
    private val computedStatus: (AdminOverviewFragment.InventoryItem) -> String,
    private val onItemClick: (AdminOverviewFragment.InventoryItem) -> Unit,
    private val onPlaceholderClick: () -> Unit,
) : RecyclerView.Adapter<StandaloneMedicineChipAdapter.VH>() {

    companion object {
        /** Synthetic rows use ids <= this so the UI can show at least three cards without touching real data. */
        const val PLACEHOLDER_CHIP_MAX_ID: Long = -910_000L

        fun isPlaceholderItem(item: AdminOverviewFragment.InventoryItem): Boolean =
            item.id <= PLACEHOLDER_CHIP_MAX_ID
    }

    private val items = mutableListOf<AdminOverviewFragment.InventoryItem>()

    private fun chipStrokePx(ctx: Context): Int =
        (1.5f * ctx.resources.displayMetrics.density).toInt().coerceAtLeast(1)

    fun submit(list: List<AdminOverviewFragment.InventoryItem>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val card = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_standalone_medicine_chip, parent, false) as MaterialCardView
        return VH(card)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    inner class VH(private val card: MaterialCardView) : RecyclerView.ViewHolder(card) {
        private val contentRoot: FrameLayout = card.findViewById(R.id.chip_content_root)
        private val tvName: TextView = card.findViewById(R.id.tv_chip_name)
        private val tvBox: TextView = card.findViewById(R.id.tv_chip_box)
        private val tvStock: TextView = card.findViewById(R.id.tv_chip_stock)
        private val ivCornerDot: ImageView = card.findViewById(R.id.iv_chip_corner_dot)

        fun bind(item: AdminOverviewFragment.InventoryItem) {
            val ctx = card.context
            contentRoot.background = null
            if (isPlaceholderItem(item)) {
                tvName.text = ctx.getString(R.string.standalone_chip_placeholder_name)
                tvBox.text = ctx.getString(R.string.em_dash)
                tvStock.text = ctx.getString(R.string.em_dash)
                card.alpha = 0.88f
                card.setCardBackgroundColor(ContextCompat.getColor(ctx, R.color.standalone_medicine_chip_normal))
                card.strokeColor = ContextCompat.getColor(ctx, R.color.summary_stroke)
                card.strokeWidth = chipStrokePx(ctx)
                ivCornerDot.imageTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(ctx, R.color.standalone_medicine_chip_dot_tint_placeholder),
                )
                card.isClickable = true
                card.isFocusable = true
                card.setOnClickListener { onPlaceholderClick() }
                return
            }
            card.alpha = 1f
            val status = computedStatus(item)
            tvName.text = item.name
            tvBox.text = item.box
            tvStock.text = item.stock.toString()
            val bgStroke = when (status) {
                "Expiring" -> R.color.standalone_medicine_chip_exp to R.color.standalone_medicine_chip_stroke_exp
                "Low" -> R.color.standalone_medicine_chip_low to R.color.standalone_medicine_chip_stroke_low
                else -> R.color.standalone_medicine_chip_normal to R.color.standalone_medicine_chip_stroke_normal
            }
            card.setCardBackgroundColor(ContextCompat.getColor(ctx, bgStroke.first))
            card.strokeColor = ContextCompat.getColor(ctx, bgStroke.second)
            card.strokeWidth = chipStrokePx(ctx)
            val dotTint = when (status) {
                "Expiring" -> R.color.standalone_medicine_chip_dot_tint_exp
                "Low" -> R.color.standalone_medicine_chip_dot_tint_low
                else -> R.color.standalone_medicine_chip_dot_tint_normal
            }
            ivCornerDot.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(ctx, dotTint))
            card.isClickable = true
            card.isFocusable = true
            card.setOnClickListener { onItemClick(item) }
        }
    }
}
