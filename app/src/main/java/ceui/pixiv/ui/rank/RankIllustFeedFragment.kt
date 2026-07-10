package ceui.pixiv.ui.rank

import android.os.Bundle
import ceui.lisa.activities.Shaft
import ceui.lisa.models.IllustsBean
import ceui.loxia.Client
import ceui.pixiv.db.discovery.DiscoveryPool
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.PixivFeedSource
import ceui.pixiv.feeds.feedViewModels
import ceui.pixiv.ui.common.IllustFeedFragment
import ceui.pixiv.ui.common.IllustFeedItem

/**
 * 插画 / 漫画排行榜列表页（feeds 框架版，替代 legacy FragmentRankIllust + IAdapter）。
 * 宿主是 RankActivity 的 ViewPager，一个 tab 一个实例；卡片用基类的标准瀑布流插画卡。
 *
 * 与 legacy 的行为对齐点：
 * - 懒加载：Fragment 会被 ViewPager 提前创建，数据等 tab 真正可见（onResume）才拉，
 *   不替用户偷偷请求没打开过的 tab（R18 榜尤其）；
 * - 每页数据在过滤前整页喂给 DiscoveryPool（发现页画像采集，对齐 RankIllustRepo.doOnNext）；
 * - R18 专属榜单（mode 含 r18）跳过全局 R18 过滤，详情 pager 续拉回流的页同样跳过；
 * - 「排行榜过滤已收藏」设置（isFilterRankBookmarked）。
 */
class RankIllustFeedFragment : IllustFeedFragment() {

    private val mode: String by lazy(LazyThreadSafetyMode.NONE) {
        requireArguments().getString(ARG_MODE).orEmpty()
    }
    private val queryDate: String? by lazy(LazyThreadSafetyMode.NONE) {
        requireArguments().getString(ARG_DATE)?.takeIf { it.isNotEmpty() }
    }
    private val skipR18Filter: Boolean get() = mode.contains("r18")

    override val feedViewModel by feedViewModels(autoLoad = false) {
        // 零捕获约定（见 feedViewModels 文档）：source/mapper 归 VM 长期持有，
        // 只捕获局部值、映射走伴生函数，不把 Fragment 实例钉进 VM
        val mode = mode
        val queryDate = queryDate
        val skipR18Filter = skipR18Filter
        PixivFeedSource({ Client.appApi.getRankingIllusts(mode, queryDate) }) { resp, isFirstPage ->
            mapRankPage(resp.displayList, isFirstPage, mode, skipR18Filter)
        }
    }

    /** 详情 pager 回流的页也要跳过 R18 过滤，否则 R18 榜续拉整页被清空。 */
    override fun feedItemFromBean(bean: IllustsBean?): IllustFeedItem? {
        return IllustFeedItem.fromBean(bean, skipR18Filter)
    }

    companion object {
        private const val ARG_MODE = "rank_mode"
        private const val ARG_DATE = "rank_date"

        /** 与 RankActivity 的 tab 标题一一对应（顺序即 tab 顺序）。 */
        @JvmField
        val ILLUST_MODES = arrayOf(
            "day", "week", "month", "day_ai", "day_male", "day_female",
            "week_original", "week_rookie", "day_r18", "week_r18",
            "day_male_r18", "day_female_r18", "day_r18_ai", "week_r18g",
        )

        @JvmField
        val MANGA_MODES = arrayOf(
            "day_manga", "week_manga", "month_manga", "week_rookie_manga", "day_r18_manga",
        )

        @JvmStatic
        fun newInstance(mode: String, date: String?): RankIllustFeedFragment {
            return RankIllustFeedFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_MODE, mode)
                    putString(ARG_DATE, date)
                }
            }
        }

        /** 页响应 → 条目。跑在 Default 线程、被 VM 长期持有，放伴生对象保证零捕获。 */
        private fun mapRankPage(
            illusts: List<ceui.loxia.Illust>,
            isFirstPage: Boolean,
            mode: String,
            skipR18Filter: Boolean,
        ): List<FeedItem> {
            val pairs = illusts.mapNotNull { illust ->
                IllustFeedItem.beanOf(illust)?.let { bean -> illust to bean }
            }
            // 对齐 legacy RankIllustRepo：过滤前的整页喂给 DiscoveryPool（它内部自带去重/静音判断）
            DiscoveryPool.collect(
                pairs.map { it.second },
                if (isFirstPage) "rank:$mode" else "rank_next:$mode",
            )
            val filterBookmarked = Shaft.sSettings.isFilterRankBookmarked
            return pairs.mapNotNull { (illust, bean) ->
                if (filterBookmarked && bean.isIs_bookmarked) return@mapNotNull null
                IllustFeedItem.of(illust, bean, skipR18Filter)
            }
        }
    }
}
