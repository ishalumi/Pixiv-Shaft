package ceui.pixiv.ui.download

import android.graphics.Color
import android.os.Bundle
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.core.DownloadItem
import ceui.lisa.core.Manager
import ceui.lisa.core.ManagerReactive
import ceui.lisa.database.AppDatabase
import ceui.lisa.download.FileSizeUtil
import ceui.lisa.utils.GlideUtil
import ceui.pixiv.db.queue.DownloadQueueDao
import ceui.pixiv.ui.bulk.QueueDownloadManager
import com.bumptech.glide.Glide
import com.qmuiteam.qmui.skin.QMUISkinManager
import com.qmuiteam.qmui.widget.dialog.QMUIDialog
import com.qmuiteam.qmui.widget.dialog.QMUIDialogAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * V3 风格 "正在下载" —— 订阅 [ManagerReactive.contentFlow]。
 *
 * 数据源是 reactive 的：Manager 任何 mutation（addTask / state 翻转 /
 * progress 1% / complete / clearAll / pumpAvailableSlots / ...）后都会
 * `invalidate()`，本 fragment 的 collector 立刻拿到当前 snapshot。
 * 完全替代旧的 1s 轮询 + DOWNLOAD_ING 广播 + tickle channel 三层架构 ——
 * 0 timer、0 broadcast。
 *
 * 并发下载（Settings.maxConcurrentDownloads，默认 1，上限 5）：
 *   - 任意时刻 DOWNLOADING 数量 ≤ 用户配置的并发数
 *   - 其余可下载的 page 处于 INIT（等待）
 *   - DOWNLOADING 卡：完整不透明 + 蓝色进度条 + 实时大小/百分比
 *   - INIT 卡：半透明 0.55 + 隐藏进度条/大小 + 文字 "等待中…"
 *   - 顶部状态行明确写 "N 正在 · M 等待"
 *   - 运行时 invariant：snapshot 里 DOWNLOADING > 配置上限 直接 warn 到日志
 */
class ActiveListV3Fragment : Fragment() {

    private val adapter = ActiveAdapterV3()
    private var statusHeader: TextView? = null
    private val queueDao: DownloadQueueDao by lazy {
        AppDatabase.getAppDatabase(Shaft.getContext()).downloadQueueDao()
    }

    /**
     * 1s 采样的整体下载速度（B/s）。0 表示当前没有可观测的字节增量（空闲 / 暂停 / 全失败）。
     *
     * 由 [SpeedSampler] 每 1s 计算一次，driver coroutine 写、status 渲染 collector
     * 读，所以是 [MutableStateFlow] 而不是普通字段 —— [combine] 让"内容变更"和
     * "速度更新"任一发生时 status header 都会刷新。
     */
    private val speedBpsFlow = MutableStateFlow(0L)

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_download_list_v3, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val list = view.findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = adapter
        // RV 自身高度由父布局固定（fragment 内全屏 match_parent），跟 adapter 内容
        // 多少无关。setHasFixedSize(true) 跳过每次 notifyItem* 重测自身。
        list.setHasFixedSize(true)
        // 完全去掉 ItemAnimator —— 试过 sample 节流 + 调 moveDuration 后仍然 choppy，
        // OnePlus / 这套 layout 上 DefaultItemAnimator 的预测式 move 动画就是不健康。
        // 直接 null 化：item 完成 → 瞬间消失；2/3 上滑 → 瞬间 snap；4 进入 → 瞬间显示。
        // 没有动画就没有"被打断的动画"。代价：完成事件不再被视觉庆祝（aria2 / Chrome
        // 下载列表也是这风格，可以接受）。进度条 / 数字 / 速度仍然实时跟手刷新。
        list.itemAnimator = null

        val empty = view.findViewById<View>(R.id.emptyState)
        view.findViewById<TextView>(R.id.emptyTitle).text = getString(R.string.dlmgr_active_empty_title)
        view.findViewById<TextView>(R.id.emptyHint).text = getString(R.string.dlmgr_active_empty_hint)

