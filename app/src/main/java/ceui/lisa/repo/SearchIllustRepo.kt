package ceui.lisa.repo

import android.text.TextUtils
import ceui.lisa.activities.Shaft
import ceui.lisa.core.FilterMapper
import ceui.lisa.core.RemoteRepo
import ceui.lisa.http.Retro
import ceui.lisa.model.ListIllust
import ceui.lisa.utils.PixivOperate
import ceui.lisa.utils.PixivSearchParamUtil
import ceui.lisa.utils.SearchTypeUtil
import ceui.lisa.viewmodel.SearchModel
import ceui.pixiv.ui.prime.PrimeIllustLoader
import ceui.pixiv.ui.search.SortType
import io.reactivex.Observable
import io.reactivex.functions.Function

class SearchIllustRepo @JvmOverloads constructor(
    var keyword: String?,
    private var sortType: String?,
    var searchType: String?,
    var starSize: String?,
    //var isPopular: Boolean,
    private var isPremium: Boolean?,
    private var startDate: String?,
    private var endDate: String?,
    private var r18Restriction: Int?,
    // V3 filter 字段——legacy FragmentFilter 没用过；V3 sheet 经
    // SearchFilterV3LegacyBridge 写到 SearchModel，再透到 retrofit。
    private var bookmarkMin: Int? = null,
    private var tool: String? = null,
    private var lang: String? = null,
    private var duration: String? = null,
    private var searchAiType: Int? = null,
) : RemoteRepo<ListIllust>() {

    private var filterMapper: FilterMapper? = null

    override fun initApi(): Observable<ListIllust> {
        if (sortType == PixivSearchParamUtil.TRENDING_BUILTIN_SORT_VALUE) {
            return loadTrendingBuiltinIllusts()
        }
        PixivOperate.insertSearchHistory(keyword, SearchTypeUtil.SEARCH_TYPE_DB_KEYWORD)

        // V3 filter 通过 bookmark_num_min 走原生 query 参数，遇到时跳过 starSize 关键字 hack
        // 避免和 query 参数双写。否则沿用旧的 keyword 后缀逻辑兼容老入口。
        val useBookmarkQuery = (bookmarkMin ?: 0) > 0
        val keywordSuffix = if (useBookmarkQuery) "" else when {
            TextUtils.isEmpty(starSize) -> ""
            else -> " $starSize"
        }
        val assembledKeyword: String = (keyword + keywordSuffix + when (r18Restriction) {
            null -> ""
            else -> " ${PixivSearchParamUtil.R18_RESTRICTION_VALUE[r18Restriction!!]}"
        }).trim()

        // 路由 sort：
        //  - popular_preview 是 popular-preview endpoint 专属——/v1/search/illust 收到会 400
        //  - popular_desc 非 premium 用户也走 popular-preview（pixiv 的旧约束）
        //  其余值（date_desc / date_asc / popular_desc-premium）走 /v1/search/illust，sort 透传。
        val usePopularPreview = sortType == SortType.POPULAR_PREVIEW ||
                (sortType == PixivSearchParamUtil.POPULAR_SORT_VALUE && isPremium != true)

        return if (usePopularPreview) {
            Retro.getAppApi().popularPreview(
                assembledKeyword,
                startDate,
                endDate,
                searchType,
                bookmarkMin,
                tool,
                lang,
                duration,
                searchAiType,
            )
        } else {
            Retro.getAppApi().searchIllust(
                assembledKeyword,
                sortType,
                startDate,
                endDate,
                searchType,
                bookmarkMin,
                tool,
                lang,
                duration,
                searchAiType,
            )
        }
    }

    override fun initNextApi(): Observable<ListIllust> {
        return Retro.getAppApi().getNextIllust(nextUrl)
    }

    override fun mapper(): Function<in ListIllust, ListIllust> {
        if (this.filterMapper == null) {
            this.filterMapper = FilterMapper().enableFilterStarSize()
        }
        return this.filterMapper!!
    }

    private fun loadTrendingBuiltinIllusts(): Observable<ListIllust> {
        val result = PrimeIllustLoader.loadForKeyword(keyword)
        if (result != null) {
            return Observable.just(result)
        }
        return Retro.getAppApi().popularPreview(
            keyword ?: "", startDate, endDate, searchType,
            bookmarkMin, tool, lang, duration, searchAiType,
        )
    }

    fun update(searchModel: SearchModel) {
        keyword = searchModel.keyword.value
        sortType = searchModel.sortType.value
        searchType = searchModel.searchType.value
        starSize = searchModel.starSize.value
        //isPopular = pop
        isPremium = searchModel.isPremium.value
        startDate = searchModel.startDate.value
        endDate = searchModel.endDate.value
        r18Restriction = searchModel.r18Restriction.value
        bookmarkMin = searchModel.bookmarkMin.value
        tool = searchModel.tool.value
        lang = searchModel.lang.value
        duration = searchModel.duration.value
        // 老版没显式 AI 字段；用全局开关派生（FragmentFilter 历史就这么干）。
        searchAiType = if (Shaft.sSettings.isDeleteAIIllust) 1 else 0

        this.filterMapper?.updateStarSizeLimit(this.getStarSizeLimit())
    }

    private fun getStarSizeLimit(): Int {
        // V3 走原生 bookmark_num_min 的话直接用，不再扫 starSize 字符串
        bookmarkMin?.takeIf { it > 0 }?.let { return it }
        if (TextUtils.isEmpty(this.starSize)) {
            return 0
        }
        val match = Regex("""\d+""").find(starSize!!)
        if (match != null) {
            return match.value.toInt()
        }
        return 0
    }
}
