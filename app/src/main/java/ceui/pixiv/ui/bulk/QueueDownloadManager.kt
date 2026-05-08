package ceui.pixiv.ui.bulk

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.core.DownloadItem
import ceui.lisa.core.Manager
import ceui.lisa.core.ManagerReactive
import ceui.lisa.database.AppDatabase
import ceui.lisa.download.IllustDownload
import ceui.lisa.http.Retro
import ceui.lisa.models.IllustsBean
import ceui.lisa.utils.DownloadLimitTypeUtil
import ceui.loxia.ObjectPool
import ceui.pixiv.db.queue.DownloadQueueDao
import ceui.pixiv.db.queue.DownloadQueueEntity
import ceui.pixiv.db.queue.QueueStatus
import ceui.pixiv.download.maintenance.MediaStoreOrphanCleaner
import com.qmuiteam.qmui.skin.QMUISkinManager
import com.qmuiteam.qmui.widget.dialog.QMUIDialog
import com.qmuiteam.qmui.widget.dialog.QMUIDialogAction
import io.reactivex.Observable
import io.reactivex.schedulers.Schedulers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 批量下载持久化队列消费者 —— 页级并发流水线。
 *
 * # 设计要点（对比旧版"illust 串行 + awaitIllustSettled"）
 *
 *   旧：拿一条 download_queue 行 → 把这个 illust 全部 P 一股脑 [Manager.addTask]
 *       → suspend 等所有 P 从 Manager.content 消失 → 标 SUCCESS → 取下一条。
 *       缺点：多 P illust 会让 [Manager.content] 同时塞 N 条（N >> 并发数），
 *       "下载中" tab 可视行数远超用户配置的并发数；多 illust 永远不会同框。
 *
 *   新：事件驱动主循环，按页粒度补槽。同时刻可以有多条 illust 在飞，
 *       [Manager.content] 里的"活跃 P 数（INIT+DOWNLOADING、未暂停）"严格不超过
 *       [Shaft.sSettings.getMaxConcurrentDownloads]。
 *
 * # 不变量
 *
 *   1. 任何时刻 Manager.content 内 (state=INIT || state=DOWNLOADING) && !paused
 *      的元素数 ≤ maxConcurrentDownloads（[fillSlots] 唯一负责加 P，加之前查 footprint）
 *   2. 一条 download_queue 行最多对应一个 [InFlightIllust]；inflight 在 settle 时移除
 *   3. illust 全部 P 都 settle（成功被 remove / 失败留下 FAILED）后才标 SUCCESS / FAILED
 *   4. canDownloadNow=false 时 consumer 只睡，不 pull / addTask / 不标 DOWNLOADING
 *   5. cold start: [DownloadQueueDao.resurrectInProgress] 把残留 DOWNLOADING 复位 PENDING；
 *      Manager.restore 带回的 P 会在 fillSlots 拉它对应行时走"retry path"被认领回来
 *
 * # 主循环结构
 *
 *   ```
 *   ManagerReactive.contentFlow  ─┐
 *   tickle channel                ─┤── ticker (CONFLATED) ──→ withTimeoutOrNull(POLL_INTERVAL)
 *                                  │
 *                                  ▼
 *   while (!paused && canDownload) {
 *     settleCompleted()    // inflight 全部 P 都没了 → 标 SUCCESS / FAILED
 *     fillSlots()           // 按 maxConc 补 P：先补现有 inflight 剩余 P，再 pull 新行
 *     Manager.startAll()   // 触发 pumpAvailableSlots
 *     ticker.receive(timeout = POLL_INTERVAL)  // 等下一次事件或兜底超时
 *   }
 *   ```
 */
object QueueDownloadManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 外部触发的"队列内容变了，再看一眼"信号；和 ManagerReactive 共同喂给主循环 */
    private val tickle = Channel<Unit>(Channel.CONFLATED)

    private var loopJob: Job? = null
    @Volatile private var paused: Boolean = false

    /**
     * Reactive 暂停状态：UI 端 collect 这个 StateFlow 让"暂停 / 继续"按钮文案
     * 自动跟随真实状态；任何 tab 调 pause() / resume() 都会即时同步。
     */
    private val _pausedFlow = MutableStateFlow(false)
    val pausedFlow: StateFlow<Boolean> get() = _pausedFlow

    /**
     * 队列脏标记 SharedFlow。任何会改变 download_queue 表内容的操作
     * （consumer 的 dao.updateStatus / LegacyBatchEnqueue 的 appendBatch /
     * 用户手动 deleteAll）都 tryEmit(Unit)，UI 端 collect 后用 suspend 的
     * dao.pageActive 拿最新行。
     *
     * 为什么不直接用 Room 的 `Flow<List<...>>`：实测 Room InvalidationTracker
     * 在 ~10 次/秒的连续 UPDATE 序列下**不可靠** —— 首次 emit 之后静默不再
     * 重新 emit，导致 queue tab list 卡在初始状态。这个 SharedFlow 是
     * belt-and-suspenders 兜底，不依赖 Room InvalidationTracker。
     *
     * replay=1 + DROP_OLDEST：新 collector 立刻拿一帧；高频 tick 自动合并。
     * 初始 tryEmit 让首个 collector 不用等 mutation 就有数据。
     */
    val queueListInvalidations: MutableSharedFlow<Unit> = MutableSharedFlow<Unit>(
        replay = 1,
        extraBufferCapacity = 0,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    ).also { it.tryEmit(Unit) }

    // —— 调度参数 ——
    /** 单条 illust 全部 P FAILED 后的最大重试次数（对应 download_queue.retryCount） */
    private const val MAX_RETRY = 3
    /** 主循环兜底 polling：即使 ticker 没动，也每隔一段时间复查一次 */
    private const val POLL_INTERVAL_MS = 800L
    /** canDownloadNow=false 时挂起的 sleep 周期 */
    private const val NETWORK_GATE_SLEEP_MS = 30_000L
    /** 一条 inflight illust 持续无任何 P 状态变化的"停滞"上限；超过则强制按失败收口 */
    private const val STALL_TIMEOUT_MS = 90_000L
    /** resolveIllustsBean 失败 / addTask 异常等"软错误"后的退避 */
    private const val SOFT_ERROR_BACKOFF_MS = 1500L

    private var appContext: Context? = null

    /**
     * 懒加载 dao —— 必须延后到 [loopJob] 协程（IO 线程）首次使用时才触发 DB 打开 + migration，
     * 否则 [init] 会被 [Shaft.onCreate] 在 Main 线程上拖住（v33 首次升级时 ~数百 ms）。
     */
    private val dao: DownloadQueueDao by lazy {
        val ctx = appContext ?: throw IllegalStateException("QueueDownloadManager not initialized")
        AppDatabase.getAppDatabase(ctx).downloadQueueDao()
    }

    /**
     * 当前在飞的 illust 跟踪表。键 = download_queue.id，按 seq 顺序插入
     * （[LinkedHashMap] 保留迭代顺序）。
     *
     * 多 illust 同时在飞时，[fillSlots] 优先把"当前 inflight 中下一页未加"的 P 喂给
     * Manager；都加完了才拉 [DownloadQueueDao.nextByStatusExcluding] 的下一条 PENDING。
     *
     * 该 map 只在 [loopJob] 单一协程内读写，不需要同步。
     */
    private val inFlight: LinkedHashMap<Long, InFlightIllust> = LinkedHashMap()

    private data class InFlightIllust(
        val queueRowId: Long,
        val illustId: Long,
        val bean: IllustsBean,
        val totalPages: Int,
        /** 已经被 [Manager.addTask] 喂出去的页数；下一次要 add 的页索引 = nextPageToAdd */
        var nextPageToAdd: Int,
        /** 入队时 download_queue.retryCount 的快照；finalize 时决定是 bumpRetry 还是 FAILED */
        val retryCountAtPull: Int,
        /** 停滞检测：上一轮 settle 看到的 (uuid:state:nonius) 拼接签名 */
        var lastSignature: String = "",
        /** 上一次 signature 发生变化的墙钟时间 */
        var lastChangeAt: Long = System.currentTimeMillis(),
    )

    fun init(context: Context) {
        if (loopJob != null) return
        appContext = context.applicationContext
        // 注意：这里不要触碰 dao；首次 dao 访问发生在下面的 launch 协程（IO 线程）里。
        loopJob = scope.launch {
            // 冷启动：上次崩溃残留的 DOWNLOADING 全部归位为 PENDING
            runCatching { dao.resurrectInProgress() }
                .onFailure { Timber.tag(TAG).e(it, "resurrectInProgress failed") }

            // issue #857：清理上一次会话遗留的 IS_PENDING=1 行（4.6.0~4.6.4 版本
            // 在网络抖动时下载失败留下的 0 字节 `.pending-NNNN` 文件）。这一时刻
            // 没有任何下载在进行，所以查到的全是孤儿，删除安全。
            runCatching {
                val ctx = appContext ?: return@runCatching
                MediaStoreOrphanCleaner.cleanupPendingOrphans(ctx)
            }.onFailure { Timber.tag(TAG).w(it, "cleanupPendingOrphans failed") }

            // 检查是否有需要恢复的批量下载 —— 不无脑继续，让用户决定
            val pending = runCatching { dao.countByStatus(QueueStatus.PENDING) }.getOrDefault(0)
            if (pending > 0) {
                paused = true   // 默认暂停；等用户在第一个 Activity 弹窗里决定
                _pausedFlow.value = true
                (appContext as? Application)?.let { app ->
                    promptResumeOnFirstActivity(app, pending)
                }
                Timber.tag(TAG).i("cold start: $pending pending items, awaiting user decision")
            } else {
                paused = false
                _pausedFlow.value = false
            }

            // 把 ManagerReactive.contentFlow 的 emit 桥到 ticker，让主循环既能响应
            // 队列变更（tickle）也能响应 Manager 内部状态变更（addTask / state 翻转 /
            // P 完成 remove / clearAll / pumpAvailableSlots / progress）。
            launch {
                ManagerReactive.contentFlow.collect { tickle.trySend(Unit) }
            }

            runMainLoop()
        }
        Timber.tag(TAG).d("QueueDownloadManager initialized")
    }

    /**
     * 主循环。在 IO 线程协程内运行。
     */
    private suspend fun runMainLoop() {
        Timber.tag(TAG).i("[QUEUE-CONSUMER] main loop start")
        while (true) {
            if (paused) {
                tickle.receive()  // 阻塞直到 resume() 触发 tickle
                continue
            }
            if (!DownloadLimitTypeUtil.canDownloadNow()) {
                Timber.tag(TAG).i("[QUEUE-CONSUMER] canDownloadNow=false, holding")
                withTimeoutOrNull(NETWORK_GATE_SLEEP_MS) { tickle.receive() }
                continue
            }

            try {
                settleCompleted()
                val didAdd = fillSlots()
                // 只在真加了 P 时才 startAll —— ManagerReactive.contentFlow 桥到 tickle
                // 后，每个 progress 1% 都会唤醒主循环；空跑也调 startAll 就是每秒做几百
                // 次"全 content 扫描 + flip FAILED→INIT + pump"，纯 CPU 浪费，还会把
                // settle 还没来得及 clearOne 的 FAILED P 短暂回弹到 INIT。
                if (didAdd) {
                    runCatching { Manager.get().startAll() }
                        .onFailure { Timber.tag(TAG).w(it, "[QUEUE-CONSUMER] startAll failed") }
                }
            } catch (t: kotlinx.coroutines.CancellationException) {
                throw t
            } catch (t: Throwable) {
                // 主循环最外层兜底：单轮异常不能让 consumer 死掉
                Timber.tag(TAG).e(t, "[QUEUE-CONSUMER] tick threw")
            }

            // inflight 空且没有 PENDING 了：纯空闲态，停下来等下一次 tickle（不再忙等）
            if (inFlight.isEmpty()) {
                // 进真正的"空闲睡眠"前再修一次 DB —— dao.updateStatus 用 runCatching
                // 包着，某次抛了被吞会让 DB 里残留"幽灵 DOWNLOADING"行（inflight 已经
                // remove，下次 nextByStatusExcluding 也不会拿它）。此刻 inflight 为空，
                // 任何 DB 里的 DOWNLOADING 都是脏数据，统一拉回 PENDING 安全。
                runCatching { dao.resurrectInProgress() }
                    .onFailure { Timber.tag(TAG).w(it, "[QUEUE-CONSUMER] idle resurrect failed") }
                val hasPending = runCatching { dao.countByStatus(QueueStatus.PENDING) > 0 }
                    .getOrDefault(false)
                if (!hasPending) {
                    Timber.tag(TAG).i("[QUEUE-CONSUMER] idle, awaiting next tickle")
                    tickle.receive()
                    continue
                }
            }

            // 有 inflight 在跑：等下一个事件（content 变化 / tickle）或 polling 兜底
            withTimeoutOrNull(POLL_INTERVAL_MS) { tickle.receive() }
        }
    }

    /**
     * 扫一遍 [inFlight] 看哪些 illust 已经全部 P 都 settle 了：
     *   - 全部 page 已加进 Manager.content 过（[InFlightIllust.nextPageToAdd] == totalPages）
     *   - 当前 Manager.content 内对应该 illust 的 page 全是 FAILED（其他状态都 settle 完）
     *
     * 然后据此标 SUCCESS / FAILED / 触发重试。同时做停滞检测，避免下载内核卡死时
     * 永远不出来。
     */
    private suspend fun settleCompleted() {
        if (inFlight.isEmpty()) return

        val snapshot = snapshotManagerContent()
        // illustId.toInt() 因为 IllustsBean.id 是 int，DownloadItem.illust.id 也是 int
        val byIllust: Map<Int, List<DownloadItem>> = snapshot.groupBy { it.illust?.id ?: -1 }

        val now = System.currentTimeMillis()
        val toFinalize = mutableListOf<Pair<InFlightIllust, FinalizeKind>>()

        val iter = inFlight.entries.iterator()
        while (iter.hasNext()) {
            val inf = iter.next().value
            val pages = byIllust[inf.illustId.toInt()] ?: emptyList()

            // 停滞检测：以 (uuid:state:nonius) 列表为签名
            val signature = pages.joinToString("|") { it.uuid + ":" + it.state + ":" + it.nonius }
            if (signature != inf.lastSignature) {
                inf.lastSignature = signature
                inf.lastChangeAt = now
            } else if (now - inf.lastChangeAt > STALL_TIMEOUT_MS) {
                Timber.tag(TAG).w(
                    "[QUEUE-CONSUMER] stall detected illust=${inf.illustId} " +
                            "remaining=${pages.size} totalPages=${inf.totalPages} → finalize as failed"
                )
                toFinalize += inf to FinalizeKind.STALLED
                iter.remove()
                continue
            }

            // 还没把所有 P 都 add 出去 —— 后面 fillSlots 会继续推进，这里先跳过
            if (inf.nextPageToAdd < inf.totalPages) continue

            if (pages.isEmpty()) {
                // 所有 P 都成功被 remove 了
                toFinalize += inf to FinalizeKind.SUCCESS
                iter.remove()
                continue
            }

            // 只要还有 INIT / DOWNLOADING / PAUSED 在 → 还没 settle
            val unsettled = pages.count {
                it.state == DownloadItem.DownloadState.INIT
                        || it.state == DownloadItem.DownloadState.DOWNLOADING
                        || it.isPaused
                        || it.state == DownloadItem.DownloadState.PAUSED
            }
            if (unsettled > 0) continue

            // 剩下的应该都是 FAILED：illust 视为失败
            toFinalize += inf to FinalizeKind.FAILED
            iter.remove()
        }

        if (toFinalize.isEmpty()) return

        for ((inf, kind) in toFinalize) {
            // 复用顶部的 snapshot：每个 illust 的 page 集合互不影响，clearOne illust A
            // 不会改 illust B 的 byIllust[B.id] 视图。省掉每个 finalize 分支再各自
            // snapshotManagerContent() 的同步开销。
            val remainingForThis = byIllust[inf.illustId.toInt()] ?: emptyList()
            when (kind) {
                FinalizeKind.SUCCESS -> {
                    val ok = runCatching {
                        dao.updateStatus(
                            inf.queueRowId, QueueStatus.SUCCESS,
                            finishedAt = System.currentTimeMillis()
                        )
                    }.onFailure {
                        // 关键 DB 失败必须 ERROR 级 —— 这条行会卡 DOWNLOADING，幸好
                        // [runMainLoop] idle 路径里有 resurrectInProgress 兜底。
                        Timber.tag(TAG).e(it, "[QUEUE-CONSUMER] mark SUCCESS failed id=${inf.queueRowId}")
                    }.isSuccess
                    queueListInvalidations.tryEmit(Unit)
                    Timber.tag(TAG).i(
                        "[QUEUE-CONSUMER] illust=${inf.illustId} SUCCESS (db=${if (ok) "ok" else "fail"})"
                    )
                }

                FinalizeKind.FAILED, FinalizeKind.STALLED -> {
                    val canRetry = inf.retryCountAtPull + 1 < MAX_RETRY
                    if (canRetry) {
                        // 标回 PENDING 并 bumpRetry —— 失败的 page 留在 Manager.content 里
                        // 让下一轮 fillSlots 走 retry path（FAILED→INIT 后继续跑）。
                        // STALLED 例外：先把"卡住"的 page 强制 clearOne 让下一轮干净启动。
                        if (kind == FinalizeKind.STALLED) {
                            for (p in remainingForThis) {
                                runCatching { Manager.get().clearOne(p.uuid) }
                                    .onFailure { Timber.tag(TAG).w(it, "clearOne stalled p=${p.uuid}") }
                            }
                        }
                        runCatching { dao.bumpRetry(inf.queueRowId) }
                            .onFailure { Timber.tag(TAG).e(it, "[QUEUE-CONSUMER] bumpRetry failed id=${inf.queueRowId}") }
                        runCatching {
                            dao.updateStatus(
                                inf.queueRowId, QueueStatus.PENDING,
                                err = if (kind == FinalizeKind.STALLED) "stalled" else null
                            )
                        }.onFailure {
                            Timber.tag(TAG).e(it, "[QUEUE-CONSUMER] mark PENDING failed id=${inf.queueRowId}")
                        }
                        queueListInvalidations.tryEmit(Unit)
                        Timber.tag(TAG).i(
                            "[QUEUE-CONSUMER] illust=${inf.illustId} retry " +
                                    "(${inf.retryCountAtPull + 1}/$MAX_RETRY) kind=$kind"
                        )
                    } else {
                        // 终态失败：把残留的 FAILED P 从 content 清掉，免得长期下载活动后
                        // "下载中" tab 堆一堆 FAILED 行 / Manager.startAll 又把它们翻 INIT。
                        for (p in remainingForThis) {
                            runCatching { Manager.get().clearOne(p.uuid) }
                                .onFailure { Timber.tag(TAG).w(it, "clearOne terminal-fail p=${p.uuid}") }
                        }
                        runCatching {
                            dao.updateStatus(
                                inf.queueRowId, QueueStatus.FAILED,
                                err = if (kind == FinalizeKind.STALLED) "stalled" else "all pages failed",
                                finishedAt = System.currentTimeMillis(),
                            )
                        }.onFailure {
                            Timber.tag(TAG).e(it, "[QUEUE-CONSUMER] mark FAILED failed id=${inf.queueRowId}")
                        }
                        queueListInvalidations.tryEmit(Unit)
                        Timber.tag(TAG).w(
                            "[QUEUE-CONSUMER] illust=${inf.illustId} permanently FAILED kind=$kind"
                        )
                    }
                }
            }
        }
    }

    private enum class FinalizeKind { SUCCESS, FAILED, STALLED }

    /**
     * 按 maxConc 补 P 进 Manager.content：
     *   - 优先填现有 inflight illust 的下一未 add 页
     *   - 都填满了再 pull 下一条 PENDING（排除已在 inflight 的）
     *
     * 每轮迭代实时重算 footprint —— addTask 之后 [Manager.content] 已经同步变长，
     * 拿最新 snapshot 重算最稳妥。
     *
     * @return 本轮是否真的把至少一个 P 添进了 [Manager.content]。给主循环判断
     *         "需不需要调 startAll 触发 pump" 用，避免空转的 startAll 风暴。
     */
    private suspend fun fillSlots(): Boolean {
        val maxConcRaw = runCatching { Shaft.sSettings.getMaxConcurrentDownloads() }.getOrDefault(1)
        val maxConc = maxConcRaw.coerceIn(1, 5)

        // 防御性迭代上限。正常 budget = maxConc 次"添加"+ 至多 maxConc 次"拉行"
        // = 2*maxConc；+8 给 retry path 偶发返回 false 重拉的余量。命中说明上面
        // 的逻辑出 bug 了，超出就停 + 警告，等下一 tick。
        var didAdd = false
        var safety = maxConc * 2 + 8
        while (safety-- > 0) {
            if (!DownloadLimitTypeUtil.canDownloadNow() || paused) return didAdd

            val snapshot = snapshotManagerContent()
            val activeFootprint = snapshot.count {
                !it.isPaused && (it.state == DownloadItem.DownloadState.INIT
                        || it.state == DownloadItem.DownloadState.DOWNLOADING)
            }
            if (activeFootprint >= maxConc) return didAdd

            // 1) 现有 inflight 还有未 add 的 P → 添一个
            val infNeedingPage = inFlight.values.firstOrNull { it.nextPageToAdd < it.totalPages }
            if (infNeedingPage != null) {
                val i = infNeedingPage.nextPageToAdd
                val ok = runCatching {
                    val di = DownloadItem(infNeedingPage.bean, i)
                    di.url = IllustDownload.getUrl(infNeedingPage.bean, i)
                    di.showUrl = IllustDownload.getShowUrl(infNeedingPage.bean, i)
                    Manager.get().addTask(di)
                }.onFailure {
                    Timber.tag(TAG).w(
                        it, "[QUEUE-CONSUMER] addTask failed illust=${infNeedingPage.illustId} page=$i"
                    )
                }.isSuccess
                infNeedingPage.nextPageToAdd = i + 1
                if (ok) didAdd = true
                continue
            }

            // 2) inflight 都满了 P，拉下一条 PENDING
            val excludeIds = inFlight.keys.toList()
            val row = runCatching {
                if (excludeIds.isEmpty()) dao.nextByStatus(QueueStatus.PENDING)
                else dao.nextByStatusExcluding(QueueStatus.PENDING, excludeIds)
            }.getOrNull() ?: return didAdd  // 没有更多 PENDING 可拉

            // pullRowToInFlight 返回 false 时该行已就地 SUCCESS / FAILED / PENDING 处理；
            // 下一轮 while 自然继续尝试。retry path 进 inflight 后 footprint 会被
            // existing pages 顶起，下一轮 footprint 检查就 break。
            pullRowToInFlight(row)
        }
        if (safety <= 0) {
            Timber.tag(TAG).w("[QUEUE-CONSUMER] fillSlots safety exhausted (maxConc=$maxConc)")
        }
        return didAdd
    }

    /**
     * 把 [row] 转成 inflight 入口；对 GIF / API 解析失败 / 已在 content 的 retry path
     * 各自处理。返回 true 表示成功放入 inflight，false 表示这条已就地处理（GIF skip /
     * 错误已记账），调用方应该 continue 拉下一条。
     */
    private suspend fun pullRowToInFlight(row: DownloadQueueEntity): Boolean {
        val bean = try {
            resolveIllustsBean(row)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "[QUEUE-CONSUMER] resolveBean failed illust=${row.illustId}")
            if (row.retryCount + 1 < MAX_RETRY) {
                runCatching { dao.bumpRetry(row.id) }
                runCatching { dao.updateStatus(row.id, QueueStatus.PENDING, err = e.message) }
                delay(SOFT_ERROR_BACKOFF_MS)
            } else {
                runCatching {
                    dao.updateStatus(
                        row.id, QueueStatus.FAILED, err = e.message,
                        finishedAt = System.currentTimeMillis()
                    )
                }
            }
            queueListInvalidations.tryEmit(Unit)
            return false
        }

        if (bean.isGif) {
            // GIF 走 ugoira zip + 解压，不适合走本队列；直接当 SUCCESS 跳过。
            runCatching {
                dao.updateStatus(
                    row.id, QueueStatus.SUCCESS, finishedAt = System.currentTimeMillis()
                )
            }
            queueListInvalidations.tryEmit(Unit)
            Timber.tag(TAG).i("[QUEUE-CONSUMER] skip gif illust=${row.illustId}")
            return false
        }

        val pageCount = if (bean.page_count <= 0) 1 else bean.page_count

        // 标 DOWNLOADING（入 inflight 之前 —— 这样 nextByStatusExcluding 排除生效）
        runCatching { dao.updateStatus(row.id, QueueStatus.DOWNLOADING) }
        queueListInvalidations.tryEmit(Unit)

        // Retry path 检测：Manager.content 里残留这个 illust 的 P
        // （来源：上轮 bumpRetry → PENDING；或冷启动 Manager.restore 带回的）
        val target = row.illustId.toInt()
        val existing = snapshotManagerContent().filter { it.illust?.id == target }

        val nextPageToAddInit = if (existing.isNotEmpty()) {
            // 把所有非 INIT 状态翻 INIT，让 pumpAvailableSlots 重新挑选：
            //   - FAILED：上轮真失败的 P
            //   - DOWNLOADING：**冷启动 Manager.restore 带回的 stranded 状态**——
            //     原 Disposable 已随进程消失，pump 又把 DOWNLOADING 算进 activeCount
            //     但 getFirstReady 只挑 INIT，不翻就永远占着槽位卡死整队
            //   - PAUSED：之前用户手动暂停过，现在重新接管要抹掉
            // SUCCESS 不会出现（complete 时已 content.remove）；INIT 已经 ready，跳过。
            //
            // 直接改 DownloadItem 字段不走 synchronized：跟 Manager.startAll 内部循环
            // 同源做法（state 是 volatile-style 单值，顺序不强）；只在 IO 线程做。
            for (p in existing) {
                val s = p.state
                if (s == DownloadItem.DownloadState.FAILED
                    || s == DownloadItem.DownloadState.DOWNLOADING
                    || s == DownloadItem.DownloadState.PAUSED) {
                    p.setState(DownloadItem.DownloadState.INIT)
                }
                if (p.isPaused) p.setPaused(false)
            }
            ManagerReactive.invalidate()
            // 已知 gap：existing.size 可能 < pageCount —— 比如上轮 SUCCESS 被 remove
            // 的 P 不在 content 里、但又有别的途径让其它 P 缺席（如 clearOne）。我们
            // 只能用 existing 这部分继续跑，缺的 P 不补；老 awaitIllustSettled 也是
            // 同样行为，没回归。要补齐需要按 page index 精确比对，目前不值得。
            pageCount
        } else 0

        inFlight[row.id] = InFlightIllust(
            queueRowId = row.id,
            illustId = row.illustId,
            bean = bean,
            totalPages = pageCount,
            nextPageToAdd = nextPageToAddInit,
            retryCountAtPull = row.retryCount,
        )
        Timber.tag(TAG).i(
            "[QUEUE-CONSUMER] TAKE id=${row.id} illustId=${row.illustId} " +
                    "pageCount=$pageCount existing=${existing.size} retry=${row.retryCount}"
        )
        return true
    }

    // —— Cold-start dialog ——

    /**
     * 注册 ActivityLifecycleCallbacks，等到第一个真正进入 RESUMED 的 Activity，弹 QMUI
     * dialog 询问用户是否继续。回调只触发一次，弹完即注销。
     *
     * Activity 必须是用户可见的 (state RESUMED) 且没在 finishing/destroyed —— 否则
     * QMUIDialog 会在错误的窗口上 attach。
     */
    private fun promptResumeOnFirstActivity(app: Application, pendingCount: Int) {
        val cb = object : Application.ActivityLifecycleCallbacks {
            @Volatile var fired = false
            override fun onActivityResumed(activity: Activity) {
                if (fired) return
                if (activity.isFinishing || activity.isDestroyed) return
                fired = true
                app.unregisterActivityLifecycleCallbacks(this)
                showResumePrompt(activity, pendingCount)
            }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        }
        app.registerActivityLifecycleCallbacks(cb)
    }

    private fun showResumePrompt(activity: Activity, pendingCount: Int) {
        // QMUIDialog 必须在主线程展示
        activity.runOnUiThread {
            if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
            try {
                QMUIDialog.MessageDialogBuilder(activity)
                    .setTitle(R.string.bulk_resume_prompt_title)
                    .setMessage(activity.getString(R.string.bulk_resume_prompt_message, pendingCount))
                    .setSkinManager(QMUISkinManager.defaultInstance(activity))
                    .addAction(0, activity.getString(R.string.bulk_resume_prompt_decline), QMUIDialogAction.ACTION_PROP_NEUTRAL) { d, _ ->
                        // 保持 paused —— 用户可去 下载管理 → 批量队列 手动点 "继续"
                        Timber.tag(TAG).i("user declined cold-start resume; staying paused")
                        d.dismiss()
                    }
                    .addAction(0, activity.getString(R.string.bulk_resume_prompt_continue)) { d, _ ->
                        Timber.tag(TAG).i("user confirmed cold-start resume; pending=$pendingCount")
                        resume()
                        d.dismiss()
                    }
                    .show()
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "failed to show resume prompt; auto-resuming as fallback")
                // 极端情况（窗口已坏）下 fallback 到自动恢复，免得任务永远卡在 paused
                resume()
            }
        }
    }

    // —— 公共 API（被 Fragment / 其他 enqueue 入口调用） ——

    fun notifyNewItems() { tickle.trySend(Unit) }
    fun pause() {
        paused = true
        _pausedFlow.value = true
        Timber.tag(TAG).i("[QUEUE-CONSUMER] pause() called")
    }
    fun resume() {
        paused = false
        _pausedFlow.value = false
        val sent = tickle.trySend(Unit).isSuccess
        Timber.tag(TAG).i("[QUEUE-CONSUMER] resume() called, tickle.trySend=$sent")
    }
    fun isPaused(): Boolean = paused

    // —— 工具 ——

    /** Manager.content 是非线程安全 List，且主线程会 remove。安全 snapshot 含重试。 */
    private fun snapshotManagerContent(): List<DownloadItem> {
        for (attempt in 1..5) {
            try {
                return Manager.get().contentSnapshot()
            } catch (e: Exception) {
                if (attempt == 5) {
                    Timber.tag(TAG).w(e, "snapshotManagerContent failed after retries")
                    return emptyList()
                }
            }
        }
        return emptyList()
    }

    /**
     * 解析 [row] 对应的 [IllustsBean]，优先级：
     *   1. ObjectPool 命中（用户最近浏览过 / 同一会话之前已解析过）
     *   2. 反序列化 [DownloadQueueEntity.illustGson] —— 入队时存进 DB 的 JSON，
     *      冷启动 100+ PENDING 都靠这条路，0 次网络请求
     *   3. 回退 API getIllustByID —— 只有老版本入队的行 illustGson=null 才走，
     *      不会 429（量极少）
     * 解析成功的都灌一次 ObjectPool，下一次同 id 命中第 1 步。
     */
    private suspend fun resolveIllustsBean(row: DownloadQueueEntity): IllustsBean {
        val illustId = row.illustId
        // 1) 内存池
        val cached = runCatching { ObjectPool.getIllust(illustId).value }.getOrNull()
        if (cached != null) return cached

        // 2) DB 里入队时存的 JSON —— 主路径
        val gson = row.illustGson
        if (!gson.isNullOrEmpty()) {
            val parsed = runCatching { Shaft.sGson.fromJson(gson, IllustsBean::class.java) }
                .getOrNull()
            if (parsed != null) {
                withContext(Dispatchers.Main.immediate) {
                    runCatching { ObjectPool.updateIllust(parsed) }
                }
                return parsed
            }
            Timber.tag(TAG).w("[QUEUE-CONSUMER] illustGson parse failed illust=$illustId, falling back to API")
        }

        // 3) 老行 fallback：API 拉一次，这一路不应该是常态
        val resp = Retro.getAppApi().getIllustByID(illustId).awaitFirstSafe()
        val bean = resp.illust
            ?: throw IllegalStateException("getIllustByID returned null for $illustId")
        withContext(Dispatchers.Main.immediate) {
            runCatching { ObjectPool.updateIllust(bean) }
        }
        return bean
    }

    private const val TAG = "QueueDownloadManager"
}

// awaitFirstSafe 已迁到 BulkObjectFetcher.kt 内的 internal 扩展，本包共享。
