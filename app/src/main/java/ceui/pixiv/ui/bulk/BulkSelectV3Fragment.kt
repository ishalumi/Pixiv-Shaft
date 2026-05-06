package ceui.pixiv.ui.bulk

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ceui.lisa.R
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.models.IllustsBean
import ceui.lisa.utils.GlideUtil
import com.bumptech.glide.Glide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * V3 风格批量下载 · 多选页。
 *
 * 入口：IAdapter / TagAdapter 长按 → MultiDownload.startDownload() → TemplateActivity("批量选择") →
 *       本 fragment 取 BulkSelectStorage.consume() 拿到列表
 *
 * 行为：
 *  - 默认全选（除 GIF —— GIF 走单独 ugoira 管线，本队列不接）
 *  - 全选 / 反选 在 toolbar 右上 menu（不再占用底部 bar）
 *  - 选中态：粗 v3_blue 边框 + 实色圆形勾标 + 微缩小 0.94，三层视觉差
 *  - 确认按钮：把选中的灌入 download_queue（走 LegacyBatchEnqueue），完成后跳转
 *    "下载管理" V3 总览页让用户看到入队进度，然后 finish 当前页
 */
class BulkSelectV3Fragment : Fragment() {

    private val items = mutableListOf<SelectableItem>()
    private val adapter: BulkSelectAdapter by lazy {
        BulkSelectAdapter(items) { pos -> toggleAt(pos) }
    }

    private fun toggleAt(pos: Int) {
        if (pos < 0 || pos >= items.size) return
        if (!items[pos].selectable) return
        items[pos] = items[pos].copy(selected = !items[pos].selected)
        adapter.notifyItemChanged(pos)
        refreshHeaderAndCta()
    }

    private lateinit var toolbar: Toolbar
    private lateinit var hint: TextView
    private lateinit var btnConfirm: Button

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_bulk_select_v3, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        toolbar = view.findViewById(R.id.toolbar)
        toolbar.setNavigationOnClickListener { requireActivity().finish() }
        // 单按钮 master-checkbox 模式：icon 跟选中态切（refreshSelectToggleIcon），
        // 点击行为也跟 icon 一致 —— 没全选 → 全选；已全选 → 取消全选。
        // 反选已废，使用频率低 + 单按钮表达不出第三种状态。
        toolbar.inflateMenu(R.menu.menu_bulk_select_v3)
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_select_toggle -> {
                    selectAllToggle()
                    true
                }
                else -> false
            }
        }

        hint = view.findViewById(R.id.hint)
        btnConfirm = view.findViewById(R.id.btnConfirm)

        val grid = view.findViewById<RecyclerView>(R.id.grid)
        grid.layoutManager = GridLayoutManager(requireContext(), 3)
        grid.adapter = adapter

        // 取列表 —— 大列表（10000+）SelectableItem 构造也搬 IO 避免主线程长时间循环。
        val raw = BulkSelectStorage.consume()
        if (raw.isNullOrEmpty()) {
            hint.text = getString(R.string.bulk_select_no_items)
            btnConfirm.isEnabled = false
            btnConfirm.text = "—"
            // 没东西可选，菜单也禁用了避免误导
            toolbar.menu.findItem(R.id.action_select_toggle)?.isEnabled = false
            return
        }
        hint.text = getString(R.string.bulk_select_loading)
        btnConfirm.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            val prepared = withContext(Dispatchers.IO) {
                raw.map { illust ->
                    val selectable = !illust.isGif
                    SelectableItem(illust, selected = selectable, selectable = selectable)
                }
            }
            items.clear()
            items.addAll(prepared)
            adapter.notifyDataSetChanged()
            refreshHeaderAndCta()
        }

        btnConfirm.setOnClickListener {
            // 大列表（10000+）的 filter/map 也走 IO 防卡帧；快照后立刻禁用按钮防双击。
            btnConfirm.isEnabled = false
            val snapshot = items.toList()
            val ctx = requireContext()
            viewLifecycleOwner.lifecycleScope.launch {
                val picked = withContext(Dispatchers.IO) {
                    snapshot.asSequence()
                        .filter { it.selected && it.selectable }
                        .map { it.illust }
                        .toList()
                }
                if (picked.isNotEmpty()) {
                    LegacyBatchEnqueue.enqueueAndToast(ctx, picked)
                    // 入队完成立刻跳转下载管理 V3 总览，让用户能看到队列动起来 ——
                    // 否则用户点完确认眼前一黑（finish）只看到 toast，不知道东西去哪了。
                    val intent = Intent(ctx, TemplateActivity::class.java)
                        .putExtra(TemplateActivity.EXTRA_FRAGMENT, "下载管理") // route key, not UI text
                    ctx.startActivity(intent)
                }
                requireActivity().finish()
            }
        }
    }

    private fun selectAllToggle() {
        val anyUnselected = items.any { it.selectable && !it.selected }
        val target = anyUnselected // 有未选 → 全选；否则 → 全不选
        viewLifecycleOwner.lifecycleScope.launch {
            val rebuilt = withContext(Dispatchers.IO) {
                items.map { if (it.selectable) it.copy(selected = target) else it }
            }
            items.clear()
            items.addAll(rebuilt)
            adapter.notifyDataSetChanged()
            refreshHeaderAndCta()
        }
    }

    private fun refreshHeaderAndCta() {
        val total = items.size
        val selected = items.count { it.selected && it.selectable }
        val gifSkipped = items.count { !it.selectable }
        // 求和已选作品的页数 —— 每个 illust 的 page_count 累加；page_count<=0 当 1 算
        val selectedImageCount = items.asSequence()
            .filter { it.selected && it.selectable }
            .sumOf { (it.illust.page_count.takeIf { c -> c > 0 } ?: 1) }
        hint.text = if (gifSkipped > 0) {
            getString(R.string.bulk_select_summary_with_gif, total, selected, selectedImageCount, gifSkipped)
        } else {
            getString(R.string.bulk_select_summary, total, selected, selectedImageCount)
        }
        btnConfirm.isEnabled = selected > 0
        btnConfirm.text = if (selected > 0) {
            getString(R.string.bulk_select_confirm, selected, selectedImageCount)
        } else {
            getString(R.string.bulk_select_confirm_empty)
        }
        refreshSelectToggleIcon(selected)
    }

    /**
     * Master-checkbox 模式：根据"可选项有多少已选中"切 toolbar 单按钮的 icon + title。
     *  - 没全选（含部分选中 / 全没选） → ic_select_all_24，title="全选"
     *  - 已全选（可选项全选中）       → ic_deselect_24，title="取消全选"
     * 状态不仅是视觉信号，也跟 selectAllToggle 行为一致：用户看到啥 icon 就预期点击会做啥。
     */
    private fun refreshSelectToggleIcon(selectedCount: Int) {
        val item = toolbar.menu.findItem(R.id.action_select_toggle) ?: return
        val selectableCount = items.count { it.selectable }
        val allSelected = selectableCount > 0 && selectedCount == selectableCount
        item.setIcon(if (allSelected) R.drawable.ic_deselect_24 else R.drawable.ic_select_all_24)
        item.setTitle(if (allSelected) R.string.bulk_select_clear_all else R.string.bulk_select_select_all)
        // 防止 Toolbar / theme overlay 给菜单 icon 套统一 tint，把
        // ic_deselect_24 内部写死的 v3_blue 压成灰色。每次 setIcon 后清掉
        // iconTintList，让 drawable 自己的 fillColor 说了算。
        item.iconTintList = null
    }
}

