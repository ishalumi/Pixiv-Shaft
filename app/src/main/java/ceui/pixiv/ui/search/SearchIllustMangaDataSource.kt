package ceui.pixiv.ui.search

import ceui.loxia.Client
import ceui.loxia.Illust
import ceui.loxia.IllustResponse
import ceui.pixiv.session.SessionManager
import ceui.pixiv.ui.common.DataSource
import ceui.pixiv.ui.common.IllustCardHolder

class SearchIllustMangaDataSource(
    private val provider: () -> SearchConfig
) : DataSource<Illust, IllustResponse>(
    dataFetcher = {
        val config = provider()
        if (shouldUsePopularPreview(config.sort)) {
            // popular_preview / trending_builtin / (popular_desc + 非 premium) 都得走 popular-preview
            // endpoint —— /v1/search/illust 不接受这些 sort 值（trending_builtin 是 Shaft 自定义；
            // popular_desc 仅 premium 用户）。这条路径不带 sort 参数。
            Client.appApi.popularPreview(
                word = config.keyword,
                sort = config.sort,
                search_target = config.search_target,
                merge_plain_keyword_results = config.merge_plain_keyword_results,
                include_translated_tag_results = config.include_translated_tag_results,
                search_ai_type = config.searchAiType,
                bookmark_num_min = config.bookmarkMin,
                tool = config.tool,
                lang = config.lang,
                start_date = config.startDate,
                end_date = config.endDate,
                ratio_pattern = config.ratioPattern,
                content_type = config.contentType,
                width_min = config.widthMin,
                width_max = config.widthMax,
                height_min = config.heightMin,
                height_max = config.heightMax,
            )
        } else {
            Client.appApi.searchIllustManga(
                word = config.keyword,
                sort = config.sort,
                search_target = config.search_target,
                merge_plain_keyword_results = config.merge_plain_keyword_results,
                include_translated_tag_results = config.include_translated_tag_results,
                search_ai_type = config.searchAiType,
                bookmark_num_min = config.bookmarkMin,
                tool = config.tool,
                lang = config.lang,
                start_date = config.startDate,
                end_date = config.endDate,
                ratio_pattern = config.ratioPattern,
                content_type = config.contentType,
                width_min = config.widthMin,
                width_max = config.widthMax,
                height_min = config.heightMin,
                height_max = config.heightMax,
            )
        }
    },
    itemMapper = { illust -> listOf(IllustCardHolder(illust)) },
    // R18 三档客户端过滤：只看真实 x_restrict（与 IllustsBean.isR18File 同口径，不碰
    // sanity_level，避免误伤普通插画）。「全部」时 accepts 恒 true，零开销。
    filter = { illust -> provider().r18Mode.accepts(illust.x_restrict) }
) {
    override fun initialLoad(): Boolean {
        return provider().keyword.isNotEmpty()
    }
}

/**
 * V3 sort 路由——参考 [ceui.lisa.repo.SearchIllustRepo] 与 [ceui.lisa.repo.SearchNovelRepo]：
 *   - `popular_preview`：popular-preview endpoint 专属 sort 值，传给 /v1/search/illust 会 400
 *   - `trending_builtin`：Shaft 自定义，pixiv 不识别（legacy 走 PrimeIllustLoader cache + fallback
 *     到 popular-preview；V3 新路径直接走 popular-preview，不带 sort 参数）
 *   - `popular_desc` / `popular_male_desc` / `popular_female_desc` 非 premium：pixiv 旧约束——
 *     非付费用户不能用人气系列 sort，需走 popular-preview。男女向两档（issue #575）平时被
 *     [SearchFilterV3BottomSheet.sortList] gate 住非会员根本看不到，这里再兜一层防御。
 *
 * `popularPreview` 接收 `sort` 参数但忽略——把 V3 的字符串值原样塞进去也不会 400。
 */
internal fun shouldUsePopularPreview(sort: String): Boolean {
    if (sort == SortType.POPULAR_PREVIEW) return true
    if (sort == SortType.TRENDING_BUILTIN) return true
    if (!SessionManager.isPremium && (
            sort == SortType.POPULAR_DESC ||
            sort == SortType.POPULAR_MALE_DESC ||
            sort == SortType.POPULAR_FEMALE_DESC
        )) return true
    return false
}