        // 顶部状态行（占用 btn3 这个空位 button 改为只读 TextView 风格）
        statusHeader = view.findViewById<Button>(R.id.btn3).apply {
            text = "—"
            isEnabled = false
            // 视觉去按钮化
            setTextColor(Color.parseColor("#7CB668"))
        }

        // 操作 bar —— mutation 后 Manager 内部会 invalidate，contentFlow
        // 自动 emit 新快照，UI 即时刷新；不需要业务层手动 tickle / 刷 UI。
        val btnResume = view.findViewById<Button>(R.id.btn1).apply {
            text = getString(R.string.dlmgr_active_action_resume_all)
            setOnClickListener {
                Manager.get().startAll()
                QueueDownloadManager.resume()
            }
        }
        val btnPause = view.findViewById<Button>(R.id.btn2).apply {
            text = getString(R.string.dlmgr_active_action_pause_all)
            setOnClickListener {
                Manager.get().stopAll()
                QueueDownloadManager.pause()
            }
        }
        val btnClear = view.findViewById<Button>(R.id.btn4).apply {
            text = getString(R.string.dlmgr_active_action_clear)
            // 联动：清空 active 同时把批量队列 DB 也清掉，避免用户清完 active 又被
            // 队列消费者重新填回去，看起来"清不掉"。
            //
            // 加确认 dialog：destructive 操作不应一键直行 —— 用户误点损失大。
            setOnClickListener {
                showClearConfirmDialog {
                    Manager.get().clearAll()
                    viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                        runCatching { queueDao.deleteAll() }
                    }
                }
            }
        }

        // Reactive: ManagerReactive.contentFlow 在 Manager 任何 mutation 后
        // 自动推一帧（progress / state / add / remove / clearAll 全覆盖）。
        // 0 timer，0 broadcast，纯事件驱动。
        //
        // 没有 ItemAnimator 后不再需要 sample 节流（之前是为了保护动画时间窗口）。
        // conflate() 兜 backpressure：collector 慢的时候上游高频 emit 会被合并成
        // "最后一帧"，不会堆积。combine 上游的 contentFlow 本身就是 replay=1 +
        // DROP_OLDEST 的 SharedFlow，已经天然 conflate；这里再 conflate 一次双保险。
        //
        // flowOn(Default)：snapshot copy + count 计算放后台线程，UI 不挡帧。
        // combine speedBpsFlow：让"内容变更"和"速度采样"任一发生时 status header 都会刷新。
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                ManagerReactive.contentFlow
                    .combine(speedBpsFlow) { snapshot, speedBps -> snapshot to speedBps }
                    .conflate()
                    .flowOn(Dispatchers.Default)
                    .collect { (snapshot, speedBps) ->
                        val downloadingCount = snapshot.count { it.state == DownloadItem.DownloadState.DOWNLOADING }
                        val initCount = snapshot.count { it.state == DownloadItem.DownloadState.INIT }
                        val pausedCount = snapshot.count { it.state == DownloadItem.DownloadState.PAUSED }
                        val failedCount = snapshot.count { it.state == DownloadItem.DownloadState.FAILED }

                        // 运行时不变量：DOWNLOADING 数量永远不应该超过绝对上限 5。
                        // 注意不要拿当前 maxConcurrent 比 —— 用户从 5 调到 2 后那 5 条
                        // 在传的不会立即被掐，会自然消化，期间 downloadingCount > 2
                        // 是正常现象，不能误报。
                        if (downloadingCount > 5) {
                            Timber.tag(TAG).w(
                                "INVARIANT: ${downloadingCount} DOWNLOADING > absolute max 5! " +
                                    snapshot.filter { it.state == DownloadItem.DownloadState.DOWNLOADING }
                                        .joinToString { "${it.uuid}/${it.illust?.id}" }
                            )
                        }

                        // 顶部状态行：N 正在 · M 等待 · ... · 1.2 MB/s
                        // 速度只在 downloadingCount > 0 且采样到非零时显示，避免暂停 / 空闲态
                        // 显示一个"陈旧"的速度值。
                        val parts = buildList {
                            if (downloadingCount > 0) add(getString(R.string.dlmgr_active_status_downloading_n, downloadingCount))
                            if (initCount > 0) add(getString(R.string.dlmgr_active_status_waiting_n, initCount))
                            if (pausedCount > 0) add(getString(R.string.dlmgr_active_status_paused_n, pausedCount))
                            if (failedCount > 0) add(getString(R.string.dlmgr_active_status_failed_n, failedCount))
                            if (downloadingCount > 0 && speedBps > 0) add(formatSpeed(speedBps))
                        }
                        statusHeader?.text = if (parts.isEmpty()) "—" else parts.joinToString(" · ")

                        adapter.submit(snapshot)
                        empty.visibility = if (snapshot.isEmpty()) View.VISIBLE else View.GONE
                        // 没有任何活跃任务时把操作按钮置灰，避免用户在空列表上反复点
                        val hasWork = snapshot.isNotEmpty()
                        btnResume.isEnabled = hasWork
                        btnResume.alpha = if (hasWork) 1f else 0.4f
                        btnPause.isEnabled = hasWork
                        btnPause.alpha = if (hasWork) 1f else 0.4f
                        btnClear.isEnabled = hasWork
                        btnClear.alpha = if (hasWork) 1f else 0.4f
                    }
            }
        }

        // 速度采样：每 1s 算一次整体网速。和上面的内容流是两条独立 coroutine，
        // 通过 [speedBpsFlow] 解耦 —— driver 写、内容流的 combine 读。
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                val sampler = SpeedSampler()
                while (isActive) {
                    val snapshot = Manager.get().contentSnapshot()
                    speedBpsFlow.value = sampler.sample(snapshot)
                    delay(SPEED_SAMPLE_INTERVAL_MS)
                }
            }
        }
    }

    private fun showClearConfirmDialog(onConfirm: () -> Unit) {
        val act = activity ?: return
        if (act.isFinishing || act.isDestroyed) return
        QMUIDialog.MessageDialogBuilder(act)
            .setTitle(R.string.dlmgr_clear_active_queue_title)
            .setMessage(R.string.dlmgr_clear_active_queue_message)
            .setSkinManager(QMUISkinManager.defaultInstance(act))
            .addAction(R.string.cancel) { d, _ -> d.dismiss() }
            .addAction(0, R.string.sure, QMUIDialogAction.ACTION_PROP_NEGATIVE) { d, _ ->
                d.dismiss()
                onConfirm()
            }
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // ⚠️ 不调 Manager.clearCallback() —— 那会清掉别的页面（如 ArtworkV3Fragment）的回调。
        //    我们 setCallback 用的 key=item.uuid，新 bind 会覆盖旧的，无需主动清。
    }

    companion object {
        private const val TAG = "ActiveListV3"
        /** 整体下载速度采样间隔（毫秒）。1s 比较接近用户对"实时网速"的预期 */
        private const val SPEED_SAMPLE_INTERVAL_MS = 1000L
    }
}

