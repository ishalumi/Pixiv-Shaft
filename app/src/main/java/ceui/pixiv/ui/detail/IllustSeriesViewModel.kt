package ceui.pixiv.ui.detail

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.http.Retro
import ceui.loxia.Client
import ceui.loxia.Illust
import ceui.loxia.IllustSeriesResp
import ceui.loxia.ObjectPool
import ceui.loxia.RefreshHint
import ceui.loxia.RefreshState
import ceui.loxia.SeriesCache
import ceui.loxia.User
import ceui.pixiv.ui.common.ArtworkV3Holder
import ceui.pixiv.ui.common.DataSource
import ceui.pixiv.ui.common.HoldersViewModel
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.LoadingHolder
import ceui.pixiv.ui.common.V3SectionLabelHolder
import ceui.pixiv.ui.common.createResponseStore
import ceui.pixiv.ui.novel.NovelSeriesCaptionHolder
import io.reactivex.Observable
import io.reactivex.disposables.Disposable
import io.reactivex.schedulers.Schedulers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 漫画系列 V3 详情页的 ViewModel。整页镜像 [ceui.pixiv.ui.novel.NovelSeriesViewModel]：
 * hero(标题/收藏/阅读最新一话) + 作者卡 + 作品档案 + 简介 + 「作品列表」标题 + 单话卡片。
 * 用户诉求把旧瀑布流换成标题优先的单话列表，所以列表项用 [MangaSeriesItemHolder]。
 */
