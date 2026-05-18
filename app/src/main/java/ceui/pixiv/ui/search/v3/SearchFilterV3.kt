package ceui.pixiv.ui.search.v3

import ceui.lisa.activities.Shaft
import ceui.pixiv.ui.search.SortType

/**
 * V3 搜索筛选器的不可变状态。SearchViewModel 持有 illust + novel 两份独立 LiveData。
 *
 * 涵盖维度：
 *   1. sort                  — 排序（含官方新加的 trending_builtin）
 *   2. searchTarget          — 匹配方式（illust 3 项 / novel 4 项）
 *   3. bookmarkBucket        — 收藏量起步值，走官方 `bookmark_num_min` query 参数
 *                              （popular 排序需要 premium 才生效）
 *   4. keywordUsersBucket    — 旧版「Xusers入り」标签 hack：把档位作为关键字后缀拼到 query 里。
 *                              对非会员有效；与 bookmarkBucket 共存（独立维度，互不屏蔽）。
 *   5. tool                  — 绘画工具（仅 illust，从 /v1/search/options 拉）
 *   6. genre                 — 小说类型（仅 novel）
 *   7. lang                  — 语种
 *   8. duration              — 投稿时间（相对）
 *   9. startDate /
 *      endDate               — 投稿时间（绝对，与 duration 互斥）
 *  10. excludeAi             — 屏蔽 AI 作品（驱动 search_ai_type）
 *  11. r18Mode               — R18 限制（沿用旧版「-R-18」「R-18」关键字 hack）
 *  12. ratioPattern          — 长宽比（仅 illust/manga，走官方 `ratio_pattern` query 参数）
 */
data class SearchFilterV3(
    val sort: String = SortType.DATE_DESC,
    val searchTarget: SearchTarget = SearchTarget.PartialMatchForTags,
    val bookmarkBucket: BookmarkBucket = BookmarkBucket.None,
    val keywordUsersBucket: KeywordUsersBucket = KeywordUsersBucket.None,
    val tool: String? = null,
    val genre: Int? = null,
    val lang: String? = null,
    val duration: SearchDuration? = null,
    val startDate: String? = null,    // YYYY-MM-DD
    val endDate: String? = null,      // YYYY-MM-DD
    val excludeAi: Boolean = false,
    val r18Mode: R18Mode = R18Mode.All,
    val ratioPattern: RatioPattern? = null,   // illust/manga only
    // novel 专属（pixiv iOS 8.6.5 「仅限原创作品」/「仅限支持单词置换的作品」开关）
    val isOriginalOnly: Boolean = false,
    val isReplaceableOnly: Boolean = false,
) {

    companion object {
        /**
         * 读 [Shaft.sSettings] 里的三项全局默认偏好——保证 V3 sheet 第一次打开就反映用户在
         * 设置页配的偏好，与老 [ceui.lisa.fragments.FragmentFilter] 行为一致：
         *   - sort：`getSearchDefaultSortType()`（默认 date_desc）
         *   - keywordUsersBucket：`getSearchFilter()`（"" / "1000users入り" 之类）—— 这条设置
         *     从老 FragmentFilter 起就是关键字后缀语义，所以落到 keyword 维度，不是 bookmark
         *     query 维度。bookmarkBucket 维度走 query 参数，没有全局默认。
         *   - excludeAi：`isDeleteAIIllust`
         *
         * 用户的「activeCount」基线也跟着跑——例如全局已开 AI 屏蔽，sheet 打开「其他条件」
         * 行就会显示「屏蔽 AI」徽标，不再误以为没改过。
         */
        fun fromGlobalDefaults(): SearchFilterV3 {
            val s = Shaft.sSettings
            val sort = s.searchDefaultSortType.takeIf { it.isNotEmpty() } ?: SortType.DATE_DESC
            val bucketMin = Regex("""\d+""").find(s.searchFilter.orEmpty())
                ?.value?.toIntOrNull() ?: 0
            val keywordBucket = KeywordUsersBucket.values().firstOrNull { it.min == bucketMin }
                ?: KeywordUsersBucket.None
            return SearchFilterV3(
                sort = sort,
                keywordUsersBucket = keywordBucket,
                excludeAi = s.isDeleteAIIllust,
            )
        }
    }

    /** 已经设置了几个非默认维度——给入口按钮显示徽标用。 */
    fun activeCount(isNovel: Boolean): Int {
        var n = 0
        if (sort != SortType.DATE_DESC) n++
        if (searchTarget != SearchTarget.PartialMatchForTags) n++
        if (bookmarkBucket != BookmarkBucket.None) n++
        if (keywordUsersBucket != KeywordUsersBucket.None) n++
        if (!isNovel && tool != null) n++
        if (isNovel && genre != null) n++
        if (lang != null) n++
        if (duration != null) n++
        if (startDate != null || endDate != null) n++
        if (excludeAi) n++
        if (r18Mode != R18Mode.All) n++
        if (isNovel && isOriginalOnly) n++
        if (isNovel && isReplaceableOnly) n++
        if (!isNovel && ratioPattern != null) n++
        return n
    }
}