/**
 * 整体下载速度采样器 —— 不修改 Manager.java 的字节计数路径，纯靠 fragment 端
 * 对 [DownloadItem.currentSize] 做差分。
 *
 * # 算法
 * 每次 [sample] 拍一张 (uuid → currentSize/totalSize) 表，跟上次对比：
 *   - 还在的 uuid：增量 = 当前 currentSize - 上次 currentSize
 *   - 上次有本次消失的 uuid：**仅当上次 currentSize 接近 totalSize**（视为自然完成）
 *     才补 totalSize - 上次 currentSize；否则视为用户 clearOne / clearAll 取消，
 *     不补差，避免取消瞬间速度虚假飙到 GB/s。
 *   - 本次新出现的 uuid：增量 = 0（首次见到，没有基线可比）
 *
 * 速度 = 总增量 / 时间差。负数截到 0。
 *
 * # 为什么不在 Manager 里加全局字节计数
 * 1. 改 Manager.java 的下载内核是高频热路径（每 ~8KB 一次），AtomicLong 虽然便宜
 *    但要新加 import + 暴露 getter，跨 Java/Kotlin 边界。
 * 2. 这里 1s 采样足够：UI 刷新本身就是 1s 量级，没必要追字节级精度。
 * 3. 跳过 cache hit / SAF skip 等无字节传输路径自然不计入 —— item 直接 currentSize=0
 *    然后 disappear，按下面的"完成阈值"判定也不会误算（currentSize=0 不达 0.95×total）。
 */
