package ceui.pixiv.ui.search

import ceui.loxia.Client
import ceui.loxia.Novel
import ceui.loxia.NovelResponse
import ceui.pixiv.ui.common.DataSource
import ceui.pixiv.ui.common.NovelCardHolder
import ceui.pixiv.ui.search.v3.SearchTarget

class SearchNovelDataSource(
    private val provider: () -> SearchConfig
) : DataSource<Novel, NovelResponse>(
    dataFetcher = {
        val config = provider()
        if (shouldUsePopularPreview(config.sort)) {
            // 与 illust 同样的 sort 路由——参见 [shouldUsePopularPreview] 注释。
            Client.appApi.popularPreviewNovel(
                word = config.keyword,
                sort = config.sort,
                // 默认档不传 search_target，让标题命中也能搜到（#906）
                search_target = SearchTarget.toQueryValue(config.search_target),
                merge_plain_keyword_results = config.merge_plain_keyword_results,
                include_translated_tag_results = config.include_translated_tag_results,
                search_ai_type = config.searchAiType,
                bookmark_num_min = config.bookmarkMin,
                genre = config.genre,
                lang = config.lang,
                start_date = config.startDate,
                end_date = config.endDate,
                is_original_only = config.isOriginalOnly,
                is_replaceable_only = config.isReplaceableOnly,
                text_length_min = config.textLengthMin,
                text_length_max = config.textLengthMax,
                word_count_min = config.wordCountMin,
                word_count_max = config.wordCountMax,
                reading_time_min = config.readingTimeMin,
                reading_time_max = config.readingTimeMax,
            )
        } else {
            Client.appApi.searchNovel(
                word = config.keyword,
                sort = config.sort,
                search_target = SearchTarget.toQueryValue(config.search_target),
                merge_plain_keyword_results = config.merge_plain_keyword_results,
                include_translated_tag_results = config.include_translated_tag_results,
                search_ai_type = config.searchAiType,
                bookmark_num_min = config.bookmarkMin,
                genre = config.genre,
                lang = config.lang,
                start_date = config.startDate,
                end_date = config.endDate,
                is_original_only = config.isOriginalOnly,
                is_replaceable_only = config.isReplaceableOnly,
                text_length_min = config.textLengthMin,
                text_length_max = config.textLengthMax,
                word_count_min = config.wordCountMin,
                word_count_max = config.wordCountMax,
                reading_time_min = config.readingTimeMin,
                reading_time_max = config.readingTimeMax,
            )
        }
    },
    itemMapper = { novel -> listOf(NovelCardHolder(novel)) },
    // visible 过滤之外叠加 R18 三档客户端过滤（按真实 x_restrict，「全部」时恒 true）
    filter = { novel -> novel.visible != false && provider().r18Mode.accepts(novel.x_restrict) }
) {
    override fun initialLoad(): Boolean {
        return provider().keyword.isNotEmpty()
    }
}