enum class SearchTarget(val apiValue: String) {
    PartialMatchForTags("partial_match_for_tags"),
    ExactMatchForTags("exact_match_for_tags"),
    TitleAndCaption("title_and_caption"),   // illust 走「标题/简介」
    NovelText("text"),                      // novel 走「正文」
    NovelKeyword("keyword");                // novel 走「关键词」

    companion object {
        fun forIllust(): List<SearchTarget> =
            listOf(PartialMatchForTags, ExactMatchForTags, TitleAndCaption)

        fun forNovel(): List<SearchTarget> =
            listOf(PartialMatchForTags, ExactMatchForTags, NovelText, NovelKeyword)
    }
}

/**
 * 收藏量预设——走官方 `bookmark_num_min` API 参数。会员 + popular 排序时服务端按此过滤；
 * 非会员当前 endpoint（popular-preview）忽略该参数，所以非会员请用 [KeywordUsersBucket]。
 */
enum class BookmarkBucket(val min: Int) {
    None(0),
    B100(100),
    B500(500),
    B1000(1000),
    B2000(2000),
    B5000(5000),
    B7500(7500),
    B10000(10000),
    B20000(20000),
    B30000(30000),
    B50000(50000),
    B100000(100000);

    fun bookmarkMin(): Int? = if (min > 0) min else null
}

/**
 * 旧版「Xusers入り」标签 hack：把档位作为关键字后缀（如 `1000users入り`）拼到 query 里，
 * 命中 pixiv 自动打的桶标签——非会员也能用，对齐旧 [ceui.lisa.fragments.FragmentFilter]
 * 的 `ALL_SIZE_VALUE`。与 [BookmarkBucket] 独立，可同时设置。
 */
enum class KeywordUsersBucket(val min: Int) {
    None(0),
    B500(500),
    B1000(1000),
    B2000(2000),
    B5000(5000),
    B7500(7500),
    B10000(10000),
    B20000(20000),
    B50000(50000),
    B100000(100000);

    /** 关键字后缀；None 时返回空串。 */
    fun keywordSuffix(): String = if (min > 0) "${min}users入り" else ""
}

enum class SearchDuration(val apiValue: String) {
    Day("within_last_day"),
    Week("within_last_week"),
    Month("within_last_month"),
    HalfYear("within_last_half_year"),
    Year("within_last_year"),
}

enum class R18Mode(val keywordSuffix: String) {
    All(""),
    SafeOnly("-R-18"),
    R18Only("R-18"),
}

/**
 * 长宽比（仅 illust/manga）—— 走官方 `ratio_pattern` query 参数。
 * 「所有纵横比」= 不传该参数（[SearchFilterV3.ratioPattern] = null）。
 */
enum class RatioPattern(val apiValue: String) {
    Landscape("landscape"),
    Portrait("portrait"),
    Square("square"),
}
