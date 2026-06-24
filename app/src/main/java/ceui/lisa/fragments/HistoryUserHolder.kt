package ceui.lisa.fragments

import android.content.Intent
import androidx.core.view.isVisible
import ceui.lisa.activities.Shaft
import ceui.lisa.activities.UActivity
import ceui.lisa.annotations.ItemHolder
import ceui.lisa.databinding.CellHistoryUserBinding
import ceui.lisa.utils.GlideUtil
import ceui.lisa.utils.Params
import ceui.loxia.User
import ceui.pixiv.db.GeneralEntity
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.ListItemViewHolder
import com.bumptech.glide.Glide
import java.text.SimpleDateFormat
import java.util.Locale

class HistoryUserHolder(
    val entity: GeneralEntity,
    val onRequestDelete: (GeneralEntity) -> Unit,
    val isSelectionMode: Boolean = false,
    val isSelected: Boolean = false,
    val onToggleSelect: (GeneralEntity) -> Unit = {},
) : ListItemHolder() {
    override fun getItemId(): Long = entity.id

    override fun areContentsTheSame(other: ListItemHolder): Boolean {
        return other is HistoryUserHolder &&
            other.entity.updatedTime == entity.updatedTime &&
            other.isSelectionMode == isSelectionMode &&
            other.isSelected == isSelected
    }
}

@ItemHolder(HistoryUserHolder::class)
class HistoryUserViewHolder(bd: CellHistoryUserBinding) :
    ListItemViewHolder<CellHistoryUserBinding, HistoryUserHolder>(bd) {

    private val timeFormat by lazy {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    }

    override fun onBindViewHolder(holder: HistoryUserHolder, position: Int) {
        super.onBindViewHolder(holder, position)
        val user = runCatching { Shaft.sGson.fromJson(holder.entity.json, User::class.java) }.getOrNull()
        binding.userName.text = user?.name ?: "User #${holder.entity.id}"
        binding.visitTime.text = timeFormat.format(holder.entity.updatedTime)
        val avatarUrl = user?.profile_image_urls?.medium
        if (!avatarUrl.isNullOrEmpty()) {
            Glide.with(context).load(GlideUtil.getUrl(avatarUrl)).into(binding.userAvatar)
        }
        HistorySelectBadge.bind(binding.selectCheck, holder.isSelectionMode, holder.isSelected)
        binding.deleteItem.isVisible = !holder.isSelectionMode

        binding.root.setOnClickListener {
            if (holder.isSelectionMode) {
                holder.onToggleSelect(holder.entity)
                return@setOnClickListener
            }
            context.startActivity(Intent(context, UActivity::class.java).apply {
                putExtra(Params.USER_ID, holder.entity.id.toInt())
            })
        }
        binding.root.setOnLongClickListener {
            if (holder.isSelectionMode) return@setOnLongClickListener true
            holder.onRequestDelete(holder.entity)
            true
        }
        binding.deleteItem.setOnClickListener {
            holder.onRequestDelete(holder.entity)
        }
    }
}
