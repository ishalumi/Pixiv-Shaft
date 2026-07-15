package ceui.pixiv.ui.common

import ceui.lisa.helper.IllustNovelFilter
import ceui.loxia.Novel
import ceui.pixiv.feeds.FeedItem

/**
 * 小说 feed 条目：只持不可变的 loxia [Novel]（data class）。
 *
 * 对齐插画侧 [IllustFeedItem] 的思路，但小说全链路已 loxia 化（收藏走
 * [ceui.loxia.API.addNovelBookmark]、详情走 [DetailFeedSupport.openNovelDetail]、
 * 标签流 [ceui.pixiv.widgets.V3TagFlowView.setTags] 直接吃 loxia [ceui.loxia.Tag]），
 * 因此不再像 IllustFeedItem 那样并存 legacy 可变 bean、也不做 gson 往返——
 * 卡片渲染、收藏、跳转全部读这一个 data class。
 *
 * 内容相等性只看 [novel]（data class 深比较），驱动 DiffUtil 原地重绑。
 */
class NovelFeedItem(val novel: Novel) : FeedItem {

    override val feedKey: Any get() = novel.id

    override fun equals(other: Any?): Boolean {
        return other is NovelFeedItem && other.novel == novel
    }

    override fun hashCode(): Int = novel.hashCode()

    /**
     * 收藏态变更：copy 出新实例（相等性变化触发 DiffUtil 重绑爱心 + 收藏数）。
     * 幂等——已是目标态直接返回自身：乐观切态后 LIKED_NOVEL 广播会带着同一状态回流到
     * 本列表(含自己发的)再走一次 withBookmarked,不加这道守卫收藏数会被重复 ±1。
     */
    fun withBookmarked(liked: Boolean): NovelFeedItem {
        if ((novel.is_bookmarked == true) == liked) return this
        val delta = if (liked) 1 else -1
        val newCount = ((novel.total_bookmarks ?: 0) + delta).coerceAtLeast(0)
        return NovelFeedItem(novel.copy(is_bookmarked = liked, total_bookmarks = newCount))
    }

    companion object {
        /** 过滤 + 建条目；整页被滤空时由 FeedViewModel 空页追载兜住。 */
        fun of(novel: Novel): NovelFeedItem? {
            return if (passesContentFilters(novel)) NovelFeedItem(novel) else null
        }

        /**
         * 与 legacy [ceui.lisa.core.Mapper] 的小说分支逐条对齐（tag / id / 作者 / R18 过滤）。
         * 走 [IllustNovelFilter] 的 loxia Novel 重载，无需 NovelBean。
         */
        private fun passesContentFilters(novel: Novel): Boolean {
            if (IllustNovelFilter.judgeTag(novel)) return false
            if (IllustNovelFilter.judgeID(novel)) return false
            if (IllustNovelFilter.judgeUserID(novel)) return false
            if (IllustNovelFilter.judgeR18Filter(novel)) return false
            return true
        }
    }
}