private class SpeedSampler {
    private data class Sample(val currentSize: Long, val totalSize: Long)

    private var prevByUuid: Map<String, Sample> = emptyMap()
    private var prevTimeMs: Long = 0L

    /**
     * 拍一帧并返回当前速度（B/s）。snapshot 不需要按状态过滤 —— 非
     * DOWNLOADING 的 currentSize 不会涨，差分自然 0；省一次 filter。
     */
    fun sample(snapshot: List<ceui.lisa.core.DownloadItem>): Long {
        val now = System.currentTimeMillis()
        val cur: Map<String, Sample> = snapshot.associate { it.uuid to Sample(it.currentSize, it.totalSize) }

        // 第一次采样：只记录基线，不算速度（没有 prev 时间点可比）
        if (prevTimeMs == 0L) {
            prevByUuid = cur
            prevTimeMs = now
            return 0L
        }

        var deltaBytes = 0L
        for ((uuid, s) in cur) {
            val prev = prevByUuid[uuid]
            // 新出现的 uuid：prev?.currentSize ?: 0 → 增量 = currentSize
            val inc = s.currentSize - (prev?.currentSize ?: 0L)
            if (inc > 0) deltaBytes += inc
        }
        for ((uuid, s) in prevByUuid) {
            if (cur.containsKey(uuid)) continue
            // 消失的 item —— 仅当上次进度已经过门槛（接近 totalSize）才视为自然完成
            // 补差。门槛 95% 既能覆盖 progress 回调粗粒度（每 1% 一次）让最后一档没
            // 上报满格的情况，又能把"早早就被用户 clearOne 掉"的取消挡在外面（取消
            // 通常在 currentSize 远小于 totalSize 时发生）。
            if (s.totalSize > 0 && s.currentSize >= (s.totalSize * 95L / 100L)) {
                val inc = s.totalSize - s.currentSize
                if (inc > 0) deltaBytes += inc
            }
        }

        val deltaMs = (now - prevTimeMs).coerceAtLeast(1L)
        val speedBps = (deltaBytes * 1000L / deltaMs).coerceAtLeast(0L)

        prevByUuid = cur
        prevTimeMs = now
        return speedBps
    }
}

/** 把 B/s 整数格式化成人类可读 —— 跟 [FileSizeUtil.formatFileSize] 一样的进位规则，再追加 "/s" */
private fun formatSpeed(bps: Long): String =
    if (bps <= 0) "0B/s" else "${ceui.lisa.download.FileSizeUtil.formatFileSize(bps)}/s"

/**
 * 关键陷阱：[Manager] 原地修改 [DownloadItem]（setNonius / setPaused / ...），
 * 不会创建新对象。如果 ListAdapter 直接拿 DownloadItem 做 DiffUtil 元素，旧
 * snapshot 列表和新 snapshot 列表持有**同一份对象引用**，DiffUtil 调
 * areContentsTheSame 时 a 和 b 是同一个 obj，所有字段比较恒等 → 永不发现变化
 * → 进度条视觉上冻死。这是 ListAdapter + 可变模型的经典 aliasing 陷阱。
 *
 * 修法：每次 submit 时把 DownloadItem 的"渲染相关字段"拍进一个 immutable
 * data class [ActiveSnapshot]。新旧 ActiveSnapshot 之间字段比较真实反映变化；
 * data class auto equals 让 DiffUtil 干净工作。
 */
private data class ActiveSnapshot(
    /** 持有原 DownloadItem 引用，让 click handler 能拿到 uuid 给 Manager 调命令 */
    val item: DownloadItem,
    val state: Int,
    val isPaused: Boolean,
    val nonius: Int,
    val currentSize: Long,
    val totalSize: Long,
    val name: String?,
    val showUrl: String?,
) {
    companion object {
        fun of(d: DownloadItem) = ActiveSnapshot(
            item = d,
            state = d.state,
            isPaused = d.isPaused,
            nonius = d.nonius,
            currentSize = d.currentSize,
            totalSize = d.totalSize,
            name = d.name,
            showUrl = d.showUrl,
        )
    }
}

