package ceui.pixiv.ui.download

import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import android.app.Application
import ceui.lisa.activities.Shaft
import ceui.lisa.core.DownloadItem
import ceui.lisa.core.Manager
import ceui.lisa.database.AppDatabase
import ceui.pixiv.db.queue.DownloadQueueDao
import ceui.pixiv.db.queue.QueueStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.delay

/**
 * 三个 tab 共享的 stats 数据源；统一在 IO 线程查 DAO 和 Manager.content。
 */
class DownloadManagerSharedViewModel(app: Application) : AndroidViewModel(app) {

    private val queueDao: DownloadQueueDao by lazy {
        AppDatabase.getAppDatabase(Shaft.getContext()).downloadQueueDao()
    }
    private val downloadDao by lazy {
        AppDatabase.getAppDatabase(Shaft.getContext()).downloadDao()
    }

    data class Snapshot(
        val queuePending: Int,
        val queueDownloading: Int,
        val queueSuccess: Int,
        val queueFailed: Int,
        val activeCount: Int,
    )

    /**
     * 自适应轮询 flow：有活儿在跑就 [BUSY_INTERVAL_MS] 频率刷，
     * 闲下来就拉长到 [IDLE_INTERVAL_MS]（避免空 SQL count() 持续打 IO）。
     * IO 线程查询，UI 收集。Cold flow —— 配合 lifecycleScope.repeatOnLifecycle
     * (STARTED) 自动停跑。
     */
    fun snapshots(): Flow<Snapshot> = flow {
        while (true) {
            val s = Snapshot(
                queuePending = runCatching { queueDao.countByStatus(QueueStatus.PENDING) }.getOrDefault(0),
                queueDownloading = runCatching { queueDao.countByStatus(QueueStatus.DOWNLOADING) }.getOrDefault(0),
                queueSuccess = runCatching { queueDao.countByStatus(QueueStatus.SUCCESS) }.getOrDefault(0),
                queueFailed = runCatching { queueDao.countByStatus(QueueStatus.FAILED) }.getOrDefault(0),
                activeCount = runCatching {
                    // 只计真正在传输中的 page。Manager 现支持 1-5 并发，
                    // 任意时刻 DOWNLOADING 数量上限 = Settings.maxConcurrentDownloads。
                    // 其它 INIT/PAUSED/FAILED 都不算"正在下载"。
                    // Manager.content 非线程安全；snapshot 失败给 0，下个周期会再试。
                    ArrayList(Manager.get().content)
                        .count { it.state == DownloadItem.DownloadState.DOWNLOADING }
                }.getOrDefault(0),
            )
            emit(s)
            // 有 PENDING/DOWNLOADING 队列项 或 有正在传输的 page → 计数会变 → 频繁刷
            // 全空（没活儿） → 拉长间隔；新任务入队会触发 startAll → 下一轮自然
            // 进入 busy 节奏。SUCCESS / FAILED 累积量变化不影响节奏判断（用户不
            // 在乎已完成数字慢几秒变化）。
            val hasWork = s.queuePending + s.queueDownloading + s.activeCount > 0
            delay(if (hasWork) BUSY_INTERVAL_MS else IDLE_INTERVAL_MS)
        }
    }.flowOn(Dispatchers.IO)

    companion object {
        /** 有活在跑：tab 计数和 page 进度需要跟得上 */
        private const val BUSY_INTERVAL_MS = 1500L
        /** 全空：节能，5s 一查就够（队列入队 / Manager 派发都会立刻把状态翻成 busy） */
        private const val IDLE_INTERVAL_MS = 5000L
    }
}
