package ceui.pixiv.ui.search.v3

import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import ceui.lisa.viewmodel.SearchModel
import ceui.loxia.observeEvent
import ceui.pixiv.ui.search.SearchViewModel
import ceui.pixiv.ui.search.SortType
import java.util.WeakHashMap

/**
 * 把 V3 搜索筛选器接进老版本 [ceui.lisa.activities.SearchActivity] 的桥。
 *
 * 老搜索的真单源是 Java [SearchModel]：[ceui.lisa.fragments.FragmentSearchIllust] /
 * [ceui.lisa.fragments.FragmentSearchNovel] 都是观察 `searchModel.nowGo` 触发刷新，再读
 * `searchModel.{sortType, searchType, starSize, startDate, endDate, r18Restriction}` 当做参数。
 *
 * V3 sheet 的真单源是 Kotlin [SearchViewModel] 的 `illustFilter` / `novelFilter` LiveData。
 * 这个 bridge 做两件事：
 *   1. 启动时把 SearchModel 的现状种到 SearchViewModel —— 这样 sheet 第一次打开看到的是
 *      用户上次留下的筛选状态，而不是默认值。
 *   2. 持续观察 SearchViewModel 的 filter 变化，原子地翻成 SearchModel 字段；当 sheet 触发
 *      搜索事件时，给 SearchModel.nowGo 一脚，老 fragment 就刷新。
 *
 * 全部 V3 维度（tool / lang / genre / duration / bookmark / 仅原创 / 仅单词置换）legacy AppApi
 * 已经扩展支持，bridge 全字段双向翻译；老版与新版功能完全对齐。
 */
object SearchFilterV3LegacyBridge {

    /**
     * 已安装过 bridge 的 activity 集合——activity 死亡时自动清理（DefaultLifecycleObserver
     * 监听 onDestroy）。WeakHashMap 兜底——避免活引用 activity，杜绝 leak。
     */
    private val installed: MutableSet<AppCompatActivity> =
        java.util.Collections.newSetFromMap(WeakHashMap())

