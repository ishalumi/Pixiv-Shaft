package ceui.pixiv.ui.novel

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.http.Retro
import ceui.loxia.Client
import ceui.loxia.Novel
import ceui.loxia.NovelSeriesResp
import ceui.loxia.ObjectPool
import ceui.loxia.RefreshHint
import ceui.loxia.RefreshState
import ceui.loxia.User
import ceui.pixiv.ui.common.ArtworkV3Holder
import ceui.pixiv.ui.common.DataSource
import ceui.pixiv.ui.common.HoldersViewModel
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.LoadingHolder
import ceui.pixiv.ui.common.NovelV3Holder
import ceui.pixiv.ui.common.V3SectionLabelHolder
import ceui.pixiv.ui.common.createResponseStore
import ceui.pixiv.ui.detail.ArtworksMap
import io.reactivex.Observable
import io.reactivex.disposables.Disposable
import io.reactivex.schedulers.Schedulers
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class NovelSeriesViewModel(
    private val seriesId: Long,
) : HoldersViewModel() {

    private var _lastOrder: Int? = null

    private val _series = MutableLiveData<NovelSeriesResp>()
    val series: LiveData<NovelSeriesResp> = _series

    // ── Multi-select state ──────────────────────────────────────────
    // Kept in the VM so it survives config changes (rotation, theme
    // switch). The fragment observes both values to drive its top-right
    // toggle icon and the bottom action bar.
    private val _isMultiSelect = MutableLiveData(false)
    val isMultiSelect: LiveData<Boolean> = _isMultiSelect

    private val _selectedIds = MutableLiveData<Set<Long>>(emptySet())
    val selectedIds: LiveData<Set<Long>> = _selectedIds

    fun setMultiSelectMode(enabled: Boolean) {
        if (_isMultiSelect.value == enabled) return
        _isMultiSelect.value = enabled
        if (!enabled) {
            _selectedIds.value = emptySet()
        }
        updateHoldersSelectionState()
    }

    fun toggleSelection(novelId: Long) {
        val current = _selectedIds.value.orEmpty()
        _selectedIds.value = if (novelId in current) current - novelId else current + novelId
        updateHoldersSelectionState()
    }

    fun selectAll() {
        _selectedIds.value = allNovelIds().toSet()
        updateHoldersSelectionState()
    }

    fun clearSelection() {
        _selectedIds.value = emptySet()
        updateHoldersSelectionState()
    }

    /**
     * 切换观看清单状态。沿用旧 FragmentNovelSeriesDetail 的接口
     * `postWatchlistNovelAdd` / `postWatchlistNovelDelete`。成功后本地把
     * `series.watchlist_added` 翻转，并替换列表里的 HeroHolder 触发 DiffUtil
     * 重绑书签 icon（NovelSeriesHeroHolder.areContentsTheSame 已纳入
     * watchlist_added 字段比较）。
     *
     * suspend 函数：调用方用 lifecycleScope.launch 包，离开页面时协程取消，
     * 通过 invokeOnCancellation 自动 dispose Rx 订阅。失败抛异常。
     */
    suspend fun toggleWatchlist() {
        val resp = _series.value ?: throw IllegalStateException("series not loaded")
        val detail = resp.novel_series_detail
            ?: throw IllegalStateException("series detail null")
        val nowAdded = detail.watchlist_added == true
        val seriesIdInt = detail.id.toInt()
        val obs = if (nowAdded) {
            Retro.getAppApi().postWatchlistNovelDelete(seriesIdInt)
        } else {
            Retro.getAppApi().postWatchlistNovelAdd(seriesIdInt)
        }
        obs.awaitFirst()
        applyWatchlistLocal(!nowAdded)
    }

    private fun applyWatchlistLocal(nextAdded: Boolean) {
        val currentResp = _series.value ?: return
        val detail = currentResp.novel_series_detail ?: return
        val newDetail = detail.copy(watchlist_added = nextAdded)
        _series.value = currentResp.copy(novel_series_detail = newDetail)
        val list = _itemHolders.value ?: return
        _itemHolders.value = list.map { h ->
            if (h is NovelSeriesHeroHolder) {
                NovelSeriesHeroHolder(newDetail, h.latestNovelId, h.latestNovelChapterIndex)
            } else h
        }
    }

    private fun updateHoldersSelectionState() {
        val multiSelect = _isMultiSelect.value == true
        val selected = _selectedIds.value.orEmpty()
        val currentList = _itemHolders.value ?: return
        _itemHolders.value = currentList.map { holder ->
            if (holder is NovelV3Holder) {
                val sel = holder.novel.id in selected
                if (holder.isMultiSelectMode != multiSelect || holder.isSelected != sel) {
                    NovelV3Holder(holder.novel).also {
                        it.isMultiSelectMode = multiSelect
                        it.isSelected = sel
                    }
                } else {
                    holder
                }
            } else {
                holder
            }
        }
    }

    fun allNovelIds(): List<Long> = _itemHolders.value.orEmpty()
        .filterIsInstance<NovelV3Holder>()
        .map { it.novel.id }

    /** 当前已加载到列表里的全部章节（按出现顺序）。「合并下载」用来预填充，之后
     *  任务本身还会继续翻页补齐。 */
    fun allLoadedNovels(): List<Novel> = _itemHolders.value.orEmpty()
        .filterIsInstance<NovelV3Holder>()
        .map { it.novel }

    fun selectedNovels(): List<Novel> {
        val selected = _selectedIds.value.orEmpty()
        if (selected.isEmpty()) return emptyList()
        return _itemHolders.value.orEmpty()
            .filterIsInstance<NovelV3Holder>()
            .filter { it.novel.id in selected }
            .map { it.novel }
    }

    private val _seriesNovelsDataSource = object : DataSource<Novel, NovelSeriesResp>(
        dataFetcher = { Client.appApi.getNovelSeries(seriesId, _lastOrder) },
        responseStore = createResponseStore({ "novel-series-$seriesId" }),
        itemMapper = { novel -> listOf(NovelV3Holder(novel)) }
    ) {
        override fun updateHolders(holders: List<ListItemHolder>) {
            // 从现有列表中剔除 LoadingHolder
            val filteredList =
                (_itemHolders.value ?: listOf()).filterNot { it is LoadingHolder }.toMutableList()
            Timber.d("dfsasfs2 ${holders.size}")
            // 添加新数据
            filteredList.addAll(holders)

            // 更新列表
            _itemHolders.value = filteredList
            _refreshState.value = RefreshState.LOADED(
                hasContent = true,
                hasNext = hasNext()
            )
        }
    }

    init {
        refresh(RefreshHint.InitialLoad)
    }

    override suspend fun refreshImpl(hint: RefreshHint) {
        super.refreshImpl(hint)
        val context = Shaft.getContext()
        val resp = Client.appApi.getNovelSeries(seriesId)
        _series.value = resp
        val result = mutableListOf<ListItemHolder>()
        resp.novel_series_detail?.let { detail ->
            detail.user?.let { user -> ObjectPool.update(user) }
            // 章节序号取 detail.content_count（API 给的总集数），不用累加
            // 计数——novels 列表是分页加载的，hero 比章节列表先渲染。
            val latestNovel = resp.novel_series_latest_novel
            val latestIdx = if (latestNovel != null && detail.content_count > 0) {
                detail.content_count
            } else null
            result.add(NovelSeriesHeroHolder(detail, latestNovel?.id, latestIdx))
            result.add(
                ArtworkV3Holder(
                    ObjectPool.get<User>(detail.user?.id ?: 0L) as LiveData<User?>
                )
            )
            result.add(NovelSeriesProfileHolder(detail))
            if (!detail.caption.isNullOrBlank()) {
                result.add(NovelSeriesCaptionHolder(detail))
            }
        }
        result.add(
            V3SectionLabelHolder(
                context.getString(R.string.novel_series_section_works)
            )
        )
        result.addAll(resp.displayList.map { novel -> NovelV3Holder(novel) })
        _lastOrder = resp.novels?.size
        _itemHolders.value = result
        val hasNext = resp.next_url != null
        _refreshState.value = RefreshState.LOADED(
            hasContent = true, hasNext = hasNext
        )
        if (hasNext) {
            _seriesNovelsDataSource.refreshImpl(hint)
        }
    }

    override suspend fun loadMoreImpl() {
        super.loadMoreImpl()
        _seriesNovelsDataSource.loadMoreImpl()
    }

    override fun prepareIdMap(fragmentUniqueId: String) {
        val filteredList = _itemHolders.value.orEmpty()
            .filterIsInstance<NovelV3Holder>()
            .map { it.novel.id }

        ArtworksMap.store[fragmentUniqueId] = filteredList
    }
}

/** Bridge Rx2 Observable to suspend; cancellation disposes the subscription. */
private suspend fun <T : Any> Observable<T>.awaitFirst(): T = suspendCancellableCoroutine { cont ->
    val disposable: Disposable = subscribeOn(Schedulers.io())
        .firstOrError()
        .subscribe(
            { cont.resume(it) },
            { cont.resumeWithException(it) },
        )
    cont.invokeOnCancellation { disposable.dispose() }
}
