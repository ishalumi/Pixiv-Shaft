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
 *   8. durationBucket        — 投稿期间相对预设档（24小时内/一周内/一月内/半年内/一年内）；
 *                              与 startDate/endDate 互斥
 *   9. startDate /
 *      endDate               — 投稿期间「指定期间」自定义起止；与 durationBucket 互斥
 *                              说明：V3 不再发 within_last_* 的 `duration` 参数；durationBucket
 *                              在 [buildSearchConfig] 当场算 today−N → start_date/end_date 发出去，
 *                              对齐 iOS pixiv 8.6.6 抓包行为
 *  10. excludeAi             — 屏蔽 AI 作品（驱动 search_ai_type）
 *  11. r18Mode               — R18 限制（沿用旧版「-R-18」「R-18」关键字 hack）
 *  12. ratioPattern          — 长宽比（仅 illust/manga，走官方 `ratio_pattern` query 参数）
 *  13. resolutionBucket      — 分辨率档位（仅 illust/manga，走官方 `width_min/max` + `height_min/max`）
 *  14. bodyLength            — 正文长度（仅 novel；统一维度，按 unit 三选一：文字数 / 单词数 /
 *                              阅读预计用时）
 *  15. contentType           — 作品类别（仅 illust/manga，走官方 `content_type` query 参数）；
 *                              默认 [IllustContentType.IllustAndMangaAndUgoira] 等价于不传
 */