private data class SelectableItem(
    val illust: IllustsBean,
    val selected: Boolean,
    val selectable: Boolean,
)

private class BulkSelectAdapter(
    private val items: List<SelectableItem>,
    private val onToggle: (position: Int) -> Unit,
) : RecyclerView.Adapter<BulkSelectAdapter.VH>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.cell_bulk_select_v3, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, pos: Int) {
        val item = items[pos]
        val illust = item.illust

        // 缩略图
        val showUrl = runCatching { illust.image_urls?.medium }.getOrNull()
        if (!showUrl.isNullOrEmpty()) {
            Glide.with(h.thumb).load(GlideUtil.getUrl(showUrl))
                .placeholder(android.R.color.transparent).into(h.thumb)
        } else {
            Glide.with(h.thumb).clear(h.thumb)
            h.thumb.setImageDrawable(null)
        }

        // 多页徽章
        val pageCount = illust.page_count
        if (pageCount > 1) {
            h.pBadge.visibility = View.VISIBLE
            h.pBadge.text = "${pageCount}P"
        } else {
            h.pBadge.visibility = View.GONE
        }

        // GIF 徽章（不可选项才显示，跟 checkBadge 互斥）
        h.gifBadge.visibility = if (illust.isGif) View.VISIBLE else View.GONE

        // —— 选中态：边框 + 圆形勾标 + 微缩小 ——
        val isSelected = item.selected && item.selectable
        h.selectedBorder.visibility = if (isSelected) View.VISIBLE else View.GONE
        h.checkBadge.visibility = if (isSelected) View.VISIBLE else View.GONE
        // 微缩小 0.94 让选中项相对未选明显"凹"进去；非选中保持 1.0
        val targetScale = if (isSelected) SELECTED_SCALE else 1.0f
        h.itemView.scaleX = targetScale
        h.itemView.scaleY = targetScale

        // 不可选项视觉弱化
        h.itemView.alpha = if (item.selectable) 1f else 0.45f

        h.itemView.setOnClickListener { onToggle(h.bindingAdapterPosition) }
    }

    override fun getItemCount(): Int = items.size

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val thumb: ImageView = v.findViewById(R.id.thumb)
        val pBadge: TextView = v.findViewById(R.id.pBadge)
        val gifBadge: TextView = v.findViewById(R.id.gifBadge)
        val selectedBorder: View = v.findViewById(R.id.selectedBorder)
        val checkBadge: ImageView = v.findViewById(R.id.checkBadge)
    }

    companion object {
        /** 选中时整张卡缩到 94% —— 让选中项明显"凹"进去，跟未选的 100% 形成对比 */
        private const val SELECTED_SCALE = 0.94f
    }
}