/**
 * DiffUtil 让"看起来没变"的 item 不重 bind。issue: 1s polling 用
 * notifyDataSetChanged() 全量重 bind，每次都重 Glide.load 缩略图 → 视觉上闪烁
 * （即使在暂停态也闪，因为 polling 不区分状态）。
 *
 * 行为：
 *   - 标识相同（uuid）+ 内容完全一致 → 不 bind
 *   - 仅进度/状态变化 → 走 payload，仅刷新进度条/百分比/大小/徽章
 *   - 缩略图 URL 没变就根本不调 Glide.load
 */
private object ActiveDiff : DiffUtil.ItemCallback<ActiveSnapshot>() {
    override fun areItemsTheSame(a: ActiveSnapshot, b: ActiveSnapshot): Boolean =
        a.item.uuid == b.item.uuid
    override fun areContentsTheSame(a: ActiveSnapshot, b: ActiveSnapshot): Boolean = a == b
    override fun getChangePayload(oldItem: ActiveSnapshot, newItem: ActiveSnapshot): Any = PROGRESS_PAYLOAD
}

private const val PROGRESS_PAYLOAD = "progress"

private class ActiveAdapterV3 : ListAdapter<ActiveSnapshot, ActiveAdapterV3.VH>(ActiveDiff) {

    fun submit(newItems: List<DownloadItem>) {
        // 把可变 DownloadItem 拍成 immutable snapshot 后再交给 ListAdapter，
        // 让 DiffUtil 拿到的新旧元素是不同对象、字段比较真实反映变化。
        submitList(newItems.map { ActiveSnapshot.of(it) })
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.cell_download_active_v3, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, pos: Int) {
        bindFull(h, getItem(pos))
    }

    override fun onBindViewHolder(h: VH, pos: Int, payloads: List<Any>) {
        if (payloads.isEmpty()) {
            bindFull(h, getItem(pos))
        } else {
            // payload-only：进度/状态变了，但缩略图、文件名没变
            bindStateAndProgress(h, getItem(pos))
        }
    }

    private fun bindFull(h: VH, snap: ActiveSnapshot) {
        h.taskName.text = snap.name
        bindStateAndProgress(h, snap)

        // 缩略图：原始 showUrl 没变就不 Glide.load —— 这是消除闪烁的关键。
        // 用原始 String 比较（GlideUrl 没实现稳定的 equals，比较起来不靠谱）
        val newShowUrl = snap.showUrl?.takeIf { !TextUtils.isEmpty(it) }
        if (newShowUrl != h.lastLoadedUrl) {
            if (!newShowUrl.isNullOrEmpty()) {
                Glide.with(h.thumb)
                    .load(GlideUtil.getUrl(newShowUrl))
                    .placeholder(android.R.color.transparent)
                    .into(h.thumb)
            } else {
                Glide.with(h.thumb).clear(h.thumb)
                h.thumb.setImageDrawable(null)
            }
            h.lastLoadedUrl = newShowUrl
        }

        // 暂停/继续 + 取消（每次 full bind 重设，保证 lambda 引用最新 item）
        h.pauseBtn.setOnClickListener {
            // 即时反馈：snapshot 列表是 immutable，无法靠 notifyItemChanged 让
            // payload-bind 看到新状态（snapshot 还是旧的）。直接操作 view —
            // 下一轮 1s polling 来重 snapshot 时 DiffUtil 会再 reconcile 一次。
            val live = snap.item
            val wasPaused = live.isPaused
            if (wasPaused) Manager.get().startOne(live.uuid)
            else Manager.get().stopOne(live.uuid)
            h.pauseBtn.setImageResource(
                if (wasPaused) R.drawable.ic_baseline_pause_24
                else R.drawable.ic_baseline_play_arrow_24
            )
        }
        h.cancelBtn.setOnClickListener {
            // Manager.clearOne 会从 content 移除该 item，下一轮 polling 通过
            // DiffUtil 自动消失，不需要手动通知。
            Manager.get().clearOne(snap.item.uuid)
        }

        // 故意不再 Manager.get().setCallback(item.uuid) { ... } —— 之前每次 bind 都
        // 注册一个 lambda，Manager.mCallback HashMap 按 uuid 存且永远不清，长跑下
        // 100000+ 闭包会持有 ViewHolder 引用 → 内存堆积 OOM。
        // 进度更新依赖 1s polling + DiffUtil payload 触发 bindStateAndProgress。
    }

