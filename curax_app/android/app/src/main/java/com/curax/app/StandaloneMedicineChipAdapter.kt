package com.curax.app

import android.content.Context
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import java.util.Locale
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView

private fun medicineChipStrokePx(ctx: Context): Int =
    (1.5f * ctx.resources.displayMetrics.density).toInt().coerceAtLeast(1)

private fun medicineChipSelectedStrokePx(ctx: Context): Int =
    (3f * ctx.resources.displayMetrics.density).toInt().coerceAtLeast(2)

/**
 * Shared by [StandaloneMedicineChipAdapter] and dashboard [HorizontalScrollView] chip row.
 */
fun MaterialCardView.bindStandaloneMedicineChip(
    item: AdminOverviewFragment.InventoryItem,
    computedStatus: (AdminOverviewFragment.InventoryItem) -> String,
    selectedBoxUpper: String?,
    onItemClick: (AdminOverviewFragment.InventoryItem) -> Unit,
    onPlaceholderClick: () -> Unit,
    showBoxLabelWhenPlaceholder: Boolean = false,
    onPlaceholderItemClick: ((AdminOverviewFragment.InventoryItem) -> Unit)? = null,
) {
    val ctx = context
    val contentRoot = findViewById<FrameLayout>(R.id.chip_content_root)
    val tvName = findViewById<TextView>(R.id.tv_chip_name)
    val tvBox = findViewById<TextView>(R.id.tv_chip_box)
    val tvStock = findViewById<TextView>(R.id.tv_chip_stock)
    val ivCornerDot = findViewById<ImageView>(R.id.iv_chip_corner_dot)
    contentRoot.background = null
    if (StandaloneMedicineChipAdapter.isPlaceholderItem(item)) {
        tvName.text = ctx.getString(R.string.standalone_chip_placeholder_name)
        tvBox.text = if (showBoxLabelWhenPlaceholder && item.box.isNotBlank()) {
            item.box
        } else {
            ctx.getString(R.string.em_dash)
        }
        tvStock.text = ctx.getString(R.string.em_dash)
        alpha = 0.88f
        setCardBackgroundColor(ContextCompat.getColor(ctx, R.color.standalone_medicine_chip_normal))
        val slotSelected = selectedBoxUpper != null &&
            item.box.trim().uppercase(Locale.US) == selectedBoxUpper
        strokeColor = if (slotSelected) {
            ContextCompat.getColor(ctx, R.color.standalone_medicine_chip_stroke_exp)
        } else {
            ContextCompat.getColor(ctx, R.color.summary_stroke)
        }
        strokeWidth = if (slotSelected) medicineChipSelectedStrokePx(ctx) else medicineChipStrokePx(ctx)
        ivCornerDot.imageTintList = ColorStateList.valueOf(
            ContextCompat.getColor(ctx, R.color.standalone_medicine_chip_dot_tint_placeholder),
        )
        isClickable = true
        isFocusable = true
        setOnClickListener {
            if (onPlaceholderItemClick != null) {
                onPlaceholderItemClick.invoke(item)
            } else {
                onPlaceholderClick()
            }
        }
        return
    }
    alpha = 1f
    val status = computedStatus(item)
    tvName.text = item.name
    tvBox.text = item.box
    tvStock.text = item.stock.toString()
    val bgStroke = when (status) {
        "Expiring" -> R.color.standalone_medicine_chip_exp to R.color.standalone_medicine_chip_stroke_exp
        "Low" -> R.color.standalone_medicine_chip_low to R.color.standalone_medicine_chip_stroke_low
        else -> R.color.standalone_medicine_chip_normal to R.color.standalone_medicine_chip_stroke_normal
    }
    setCardBackgroundColor(ContextCompat.getColor(ctx, bgStroke.first))
    val slotSelected = selectedBoxUpper != null &&
        item.box.trim().uppercase(Locale.US) == selectedBoxUpper
    strokeColor = ContextCompat.getColor(
        ctx,
        if (slotSelected) R.color.standalone_medicine_chip_stroke_exp else bgStroke.second,
    )
    strokeWidth = if (slotSelected) medicineChipSelectedStrokePx(ctx) else medicineChipStrokePx(ctx)
    val dotTint = when (status) {
        "Expiring" -> R.color.standalone_medicine_chip_dot_tint_exp
        "Low" -> R.color.standalone_medicine_chip_dot_tint_low
        else -> R.color.standalone_medicine_chip_dot_tint_normal
    }
    ivCornerDot.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(ctx, dotTint))
    isClickable = true
    isFocusable = true
    setOnClickListener { onItemClick(item) }
}

class StandaloneMedicineChipAdapter(
    private val computedStatus: (AdminOverviewFragment.InventoryItem) -> String,
    private val onItemClick: (AdminOverviewFragment.InventoryItem) -> Unit,
    private val onPlaceholderClick: () -> Unit,
    /** When true, empty slots still show the target box id (e.g. B1) for dose tracking. */
    private val showBoxLabelWhenPlaceholder: Boolean = false,
    /** When set, placeholder taps invoke this with the row (e.g. select empty B1) instead of [onPlaceholderClick]. */
    private val onPlaceholderItemClick: ((AdminOverviewFragment.InventoryItem) -> Unit)? = null,
) : RecyclerView.Adapter<StandaloneMedicineChipAdapter.VH>() {

    companion object {
        /** Synthetic rows use ids <= this so the UI can show at least three cards without touching real data. */
        const val PLACEHOLDER_CHIP_MAX_ID: Long = -910_000L

        fun isPlaceholderItem(item: AdminOverviewFragment.InventoryItem): Boolean =
            item.id <= PLACEHOLDER_CHIP_MAX_ID
    }

    private val items = mutableListOf<AdminOverviewFragment.InventoryItem>()

    /** Highlight selected box (dose tracking). */
    var selectedBoxUpper: String? = null
        set(value) {
            field = value?.trim()?.uppercase(Locale.US)
            notifyDataSetChanged()
        }

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
        fun bind(item: AdminOverviewFragment.InventoryItem) {
            card.bindStandaloneMedicineChip(
                item = item,
                computedStatus = computedStatus,
                selectedBoxUpper = selectedBoxUpper,
                onItemClick = onItemClick,
                onPlaceholderClick = onPlaceholderClick,
                showBoxLabelWhenPlaceholder = showBoxLabelWhenPlaceholder,
                onPlaceholderItemClick = onPlaceholderItemClick,
            )
        }
    }
}

/** Dashboard / dose tracking: horizontal row of chips without [RecyclerView]. */
fun populateStandaloneMedicineChipRow(
    container: LinearLayout,
    inflater: LayoutInflater,
    items: List<AdminOverviewFragment.InventoryItem>,
    computedStatus: (AdminOverviewFragment.InventoryItem) -> String,
    selectedBoxUpper: String?,
    onItemClick: (AdminOverviewFragment.InventoryItem) -> Unit,
    onPlaceholderClick: () -> Unit,
    showBoxLabelWhenPlaceholder: Boolean = false,
    onPlaceholderItemClick: ((AdminOverviewFragment.InventoryItem) -> Unit)? = null,
) {
    container.removeAllViews()
    for (item in items) {
        val card = inflater.inflate(R.layout.item_standalone_medicine_chip, container, false) as MaterialCardView
        card.bindStandaloneMedicineChip(
            item,
            computedStatus,
            selectedBoxUpper,
            onItemClick,
            onPlaceholderClick,
            showBoxLabelWhenPlaceholder,
            onPlaceholderItemClick,
        )
        container.addView(card)
    }
}
