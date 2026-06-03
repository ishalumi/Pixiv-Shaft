package ceui.pixiv.ui.detail

import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ceui.lisa.activities.Shaft
import ceui.lisa.core.Mapper
import ceui.lisa.database.AppDatabase
import ceui.lisa.model.ListIllust
import ceui.lisa.models.IllustsBean
import ceui.lisa.models.UserBean
import ceui.lisa.utils.Common
import ceui.loxia.Client
import ceui.loxia.Comment
import ceui.loxia.Illust
import ceui.loxia.ObjectPool
import ceui.lisa.download.IllustDownload
import ceui.pixiv.db.RecordType
import com.google.gson.Gson
import io.reactivex.Observable
import io.reactivex.schedulers.Schedulers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import timber.log.Timber
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ArtworkV3ViewModel(
    private val illustId: Long
) : ViewModel() {

    // ── internal state ──
    private var illustBean: IllustsBean? = null
    private val gson = Gson()
    private var commentsLoadTriggered = false
    private var authorWorksLoadTriggered = false
    private var relatedLoadTriggered = false

    // ── output: header sections ──
    private val _headerItems = MutableLiveData<List<ArtworkDetailItem>>()
    val headerItems: LiveData<List<ArtworkDetailItem>> = _headerItems

    // ── lazy-loaded section data (observed directly by ViewHolders) ──
    private val _commentsData = MutableLiveData<List<Comment>?>(null)
    val commentsData: LiveData<List<Comment>?> = _commentsData

    private val _authorWorksData = MutableLiveData<List<IllustsBean>?>(null)
    val authorWorksData: LiveData<List<IllustsBean>?> = _authorWorksData

    private val _relatedState = MutableLiveData<Boolean?>(null)
    val relatedState: LiveData<Boolean?> = _relatedState

    // ── output: related illusts (for IAdapter) ──
    private val relatedList = mutableListOf<IllustsBean>()
    private val _relatedIllusts = MutableLiveData<List<IllustsBean>>()
    val relatedIllusts: LiveData<List<IllustsBean>> = _relatedIllusts

    private val _isBookmarked = MutableLiveData<Boolean>()
    val isBookmarked: LiveData<Boolean> = _isBookmarked

    // ── 手动下拉刷新(只刷 illust 详情本身) ──
    private val _isRefreshingDetail = MutableLiveData(false)
    val isRefreshingDetail: LiveData<Boolean> = _isRefreshingDetail

    var relatedNextUrl: String? = null
        private set
    private val _isLoadingRelated = MutableLiveData(false)
    val isLoadingRelated: LiveData<Boolean> = _isLoadingRelated
    private var isLoadingMore = false
    val hasMoreRelated: Boolean get() = !relatedNextUrl.isNullOrEmpty() && !isLoadingMore

    // ── ObjectPool observers ──
    private val illustBeanLiveData = ObjectPool.get<IllustsBean>(illustId)

    // Coalesce multiple triggers fired within the same main-thread turn
    // into a single header rebuild.
    private val mainHandler = Handler(Looper.getMainLooper())
    private val rebuildRunnable = Runnable { doBuildHeaderItems() }
    private val enableLoadMoreRunnable = Runnable { isLoadingMore = false }

    private val illustBeanObserver = Observer<IllustsBean> { bean ->
        if (bean != null) {
            val mpInfo = try {
                if (bean.meta_pages == null) "null" else "size=${bean.meta_pages.size}"
            } catch (e: Throwable) { "throws ${e.javaClass.simpleName}" }
            Timber.tag("V3MultiP").d(
                "[ViewModel.illustBeanObserver] FIRE illustId=$illustId, " +
                    "page_count=${bean.page_count}, w=${bean.width}, h=${bean.height}, " +
                    "meta_pages=$mpInfo, prevIllustBeanWasNull=${illustBean == null}"
            )
            illustBean = bean
            _isBookmarked.value = bean.isIs_bookmarked
            attachArtistFollowObserver(bean.user?.id?.toLong() ?: 0L)
            buildHeaderItems()
            setupDownloadFab(bean)
        } else {
            Timber.tag("V3MultiP").w("[ViewModel.illustBeanObserver] FIRE illustId=$illustId, bean=NULL")
        }
    }

    // ObjectPool.followUser/unFollowUser republishes the artist's UserBean but
    // does NOT republish the IllustsBean — so the illust observer above never
    // fires on a follow click. Listen on the UserBean directly and trigger a
    // header rebuild; the Artist item's isFollowed snapshot will flip and the
    // adapter diff will notifyItemChanged on the artist row.
    private var artistObservedUserId: Long = 0L
    private val artistFollowObserver = Observer<UserBean> { _ -> buildHeaderItems() }

    private fun attachArtistFollowObserver(authorId: Long) {
        if (authorId <= 0L || authorId == artistObservedUserId) return
        if (artistObservedUserId > 0L) {
            ObjectPool.get<UserBean>(artistObservedUserId).removeObserver(artistFollowObserver)
        }
        artistObservedUserId = authorId
        ObjectPool.get<UserBean>(authorId).observeForever(artistFollowObserver)
    }

    // ── download FAB state machine ──
    //
    // 通过 Manager 队列串行下载，不再自行创建 DownloadTask。
    // FAB 状态仅依赖 Manager 是否正在下载当前作品 + DB 是否已有下载记录。
    private var downloadFabInitialized = false
    private val fabRefreshTick = MutableLiveData(0)

    private var downloadedCache: Boolean? = null
    private var downloadCheckInFlight = false

    private val recomputeFabRunnable = Runnable { recomputeFab() }

    private val _downloadFabState = MediatorLiveData<DownloadFab>().apply {
        value = DownloadFab.Idle
        addSource(fabRefreshTick) { recomputeFab() }
    }
    val downloadFabState: LiveData<DownloadFab> = _downloadFabState

    var isPollingProgress = false
        private set

    fun triggerDownload() {
        val illust = illustBean ?: return
        IllustDownload.downloadIllustAllPages(illust)
        _downloadFabState.value = DownloadFab.Downloading(0)
        startProgressPolling(illust.page_count)
    }

    /**
     * 轮询 Manager 队列中当前 illust 的下载进度。
     *
     * 只关心本作品的 DownloadItem，与其他作品无关。
     * 进度 = (已完成页 × 100 + 正在下载页的 nonius) / 总页数
     *
     * 例：单 P 作品正在下载，nonius=60 → 进度 60%
     * 例：172P 作品已完成 100 页，当前页 nonius=50 → (100×100+50)/172 = 58%
     */
    private fun startProgressPolling(pageCount: Int) {
        if (isPollingProgress) return
        isPollingProgress = true
        viewModelScope.launch {
            while (isPollingProgress) {
                kotlinx.coroutines.delay(300)
                // contentSnapshot() 是带 synchronized 的浅拷贝;直接 .content 拿 live
                // list 跟 Manager 内部的 addTasks/safeAdd/remove 并发,filter 迭代 CME。
                val items = ceui.lisa.core.Manager.get().contentSnapshot()
                val myItems = items.filter { it.illust?.id == illustId.toInt() }
                if (myItems.isEmpty()) {
                    // 队列清空 = 下载完成，直接设 Done，避免经过 Idle 闪烁
                    isPollingProgress = false
                    downloadedCache = true
                    _downloadFabState.postValue(DownloadFab.Done)
                    break
                }
                // 队列中剩余的本作品页数
                val remaining = myItems.size
                val completedPages = pageCount - remaining
                // 找到本作品中正在下载的那一页（state=DOWNLOADING 且 nonius>0）
                val activeItem = myItems.firstOrNull {
                    it.state == ceui.lisa.core.DownloadItem.DownloadState.DOWNLOADING
                }
                val activeNonius = activeItem?.nonius ?: 0
                val totalPercent = if (pageCount > 0) {
                    ((completedPages * 100 + activeNonius) / pageCount).coerceIn(0, 99)
                } else 0
                _downloadFabState.value = DownloadFab.Downloading(totalPercent)
            }
        }
    }

    fun refreshDownloadFab() {
        isPollingProgress = false
        downloadedCache = null
        fabRefreshTick.value = (fabRefreshTick.value ?: 0) + 1
    }

    private fun setupDownloadFab(illust: IllustsBean) {
        if (downloadFabInitialized) return
        downloadFabInitialized = true
        // 若 Manager 正在下载本作品，启动进度轮询
        val items = ceui.lisa.core.Manager.get().contentSnapshot()
        val hasItems = items.any { it.illust?.id == illustId.toInt() }
        if (hasItems) {
            _downloadFabState.value = DownloadFab.Downloading(0)
            startProgressPolling(illust.page_count)
        } else {
            recomputeFab()
        }
    }

    private fun recomputeFab() {
        val cached = downloadedCache
        if (cached != null) {
            _downloadFabState.value = if (cached) DownloadFab.Done else DownloadFab.Idle
            return
        }
        if (_downloadFabState.value !is DownloadFab.Done) {
            _downloadFabState.value = DownloadFab.Idle
        }
        triggerDownloadedCheck()
    }

    private fun triggerDownloadedCheck() {
        if (downloadCheckInFlight) return
        val bean = illustBean ?: return
        downloadCheckInFlight = true
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val dao = AppDatabase.getAppDatabase(Shaft.getContext()).downloadDao()
                    Common.isIllustDownloaded(bean) ||
                            dao.hasDownloadRecordByIllustId(bean.id.toLong())
                } catch (e: Exception) {
                    Timber.e(e, "downloaded check failed")
                    false
                }
            }
            downloadedCache = result
            downloadCheckInFlight = false
            recomputeFab()
        }
    }

    init {
        // Note: only read the IllustsBean LiveData (already created above via the
        // member val). Do NOT touch ObjectPool.get<Illust>(...) here — it would
        // create a fresh empty entry as a side effect, which loadData() may not
        // have done in the short-circuit branch.
        Timber.tag("V3MultiP").d(
            "[ViewModel.init] illustId=$illustId, " +
                "IllustsBeanPoolHasValue=${illustBeanLiveData.value != null}"
        )
        illustBeanLiveData.observeForever(illustBeanObserver)
        loadData()
    }

    override fun onCleared() {
        illustBeanLiveData.removeObserver(illustBeanObserver)
        if (artistObservedUserId > 0L) {
            ObjectPool.get<UserBean>(artistObservedUserId).removeObserver(artistFollowObserver)
            artistObservedUserId = 0L
        }
        mainHandler.removeCallbacks(rebuildRunnable)
        mainHandler.removeCallbacks(enableLoadMoreRunnable)
        mainHandler.removeCallbacks(recomputeFabRunnable)
    }

    // ── build header item list (everything except individual related cards) ──
    private fun buildHeaderItems() {
        mainHandler.removeCallbacks(rebuildRunnable)
        mainHandler.post(rebuildRunnable)
    }

    private fun doBuildHeaderItems() {
        val illust = illustBean ?: return
        val list = mutableListOf<ArtworkDetailItem>()

        list.add(ArtworkDetailItem.Hero(illust))

        if (illust.series != null && !TextUtils.isEmpty(illust.series.title)) {
            list.add(ArtworkDetailItem.Series(illust))
        }

        list.add(ArtworkDetailItem.Artist(illust))

        if (!TextUtils.isEmpty(illust.caption)) {
            list.add(ArtworkDetailItem.Desc(illust.caption))
        }

        list.add(ArtworkDetailItem.Tags(illust))
        list.add(ArtworkDetailItem.Stats(illust))
        list.add(ArtworkDetailItem.DetailPanel(illust))

        list.add(ArtworkDetailItem.Comments(_commentsData, illust.id, illust.title ?: ""))
        list.add(
            ArtworkDetailItem.AuthorWorks(
                _authorWorksData,
                illust.user?.name ?: "",
                illust.user?.id ?: 0
            )
        )
        list.add(ArtworkDetailItem.RelatedHeader(_relatedState, illust.id, illust.title ?: ""))

        _headerItems.value = list
    }

    // ── data loading (deep-link fallback) ──
    private fun loadData() {
        // IllustsBean is normally already in ObjectPool from the list page.
        // Only fetch when entering via deep link / history where the pool is empty.
        if (illustBean != null) {
            Timber.tag("V3MultiP").d("[ViewModel.loadData] short-circuit: IllustsBean already present (illustId=$illustId)")
            return
        }
        Timber.tag("V3MultiP").d("[ViewModel.loadData] IllustsBean is NULL, falling through to Illust pool / DB / API path.")
        viewModelScope.launch {
            try {
                val resolved = ObjectPool.get<Illust>(illustId).value
                    ?: withContext(Dispatchers.IO) {
                        val ctx = Shaft.getContext()
                        AppDatabase.getAppDatabase(ctx).generalDao()
                            .getByRecordTypeAndId(RecordType.VIEW_ILLUST_HISTORY, illustId)
                            ?.typedObject<Illust>()?.also {
                                Timber.tag("V3MultiP").d("[ViewModel.loadData] hit DB history, updating Illust(modern) pool")
                                ObjectPool.update(it)
                            }
                    }
                    ?: Client.appApi.getIllust(illustId).illust?.also {
                        Timber.tag("V3MultiP").d(
                            "[ViewModel.loadData] fetched via API, page_count=${it.page_count}, " +
                                "meta_pages=${it.meta_pages?.size ?: -1}; updating Illust(modern) pool"
                        )
                        ObjectPool.update(it)
                    }
                // Fragment 观察的是 legacy IllustsBean pool;任何只能拿到 modern Illust
                // 的入口,如果不在这里桥接回 legacy pool,illustBeanObserver 永远不 fire,
                // header 不构建,UI 白屏。Pixiv 后端 illust JSON 只有一种,Illust /
                // IllustsBean 字段名一致,Gson round-trip 安全。
                if (resolved != null && ObjectPool.get<IllustsBean>(illustId).value == null) {
                    runCatching { gson.fromJson(gson.toJson(resolved), IllustsBean::class.java) }
                        .onFailure { Timber.tag("V3MultiP").e(it, "[ViewModel.loadData] legacy bridge failed") }
                        .getOrNull()
                        ?.let {
                            Timber.tag("V3MultiP").d("[ViewModel.loadData] bridged to legacy IllustsBean pool")
                            ObjectPool.updateIllust(it)
                        }
                }
            } catch (e: Exception) {
                Timber.tag("V3MultiP").e(e, "[ViewModel.loadData] EXCEPTION")
            }
        }
    }

    /**
     * 手动下拉刷新:只重新请求 v1/illust/detail 并发布到 ObjectPool,
     * illustBeanObserver 收到新数据后自动重建 header(标题/标签/收藏数等)。
     * 评论 / 作者其他作品 / 相关作品等懒加载区块一律不动。
     */
    fun refreshIllustDetail() {
        if (_isRefreshingDetail.value == true) return
        _isRefreshingDetail.value = true
        viewModelScope.launch {
            try {
                val fresh = withContext(Dispatchers.IO) {
                    ceui.lisa.http.Retro.getAppApi().getIllustByID(illustId).awaitFirst().illust
                }
                // 作品被删除/设为非公开时服务端返回空壳对象,不要让它覆盖页面上已有的完整数据
                if (fresh != null && fresh.id != 0 && fresh.isVisible) {
                    // 详情接口返回的是完整版本,整体覆盖(isFullVersion)而非 merge,
                    // 这样标签删除、简介清空等"变少"的修改也能反映出来
                    ObjectPool.update(fresh, isFullVersion = true)
                    // 内嵌 user 是精简版,走默认 merge,不降级池里更完整的 UserBean
                    fresh.user?.let { ObjectPool.update(it) }
                }
            } catch (e: Exception) {
                Timber.e(e, "[refreshIllustDetail] failed, illustId=$illustId")
            } finally {
                _isRefreshingDetail.value = false
            }
        }
    }

    fun loadComments() {
        if (commentsLoadTriggered) return
        commentsLoadTriggered = true
        viewModelScope.launch {
            _commentsData.value = withContext(Dispatchers.IO) {
                runCatching {
                    Client.appApi.getIllustComments(illustId).comments.take(3)
                }
                    .getOrElse { Timber.e(it); emptyList() }
            }
        }
    }

    fun loadAuthorWorks() {
        if (authorWorksLoadTriggered) return
        authorWorksLoadTriggered = true
        val userId = illustBean?.user?.id ?: return
        viewModelScope.launch {
            _authorWorksData.value = try {
                withContext(Dispatchers.IO) {
                    runCatching {
                        val resp = ceui.lisa.http.Retro.getAppApi()
                            .getUserSubmitIllust(userId, "illust")
                            .awaitFirst()
                        // 作者其他作品同属页面内列表，应用与全局一致的屏蔽过滤
                        if (resp.illusts != null) {
                            Mapper<ListIllust>().apply(resp)
                        }
                        resp.list?.filter { it.id != illustId.toInt() }?.take(10) ?: emptyList()
                    }.getOrElse { Timber.e(it); emptyList() }
                }
            } catch (e: Exception) {
                Timber.e(e); emptyList()
            }
        }
    }

    fun loadRelated() {
        if (relatedLoadTriggered) return
        relatedLoadTriggered = true
        viewModelScope.launch {
            try {
                val resp = withContext(Dispatchers.IO) {
                    runCatching {
                        val body = Client.appApi.generalGet(
                            "https://app-api.pixiv.net/v2/illust/related?illust_id=$illustId"
                        )
                        val parsed = gson.fromJson(body.string(), ListIllust::class.java)
                        // 相关作品此前直接 addAll 原始返回，绕过了全局屏蔽，是本次修复的 bug。
                        // 走与所有列表相同的 Mapper：屏蔽 tag / 作者 / 作品 + R18 + AI 设置（就地过滤）。
                        if (parsed?.illusts != null) {
                            Mapper<ListIllust>().apply(parsed)
                        }
                        parsed
                    }.getOrElse { Timber.e(it); null }
                }
                resp?.let { r ->
                    relatedList.clear()
                    relatedList.addAll(r.list ?: emptyList())
                    relatedNextUrl = r.next_url
                    _relatedIllusts.value = relatedList.toList()
                }
            } catch (e: Exception) {
                Timber.e(e)
            } finally {
                _relatedState.value = relatedList.isNotEmpty()
                // Prevent scroll inertia from immediately triggering loadMoreRelated
                isLoadingMore = true
                mainHandler.postDelayed(enableLoadMoreRunnable, 300)
            }
        }
    }

    fun loadMoreRelated() {
        val url = relatedNextUrl
        if (url.isNullOrEmpty() || isLoadingMore) return
        isLoadingMore = true
        _isLoadingRelated.value = true

        viewModelScope.launch(Dispatchers.Main) {
            try {
                val resp = withContext(Dispatchers.IO) {
                    val body = Client.appApi.generalGet(url)
                    val parsed = gson.fromJson(body.string(), ListIllust::class.java)
                    // 分页同样应用全局屏蔽过滤，保持与首屏 loadRelated 一致
                    if (parsed?.illusts != null) {
                        Mapper<ListIllust>().apply(parsed)
                    }
                    parsed
                }
                relatedNextUrl = resp.next_url
                resp.list?.let { newItems ->
                    relatedList.addAll(newItems)
                    _relatedIllusts.value = relatedList.toList()
                }
            } catch (e: Exception) {
                Timber.e(e)
            } finally {
                isLoadingMore = false
                _isLoadingRelated.value = false
            }
        }
    }
}

/**
 * Bridge Rx2 Observable to a suspend function without pulling in kotlinx-coroutines-rx2.
 * Runs the subscription on Schedulers.io so the calling coroutine thread is freed while waiting.
 */
sealed interface DownloadFab {
    data object Idle : DownloadFab
    data class Downloading(val percent: Int) : DownloadFab
    data object Done : DownloadFab
}

private suspend fun <T : Any> Observable<T>.awaitFirst(): T = suspendCancellableCoroutine { cont ->
    val disposable = subscribeOn(Schedulers.io())
        .firstOrError()
        .subscribe(
            { cont.resume(it) },
            { cont.resumeWithException(it) }
        )
    cont.invokeOnCancellation { disposable.dispose() }
}