    /** Activity-scoped 工厂——SearchViewModel 没无参 ctor，得显式提供。 */
    fun resolveSearchViewModel(activity: AppCompatActivity): SearchViewModel {
        val factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return SearchViewModel("") as T
            }
        }
        return ViewModelProvider(activity, factory)[SearchViewModel::class.java]
    }

    /**
     * 在 [activity] 上安装 bridge。activity 应在 onCreate / initData 末尾调一次。
     * 重复调用静默 no-op；activity onDestroy 时自动从 [installed] 摘除。
     */
    fun install(activity: AppCompatActivity, searchModel: SearchModel) {
        if (!installed.add(activity)) return
        activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                installed.remove(activity)
            }
        })
        val vm = resolveSearchViewModel(activity)

        // ── 1. seed：SearchModel → SearchViewModel.filter ──
        // 仅在 SearchViewModel 还是默认状态时种一次，避免覆盖用户在 sheet 内的临时改动。
        if (vm.illustFilter.value == SearchFilterV3()) {
            vm.illustFilter.value = seedFromLegacy(searchModel, isNovel = false)
        }
        if (vm.novelFilter.value == SearchFilterV3()) {
            vm.novelFilter.value = seedFromLegacy(searchModel, isNovel = true)
        }

        // ── 2. observe：SearchViewModel.filter 变化 → 翻译到 SearchModel ──
        vm.illustFilter.observe(activity) { filter ->
            if (filter != null) applyToLegacy(filter, searchModel)
        }
        vm.novelFilter.observe(activity) { filter ->
            if (filter != null) applyToLegacy(filter, searchModel)
        }

        // ── 3. 搜索事件 → searchModel.nowGo 触发老 fragment 刷新 ──
        vm.searchIllustMangaEvent.observeEvent(activity) {
            // 切到 illust 当前 filter 再写一遍，确保即时性
            vm.illustFilter.value?.let { applyToLegacy(it, searchModel) }
            searchModel.nowGo.value = "search_now"
        }
        vm.searchNovelEvent.observeEvent(activity) {
            vm.novelFilter.value?.let { applyToLegacy(it, searchModel) }
            searchModel.nowGo.value = "search_now"
        }
    }

    /**
     * 从 [SearchModel] 当前值构造一个 [SearchFilterV3]。fields 一对一翻：
     *  - `starSize` ("XXXusers入り") → bookmarkBucket（按 min 反查；非标准值落到 None）
     *  - `searchType`            → searchTarget（不识别就回 partial）
     *  - `sortType`              → sort（不识别就回 popular_preview）
     *  - `startDate / endDate`   → 同名字段
     *  - `r18Restriction`        → R18Mode（0/1/2 ↔ All/SafeOnly/R18Only）
     *  - AI 屏蔽：legacy 没存在 SearchModel，由 [ceui.lisa.activities.Shaft.sSettings.isDeleteAIIllust]
     *    全局读，所以这里直接读全局 + 写到 filter。
     */
    private fun seedFromLegacy(searchModel: SearchModel, isNovel: Boolean): SearchFilterV3 {
        val sort = searchModel.sortType.value ?: SortType.POPULAR_PREVIEW
        val target = SearchTarget.values().firstOrNull { it.apiValue == searchModel.searchType.value }
            ?: SearchTarget.PartialMatchForTags

        // 优先用 SearchModel.bookmarkMin（V3 原生 query 参数路径），兜底解析 starSize 后缀
        val storedMin = searchModel.bookmarkMin.value ?: 0
        val bucketMin = if (storedMin > 0) storedMin else parseStarSizeMin(searchModel.starSize.value)
        val bucket = BookmarkBucket.values().firstOrNull { it.min == bucketMin } ?: BookmarkBucket.None

        val r18 = when (searchModel.r18Restriction.value) {
            1 -> R18Mode.SafeOnly
            2 -> R18Mode.R18Only
            else -> R18Mode.All
        }
        val storedDuration = searchModel.duration.value
        val duration = SearchDuration.values().firstOrNull { it.apiValue == storedDuration }

        return SearchFilterV3(
            sort = sort,
            searchTarget = target,
            bookmarkBucket = bucket,
            tool = if (isNovel) null else searchModel.tool.value,
            genre = if (isNovel) searchModel.genre.value else null,
            lang = searchModel.lang.value,
            duration = duration,
            startDate = searchModel.startDate.value,
            endDate = searchModel.endDate.value,
            r18Mode = r18,
            excludeAi = ceui.lisa.activities.Shaft.sSettings.isDeleteAIIllust,
            isOriginalOnly = isNovel && searchModel.isOriginalOnly.value == true,
            isReplaceableOnly = isNovel && searchModel.isReplaceableOnly.value == true,
        )
    }

    private fun parseStarSizeMin(starSize: String?): Int {
        if (starSize.isNullOrEmpty()) return 0
        val match = Regex("""\d+""").find(starSize) ?: return 0
        return match.value.toIntOrNull() ?: 0
    }

    /**
     * SearchFilterV3 → SearchModel。AppApi.searchIllust/searchNovel 已经扩展 V3 维度，所以
     * 这里把 bookmark / tool / genre / lang / duration 都写到 SearchModel；V3 路径走 query
     * 参数，[SearchIllustRepo.initApi] 检测 bookmarkMin > 0 时就跳过老的 starSize keyword 后缀。
     *
     * excludeAi 不写 SearchModel——它通过 [ceui.lisa.activities.Shaft.sSettings.isDeleteAIIllust]
     * 全局生效（[OtherFilterSheet] 提交时已经落盘，Repo.update 也读全局）。
     */
    private fun applyToLegacy(filter: SearchFilterV3, searchModel: SearchModel) {
        searchModel.sortType.value = filter.sort
        searchModel.searchType.value = filter.searchTarget.apiValue
        // 留 starSize 是 legacy 路径回退用，bookmarkMin 优先（同时写不冲突——Repo 检测后清掉）
        searchModel.starSize.value = if (filter.bookmarkBucket == BookmarkBucket.None) ""
        else "${filter.bookmarkBucket.min}users入り"
        searchModel.bookmarkMin.value = filter.bookmarkBucket.bookmarkMin()
        searchModel.tool.value = filter.tool
        searchModel.genre.value = filter.genre
        searchModel.lang.value = filter.lang
        searchModel.duration.value = filter.duration?.apiValue
        searchModel.startDate.value = filter.startDate
        searchModel.endDate.value = filter.endDate
        searchModel.r18Restriction.value = when (filter.r18Mode) {
            R18Mode.All -> 0
            R18Mode.SafeOnly -> 1
            R18Mode.R18Only -> 2
        }
        searchModel.isOriginalOnly.value = filter.isOriginalOnly
        searchModel.isReplaceableOnly.value = filter.isReplaceableOnly
    }
}
