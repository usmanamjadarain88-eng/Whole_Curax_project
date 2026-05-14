package com.curax.app

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton

data class AdminLinkedUserUiModel(
    val userId: String,
    val name: String,
    val email: String,
    val desktopLinked: Boolean,
    val profilePictureDataUrl: String = "",
    val isDemo: Boolean = false,
    /** Server `user_display_mode`: `standalone` | `default` | empty */
    val userDisplayMode: String = "",
)

private fun avatarAccentForRow(ctx: Context, row: AdminLinkedUserUiModel): Int {
    if (row.isDemo) {
        val resId = when (row.userId) {
            "demo_usman" -> R.color.demo_user_avatar_tint_1
            "demo_hamad" -> R.color.demo_user_avatar_tint_2
            "demo_abdullah" -> R.color.demo_user_avatar_tint_3
            "demo_zara" -> R.color.demo_user_avatar_tint_4
            else -> R.color.demo_user_avatar_tint_1
        }
        return ContextCompat.getColor(ctx, resId)
    }
    val palette = intArrayOf(
        R.color.demo_user_avatar_tint_1,
        R.color.demo_user_avatar_tint_2,
        R.color.demo_user_avatar_tint_3,
        R.color.demo_user_avatar_tint_4,
    )
    val idx = kotlin.math.abs(row.userId.hashCode()) % palette.size
    return ContextCompat.getColor(ctx, palette[idx])
}

private fun modeLabel(ctx: Context, row: AdminLinkedUserUiModel): String {
    val m = row.userDisplayMode.trim().lowercase()
    return when (m) {
        "standalone" -> ctx.getString(R.string.user_mode_standalone)
        "default" -> ctx.getString(R.string.user_mode_default)
        else -> ctx.getString(R.string.admin_user_mode_unknown)
    }
}

class AdminUsersAdapter(
    private val onCareMode: (AdminLinkedUserUiModel) -> Unit,
) : RecyclerView.Adapter<AdminUsersAdapter.VH>() {

    private val items = mutableListOf<AdminLinkedUserUiModel>()
    var managingUserId: String = ""

    fun submit(list: List<AdminLinkedUserUiModel>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_admin_user_row, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position], managingUserId, onCareMode)
    }

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val root = itemView.findViewById<View>(R.id.rowAdminUserRoot)
        private val ivAvatar = itemView.findViewById<ImageView>(R.id.ivAdminUserAvatar)
        private val tvInitial = itemView.findViewById<TextView>(R.id.tvAdminUserInitial)
        private val tvName = itemView.findViewById<TextView>(R.id.tvAdminUserName)
        private val tvMode = itemView.findViewById<TextView>(R.id.tvAdminUserMode)
        private val tvStatus = itemView.findViewById<TextView>(R.id.tvAdminUserStatus)
        private val btnCare = itemView.findViewById<MaterialButton>(R.id.btnAdminUserCareMode)

        fun bind(
            row: AdminLinkedUserUiModel,
            managingUserId: String,
            onCareMode: (AdminLinkedUserUiModel) -> Unit,
        ) {
            val ctx = itemView.context
            val displayName = row.name.trim().ifEmpty { ctx.getString(R.string.admin_user_display_fallback) }
            val initial = displayName.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
            tvInitial.text = initial
            tvName.text = displayName
            tvMode.text = modeLabel(ctx, row)

            val bmp = ProfilePictureDataUrl.decodeBitmap(row.profilePictureDataUrl)
            if (bmp != null) {
                ivAvatar.background = null
                ivAvatar.setImageBitmap(bmp)
                ImageViewCompat.setImageTintList(ivAvatar, null)
                ivAvatar.scaleType = ImageView.ScaleType.CENTER_CROP
                ivAvatar.setPadding(0, 0, 0, 0)
                ivAvatar.visibility = View.VISIBLE
                tvInitial.visibility = View.GONE
            } else {
                val accent = avatarAccentForRow(ctx, row)
                ivAvatar.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(accent)
                }
                ivAvatar.setImageResource(R.drawable.ic_admin_avatar_silhouette)
                ImageViewCompat.setImageTintList(
                    ivAvatar,
                    ColorStateList.valueOf(
                        ContextCompat.getColor(ctx, android.R.color.white),
                    ),
                )
                ivAvatar.scaleType = ImageView.ScaleType.CENTER_INSIDE
                val pad = (6f * ctx.resources.displayMetrics.density).toInt()
                ivAvatar.setPadding(pad, pad, pad, pad)
                ivAvatar.visibility = View.VISIBLE
                tvInitial.visibility = View.GONE
            }

            if (row.desktopLinked) {
                tvStatus.text = ctx.getString(R.string.admin_hub_status_ready_short)
                btnCare.isEnabled = true
                btnCare.alpha = 1f
            } else {
                tvStatus.text = ctx.getString(R.string.admin_hub_status_pending_short)
                btnCare.isEnabled = false
                btnCare.alpha = 0.55f
            }
            val managing = row.userId == managingUserId
            root.setBackgroundColor(
                if (managing) {
                    ContextCompat.getColor(ctx, R.color.inventory_row_selected_bg)
                } else {
                    ContextCompat.getColor(ctx, android.R.color.transparent)
                },
            )
            btnCare.setOnClickListener { onCareMode(row) }
        }
    }
}
