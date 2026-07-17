package ceui.pixiv.ui.detail

import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ceui.lisa.activities.Shaft
import ceui.lisa.database.AppDatabase
import ceui.lisa.database.downloadProbeDispatcher
import ceui.lisa.database.hasDownloadRecord
import ceui.lisa.download.IllustDownload
import ceui.lisa.models.IllustsBean
import ceui.lisa.utils.Common
import ceui.loxia.ObjectPool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * ArtworkV3 详情页的 chrome VM(feeds 版精简后只剩两件事):
 * - 悬浮下载胶囊状态机(排队 / 轮询进度 / 已下载探测);
 * - 收藏态(驱动收藏 FAB 着色)。
 *
 * 列表内容(顶部大图 / header 区块 / 相关作品)全部归框架 [ceui.pixiv.feeds.FeedViewModel] +
 * [ArtworkV3FeedSource];完整详情 bean 的解析 / 拉取也由数据源负责,本 VM 只观察 ObjectPool
 * 里那条 bean 的落地来初始化 FAB / 收藏态。
 */
class ArtworkV3ViewModel(
    private val illustId: Long,
) : ViewModel() {

    private var illustBean: IllustsBean? = null

    private val _isBookmarked = MutableLiveData<Boolean>()
    val isBookmarked: LiveData<Boolean> = _isBookmarked

    private val illustBeanLiveData = ObjectPool.get<IllustsBean>(illustId)

    private val illustBeanObserver = Observer<IllustsBean> { bean ->
        if (bean != null) {
            illustBean = bean
            _isBookmarked.value = bean.isIs_bookmarked
            setupDownloadFab(bean)
        }
    }

    // ── download FAB state machine ──
    // 通过 Manager 队列串行下载;FAB 状态依赖 Manager 是否正在下载当前作品 + DB 是否已有下载记录。
    private var downloadFabInitialized = false
    private val fabRefreshTick = MutableLiveData(0)

    private var downloadedCache: Boolean? = null
    private var downloadCheckInFlight = false

    private val _downloadFabState = MediatorLiveData<DownloadFab>().apply {
        value = DownloadFab.Idle
        addSource(fabRefreshTick) { recomputeFab() }
    }
    val downloadFabState: LiveData<DownloadFab> = _downloadFabState

    var isPollingProgress = false
        private set

    init {
        illustBeanLiveData.observeForever(illustBeanObserver)
    }

    override fun onCleared() {
        illustBeanLiveData.removeObserver(illustBeanObserver)
    }

    fun triggerDownload() {
        val illust = illustBean ?: return
        IllustDownload.downloadIllustAllPages(illust)
        _downloadFabState.value = DownloadFab.Downloading(0)
        startProgressPolling(illust.page_count)
    }

    /**
     * 轮询 Manager 队列中当前 illust 的下载进度。只关心本作品的 DownloadItem。
     * 进度 = (已完成页 × 100 + 正在下载页的 nonius) / 总页数。
     */
    private fun startProgressPolling(pageCount: Int) {
        if (isPollingProgress) return
        isPollingProgress = true
        viewModelScope.launch {
            while (isPollingProgress) {
                kotlinx.coroutines.delay(300)
                // contentSnapshot() 是带 synchronized 的浅拷贝;直接 .content 拿 live list 会 CME。
                val items = ceui.lisa.core.Manager.get().contentSnapshot()
                val myItems = items.filter { it.illust?.id == illustId.toInt() }
                if (myItems.isEmpty()) {
                    // 队列清空 = 下载完成,直接设 Done,避免经过 Idle 闪烁
                    isPollingProgress = false
                    downloadedCache = true
                    _downloadFabState.postValue(DownloadFab.Done)
                    break
                }
                val remaining = myItems.size
                val completedPages = pageCount - remaining
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
                    // hasDownloadRecord 走 v38 的 illustId 索引(O(log n));存量回填未完成时退回旧 LIKE 兜底。
                    Common.isIllustDownloaded(bean) ||
                            withContext(downloadProbeDispatcher) {
                                dao.hasDownloadRecord(bean.id.toLong())
                            }
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
}

sealed interface DownloadFab {
    data object Idle : DownloadFab
    data class Downloading(val percent: Int) : DownloadFab
    data object Done : DownloadFab
}
