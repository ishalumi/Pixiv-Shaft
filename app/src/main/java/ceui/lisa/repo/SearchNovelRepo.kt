package ceui.lisa.repo

import android.text.TextUtils
import ceui.lisa.activities.Shaft
import ceui.lisa.core.RemoteRepo
import ceui.lisa.http.Retro
import ceui.lisa.model.ListNovel
import ceui.lisa.utils.PixivSearchParamUtil
import ceui.lisa.viewmodel.SearchModel
import ceui.pixiv.ui.search.SortType
import io.reactivex.Observable

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
    private var duration: String? = null,
    private var searchAiType: Int? = null,
    private var isOriginalOnly: Boolean? = null,
    private var isReplaceableOnly: Boolean? = null,
) : RemoteRepo<ListNovel>() {

    override fun initApi(): Observable<ListNovel> {
        val useBookmarkQuery = (bookmarkMin ?: 0) > 0
        val keywordSuffix = if (useBookmarkQuery) "" else when {
            TextUtils.isEmpty(starSize) -> ""
            else -> " $starSize"
        }
        val assembledKeyword: String = (keyword + keywordSuffix + when (r18Restriction) {
            null -> ""
            else -> " ${PixivSearchParamUtil.R18_RESTRICTION_VALUE[r18Restriction!!]}"
        }).trim()

        // 路由 sort：popular_preview 是 popular-preview endpoint 专属，传给 /v1/search/novel 会 400；
        // popular_desc / trending_builtin 非 premium 用户也用 popular-preview（pixiv 旧约束）；
        // 其余 (date_desc / date_asc / popular_desc-premium / trending_builtin-premium) 走主 endpoint。
        val usePopularPreview = sortType == SortType.POPULAR_PREVIEW ||
                ((sortType == PixivSearchParamUtil.POPULAR_SORT_VALUE ||
                  sortType == PixivSearchParamUtil.TRENDING_BUILTIN_SORT_VALUE) && isPremium != true)

        return if (usePopularPreview) {
            Retro.getAppApi().popularNovelPreview(
                assembledKeyword,
                startDate,
                endDate,
                searchType,
                bookmarkMin,
                genre,
                lang,
                duration,
                searchAiType,
                isOriginalOnly,
                isReplaceableOnly,
            )
        } else {
            Retro.getAppApi().searchNovel(
                assembledKeyword,
                sortType,
                startDate,
                endDate,
                searchType,
                bookmarkMin,
                genre,
                lang,
                duration,
                searchAiType,
                isOriginalOnly,
                isReplaceableOnly,
            )
        }
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
        duration = searchModel.duration.value
        searchAiType = if (Shaft.sSettings.isDeleteAIIllust) 1 else 0
        // null 让 retrofit 跳过 query；只有显式 true 才传，行为对齐 iOS（关闭时不带）
        isOriginalOnly = if (searchModel.isOriginalOnly.value == true) true else null
        isReplaceableOnly = if (searchModel.isReplaceableOnly.value == true) true else null
    }
}
