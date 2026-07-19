package ceui.pixiv.ui.search

import android.os.Bundle
import android.view.View
import androidx.lifecycle.ViewModelProvider
import ceui.lisa.activities.Shaft
import ceui.lisa.model.ListNovel
import ceui.lisa.models.NovelBean
import ceui.lisa.repo.SearchNovelRepo
import ceui.lisa.utils.PixivSearchParamUtil
import ceui.lisa.viewmodel.SearchModel
import ceui.loxia.Novel
import ceui.pixiv.feeds.FeedPage
import ceui.pixiv.feeds.FeedSource
import ceui.pixiv.feeds.feedViewModels
import ceui.pixiv.ui.common.NovelFeedFragment
import ceui.pixiv.ui.common.NovelFeedItem
import ceui.pixiv.ui.common.awaitFirstValue
import ceui.pixiv.ui.search.v3.AiMode
import ceui.pixiv.ui.search.v3.R18Mode
import ceui.pixiv.ui.search.v3.SearchFilterV3
import io.reactivex.functions.Function
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * 搜索「小说」tab（feeds 框架版）。
 * 喜欢！数在 Feed 层按 total_bookmarks 硬滤（与插画 tab 对齐，isha）。
 */
class SearchNovelFeedFragment : NovelFeedFragment() {

    private val searchModel: SearchModel by lazy(LazyThreadSafetyMode.NONE) {
        ViewModelProvider(requireActivity())[SearchModel::class.java]
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
        SearchNovelFeedSource(sm, svm)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        searchModel.nowGo.observe(viewLifecycleOwner) {
            val st = searchModel.searchType.value
            if (st == null || PixivSearchParamUtil.TAG_MATCH_VALUE_NOVEL.contains(st)) {
                feedViewModel.refresh()
            }
        }
    }

    companion object {
        @JvmStatic
        fun newInstance(): SearchNovelFeedFragment = SearchNovelFeedFragment()
    }
}

/**
 * 搜索小说数据源。
 *
 * 上游 [SearchNovelRepo] 的 Mapper **不做**收藏数过滤（只有 R18 / AI），
 * 非会员 bookmark_num_min 又被服务端忽略——所以必须在 Feed 层硬滤 total_bookmarks。
 *
 * 门槛 = max(V3.novelFilter.bookmarkBucket, V3.users入り, SearchModel.bookmarkMin/starSize)
 */
class SearchNovelFeedSource(
    private val searchModel: SearchModel,
    private val searchViewModel: SearchViewModel,
) : FeedSource<String> {

    private var repo: SearchNovelRepo? = null

    private fun resolveBookmarkFloor(): Int {
        val v3: SearchFilterV3 = searchViewModel.novelFilter.value ?: SearchFilterV3()
        val fromV3Bookmark = v3.bookmarkBucket.min
        val fromV3Users = v3.keywordUsersBucket.min
        val fromQuery = searchModel.bookmarkMin.value ?: 0
        val star = searchModel.starSize.value.orEmpty()
        val fromStar = Regex("""\d+""").find(star)?.value?.toIntOrNull() ?: 0
        val floor = maxOf(fromV3Bookmark, fromV3Users, fromQuery, fromStar)
        Timber.d(
            "isha-star-filter-novel: floor=%d (v3bm=%d v3users=%d modelBm=%d modelStar=%d)",
            floor, fromV3Bookmark, fromV3Users, fromQuery, fromStar,
        )
        return floor
    }

    private fun syncV3IntoSearchModel() {
        val filter = searchViewModel.novelFilter.value ?: return
        searchModel.sortType.value = filter.sort
        searchModel.searchType.value = filter.searchTarget.apiValue
        searchModel.starSize.value = filter.keywordUsersBucket.keywordSuffix()
        searchModel.bookmarkMin.value = filter.bookmarkBucket.bookmarkMin()
        searchModel.r18Restriction.value = when (filter.r18Mode) {
            R18Mode.All -> 0
            R18Mode.SafeOnly -> 1
            R18Mode.R18Only -> 2
        }
        searchModel.onlyAi.value = filter.aiMode == AiMode.OnlyAi
        searchModel.genre.value = filter.genre
        searchModel.lang.value = filter.lang
        searchModel.isOriginalOnly.value = filter.isOriginalOnly
        searchModel.isReplaceableOnly.value = filter.isReplaceableOnly
    }

    override suspend fun load(cursor: String?): FeedPage<String> {
        val r = repo ?: SearchNovelRepo(null, null, null, null, null, null, null, null).also { repo = it }
        syncV3IntoSearchModel()
        val floor = resolveBookmarkFloor()
        // 小说搜索始终开 spam 过滤（乱码作者 + teach.link 传送门）。
        // 首屏适度连拉凑可滚动列表；翻页只滤当前页，避免 rate limit。
        // 首屏凑一批；翻页时若本页全被滤空，继续连拉几页，否则列表“划不动”
        val isRefresh = cursor == null
        val minKeep = if (isRefresh) 10 else 1
        val maxPages = if (isRefresh) 6 else 8

        val acc = ArrayList<NovelFeedItem>()
        var next: String? = null
        var pageCursor = cursor
        var pages = 0
        var droppedStar = 0
        var droppedSpam = 0

        while (true) {
            val isFirst = pageCursor == null && pages == 0 && cursor == null
            val list: ListNovel = if (isFirst) {
                withContext(Dispatchers.IO) {
                    r.update(searchModel)
                    r.initApi()
                }.awaitFirstValue()
            } else {
                val useCursor = pageCursor ?: break
                withContext(Dispatchers.IO) {
                    r.update(searchModel)
                    r.setNextUrl(useCursor)
                    r.initNextApi()
                }.awaitFirstValue()
            }

            val pageItems = withContext(Dispatchers.Default) {
                @Suppress("UNCHECKED_CAST")
                val filtered = (r.mapper() as Function<ListNovel, ListNovel>).apply(list)
                val kept = ArrayList<NovelFeedItem>()
                for (bean in filtered.list.orEmpty()) {
                    if (SpamNovelFilter.isSpam(bean)) {
                        droppedSpam++
                        continue
                    }
                    if (floor > 0 && bean.total_bookmarks < floor) {
                        droppedStar++
                        continue
                    }
                    rawNovelItem(bean)?.let { kept.add(it) }
                }
                kept
            }
            acc.addAll(pageItems)
            next = list.nextUrl?.takeIf { it.isNotEmpty() }
            pages++

            if (acc.size >= minKeep || next.isNullOrEmpty() || pages >= maxPages) {
                break
            }
            delay(400)
            pageCursor = next
        }

        Timber.d(
            "isha-novel-filter: floor=%d kept=%d spam=%d star=%d pages=%d",
            floor, acc.size, droppedSpam, droppedStar, pages,
        )
        return FeedPage(acc, next)
    }

    private fun rawNovelItem(bean: NovelBean): NovelFeedItem? {
        val novel = runCatching {
            Shaft.sGson.fromJson(Shaft.sGson.toJsonTree(bean), Novel::class.java)
        }.getOrNull() ?: return null
        return NovelFeedItem(novel)
    }
}
