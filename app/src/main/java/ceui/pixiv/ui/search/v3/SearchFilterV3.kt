package ceui.pixiv.ui.search.v3

import ceui.pixiv.ui.search.SortType

/**
 * V3 搜索筛选器的不可变状态。SearchViewModel 持有 illust + novel 两份独立 LiveData。
 *
 * 涵盖维度：
 *   1. sort           — 排序（含官方新加的 trending_builtin）
 *   2. searchTarget   — 匹配方式（illust 3 项 / novel 4 项）
 *   3. bookmarkBucket — 收藏量起步值（沿用旧版预设 + None）
 *   4. tool           — 绘画工具（仅 illust，从 /v1/search/options 拉）
 *   5. genre          — 小说类型（仅 novel）
 *   6. lang           — 语种
 *   7. duration       — 投稿时间（相对）
 *   8. startDate /
 *      endDate        — 投稿时间（绝对，与 duration 互斥）
 *   9. excludeAi      — 屏蔽 AI 作品（驱动 search_ai_type）
 *  10. r18Mode        — R18 限制（沿用旧版「-R-18」「R-18」关键字 hack）
 */
data class SearchFilterV3(
    val sort: String = SortType.POPULAR_PREVIEW,
    val searchTarget: SearchTarget = SearchTarget.PartialMatchForTags,
    val bookmarkBucket: BookmarkBucket = BookmarkBucket.None,
    val tool: String? = null,
    val genre: Int? = null,
    val lang: String? = null,
    val duration: SearchDuration? = null,
    val startDate: String? = null,    // YYYY-MM-DD
    val endDate: String? = null,      // YYYY-MM-DD
    val excludeAi: Boolean = false,
    val r18Mode: R18Mode = R18Mode.All,
    // novel 专属（pixiv iOS 8.6.5 「仅限原创作品」/「仅限支持单词置换的作品」开关）
    val isOriginalOnly: Boolean = false,
    val isReplaceableOnly: Boolean = false,
) {

    /** 已经设置了几个非默认维度——给入口按钮显示徽标用。 */
    fun activeCount(isNovel: Boolean): Int {
        var n = 0
        if (sort != SortType.POPULAR_PREVIEW) n++
        if (searchTarget != SearchTarget.PartialMatchForTags) n++
        if (bookmarkBucket != BookmarkBucket.None) n++
        if (!isNovel && tool != null) n++
        if (isNovel && genre != null) n++
        if (lang != null) n++
        if (duration != null) n++
        if (startDate != null || endDate != null) n++
        if (excludeAi) n++
        if (r18Mode != R18Mode.All) n++
        if (isNovel && isOriginalOnly) n++
        if (isNovel && isReplaceableOnly) n++
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
 * 收藏量预设——对齐旧 FragmentFilter 的 ALL_SIZE_VALUE 桶（同时把 100users 这个 V3 才有的小桶加进来），
 * 但底层走 bookmark_num_min API 参数（不再追加 `Xusers入り` 关键字 hack）。
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
