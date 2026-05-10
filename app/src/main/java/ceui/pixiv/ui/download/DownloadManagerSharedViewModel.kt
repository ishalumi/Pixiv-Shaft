package ceui.pixiv.ui.download

import androidx.lifecycle.AndroidViewModel
import android.app.Application
import ceui.lisa.activities.Shaft
import ceui.lisa.core.DownloadItem
import ceui.lisa.core.ManagerReactive
import ceui.lisa.database.AppDatabase
import ceui.pixiv.db.queue.DownloadQueueDao
import ceui.pixiv.db.queue.QueueStatus
import ceui.pixiv.ui.bulk.QueueDownloadManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/**
 * 三个 tab 共享的 stats 数据源。
 *
 * 数据源：
 *   - 4 个队列计数从 [QueueDownloadManager.queueListInvalidations] 脏标记 +
 *     suspend [DownloadQueueDao.countByStatus] 派生。**不用** Room 的
 *     `flowCountByStatus`：实测 Room InvalidationTracker 在快速连续 UPDATE
 *     序列下首次 emit 后静默不再 re-emit（用户已复现，17 个 SUCCESS 一次都
 *     没让 Flow 再 emit）。改成自己 tick 后 suspend 查一次，可靠。
 *   - 当前活跃 page 数从 [ManagerReactive.contentFlow] 派生。
 */
class DownloadManagerSharedViewModel(app: Application) : AndroidViewModel(app) {

    private val queueDao: DownloadQueueDao by lazy {
        AppDatabase.getAppDatabase(Shaft.getContext()).downloadQueueDao()
    }

    data class Snapshot(
        val queuePending: Int,
        val queueDownloading: Int,
        val queueSuccess: Int,
        val queueFailed: Int,
        val activeCount: Int,
    )

    /**
     * 从 ManagerReactive 派生的活跃 page 数。distinctUntilChanged 避免高频
     * progress invalidate 让上层 combine 频繁重算，无谓 setText。
     */
    private val activePageCountFlow: Flow<Int> =
        ManagerReactive.contentFlow
            .map { content -> content.count { it.state == DownloadItem.DownloadState.DOWNLOADING } }
            .distinctUntilChanged()

    /**
     * 队列计数 Flow：每次 [QueueDownloadManager.queueListInvalidations] tick
     * 就 suspend 查一次 4 个状态的 count。queueListInvalidations 本身是
     * SharedFlow(replay=1)，新订阅立刻拿一次初始 tick。
     */
    private val queueCountsFlow: Flow<QueueCounts> =
        QueueDownloadManager.queueListInvalidations
            .map {
                QueueCounts(
                    pending = runCatching { queueDao.countByStatus(QueueStatus.PENDING) }.getOrDefault(0),
                    downloading = runCatching { queueDao.countByStatus(QueueStatus.DOWNLOADING) }.getOrDefault(0),
                    success = runCatching { queueDao.countByStatus(QueueStatus.SUCCESS) }.getOrDefault(0),
                    failed = runCatching { queueDao.countByStatus(QueueStatus.FAILED) }.getOrDefault(0),
                )
            }
            .distinctUntilChanged()
            .flowOn(Dispatchers.IO)

    private data class QueueCounts(val pending: Int, val downloading: Int, val success: Int, val failed: Int)

    /**
     * 把队列 4 个 count + active count 合并成 Snapshot。
     * combine 等所有 upstream 至少各 emit 一次 —— SharedFlow / StateFlow 都
     * 自带初始值，几乎立刻 first emit。
     */
    fun snapshots(): Flow<Snapshot> = combine(
        queueCountsFlow,
        activePageCountFlow,
    ) { counts, activeCount ->
        Snapshot(
            queuePending = counts.pending,
            queueDownloading = counts.downloading,
            queueSuccess = counts.success,
            queueFailed = counts.failed,
            activeCount = activeCount,
        )
    }

    /**
     * 导出按钮的事件通道：host ([DownloadManagerV3Fragment]) 点击 toolbar 上的
     * 导出 menu 时 emit 当前 tab pos，子 fragment ([QueueListV3Fragment]
     * pos==0 / [DoneListV3Fragment] pos==2) collect 后各自触发自己的导出流程。
     *
     * replay=0：按钮事件不需要 replay，否则 fragment STARTED 时会重放历史点击。
     * extraBufferCapacity=1：tryEmit 不会丢，host 的点击都能落到唯一一个监听
     * 的 fragment（同一 tab 的 fragment 每次 onResume 才会 collect 起来）。
     */
    private val _exportRequest = MutableSharedFlow<Int>(replay = 0, extraBufferCapacity = 1)
    val exportRequest: SharedFlow<Int> = _exportRequest.asSharedFlow()

    fun requestExport(tabPos: Int) {
        _exportRequest.tryEmit(tabPos)
    }
}
