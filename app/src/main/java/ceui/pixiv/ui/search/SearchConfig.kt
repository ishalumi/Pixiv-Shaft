package ceui.pixiv.ui.search

data class SearchConfig(
    val keyword: String,
    val sort: String = "date_desc",
    val usersYori: String = "",
    val search_target: String = "partial_match_for_tags",
    val merge_plain_keyword_results: Boolean = true,
    val include_translated_tag_results: Boolean = true,

    // V3 Filter — 全部走 pixiv 官方原生 query 参数，不再依赖 keyword hack。
    val bookmarkMin: Int? = null,
    val tool: String? = null,        // illust only
    val genre: Int? = null,          // novel only
    val lang: String? = null,
    val duration: String? = null,    // within_last_day | week | month | half_year | year
    val startDate: String? = null,   // YYYY-MM-DD
    val endDate: String? = null,
    val searchAiType: Int = 0,       // 0 = include AI（默认）；1 = exclude AI
    val isOriginalOnly: Boolean? = null,    // novel only
    val isReplaceableOnly: Boolean? = null, // novel only
    val ratioPattern: String? = null,       // illust/manga only: landscape | portrait | square
    // 分辨率档位（仅 illust/manga）—— 4 个独立 query 参数，null 跳过
    val widthMin: Int? = null,
    val widthMax: Int? = null,
    val heightMin: Int? = null,
    val heightMax: Int? = null,
)
