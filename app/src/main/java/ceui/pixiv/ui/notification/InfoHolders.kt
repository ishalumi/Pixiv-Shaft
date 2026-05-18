package ceui.pixiv.ui.notification

import androidx.core.view.isVisible
import ceui.lisa.annotations.ItemHolder
import ceui.lisa.databinding.CellInfoCategoryHeaderBinding
import ceui.lisa.databinding.CellInfoEntryBinding
import ceui.loxia.CategorizedInfo
import ceui.loxia.InfoItem
import ceui.loxia.findActionReceiverOrNull
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.ListItemViewHolder
import ceui.pixiv.utils.setOnClick

/**
 * 公告条目点击/分类下钻回调。InfoLatestFragment + InfoCategoryListFragment 都实现这个。
 */
interface InfoActionReceiver {
    fun onClickInfo(item: InfoItem)
    /** category_more 点击,跳 InfoCategoryListFragment(/v1/info/list?cid=N)。 */
    fun onClickInfoCategoryMore(category: CategorizedInfo)
}

/**
 * Latest 聚合页里每个分类前的小标题。点 "查看更多" → 下钻该分类的完整 list。
 * 子页 InfoCategoryListFragment 用同一个 cell 渲染 header 不需要"更多"按钮,
 * 用 [showMore] 控制可见。
 */
class InfoCategoryHeaderHolder(
    val category: CategorizedInfo,
    val showMore: Boolean = true,
) : ListItemHolder() {
    override fun getItemId(): Long = -(category.category_id.toLong() + 1L) // header id 与 InfoItem.id 不重叠

    override fun areItemsTheSame(other: ListItemHolder): Boolean {
        val o = other as? InfoCategoryHeaderHolder ?: return false
        return category.category_id == o.category.category_id
    }

    override fun areContentsTheSame(other: ListItemHolder): Boolean {
        val o = other as? InfoCategoryHeaderHolder ?: return false
        return category.category_id == o.category.category_id &&
            category.category_title == o.category.category_title &&
            showMore == o.showMore
    }
}

@ItemHolder(InfoCategoryHeaderHolder::class)
class InfoCategoryHeaderViewHolder(bd: CellInfoCategoryHeaderBinding) :
    ListItemViewHolder<CellInfoCategoryHeaderBinding, InfoCategoryHeaderHolder>(bd) {

    override fun onBindViewHolder(holder: InfoCategoryHeaderHolder, position: Int) {
        super.onBindViewHolder(holder, position)
        binding.categoryTitle.text = holder.category.category_title.orEmpty()
        binding.categoryMore.isVisible = holder.showMore
        // 整行不响应点击,只有"查看更多"自己接事件 —— section header 不是 actionable row。
        if (holder.showMore) {
            binding.categoryMore.setOnClick { sender ->
                sender.findActionReceiverOrNull<InfoActionReceiver>()
                    ?.onClickInfoCategoryMore(holder.category)
            }
        } else {
            binding.categoryMore.setOnClickListener(null)
            binding.categoryMore.isClickable = false
        }
    }
}

class InfoEntryHolder(val item: InfoItem) : ListItemHolder() {
    override fun getItemId(): Long = item.id

    override fun areItemsTheSame(other: ListItemHolder): Boolean {
        return item.id == (other as? InfoEntryHolder)?.item?.id
    }

    override fun areContentsTheSame(other: ListItemHolder): Boolean {
        val o = (other as? InfoEntryHolder)?.item ?: return false
        return item.id == o.id &&
            item.title == o.title &&
            item.is_recent == o.is_recent
    }
}

@ItemHolder(InfoEntryHolder::class)
class InfoEntryViewHolder(bd: CellInfoEntryBinding) :
    ListItemViewHolder<CellInfoEntryBinding, InfoEntryHolder>(bd) {

    override fun onBindViewHolder(holder: InfoEntryHolder, position: Int) {
        super.onBindViewHolder(holder, position)
        val item = holder.item
        binding.infoTitle.text = item.title.orEmpty()
        binding.infoDate.text = item.date?.take(10).orEmpty() // "2026-04-21T13:00:00+09:00" → "2026-04-21"
        binding.infoRecentDot.isVisible = item.is_recent
        binding.infoEntryRoot.setOnClick { sender ->
            sender.findActionReceiverOrNull<InfoActionReceiver>()?.onClickInfo(item)
        }
    }
}
