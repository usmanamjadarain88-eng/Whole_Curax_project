package com.curax.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton

data class AdminLinkedUserUiModel(
    val userId: String,
    val name: String,
    val email: String,
    val desktopLinked: Boolean,
    val profilePictureDataUrl: String = "",
    val isDemo: Boolean = false,
)

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

            val bmp = ProfilePictureDataUrl.decodeBitmap(row.profilePictureDataUrl)
            if (bmp != null) {
                ivAvatar.setImageBitmap(bmp)
                ivAvatar.visibility = View.VISIBLE
                tvInitial.visibility = View.INVISIBLE
            } else {
                ivAvatar.setImageDrawable(null)
                ivAvatar.visibility = View.GONE
                tvInitial.visibility = View.VISIBLE
            }

            if (row.desktopLinked) {
                tvStatus.text = ctx.getString(R.string.admin_hub_status_ready_short)
                btnCare.isEnabled = !row.isDemo
                btnCare.alpha = if (row.isDemo) 0.45f else 1f
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
