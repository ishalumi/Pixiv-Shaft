package ceui.lisa.repo

import android.text.TextUtils
import ceui.lisa.activities.Shaft
import ceui.lisa.core.Mapper
import ceui.lisa.core.RemoteRepo
import ceui.lisa.http.Retro
import ceui.lisa.model.ListNovel
import ceui.lisa.utils.PixivSearchParamUtil
import ceui.lisa.viewmodel.SearchModel
import ceui.pixiv.ui.search.SortType
import ceui.pixiv.ui.search.v3.DurationBucket
import ceui.pixiv.ui.search.v3.SearchTarget
import io.reactivex.Observable
import io.reactivex.functions.Function
import java.time.LocalDate

class SearchNovelRepo @JvmOverloads constructor(
    var keyword: String?,
    private var sortType: String?,
    var searchType: String?,
    var starSize: String?,
    private var isPremium: Boolean?,
    private var startDate: String?,
    private var endDate: String?,
    private var r18Restriction: Int?,
    private var bookmarkMin: Int? = null,
    private var genre: Int? = null,
    private var lang: String? = null,
    private var searchAiType: Int? = null,
    private var isOriginalOnly: Boolean? = null,
    private var isReplaceableOnly: Boolean? = null,
    // 正文长度 / 阅读用时 6 项 —— V3 sheet 写入；mockup 参数名待真实抓包替换
    private var textLengthMin: Int? = null,
    private var textLengthMax: Int? = null,
    private var wordCountMin: Int? = null,
    private var wordCountMax: Int? = null,
    private var readingTimeMin: Int? = null,
    private var readingTimeMax: Int? = null,
    /**
     * 投稿期间相对预设档（[DurationBucket].name 字串形式）—— V3 sheet 写入。
     * 与 [startDate]/[endDate] 互斥：非 null 时 [initApi] 当场算 today−N 覆盖发出去，
     * 跨午夜也不会窗口停滞。null 时直接用 [startDate]/[endDate]（指定期间自定义）。
     */
    private var durationBucket: String? = null,
) : RemoteRepo<ListNovel>() {

    private var filterMapper: Mapper<ListNovel>? = null
    private var searchOnlyAi: Boolean = false

    // 复用基类 Mapper（已含屏蔽 tag/ID/用户 + 全局 R18 过滤）；额外承载搜索「R-18 限制」三档。
    // RemoteRepo 每次请求都会调 mapper()，在这里同步 r18 / onlyAi。
    override fun mapper(): Function<in ListNovel, ListNovel> {
        if (filterMapper == null) {
            filterMapper = Mapper()
        }
        filterMapper!!.setSearchR18Restriction(r18Restriction ?: 0)
        filterMapper!!.setSearchOnlyAi(searchOnlyAi)
        return filterMapper!!
    }

    override fun initApi(): Observable<ListNovel> {
        val useBookmarkQuery = (bookmarkMin ?: 0) > 0
        val keywordSuffix = if (useBookmarkQuery) "" else when {
            TextUtils.isEmpty(starSize) -> ""
            else -> " $starSize"
        }
        // R18 三档不再拼 -R-18 / R-18 关键字（hack 匹配字面标签会让全年龄/R 混在一起）；
        // 改由 [mapper] 的 Mapper.setSearchR18Restriction 按真实 x_restrict 客户端过滤（见 update()）。
        val assembledKeyword: String = (keyword + keywordSuffix).trim()

        // 路由 sort：popular_preview 是 popular-preview endpoint 专属，传给 /v1/search/novel 会 400；
        // popular_desc / trending_builtin 非 premium 用户也用 popular-preview（pixiv 旧约束）；
        // 其余 (date_desc / date_asc / popular_desc-premium / trending_builtin-premium) 走主 endpoint。
        val usePopularPreview = sortType == SortType.POPULAR_PREVIEW ||
                ((sortType == PixivSearchParamUtil.POPULAR_SORT_VALUE ||
                  sortType == PixivSearchParamUtil.TRENDING_BUILTIN_SORT_VALUE) && isPremium != true)

        // 投稿期间相对档当场算 today−N(每次 initApi 都重算,跨午夜窗口自动跟随今天);
        // bucket 为空时回落到自定义起止日期
        val (effectiveStartDate, effectiveEndDate) = resolveDateRange()

        // 默认档「标签部分一致」不传 search_target，让标题命中也能搜到（#906）——
        // 见 [SearchTarget.toQueryValue] 注释。
        val effectiveSearchTarget = SearchTarget.toQueryValue(searchType)

        return if (usePopularPreview) {
            Retro.getAppApi().popularNovelPreview(
                assembledKeyword,
                effectiveStartDate,
                effectiveEndDate,
                effectiveSearchTarget,
                bookmarkMin,
                genre,
                lang,
                searchAiType,
                isOriginalOnly,
                isReplaceableOnly,
                textLengthMin,
                textLengthMax,
                wordCountMin,
                wordCountMax,
                readingTimeMin,
                readingTimeMax,
            )
        } else {
            Retro.getAppApi().searchNovel(
                assembledKeyword,
                sortType,
                effectiveStartDate,
                effectiveEndDate,
                effectiveSearchTarget,
                bookmarkMin,
                genre,
                lang,
                searchAiType,
                isOriginalOnly,
                isReplaceableOnly,
                textLengthMin,
                textLengthMax,
                wordCountMin,
                wordCountMax,
                readingTimeMin,
                readingTimeMax,
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

    override fun initNextApi(): Observable<ListNovel> {
        return Retro.getAppApi().getNextNovel(nextUrl)
    }

    fun update(searchModel: SearchModel) {
        keyword = searchModel.keyword.value
        sortType = searchModel.sortType.value
        searchType = searchModel.searchType.value
        starSize = searchModel.starSize.value
        isPremium = searchModel.isPremium.value
        startDate = searchModel.startDate.value
        endDate = searchModel.endDate.value
        r18Restriction = searchModel.r18Restriction.value
        bookmarkMin = searchModel.bookmarkMin.value
        genre = searchModel.genre.value
        lang = searchModel.lang.value
        // AI：屏蔽走全局 isDeleteAIIllust → search_ai_type=1；「仅看 AI」会话态（issue #909）→
        // 服务端全返(0)，再由 Mapper 客户端按 novel_ai_type==2 筛。
        val onlyAi = searchModel.onlyAi.value == true
        searchOnlyAi = onlyAi
        searchAiType = if (onlyAi) 0 else if (Shaft.sSettings.isDeleteAIIllust) 1 else 0
        // null 让 retrofit 跳过 query；只有显式 true 才传，行为对齐 iOS（关闭时不带）
        isOriginalOnly = if (searchModel.isOriginalOnly.value == true) true else null
        isReplaceableOnly = if (searchModel.isReplaceableOnly.value == true) true else null
        textLengthMin = searchModel.textLengthMin.value
        textLengthMax = searchModel.textLengthMax.value
        wordCountMin = searchModel.wordCountMin.value
        wordCountMax = searchModel.wordCountMax.value
        readingTimeMin = searchModel.readingTimeMin.value
        readingTimeMax = searchModel.readingTimeMax.value
        durationBucket = searchModel.durationBucket.value
        // R18 三档（0=不限/1=仅安全/2=仅R-18）→ 客户端按 x_restrict 过滤
        filterMapper?.setSearchR18Restriction(r18Restriction ?: 0)
        filterMapper?.setSearchOnlyAi(onlyAi)
    }
}
