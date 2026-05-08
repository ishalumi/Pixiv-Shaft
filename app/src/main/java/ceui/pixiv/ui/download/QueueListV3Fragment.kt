package ceui.pixiv.ui.download

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import android.content.Intent
import android.widget.Toast
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.activities.VActivity
import ceui.lisa.core.Container
import ceui.lisa.core.Manager
import ceui.lisa.core.PageData
import ceui.lisa.database.AppDatabase
import ceui.lisa.models.IllustsBean
import ceui.lisa.utils.GlideUtil
import ceui.lisa.utils.Params
import ceui.loxia.ObjectPool
import ceui.pixiv.db.queue.DownloadQueueDao
import ceui.pixiv.db.queue.DownloadQueueEntity
import ceui.pixiv.db.queue.QueueStatus
import ceui.pixiv.ui.bulk.QueueDownloadManager
import com.bumptech.glide.Glide
import com.qmuiteam.qmui.skin.QMUISkinManager
import com.qmuiteam.qmui.widget.dialog.QMUIDialog
import com.qmuiteam.qmui.widget.dialog.QMUIDialogAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * V3 风格 "批量队列" 列表。
 *
 * 设计：
 *  - 顶部胶囊式操作 bar（暂停/继续, 重试失败, 清空成功, 清空全部）
 *  - 卡片化（CardView 圆角 18dp，elevation 1.5dp，背景 v3_bg）
 *  - 缩略图从 ObjectPool 取（命中率高，最近抓的都在）；不在池里就只显示占位色
 *  - 状态徽章彩色：PENDING(灰)/DOWNLOADING(蓝)/SUCCESS(绿)/FAILED(红)
 */
class QueueListV3Fragment : Fragment() {

    private val dao: DownloadQueueDao by lazy {
        AppDatabase.getAppDatabase(Shaft.getContext()).downloadQueueDao()
    }
    private val adapter = QueueAdapterV3()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_download_list_v3, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val list = view.findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = adapter
        list.setHasFixedSize(true)

        // 点击 row → VActivity 看一级详情。从 row.illustGson 反序列化拿到 IllustsBean，
        // 整段 currentList 也一起反出来组成 PageData，让用户在 VActivity 里能左右滑切到
        // 队列里的相邻 illust（跟"作品列表点击"行为一致）。
        // 没有 illustGson 的老 v33 行被过滤掉；点击它会找不到自己 → 提示一下让用户等。
        adapter.onItemClick = onItemClick@{ row, all ->
            val ctx = context ?: return@onItemClick
            val clicked = parseIllustFromRow(row)
            if (clicked == null) {
                Toast.makeText(ctx, R.string.dlmgr_queue_open_unavailable, Toast.LENGTH_SHORT).show()
                return@onItemClick
            }
            val list = all.mapNotNull { parseIllustFromRow(it) }
            val index = list.indexOfFirst { it.id == clicked.id }.coerceAtLeast(0)
            val pageData = PageData(list)
            Container.get().addPageToMap(pageData)
            startActivity(Intent(ctx, VActivity::class.java).apply {
                putExtra(Params.POSITION, index)
                putExtra(Params.PAGE_UUID, pageData.uuid)
            })
        }

        val empty = view.findViewById<View>(R.id.emptyState)
        view.findViewById<TextView>(R.id.emptyTitle).text = getString(R.string.dlmgr_queue_empty_title)
        view.findViewById<TextView>(R.id.emptyHint).text = getString(R.string.dlmgr_queue_empty_hint)

        // 文案不在这里初始化 —— pausedFlow StateFlow 一 collect 立刻 replay 当前
        // 值，下面的 combine collector 微秒级别就把 text 设上。
        val btnPause = view.findViewById<Button>(R.id.btn1)
        val btnRetry = view.findViewById<Button>(R.id.btn2).apply { text = getString(R.string.dlmgr_queue_action_retry_failed) }
        // btn3（原"清成功记录"）已废弃 —— SUCCESS 行会自动从队列消失走到"已完成" tab，
        // 这个按钮没有实际意义。
        view.findViewById<Button>(R.id.btn3).visibility = View.GONE
        val btnClearAll = view.findViewById<Button>(R.id.btn4).apply { text = getString(R.string.dlmgr_queue_action_clear_all) }

