package ceui.pixiv.ui.search

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import ceui.loxia.Event
import ceui.loxia.ObjectType
import ceui.loxia.Tag
import ceui.pixiv.ui.search.v3.BodyLengthUnit
import ceui.pixiv.ui.search.v3.R18Mode
import ceui.pixiv.ui.search.v3.SearchFilterV3
import ceui.pixiv.ui.search.v3.SearchOptionsResponse

class SearchViewModel(initialKeyword: String) : ViewModel() {


    val tagList = MutableLiveData<List<Tag>>()

    /** 兼容老的 radio_tab UI——存的是 0..3 索引；写时同步到 [illustFilter]/[novelFilter] 的 sort。
     *  初始 1 = 「从新到旧」对齐 [SearchFilterV3] 的默认 [SortType.DATE_DESC]，避免首帧高亮闪一下。 */
    val illustSelectedRadioTabIndex = MutableLiveData(1)
    val novelSelectedRadioTabIndex = MutableLiveData(1)

    /**
     * V3 Filter 的真单源——sort/bookmark/tool/lang/genre/duration/dates/ai/r18 全在这里。
     * 初始值读 [Shaft.sSettings] 里用户配的「搜索默认排序方式 / 搜索结果收藏量筛选 /
     * 不显示 AI 作品」三项，对齐老 [ceui.lisa.fragments.FragmentFilter] 行为。
     */
    val illustFilter = MutableLiveData(SearchFilterV3.fromGlobalDefaults())
    val novelFilter = MutableLiveData(SearchFilterV3.fromGlobalDefaults())

    /** /v1/search/options 的缓存——拉一次给 illust + novel 共用。 */
    val searchOptions = MutableLiveData<SearchOptionsResponse?>()

    val inputDraft = MutableLiveData("")

    init {
        if (initialKeyword.isNotEmpty()) {
            tagList.value = listOf(Tag(initialKeyword))
        }
    }

    private val _searchIllustMangaEvent = MutableLiveData<Event<Long>>()
    private val _searchUserEvent = MutableLiveData<Event<Long>>()
    private val _searchNovelEvent = MutableLiveData<Event<Long>>()

    val searchIllustMangaEvent: LiveData<Event<Long>> = _searchIllustMangaEvent

    fun triggerSearchIllustMangaEvent(index: Long) {
        _searchIllustMangaEvent.postValue(Event(index))
    }


    val searchUserEvent: LiveData<Event<Long>> = _searchUserEvent

    fun triggerSearchUserEvent(index: Long) {
        _searchUserEvent.postValue(Event(index))
    }


    val searchNovelEvent: LiveData<Event<Long>> = _searchNovelEvent

    fun triggerSearchNovelEvent(index: Long) {
        _searchNovelEvent.postValue(Event(index))
    }

    fun triggerAllRefreshEvent() {
        val now = System.currentTimeMillis()
        triggerSearchIllustMangaEvent(now)
        triggerSearchUserEvent(now)
        triggerSearchNovelEvent(now)
    }

    /** Radio tab 索引 → SortType。和 V3 Filter 的 sort 双向同步用。 */
    fun radioIndexToSort(index: Int): String = when (index) {
        0 -> SortType.POPULAR_PREVIEW
        1 -> SortType.DATE_DESC
        2 -> SortType.DATE_ASC
        3 -> SortType.POPULAR_DESC
        else -> SortType.POPULAR_PREVIEW
    }

    /** SortType → Radio tab 索引。trending_builtin 等 radio 没有的项落回热度预览。 */
    fun sortToRadioIndex(sort: String): Int = when (sort) {
        SortType.POPULAR_PREVIEW -> 0
        SortType.DATE_DESC -> 1
        SortType.DATE_ASC -> 2
        SortType.POPULAR_DESC -> 3
        else -> 0
    }

    /**
     * 用 V3 Filter + tagList 拼最终 SearchConfig。
     *
     * - keyword：在原始 tagList 后追加 R18 后缀（兼容旧版 -R-18 / R-18 关键字 hack，
     *   pixiv 服务端没有原生 r18 query 参数）
     * - usersYori 留空：V3 走 bookmark_num_min API 参数，不再追加「Xusers入り」关键字
     */
    fun buildSearchConfig(@Suppress("UNUSED_PARAMETER") usersYori: Int?, objectType: String): SearchConfig {
        val isNovel = objectType == ObjectType.NOVEL
        val filter = (if (isNovel) novelFilter.value else illustFilter.value) ?: SearchFilterV3()
        val rawKeyword = tagList.value?.joinToString(separator = " ") { it.name ?: "" }.orEmpty()
        val keyword = when (filter.r18Mode) {
            R18Mode.All -> rawKeyword
            R18Mode.SafeOnly, R18Mode.R18Only -> {
                if (rawKeyword.isEmpty()) filter.r18Mode.keywordSuffix
                else "$rawKeyword ${filter.r18Mode.keywordSuffix}"
            }
        }
        return SearchConfig(
            keyword = keyword,
            sort = filter.sort,
            search_target = filter.searchTarget.apiValue,
            bookmarkMin = filter.bookmarkBucket.bookmarkMin(),
            tool = if (isNovel) null else filter.tool,
            genre = if (isNovel) filter.genre else null,
            lang = filter.lang,
            duration = filter.duration?.apiValue,
            startDate = filter.startDate,
            endDate = filter.endDate,
            searchAiType = if (filter.excludeAi) 1 else 0,
            // novel-only switches —— illust 路径忽略；nullable 保留 retrofit 不传 query 的语义
            isOriginalOnly = if (isNovel && filter.isOriginalOnly) true else null,
            isReplaceableOnly = if (isNovel && filter.isReplaceableOnly) true else null,
            // 长宽比仅 illust/manga 维度；novel endpoint 不识别 ratio_pattern
            ratioPattern = if (isNovel) null else filter.ratioPattern?.apiValue,
            // 分辨率仅 illust/manga；4 个 query 参数从 bucket 派生
            widthMin = if (isNovel) null else filter.resolutionBucket?.widthMin,
            widthMax = if (isNovel) null else filter.resolutionBucket?.widthMax,
            heightMin = if (isNovel) null else filter.resolutionBucket?.heightMin,
            heightMax = if (isNovel) null else filter.resolutionBucket?.heightMax,
            // 正文长度 / 阅读用时仅 novel；text vs word 二选一（unit 决定哪组 query 生效）
            textLengthMin = if (isNovel && filter.bodyLength?.unit == BodyLengthUnit.Char)
                filter.bodyLength.min else null,
            textLengthMax = if (isNovel && filter.bodyLength?.unit == BodyLengthUnit.Char)
                filter.bodyLength.max else null,
            wordCountMin = if (isNovel && filter.bodyLength?.unit == BodyLengthUnit.Word)
                filter.bodyLength.min else null,
            wordCountMax = if (isNovel && filter.bodyLength?.unit == BodyLengthUnit.Word)
                filter.bodyLength.max else null,
            readingTimeMin = if (isNovel) filter.readingTime?.min else null,
            readingTimeMax = if (isNovel) filter.readingTime?.max else null,
        )
    }
}
