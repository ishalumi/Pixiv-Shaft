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
import ceui.pixiv.ui.search.v3.SearchFilterV3
import io.reactivex.functions.Function
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * 搜索「插画/漫画」tab（feeds 框架版）。
 * 数据源包裹 [SearchIllustRepo]；喜欢！数在 Feed 层再硬滤 total_bookmarks（isha）。
 */
class SearchIllustFeedFragment : IllustFeedFragment() {

    private val searchModel: SearchModel by lazy(LazyThreadSafetyMode.NONE) {
        ViewModelProvider(requireActivity())[SearchModel::class.java]
    }

    private val searchViewModel: SearchViewModel by lazy(LazyThreadSafetyMode.NONE) {
        val factory = object : ViewModelProvider.Factory {
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return SearchViewModel("") as T
            }
        }
        ViewModelProvider(requireActivity(), factory)[SearchViewModel::class.java]
    }

    override val feedViewModel by feedViewModels(autoLoad = false) {
        val sm = ViewModelProvider(requireActivity())[SearchModel::class.java]
        val factory = object : ViewModelProvider.Factory {
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return SearchViewModel("") as T
            }
        }
        val svm = ViewModelProvider(requireActivity(), factory)[SearchViewModel::class.java]
        SearchIllustFeedSource(sm, svm)
    }

    override val detailContinuationCursor: String?
        get() = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        searchModel.nowGo.observe(viewLifecycleOwner) {
            // 放宽 gate：searchType 未初始化时也允许刷新（首次进搜索页常见）
            val st = searchModel.searchType.value
            if (st == null || PixivSearchParamUtil.TAG_MATCH_VALUE.contains(st)) {
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
 * 搜索插画数据源。
 *
 * 门槛来源（取 max，避免 LiveData 时序漏写）：
 * 1. SearchViewModel.illustFilter.bookmarkBucket / keywordUsersBucket  — V3 真单源
 * 2. SearchModel.bookmarkMin / starSize — bridge 翻译结果
 *
 * 过滤：FilterMapper + Feed 层 total_bookmarks 硬滤 + 首屏自动补页。
 */
class SearchIllustFeedSource(
    private val searchModel: SearchModel,
    private val searchViewModel: SearchViewModel,
) : FeedSource<String> {

    private var repo: SearchIllustRepo? = null

    private fun resolveBookmarkFloor(): Int {
        val v3: SearchFilterV3 = searchViewModel.illustFilter.value ?: SearchFilterV3()
        val fromV3Bookmark = v3.bookmarkBucket.min
        val fromV3Users = v3.keywordUsersBucket.min
        val fromQuery = searchModel.bookmarkMin.value ?: 0
        val star = searchModel.starSize.value.orEmpty()
        val fromStar = Regex("""\d+""").find(star)?.value?.toIntOrNull() ?: 0
        val floor = maxOf(fromV3Bookmark, fromV3Users, fromQuery, fromStar)
        Timber.d(
            "isha-star-filter: floor=%d (v3bm=%d v3users=%d modelBm=%d modelStar=%d)",
            floor, fromV3Bookmark, fromV3Users, fromQuery, fromStar,
        )
        return floor
    }

    override suspend fun load(cursor: String?): FeedPage<String> {
        val r = repo ?: SearchIllustRepo(null, null, null, null, null, null, null, null).also { repo = it }
        // 每次 load 都把 V3 filter 同步进 SearchModel，防止 bridge 时序漏写
        syncV3IntoSearchModel()
        val floor = resolveBookmarkFloor()
        // 有门槛时：首屏最多连拉几页凑到可滚动；翻页不再连拉，避免 rate limit
        val isRefresh = cursor == null
        val minKeep = if (floor > 0 && isRefresh) 8 else 1
        val maxPages = if (floor > 0 && isRefresh) 4 else 1

        val acc = ArrayList<IllustFeedItem>()
        var next: String? = null
        var pageCursor = cursor
        var pages = 0
        var dropped = 0

        while (true) {
            val isFirstNetwork = pageCursor == null && pages == 0 && cursor == null
            val list: ListIllust = if (isFirstNetwork) {
                val api = withContext(Dispatchers.IO) {
                    r.update(searchModel)
                    r.initApi()
                }
                api.awaitFirstValue()
            } else {
                val useCursor = pageCursor ?: break
                val api = withContext(Dispatchers.IO) {
                    r.update(searchModel)
                    r.setNextUrl(useCursor)
                    r.initNextApi()
                }
                api.awaitFirstValue()
            }

            val pageItems = withContext(Dispatchers.Default) {
                @Suppress("UNCHECKED_CAST")
                val filtered = (r.mapper() as Function<ListIllust, ListIllust>).apply(list)
                val raw = filtered.list.orEmpty()
                val kept = ArrayList<IllustFeedItem>(raw.size)
                for (bean in raw) {
                    if (floor > 0 && bean.total_bookmarks < floor) {
                        dropped++
                        continue
                    }
                    IllustFeedItem.rawFromBean(bean)?.let { kept.add(it) }
                }
                kept
            }
            acc.addAll(pageItems)
            next = list.nextUrl?.takeIf { it.isNotEmpty() }
            pages++

            if (floor <= 0 || acc.size >= minKeep || next.isNullOrEmpty() || pages >= maxPages) {
                break
            }
            // 页间喘口气，避免一次 burst 撞 rate limit
            delay(350)
            pageCursor = next
        }

        if (floor > 0) {
            Timber.d(
                "isha-star-filter: done floor=%d kept=%d dropped=%d pages=%d next=%s",
                floor, acc.size, dropped, pages, next != null,
            )
        }
        return FeedPage(acc, next)
    }

    /** 把 V3 illustFilter 直接写进 SearchModel（与 LegacyBridge.applyToLegacy 对齐的最小子集）。 */
    private fun syncV3IntoSearchModel() {
        val filter = searchViewModel.illustFilter.value ?: return
        searchModel.sortType.value = filter.sort
        searchModel.searchType.value = filter.searchTarget.apiValue
        searchModel.starSize.value = filter.keywordUsersBucket.keywordSuffix()
        searchModel.bookmarkMin.value = filter.bookmarkBucket.bookmarkMin()
        searchModel.r18Restriction.value = when (filter.r18Mode) {
            ceui.pixiv.ui.search.v3.R18Mode.All -> 0
            ceui.pixiv.ui.search.v3.R18Mode.SafeOnly -> 1
            ceui.pixiv.ui.search.v3.R18Mode.R18Only -> 2
        }
        searchModel.onlyAi.value = filter.aiMode == ceui.pixiv.ui.search.v3.AiMode.OnlyAi
    }
}