        btnPause.setOnClickListener {
            // pausedFlow 翻转后 combine collector 自动设 text，不在这里手动重复设。
            if (QueueDownloadManager.isPaused()) {
                // 联动：批量队列恢复时，正在下载 tab 的 Manager 也跟着恢复
                QueueDownloadManager.resume()
                Manager.get().startAll()
            } else {
                // 联动：批量队列暂停时，连同 Manager 当前正在下的也暂停
                QueueDownloadManager.pause()
                Manager.get().stopAll()
            }
        }
        btnRetry.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                runCatching { dao.retryAllFailed() }
                // 用户手动点重试 → 必须 resume() 而不仅仅 tickle，否则 paused 时啥也不做
                QueueDownloadManager.resume()
            }
        }
        btnClearAll.setOnClickListener {
            // destructive 操作必须确认 —— 误点会让用户失去全部排队
            showClearConfirmDialog {
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    // 联动：清空队列同时把正在下载的也一并停掉清掉，
                    // 否则用户清完队列还看到"正在下载" tab 有残留任务在跑。
                    runCatching { Manager.get().clearAll() }
                    runCatching { dao.deleteAll() }
                    QueueDownloadManager.queueListInvalidations.tryEmit(Unit)
                }
            }
        }

        // 数据源 = 队列脏标记 (SharedFlow) + 暂停态 (StateFlow) 合并。
        //
        // ⚠️ 不用 Room 的 dao.flowActive：实测 Room InvalidationTracker 在
        // ~10 次/秒的连续 UPDATE 序列下首次 emit 后静默不再 re-emit
        // （用户复现：17 个 illust 标了 SUCCESS 都没让 flow 再 emit）。
        // 改成自己控制的 [QueueDownloadManager.queueListInvalidations]：consumer
        // 每次 status 变化 / LegacyBatchEnqueue 入队都 tryEmit。这边 collect
        // 后用 suspend 的 [DownloadQueueDao.pageActive] 取最新行，绕开 Room
        // 那个不可靠的 Flow。
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    QueueDownloadManager.queueListInvalidations,
                    QueueDownloadManager.pausedFlow,
                ) { _, paused -> paused }
                    .map { paused ->
                        val rows = runCatching { dao.pageActive(MAX_DISPLAY_ROWS, 0) }
                            .getOrDefault(emptyList())
                        rows to paused
                    }
                    .flowOn(Dispatchers.IO)
                    .collectLatest { (rows, paused) ->
                        Timber.tag("QueueListV3").i(
                            "[QUEUE-LIST] manual emit rows=${rows.size}"
                        )
                        adapter.submitList(rows)
                        empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
                        val hasWork = rows.isNotEmpty()
                        btnPause.isEnabled = hasWork
                        btnPause.alpha = if (hasWork) 1f else 0.4f
                        btnClearAll.isEnabled = hasWork
                        btnClearAll.alpha = if (hasWork) 1f else 0.4f
                        btnPause.text = getString(
                            if (paused) R.string.dlmgr_queue_action_resume
                            else R.string.dlmgr_queue_action_pause
                        )
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

    companion object {
        /**
         * UI 一次最多加载这么多条 —— 5000 条 RecyclerView 仍然流畅。
         * consumer 不受此限，会把 DB 里全部 PENDING 都消费掉，所以即使有
         * 几万条也只是前 5000 个可见，后续随消费完成自动滚出来。
         *
         * 解决 issue #858（旧代码 pageActive 只拿前 200 条让用户感觉"中间丢了"）：
         * Room flowActive 一次拉 5000 条带 LIMIT，覆盖正常规模，不再分页。
         */
        private const val MAX_DISPLAY_ROWS = 5000
    }
}

private object QueueDiff : DiffUtil.ItemCallback<DownloadQueueEntity>() {
    override fun areItemsTheSame(a: DownloadQueueEntity, b: DownloadQueueEntity): Boolean = a.id == b.id
    override fun areContentsTheSame(a: DownloadQueueEntity, b: DownloadQueueEntity): Boolean =
        a.status == b.status && a.retryCount == b.retryCount
}