    private fun bindStateAndProgress(h: VH, snap: ActiveSnapshot) {
        // —— 状态分类决定视觉权重 ——
        val isActive = snap.state == DownloadItem.DownloadState.DOWNLOADING
        val isWaiting = snap.state == DownloadItem.DownloadState.INIT
        val isPaused = snap.isPaused || snap.state == DownloadItem.DownloadState.PAUSED
        val isFailed = snap.state == DownloadItem.DownloadState.FAILED

        h.itemView.alpha = if (isActive || isFailed) 1.0f else 0.55f

        h.progress.visibility = if (isActive) View.VISIBLE else View.GONE
        h.percentText.visibility = if (isActive) View.VISIBLE else View.GONE
        if (isActive) {
            h.progress.progress = snap.nonius
            h.percentText.text = "${snap.nonius}%"
        }

        when {
            isActive -> {
                // totalSize=0 时（响应没 Content-Length）只显示已下载字节，让用户
                // 至少看到"在动"；否则照常 currentSize / totalSize。
                h.sizeText.text = when {
                    snap.totalSize > 0 -> String.format(
                        "%s / %s",
                        FileSizeUtil.formatFileSize(snap.currentSize),
                        FileSizeUtil.formatFileSize(snap.totalSize)
                    )
                    snap.currentSize > 0 -> String.format(
                        "%s / —",
                        FileSizeUtil.formatFileSize(snap.currentSize)
                    )
                    else -> "—"
                }
            }
            isWaiting -> h.sizeText.setText(R.string.dlmgr_active_size_waiting)
            isPaused -> h.sizeText.setText(R.string.dlmgr_active_size_paused)
            isFailed -> h.sizeText.setText(R.string.dlmgr_active_size_failed)
            else -> h.sizeText.text = "—"
        }

        // DOWNLOADING 用稍鲜亮的绿（活跃感），跟 DONE 的 #7CB668（柔和"已完成"绿）
        // 区分开 —— 都用绿但深浅有别：在跑的更亮，结束的更收。
        val (label, color) = when {
            isActive -> "DOWNLOADING" to "#4CAF50"
            isPaused -> "PAUSED" to "#FFB454"
            isFailed -> "FAILED" to "#FF8B8B"
            isWaiting -> "QUEUED" to "#9DA3AB"
            snap.state == DownloadItem.DownloadState.SUCCESS -> "DONE" to "#7CB668"
            else -> "—" to "#9DA3AB"
        }
        h.stateBadge.text = label
        h.stateBadge.setTextColor(Color.parseColor(color))

        h.pauseBtn.setImageResource(
            if (snap.isPaused) R.drawable.ic_baseline_play_arrow_24
            else R.drawable.ic_baseline_pause_24
        )
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val thumb: ImageView = v.findViewById(R.id.thumb)
        val taskName: TextView = v.findViewById(R.id.taskName)
        val sizeText: TextView = v.findViewById(R.id.sizeText)
        val progress: ProgressBar = v.findViewById(R.id.progress)
        val stateBadge: TextView = v.findViewById(R.id.stateBadge)
        val percentText: TextView = v.findViewById(R.id.percentText)
        val pauseBtn: ImageView = v.findViewById(R.id.pauseBtn)
        val cancelBtn: ImageView = v.findViewById(R.id.cancelBtn)
        /** 上次 Glide.load 的 URL（含 referer 拼接后）；URL 不变就跳过加载，消除闪烁 */
        var lastLoadedUrl: String? = null
    }
}
