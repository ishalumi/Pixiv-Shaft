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

/**
 * 三个 tab 共享的 stats 数据源；统一在 IO 线程查 DAO 和 Manager.content。
 *
 * 完全 reactive：
 *   - 4 个队列计数 → [DownloadQueueDao.flowCountByStatus]，Room 自动 emit
 *   - 当前活跃 page 数 → 从 [ManagerReactive.contentFlow] 派生
 *   - 当前 illust 的页级进度 → [QueueDownloadManager.currentJobFlow] +
 *     contentFlow 派生（done = totalPages - 还在 content 里的 page 数）
 *   - 6 路 [combine] 合并成 [Snapshot]，UI collect 即可
 *
 * 0 timer，0 polling。空闲时彻底沉默；任何状态变动 < 50ms 反映到 UI。
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
     * 把 contentFlow + currentJobFlow 合到一处算"页级进度 + 活跃 page 数"：
     *   - activePagesForCurrent = content 里跟 currentJob 同 illust 的 page 数
     *   - done = totalPages - activePagesForCurrent
     *
     * 高频 invalidate（progress 1% 一次）会让此 flow 频繁 emit；
     * distinctUntilChanged 让相同 (active, progress) 不重复传给下游 combine，
     * 减少 tab 文字无谓 setText 调用。
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
     * Reactive 合并：4 个 Room Flow + 派生的 (activeCount, illustProgress) Flow。
     * combine 会等所有 upstream 都至少 emit 一次再 emit；Room flow 在订阅时
     * 立刻拉一次当前 DB 值，[derivedActiveStateFlow] 透过 [ManagerReactive] 的
     * replay=1 也会立刻拿到一帧。所以 first emit 几乎立刻发生。
     */
    fun snapshots(): Flow<Snapshot> = combine(
        queueDao.flowCountByStatus(QueueStatus.PENDING),
        queueDao.flowCountByStatus(QueueStatus.DOWNLOADING),
        queueDao.flowCountByStatus(QueueStatus.SUCCESS),
        queueDao.flowCountByStatus(QueueStatus.FAILED),
        derivedActiveStateFlow,
    ) { pending, downloading, success, failed, activeAndProgress ->
        Snapshot(
            queuePending = pending,
            queueDownloading = downloading,
            queueSuccess = success,
            queueFailed = failed,
            activeCount = activeAndProgress.first,
            currentIllustProgress = activeAndProgress.second,
        )
    }.flowOn(Dispatchers.IO)
}