data class SearchFilterV3(
    val sort: String = SortType.DATE_DESC,
    val searchTarget: SearchTarget = SearchTarget.PartialMatchForTags,
    val bookmarkBucket: BookmarkBucket = BookmarkBucket.None,
    val keywordUsersBucket: KeywordUsersBucket = KeywordUsersBucket.None,
    val tool: String? = null,
    val genre: Int? = null,
    val lang: String? = null,
    val durationBucket: DurationBucket? = null,
    val startDate: String? = null,    // YYYY-MM-DD —— 与 durationBucket 互斥
    val endDate: String? = null,      // YYYY-MM-DD
    val excludeAi: Boolean = false,
    val r18Mode: R18Mode = R18Mode.All,
    val ratioPattern: RatioPattern? = null,   // illust/manga only
    val resolutionBucket: ResolutionBucket? = null,   // illust/manga only
    // 作品类别（仅 illust/manga）—— pixiv iOS 8.6.6 抓包确认走 `content_type` query 参数。
    // 默认 [IllustContentType.IllustAndMangaAndUgoira] 等价于不传该参数（[buildSearchConfig]
    // 在默认档时把 contentType 字段送 null，与既有行为对齐）。
    val contentType: IllustContentType = IllustContentType.IllustAndMangaAndUgoira,
    val bodyLength: BodyLengthSpec? = null,           // novel only
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
        if (durationBucket != null) n++
        if (startDate != null || endDate != null) n++
        if (excludeAi) n++
        if (r18Mode != R18Mode.All) n++
        if (isNovel && isOriginalOnly) n++
        if (isNovel && isReplaceableOnly) n++
        if (!isNovel && ratioPattern != null) n++
        if (!isNovel && resolutionBucket != null) n++
        if (!isNovel && contentType != IllustContentType.IllustAndMangaAndUgoira) n++
        if (isNovel && bodyLength != null) n++
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

/**
 * 投稿期间相对预设档。iOS pixiv 8.6.6 抓包确认：不再发 `duration=within_last_*`，而是把
 * 「24 小时内 / 一周内 / 一月内 / 半年内 / 一年内」当场算成 today−N → start_date/end_date 发出。
 * 「不限」=不传 start_date/end_date；「指定期间」走 [SearchFilterV3.startDate]/[endDate] 自定义。
 *
 * 三组互斥：bucket 非空 → 用 bucket；start/end 任一非空 → 用 custom；都空 → 不限。
 * UI 层 [DurationPickerSheet] 保证这条互斥律。
 */
enum class DurationBucket {
    Last24Hours, LastWeek, LastMonth, LastHalfYear, LastYear;

    /**
     * 按今日往前推 N 天/月/年，返回 (start_date, end_date) 字符串，皆 YYYY-MM-DD。
     * 对齐 iOS：end_date = today；start_date = today − offset。
     */
    fun toDateRange(today: java.time.LocalDate): Pair<String, String> {
        val start = when (this) {
            Last24Hours  -> today.minusDays(1)
            LastWeek     -> today.minusWeeks(1)
            LastMonth    -> today.minusMonths(1)
            LastHalfYear -> today.minusMonths(6)
            LastYear     -> today.minusYears(1)
        }
        return start.toString() to today.toString()
    }
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

/**
 * 作品类别（仅 illust/manga）—— pixiv iOS 8.6.6 抓包确认走官方 `content_type` query 参数。
 * 与 iOS「作品类别」picker 5 档对齐（小说档不在此范围——novel 走独立 endpoint）：
 *   - IllustAndMangaAndUgoira：插画、漫画、动图（动态插画）—— 默认；buildSearchConfig 当 null 处理不传
 *   - IllustAndUgoira：插画、动图
 *   - Illust：插画
 *   - Ugoira：动图
 *   - Manga：漫画
 */
enum class IllustContentType(val apiValue: String) {
    IllustAndMangaAndUgoira("illust_and_manga_and_ugoira"),
    IllustAndUgoira("illust_and_ugoira"),
    Illust("illust"),
    Ugoira("ugoira"),
    Manga("manga"),
}

/**
 * 分辨率档位（仅 illust/manga）—— 走官方 `width_min` / `width_max` / `height_min` / `height_max`。
 * 「全部清晰度」= 4 个参数都不传（[SearchFilterV3.resolutionBucket] = null）。
 * 与 pixiv iOS 8.6.6「分辨率」picker 三档对齐：≥3000、1000~2999、≤999（边界双闭）。
 */
enum class ResolutionBucket(
    val widthMin: Int?,
    val widthMax: Int?,
    val heightMin: Int?,
    val heightMax: Int?,
) {
    Above3000(3000, null, 3000, null),
    Between1000And2999(1000, 2999, 1000, 2999),
    Below1000(null, 999, null, 999),
}

/**
 * 正文长度（仅 novel）—— 三种单位（文字数 / 单词数 / 阅读预计用时）的统一维度，每种 4 个
 * 预设档 + 「指定」自定义范围。三种单位互斥，picker 单选；min/max 是 nullable 区间端。
 *
 * 状态只存最终 (unit, min, max) 三元组——picker 渲染时 (min, max) 对照 [CharLengthBucket] /
 * [WordLengthBucket] / [ReadingTimeBucket] 反查是否命中预设档，未命中则视为自定义。
 *
 * API 落地（iOS pixiv 8.6.6 抓包确认）：
 *   - unit = Char         →  `text_length_min`  / `text_length_max`
 *   - unit = Word         →  `word_count_min`   / `word_count_max`
 *   - unit = ReadingTime  →  `reading_time_min` / `reading_time_max`   （单位「分钟」）
 */
data class BodyLengthSpec(
    val unit: BodyLengthUnit,
    val min: Int?,
    val max: Int?,
)

enum class BodyLengthUnit { Char, Word, ReadingTime }

/** 文字数预设档（iOS pixiv 8.6.6「文字数」picker 一致）。 */
enum class CharLengthBucket(val min: Int?, val max: Int?) {
    Micro(null, 4999),                 // 微型小说（4,999 字以下）
    Short(5000, 19999),                // 短篇小说（5,000–19,999 字）
    Medium(20000, 79999),              // 中篇小说（20,000–79,999 字）
    Long(80000, null),                 // 长篇小说（80,000 字以上）
}

/** 单词数预设档（iOS pixiv 8.6.6「单词数」picker 一致；仅对适用语言生效）。 */
enum class WordLengthBucket(val min: Int?, val max: Int?) {
    Below5000(null, 4999),
    From5000(5000, 19999),
    From20000(20000, 79999),
    Above80000(80000, null),
}

/** 阅读预计用时预设档（iOS pixiv 8.6.6「阅读预计用时」picker 一致；单位「分钟」）。 */
enum class ReadingTimeBucket(val min: Int?, val max: Int?) {
    Under10(null, 9),
    From10To59(10, 59),
    From60To179(60, 179),
    Above180(180, null),
}
