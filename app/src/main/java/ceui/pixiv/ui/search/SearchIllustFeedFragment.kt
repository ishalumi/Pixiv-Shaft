package ceui.pixiv.ui.search

import android.os.Bundle
import android.view.View
import androidx.lifecycle.ViewModelProvider
import ceui.lisa.model.ListIllust
import ceui.lisa.repo.SearchIllustRepo
import ceui.lisa.utils.PixivSearchParamUtil
import ceui.lisa.viewmodel.SearchModel
import ceui.pixiv.feeds.FeedPage
import ceui.pixiv.feeds.FeedSource
import ceui.pixiv.feeds.feedViewModels
import ceui.pixiv.ui.common.IllustFeedFragment
import ceui.pixiv.ui.common.IllustFeedItem
import ceui.pixiv.ui.common.awaitFirstValue
import io.reactivex.functions.Function
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 搜索「插画/漫画」tab（feeds 框架版，替代 legacy FragmentSearchIllust + SearchIllustRepo + IAdapter）。
 * 卡片复用 [IllustFeedFragment] 的标准瀑布流插画卡。
 *
 * 搜索链路重（sort 路由 / 内置热门榜 / 投稿期间档 / 关键字后缀 / R18 三态 + 仅看 AI + starSize
 * 客户端过滤）——**为无损、零发散，数据源直接包裹既有的 [SearchIllustRepo]**（复刻它全部逻辑风险太大），
 * 只把 Rx→suspend 桥一下，过滤走 repo 自己的 FilterMapper。过滤后已是「搜索专属过滤过」的 bean，
 * 用 [IllustFeedItem.rawFromBean] 直接建条目（**绝不能走 .of/fromBean，会在仅看 AI 时误删 AI**）。
 *
 * 响应式重搜：数据源读 activity-scoped [SearchModel] 最新参数（不快照），fragment observe nowGo →
 * 命中标签匹配档才 refresh（对齐 legacy 的 TAG_MATCH_VALUE guard，防选了小说专属 target 时插画也重搜）。
 */
class SearchIllustFeedFragment : IllustFeedFragment() {

    private val searchModel: SearchModel by lazy(LazyThreadSafetyMode.NONE) {
        ViewModelProvider(requireActivity())[SearchModel::class.java]
    }

    override val feedViewModel by feedViewModels(autoLoad = false) {
        // 零捕获：捕获 activity-scoped SearchModel（≥ Activity 生命周期），先取局部 val
        val searchModel = ViewModelProvider(requireActivity())[SearchModel::class.java]
        SearchIllustFeedSource(searchModel)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        searchModel.nowGo.observe(viewLifecycleOwner) {
            // 只在「标签匹配」档响应（对齐 legacy）：选了小说专属 target 时插画 tab 不重搜。
            if (PixivSearchParamUtil.TAG_MATCH_VALUE.contains(searchModel.searchType.value)) {
                feedViewModel.refresh()
            }
        }
    }

    companion object {
        @JvmStatic
        fun newInstance(): SearchIllustFeedFragment = SearchIllustFeedFragment()
    }
}

/**
 * 搜索插画数据源：包裹 [SearchIllustRepo]。load(null) 前 `update(searchModel)` 重读最新参数 +
 * 配置 FilterMapper（R18 三态 / onlyAi / starSize）；load(cursor) 用 repo 翻页。过滤走 repo.mapper()
 * （FilterMapper，含 legacy 全部搜索过滤 + ObjectPool 合池，setValue 失败自动 postValue 兜底，off-main 安全）。
 */
class SearchIllustFeedSource(private val searchModel: SearchModel) : FeedSource<String> {

    private var repo: SearchIllustRepo? = null

    override suspend fun load(cursor: String?): FeedPage<String> {
        // 8 个必填参数先给 null，全部由 update(searchModel) 填；构造时 super 已建好 FilterMapper。
        val r = repo ?: SearchIllustRepo(null, null, null, null, null, null, null, null).also { repo = it }
        val list: ListIllust = if (cursor == null) {
            r.update(searchModel) // 读最新参数 + 配置 FilterMapper
            r.initApi().awaitFirstValue()
        } else {
            r.setNextUrl(cursor)
            r.initNextApi().awaitFirstValue()
        }
        val items = withContext(Dispatchers.Default) {
            @Suppress("UNCHECKED_CAST")
            val filtered = (r.mapper() as Function<ListIllust, ListIllust>).apply(list)
            // FilterMapper 已做完全部搜索专属过滤 → 直接建条目，不再过滤（否则仅看 AI 误删 AI）。
            filtered.list.orEmpty().mapNotNull { IllustFeedItem.rawFromBean(it) }
        }
        return FeedPage(items, list.nextUrl?.takeIf { it.isNotEmpty() })
    }
}
