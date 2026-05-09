package ceui.pixiv.ui.search

import ceui.loxia.Client
import ceui.loxia.Novel
import ceui.loxia.NovelResponse
import ceui.pixiv.ui.common.DataSource
import ceui.pixiv.ui.common.NovelCardHolder

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
                search_target = config.search_target,
                merge_plain_keyword_results = config.merge_plain_keyword_results,
                include_translated_tag_results = config.include_translated_tag_results,
                search_ai_type = config.searchAiType,
                bookmark_num_min = config.bookmarkMin,
                genre = config.genre,
                lang = config.lang,
                duration = config.duration,
                start_date = config.startDate,
                end_date = config.endDate,
                is_original_only = config.isOriginalOnly,
                is_replaceable_only = config.isReplaceableOnly,
            )
        } else {
            Client.appApi.searchNovel(
                word = config.keyword,
                sort = config.sort,
                search_target = config.search_target,
                merge_plain_keyword_results = config.merge_plain_keyword_results,
                include_translated_tag_results = config.include_translated_tag_results,
                search_ai_type = config.searchAiType,
                bookmark_num_min = config.bookmarkMin,
                genre = config.genre,
                lang = config.lang,
                duration = config.duration,
                start_date = config.startDate,
                end_date = config.endDate,
                is_original_only = config.isOriginalOnly,
                is_replaceable_only = config.isReplaceableOnly,
            )
        }
    },
    itemMapper = { novel -> listOf(NovelCardHolder(novel)) },
    filter = { novel -> novel.visible != false }
) {
    override fun initialLoad(): Boolean {
        return provider().keyword.isNotEmpty()
    }
}
