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
import ceui.pixiv.ui.search.v3.DurationBucket
import io.reactivex.Observable
import io.reactivex.functions.Function
import java.time.LocalDate

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
    private var searchAiType: Int? = null,
    private var ratioPattern: String? = null,
    // 分辨率档位 4 项 —— V3 sheet 写入，老 FragmentFilter 不暴露
    private var widthMin: Int? = null,
    private var widthMax: Int? = null,
    private var heightMin: Int? = null,
    private var heightMax: Int? = null,
    /**
     * 投稿期间相对预设档（[DurationBucket].name 字串形式）—— V3 sheet 写入。
     * 与 [startDate]/[endDate] 互斥：非 null 时 [initApi] 当场用 today−N 算出真实 start/end_date
     * 覆盖发出去，跨午夜也不会窗口停滞。null 时直接用 [startDate]/[endDate]（指定期间自定义）。
     */
    private var durationBucket: String? = null,
) : RemoteRepo<ListIllust>() {

    private var filterMapper: FilterMapper? = null

    override fun initApi(): Observable<ListIllust> {
        if (sortType == PixivSearchParamUtil.TRENDING_BUILTIN_SORT_VALUE) {
            return loadTrendingBuiltinIllusts()
        }
        PixivOperate.insertSearchHistory(keyword, SearchTypeUtil.SEARCH_TYPE_DB_KEYWORD)

        // 收藏量两条桶并存：
        //  - bookmarkMin 走官方 `bookmark_num_min` query 参数（仅会员 popular 生效）
        //  - starSize（"Xusers入り"）作为关键字后缀拼到 query 里（对非会员有效，命中 pixiv
        //    自动桶标签）
        // 两者来自 V3 sheet 的两个独立维度（bookmarkBucket / keywordUsersBucket），用户可同时设置。
        val keywordSuffix = if (TextUtils.isEmpty(starSize)) "" else " $starSize"
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

        // 投稿期间相对档当场算 today−N（每次 initApi 都重算,跨午夜窗口自动跟随今天）;
        // bucket 为空时回落到自定义起止日期
        val (effectiveStartDate, effectiveEndDate) = resolveDateRange()

        return if (usePopularPreview) {
            Retro.getAppApi().popularPreview(
                assembledKeyword,
                effectiveStartDate,
                effectiveEndDate,
                searchType,
                bookmarkMin,
                tool,
                lang,
                searchAiType,
                ratioPattern,
                widthMin,
                widthMax,
                heightMin,
                heightMax,
            )
        } else {
            Retro.getAppApi().searchIllust(
                assembledKeyword,
                sortType,
                effectiveStartDate,
                effectiveEndDate,
                searchType,
                bookmarkMin,
                tool,
                lang,
                searchAiType,
                ratioPattern,
                widthMin,
                widthMax,
                heightMin,
                heightMax,
            )
        }
    }

    /**
     * 投稿期间 → (start_date, end_date)：
     *   - bucket 非空：今日往前推 N 天/月/年（[DurationBucket.toDateRange]）
     *   - bucket 为空：回落到 [startDate]/[endDate] 原值（V3「指定期间」自定义）
     *   - bucket 名称无效：当作空 bucket 处理（fail-safe）
     */
    private fun resolveDateRange(): Pair<String?, String?> {
        val bucket = durationBucket?.let { name ->
            DurationBucket.values().firstOrNull { it.name == name }
        } ?: return startDate to endDate
        return bucket.toDateRange(LocalDate.now())
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
        val (effectiveStartDate, effectiveEndDate) = resolveDateRange()
        return Retro.getAppApi().popularPreview(
            keyword ?: "", effectiveStartDate, effectiveEndDate, searchType,
            bookmarkMin, tool, lang, searchAiType, ratioPattern,
            widthMin, widthMax, heightMin, heightMax,
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
        ratioPattern = searchModel.ratioPattern.value
        widthMin = searchModel.widthMin.value
        widthMax = searchModel.widthMax.value
        heightMin = searchModel.heightMin.value
        heightMax = searchModel.heightMax.value
        durationBucket = searchModel.durationBucket.value
        // 老版没显式 AI 字段；用全局开关派生（FragmentFilter 历史就这么干）。
        searchAiType = if (Shaft.sSettings.isDeleteAIIllust) 1 else 0

        this.filterMapper?.updateStarSizeLimit(this.getStarSizeLimit())
    }

    private fun getStarSizeLimit(): Int {
        // 客户端二次兜底过滤：取两条桶里较高的那个门槛。
        // bookmarkMin 来自官方 query；starSize 是 "Xusers入り" 关键字后缀。两条独立，可同存。
        val fromQuery = bookmarkMin ?: 0
        val fromStar = if (TextUtils.isEmpty(starSize)) 0
        else Regex("""\d+""").find(starSize!!)?.value?.toIntOrNull() ?: 0
        return maxOf(fromQuery, fromStar)
    }
}