/**
 * 从 [DownloadQueueEntity.illustGson] 反序列化出 [IllustsBean]，失败 / null / 空字符串
 * 都返回 null（adapter / consumer 调用方自己 fallback 老路径）。
 *
 * 在 onBindViewHolder 里调，3-5 个可见行每行一次 Gson.fromJson(~50KB JSON)，
 * 微秒级，不会卡。
 */
private fun parseIllustFromRow(row: DownloadQueueEntity): IllustsBean? {
    val json = row.illustGson
    if (json.isNullOrEmpty()) return null
    return runCatching { Shaft.sGson.fromJson(json, IllustsBean::class.java) }.getOrNull()
}

private class QueueAdapterV3 : ListAdapter<DownloadQueueEntity, QueueAdapterV3.VH>(QueueDiff) {

    /** 点击 row 的回调；fragment 端注册，把整段 currentList 一起传出去给 VActivity 拼 PageData */
    var onItemClick: ((row: DownloadQueueEntity, all: List<DownloadQueueEntity>) -> Unit)? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.cell_download_queue_v3, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, pos: Int) {
        val item = getItem(pos)
        h.itemView.setOnClickListener {
            val p = h.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) {
                onItemClick?.invoke(getItem(p), currentList)
            }
        }
        // illust 解析优先级跟 QueueDownloadManager.resolveIllustsBean 一致：
        //   1. ObjectPool 命中（用户最近浏览过 / consumer 已处理过）
        //   2. 反序列化 row.illustGson（入队时存的 JSON，冷启动主路径）
        //   3. 都没有 → 老行（v33 之前入队的）显示占位 "illust XXX"
        // 命中 2 时顺手灌一次 ObjectPool，下个 row 同 id 走 1，零重复 Gson。
        val illust = runCatching { ObjectPool.getIllust(item.illustId).value }.getOrNull()
            ?: parseIllustFromRow(item)?.also { bean ->
                runCatching { ObjectPool.updateIllust(bean) }
            }
        if (illust != null) {
            h.title.text = illust.title.orEmpty().ifBlank { "illustId ${item.illustId}" }
            h.author.text = illust.user?.name?.let { "by: $it" } ?: ""
            val showUrl = runCatching { illust.image_urls?.medium }.getOrNull()
            if (!showUrl.isNullOrEmpty()) {
                Glide.with(h.thumb)
                    .load(GlideUtil.getUrl(showUrl))
                    .placeholder(android.R.color.transparent)
                    .into(h.thumb)
            } else {
                Glide.with(h.thumb).clear(h.thumb)
                h.thumb.setImageDrawable(null)
            }
        } else {
            h.title.text = "illust  ${item.illustId}"
            h.author.text = ""
            Glide.with(h.thumb).clear(h.thumb)
            h.thumb.setImageDrawable(null)
        }

        h.seqLabel.text = "#${pos + 1}  ·  ${item.type}"

        h.statusBadge.text = when (item.status) {
            QueueStatus.PENDING -> "PENDING"
            QueueStatus.DOWNLOADING -> "DOWNLOADING"
            QueueStatus.SUCCESS -> "SUCCESS"
            QueueStatus.FAILED -> "FAILED"
            else -> item.status
        }
        h.statusBadge.setTextColor(
            when (item.status) {
                QueueStatus.PENDING -> Color.parseColor("#9DA3AB")
                QueueStatus.DOWNLOADING -> Color.parseColor("#5EB3FF")
                QueueStatus.SUCCESS -> Color.parseColor("#7CB668")
                QueueStatus.FAILED -> Color.parseColor("#FF8B8B")
                else -> Color.GRAY
            }
        )

        if (item.retryCount > 0) {
            h.retryBadge.visibility = View.VISIBLE
            h.retryBadge.text = "RETRY ${item.retryCount}"
        } else {
            h.retryBadge.visibility = View.GONE
        }
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val thumb: ImageView = v.findViewById(R.id.thumb)
        val title: TextView = v.findViewById(R.id.title)
        val author: TextView = v.findViewById(R.id.author)
        val seqLabel: TextView = v.findViewById(R.id.seqLabel)
        val statusBadge: TextView = v.findViewById(R.id.statusBadge)
        val retryBadge: TextView = v.findViewById(R.id.retryBadge)
    }
}