class IllustSeriesViewModel(
    private val seriesId: Long,
) : HoldersViewModel() {

    private var _lastOrder: Int? = null

    // pixiv 漫画系列 /v1/illust/series 的 illusts 是「最新在前」(降序)，且不返回
    // latest_illust。这两个字段在 refresh 时确定，供 reindex/hero 编号与「阅读最新一话」用。
    private var _descending = true
    private var _episodeTotal = 0

    private val _series = MutableLiveData<IllustSeriesResp>()
    val series: LiveData<IllustSeriesResp> = _series

    /** 切换漫画观看清单（postWatchlistManga add/delete）。成功后本地翻转
     *  watchlist_added 并替换 HeroHolder 触发 DiffUtil 重绑收藏 icon。失败抛异常。 */
    suspend fun toggleWatchlist() {
        val resp = _series.value ?: throw IllegalStateException("series not loaded")
        val detail = resp.illust_series_detail
            ?: throw IllegalStateException("series detail null")
        val nowAdded = detail.watchlist_added == true
        val seriesIdInt = detail.id.toInt()
        val obs = if (nowAdded) {
            Retro.getAppApi().postWatchlistMangaDelete(seriesIdInt)
        } else {
            Retro.getAppApi().postWatchlistMangaAdd(seriesIdInt)
        }
        obs.awaitFirst()
        applyWatchlistLocal(!nowAdded)
    }

    private fun applyWatchlistLocal(nextAdded: Boolean) {
        val currentResp = _series.value ?: return
        val detail = currentResp.illust_series_detail ?: return
        val newDetail = detail.copy(watchlist_added = nextAdded)
        _series.value = currentResp.copy(illust_series_detail = newDetail)
        val list = _itemHolders.value ?: return
        _itemHolders.value = list.map { h ->
            if (h is MangaSeriesHeroHolder) {
                MangaSeriesHeroHolder(newDetail, h.latestIllustId, h.latestEpisodeIndex)
            } else h
        }
    }

    /** 当前已加载到列表里的全部单话（按系列顺序）。点开单话时用来构造整条系列的
     *  查看器翻页数据。 */
    fun allLoadedIllusts(): List<Illust> = _itemHolders.value.orEmpty()
        .filterIsInstance<MangaSeriesItemHolder>()
        .map { it.illust }

    /** 给列表里的 [MangaSeriesItemHolder] 打真实话号。漫画系列接口是「最新在前」(降序)，
     *  所以第 0 个 = 最新 = 第 total 话，往后递减；拿不到总数时退回按位置 1-based（兜底）。
     *  追加分页(更旧的话)只需重排一遍。 */
    private fun reindexEpisodes(list: List<ListItemHolder>) {
        var pos = 0
        list.forEach { holder ->
            if (holder is MangaSeriesItemHolder) {
                holder.episodeIndex = if (_descending && _episodeTotal > 0) {
                    (_episodeTotal - pos).coerceAtLeast(1)
                } else {
                    pos + 1
                }
                pos++
            }
        }
    }

    private val _seriesIllustsDataSource = object : DataSource<Illust, IllustSeriesResp>(
        dataFetcher = { Client.appApi.getIllustSeries(seriesId, _lastOrder) },
        responseStore = createResponseStore({ "illust-series-$seriesId" }),
        itemMapper = { illust -> listOf(MangaSeriesItemHolder(illust, 0)) }
    ) {
        override fun updateHolders(holders: List<ListItemHolder>) {
            val filteredList =
                (_itemHolders.value ?: listOf()).filterNot { it is LoadingHolder }.toMutableList()
            filteredList.addAll(holders)
            reindexEpisodes(filteredList)
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
        // 用户下拉刷新时清掉本系列的 SeriesCache，让阅读器翻页 / 选话 sheet 下次拿到最新话。
        if (hint == RefreshHint.PullToRefresh) {
            SeriesCache.invalidate(seriesId)
        }
        val context = Shaft.getContext()
        val resp = Client.appApi.getIllustSeries(seriesId)
        _series.value = resp
        val episodes = resp.displayList
        // 用 illust_series_first_illust(=第1话) 反查列表方向：列表首个若不是第1话即为降序
        // (最新在前，pixiv 漫画系列的实际返回)。拿不到 first_illust 时默认降序。
        val firstEpisodeId = resp.illust_series_first_illust?.id
        _descending = firstEpisodeId == null || episodes.isEmpty() ||
                episodes.first().id != firstEpisodeId
        val result = mutableListOf<ListItemHolder>()
        resp.illust_series_detail?.let { detail ->
            detail.user?.let { user -> ObjectPool.update(user) }
            _episodeTotal = mangaSeriesEpisodeCount(detail)
            // 漫画系列接口不给 latest_illust。降序时列表首个即最新一话；万一判成升序，
            // 只有首页已全量(无 next_url)才能确定最后一个是最新，否则不给按钮。
            val latestIllust = if (_descending) episodes.firstOrNull()
                else episodes.lastOrNull()?.takeIf { resp.next_url == null }
            val latestIdx = if (latestIllust != null && _episodeTotal > 0) _episodeTotal else null
            result.add(MangaSeriesHeroHolder(detail, latestIllust?.id, latestIdx))
            result.add(
                ArtworkV3Holder(
                    ObjectPool.get<User>(detail.user?.id ?: 0L) as LiveData<User?>
                )
            )
            result.add(MangaSeriesProfileHolder(detail))
            if (!detail.caption.isNullOrBlank()) {
                result.add(NovelSeriesCaptionHolder(detail))
            }
        }
        result.add(
            V3SectionLabelHolder(
                context.getString(R.string.novel_series_section_works)
            )
        )
        result.addAll(resp.displayList.map { illust -> MangaSeriesItemHolder(illust, 0) })
        reindexEpisodes(result)
        _lastOrder = resp.illusts?.size
        _itemHolders.value = result
        val hasNext = resp.next_url != null
        _refreshState.value = RefreshState.LOADED(
            hasContent = true, hasNext = hasNext
        )
        if (hasNext) {
            _seriesIllustsDataSource.refreshImpl(hint)
        }
    }

    override suspend fun loadMoreImpl() {
        super.loadMoreImpl()
        _seriesIllustsDataSource.loadMoreImpl()
    }

    override fun prepareIdMap(fragmentUniqueId: String) {
        val filteredList = _itemHolders.value.orEmpty()
            .filterIsInstance<MangaSeriesItemHolder>()
            .map { it.illust.id }
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
