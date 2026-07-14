package ceui.pixiv.ui.chats

import androidx.core.view.isVisible
import androidx.lifecycle.LiveData
import ceui.lisa.annotations.ItemHolder
import ceui.lisa.databinding.ItemRedSectionHeaderBinding
import ceui.loxia.findActionReceiverOrNull
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.ListItemViewHolder
import ceui.pixiv.utils.setOnClick

/**
 * 从旧版 SquareFragment 抽出的共享分区标题 Holder / 语义。原本内联在 SquareFragment.kt，
 * 但 [RedSectionHeaderHolder] / [SeeMoreAction] / [SeeMoreType] 被 UserViewModel、
 * ArtworkViewModel、V3SectionLabelHolder、ArtworkV3Holder 等仍存活的代码引用,
 * 因此随框架清理一并搬到独立文件保留(包名不变,现有 import 无需改动)。
 */
class RedSectionHeaderHolder(
    val title: String,
    val type: Int = 0,
    val seeMoreString: String? = null,
    val liveEndText: LiveData<String>? = null
) : ListItemHolder() {


    override fun areItemsTheSame(other: ListItemHolder): Boolean {
        return title == (other as? RedSectionHeaderHolder)?.title
    }

    override fun areContentsTheSame(other: ListItemHolder): Boolean {
        return title == (other as? RedSectionHeaderHolder)?.title &&
                type == (other as? RedSectionHeaderHolder)?.type &&
                seeMoreString == (other as? RedSectionHeaderHolder)?.seeMoreString
    }
}


@ItemHolder(RedSectionHeaderHolder::class)
class RedSectionHeaderViewHolder(aa: ItemRedSectionHeaderBinding) :
    ListItemViewHolder<ItemRedSectionHeaderBinding, RedSectionHeaderHolder>(aa) {

    override fun onBindViewHolder(holder: RedSectionHeaderHolder, position: Int) {
        super.onBindViewHolder(holder, position)
        binding.title.text = holder.title
        binding.seeMoreLayout.isVisible = holder.type != 0
        binding.seeMoreLayout.setOnClick {
            it.findActionReceiverOrNull<SeeMoreAction>()?.seeMore(holder.type)
        }
        val liveEndText = holder.liveEndText
        if (liveEndText != null) {
            liveEndText.observe(lifecycleOwner) { liveText ->
                binding.seeMore.text = liveText
            }
        } else {
            binding.seeMore.text = holder.seeMoreString
        }
    }
}

interface SeeMoreAction {

    fun seeMore(type: Int)
}

object SeeMoreType {
    const val USER_CREATED_ILLUST = 199
    const val USER_CREATED_MANGA = 200
    const val USER_BOOKMARKED_ILLUST = 201
    const val USER_CREATED_NOVEL = 202
    const val RELATED_ILLUST = 203
}
