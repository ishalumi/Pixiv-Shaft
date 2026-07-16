package ceui.pixiv.ui.search

import ceui.lisa.utils.Params
import ceui.loxia.Client
import ceui.pixiv.feeds.feedViewModels
import ceui.pixiv.feeds.pixiv.pixivFeedSource
import ceui.pixiv.ui.common.NovelFeedFragment
import ceui.pixiv.ui.common.NovelFeedItem
import android.os.Bundle

/**
 * 「热度小说」页（feeds 框架版，替代 legacy FragmentPopularNovel + PopularNovelRepo + NAdapter）。
 * 入口是搜索结果页里的热门预览「查看更多」，TemplateActivity 路由 `"热度小说"` + [Params.KEY_WORD]。
 *
 * 小说卡渲染 / 收藏切换（含失败回退）/ LIKED_NOVEL 广播同步 / 点击语义 / 骨架图全部继承
 * [NovelFeedFragment]；本类只声明数据源。
 *
 * 端点与 legacy 一致：`v1/search/popular-preview/novel`，只发 word（见
 * [ceui.loxia.API.popularPreviewNovelByWord] 的说明——不复用搜索 V3 那个富参版，那会替 legacy
 * 编一个它从没发过的 sort）。翻页走响应自带的 nextUrl（legacy 走 getNextNovel(nextUrl)，同义）。
 *
 * 无 toolbar：legacy 也没覆写 getToolbarTitle，标题由宿主 TemplateActivity 给。
 */
class PopularNovelFeedFragment : NovelFeedFragment() {

    private val word: String by lazy(LazyThreadSafetyMode.NONE) {
        requireArguments().getString(Params.KEY_WORD).orEmpty()
    }

    override val feedViewModel by feedViewModels {
        // 零捕获：先把 Fragment 属性取成局部 val 再进 source 的 lambda
        val word = word
        pixivFeedSource({ Client.appApi.popularPreviewNovelByWord(word) }) { resp, _ ->
            resp.displayList.mapNotNull { NovelFeedItem.of(it) }
        }
    }

    companion object {
        @JvmStatic
        fun newInstance(word: String?): PopularNovelFeedFragment {
            return PopularNovelFeedFragment().apply {
                arguments = Bundle().apply {
                    putString(Params.KEY_WORD, word.orEmpty())
                }
            }
        }
    }
}
