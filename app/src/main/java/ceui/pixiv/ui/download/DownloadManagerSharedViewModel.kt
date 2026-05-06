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
 *   - 当前活跃 page 数 + 当前 illust 页级进度从 [ManagerReactive.contentFlow]
 *     + [QueueDownloadManager.currentJobFlow] 派生。Manager 端有自己的
 *     [ManagerReactive.invalidate] 机制驱动，不依赖 Room。
 */
class DownloadManagerSharedViewModel(app: Application) : AndroidViewModel(app) {

    private val queueDao: DownloadQueueDao by lazy {
        AppDatabase.getAppDatabase(Shaft.getContext()).downloadQueueDao()
    }

    /** 当前 illust 的页级进度：(已完成页, 总页)；闲置时 null */
    data class IllustProgress(val done: Int, val total: Int)

    data class Snapshot(
        val queuePending: Int,
        val queueDownloading: Int,
        val queueSuccess: Int,
        val queueFailed: Int,
        val activeCount: Int,
        /** 当前正在跑的 illust 的页级进度；用来在批量队列 tab badge 上做"30/87"那种实时数字反馈 */
        val currentIllustProgress: IllustProgress? = null,
    )

    /**
     * 从 ManagerReactive 派生的 (active page 数, 当前 illust 进度) 二元组。
     * distinctUntilChanged 避免高频 progress invalidate 让上层 combine 频繁
     * 重算，无谓 setText。
     */
    private val derivedActiveStateFlow: Flow<Pair<Int, IllustProgress?>> =
        ManagerReactive.contentFlow.combine(QueueDownloadManager.currentJobFlow) { content, job ->
            val active = content.count { it.state == DownloadItem.DownloadState.DOWNLOADING }
            val progress = job?.let { j ->
                val activePages = content.count { item ->
                    item.illust?.id?.toLong() == j.illustId
                }
                IllustProgress(
                    done = (j.totalPages - activePages).coerceAtLeast(0),
                    total = j.totalPages,
                )
            }
            active to progress
        }.distinctUntilChanged()

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
     * 把队列 4 个 count + active state 合并成 Snapshot。
     * combine 等所有 upstream 至少各 emit 一次 —— SharedFlow / StateFlow 都
     * 自带初始值，几乎立刻 first emit。
     */
    fun snapshots(): Flow<Snapshot> = combine(
        queueCountsFlow,
        derivedActiveStateFlow,
    ) { counts, activeAndProgress ->
        Snapshot(
            queuePending = counts.pending,
            queueDownloading = counts.downloading,
            queueSuccess = counts.success,
            queueFailed = counts.failed,
            activeCount = activeAndProgress.first,
            currentIllustProgress = activeAndProgress.second,
        )
    }
}
