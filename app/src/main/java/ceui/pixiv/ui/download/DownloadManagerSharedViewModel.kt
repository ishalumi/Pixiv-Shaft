package ceui.pixiv.ui.download

import androidx.lifecycle.AndroidViewModel
import android.app.Application
import ceui.lisa.activities.Shaft
import ceui.lisa.core.ManagerReactive
import ceui.lisa.database.AppDatabase
import ceui.pixiv.db.queue.DownloadQueueDao
import ceui.pixiv.db.queue.QueueStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn

/**
 * 三个 tab 共享的 stats 数据源；统一在 IO 线程查 DAO 和 Manager.content。
 *
 * 完全 reactive：
 *   - 4 个队列计数 → [DownloadQueueDao.flowCountByStatus]，Room 自动 emit
 *   - 当前活跃 page 数 → [ManagerReactive.activeCountFlow]，Manager 任何 mutation 后 emit
 *   - 5 路 [combine] 合并成 [Snapshot]，UI collect 即可
 *
 * 0 timer，0 polling。空闲时彻底沉默；任何状态变动 < 50ms 反映到 UI。
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
     * Reactive 5 路合并：4 个 Room Flow + Manager activeCountFlow。
     * combine 会等所有 upstream 都至少 emit 一次再 emit；Room flow 在订阅时
     * 会立刻拉一次当前 DB 值，[ManagerReactive.activeCountFlow] 也有初始
     * tick（来自 invalidations 的 replay=1）—— 所以 first emit 几乎立刻发生。
     */
    fun snapshots(): Flow<Snapshot> = combine(
        queueDao.flowCountByStatus(QueueStatus.PENDING),
        queueDao.flowCountByStatus(QueueStatus.DOWNLOADING),
        queueDao.flowCountByStatus(QueueStatus.SUCCESS),
        queueDao.flowCountByStatus(QueueStatus.FAILED),
        ManagerReactive.activeCountFlow,
    ) { pending, downloading, success, failed, active ->
        Snapshot(
            queuePending = pending,
            queueDownloading = downloading,
            queueSuccess = success,
            queueFailed = failed,
            activeCount = active,
        )
    }.flowOn(Dispatchers.IO)
}
