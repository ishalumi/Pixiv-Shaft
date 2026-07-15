package ceui.lisa.fragments

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LiveData
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import androidx.viewbinding.ViewBinding
import ceui.lisa.R
import ceui.lisa.activities.UActivity
import ceui.lisa.activities.VActivity
import ceui.lisa.core.Container
import ceui.lisa.core.PageData
import ceui.lisa.database.IllustHistoryEntity
import ceui.lisa.models.IllustsBean
import ceui.lisa.utils.Params
import ceui.pixiv.feeds.FeedFragment
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.FeedRenderer
import ceui.pixiv.feeds.feedViewModels
import ceui.pixiv.feeds.updateItems
import com.qmuiteam.qmui.widget.dialog.QMUIDialog
import com.qmuiteam.qmui.widget.dialog.QMUIDialogAction
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 浏览历史「插画/漫画」「小说」tab（feeds 框架版）。
 *
 * 列表数据、刷新/翻页/空态全部交给 [FeedFragment] + [HistoryFeedSource]；搜索由宿主
 * [FragmentHistoryTabs] 通过 activity-scope [HistorySearchSharedViewModel] 下发，query
 * 变化触发 feed refresh（source 读取当前 query 切数据源）。多选态住在 [HistorySelectionViewModel]，
 * 通过 [updateItems] 回灌到卡片的 isSelectionMode/isSelected（[syncSelection]，自带差异守卫防死循环）。
 * 删除走 [deleteHistoryEntities] + [FeedViewModel.removeItems]，就地摘条不整列重拉。
 */
class FragmentHistoryList : FeedFragment(), SelectableHistoryTab {

    private val historyType: Int by lazy { arguments?.getInt(ARG_TYPE, 0) ?: 0 }
    private val searchVm: HistorySearchSharedViewModel by activityViewModels()
    private val selectionVm: HistorySelectionViewModel by viewModels()

    // 懒加载:三 tab 在同一 ViewPager(BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT),只在 tab 真正
    // 可见(首次 RESUMED)才拉,避免开页就并发三次网络请求。
    override val feedViewModel by feedViewModels(autoLoad = false) {
        HistoryFeedSource(historyType, searchVm)
    }

    /** 时间格式化器:renderer 复用,别每次 onBind 都 new SimpleDateFormat。随 fragment 重建拿到当前 locale。 */
    internal val historyTimeFormat by lazy {
        SimpleDateFormat(getString(R.string.string_350), Locale.getDefault())
    }

    override fun onCreateRenderers(): List<FeedRenderer<out FeedItem, out ViewBinding>> =
        listOf(historyIllustRenderer(), historyNovelRenderer())

    override fun onCreateLayoutManager(): RecyclerView.LayoutManager {
        val spanCount = if (historyType == TYPE_NOVEL) 1 else 2
        return StaggeredGridLayoutManager(spanCount, StaggeredGridLayoutManager.VERTICAL)
    }

    override fun onListReady(listView: RecyclerView) {
        listView.itemAnimator = null
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 选中态变化 → 回灌卡片
        selectionVm.selectionMode.observe(viewLifecycleOwner) { syncSelection() }
        selectionVm.selectedIds.observe(viewLifecycleOwner) { syncSelection() }
        // 追页后新卡以「非多选」态入列，跟随当前多选态回灌
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                feedViewModel.uiState.collect { syncSelection() }
            }
        }
        // host toolbar 的 SearchView 输入通过 activity-scope SharedVM 下发；query 变化重刷。
        // drop(1)：首屏由 feedViewModels 的 autoLoad 已按当前 query 拉过，跳过初始重放避免重复请求。
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                searchVm.query.drop(1).collect { feedViewModel.refresh() }
            }
        }
        // 旋转等 view 重建时选择态可能还留着，但 host toolbar 已回普通态 → 复位。
        selectionVm.setSelectionMode(false)
    }

    // ── 多选态回灌 feed（差异守卫防止 uiState.collect ↔ updateItems 死循环）─────────────
    private fun syncSelection() {
        if (view == null) return
        val mode = selectionVm.selectionMode.value == true
        val selected = selectionVm.selectedIds.value.orEmpty()
        val needsUpdate = feedViewModel.uiState.value.items.any { item ->
            when (item) {
                is HistoryIllustFeedItem ->
                    item.isSelectionMode != mode || item.isSelected != (item.entity.illustID.toLong() in selected)
                is HistoryNovelFeedItem ->
                    item.isSelectionMode != mode || item.isSelected != (item.entity.illustID.toLong() in selected)
                else -> false
            }
        }
        if (!needsUpdate) return
        feedViewModel.updateItems<HistoryIllustFeedItem> {
            it.copy(isSelectionMode = mode, isSelected = it.entity.illustID.toLong() in selected)
        }
        feedViewModel.updateItems<HistoryNovelFeedItem> {
            it.copy(isSelectionMode = mode, isSelected = it.entity.illustID.toLong() in selected)
        }
    }

    // ── renderer 回调（HistoryFeed.kt 里的扩展 renderer 调用）────────────────────────
    internal fun toggleHistorySelect(entity: IllustHistoryEntity) = selectionVm.toggle(entity.illustID.toLong())

    internal fun openHistoryUser(uid: Int) {
        startActivity(Intent(requireContext(), UActivity::class.java).apply {
            putExtra(Params.USER_ID, uid)
        })
    }

    internal fun openHistoryIllust(illust: IllustsBean) {
        val all = loadedIllusts()
        if (all.isEmpty()) return
        val pageData = PageData(all)
        Container.get().addPageToMap(pageData)
        val index = all.indexOfFirst { it.id == illust.id }.coerceAtLeast(0)
        startActivity(Intent(requireContext(), VActivity::class.java).apply {
            putExtra(Params.POSITION, index)
            putExtra(Params.PAGE_UUID, pageData.uuid)
        })
    }

    internal fun confirmDeleteHistory(entity: IllustHistoryEntity) {
        val act = activity ?: return
        QMUIDialog.MessageDialogBuilder(act)
            .setTitle(R.string.string_143)
            .setMessage(R.string.string_352)
            .addAction(R.string.string_142) { d, _ -> d.dismiss() }
            .addAction(0, R.string.string_141, QMUIDialogAction.ACTION_PROP_NEGATIVE) { d, _ ->
                d.dismiss()
                deleteHistory(listOf(entity))
            }
            .show()
    }

    private fun deleteHistory(entities: List<IllustHistoryEntity>, onComplete: (Int) -> Unit = {}) {
        if (entities.isEmpty()) { onComplete(0); return }
        val ids = entities.map { it.illustID }.toSet()
        viewLifecycleOwner.lifecycleScope.launch {
            deleteHistoryEntities(historyType, entities)
            if (view == null) return@launch
            feedViewModel.removeItems { item ->
                (item as? HistoryIllustFeedItem)?.entity?.illustID in ids ||
                    (item as? HistoryNovelFeedItem)?.entity?.illustID in ids
            }
            onComplete(ids.size)
        }
    }

    // ── 当前已加载数据快照 ──────────────────────────────────────────────────────
    private fun loadedEntities(): List<IllustHistoryEntity> =
        feedViewModel.uiState.value.items.mapNotNull { item ->
            when (item) {
                is HistoryIllustFeedItem -> item.entity
                is HistoryNovelFeedItem -> item.entity
                else -> null
            }
        }

    private fun loadedIllusts(): List<IllustsBean> =
        feedViewModel.uiState.value.items.filterIsInstance<HistoryIllustFeedItem>().map { it.illust }

    /** host 一键清空全部历史 (#886) 后调一下，让本 tab 重新拉数据源。 */
    fun reloadFromDao() {
        if (view == null) return
        feedViewModel.refresh()
    }

    // ── SelectableHistoryTab：多选删除，具体状态在 [selectionVm] ──────────────────
    override val selectedCount: LiveData<Int> get() = selectionVm.selectedCount
    override fun hasItems(): Boolean = loadedEntities().isNotEmpty()
    override fun isAllSelected(): Boolean {
        val ids = loadedEntities().map { it.illustID.toLong() }
        return ids.isNotEmpty() && selectionVm.selectedIds.value.orEmpty().containsAll(ids)
    }
    override fun enterSelectionMode() = selectionVm.setSelectionMode(true)
    override fun exitSelectionMode() = selectionVm.setSelectionMode(false)
    override fun toggleSelectAll() {
        val ids = loadedEntities().map { it.illustID.toLong() }
        if (ids.isNotEmpty() && selectionVm.selectedIds.value.orEmpty().containsAll(ids)) {
            selectionVm.clear()
        } else {
            selectionVm.setSelected(ids.toSet())
        }
    }
    override fun deleteSelected(onComplete: (Int) -> Unit) {
        val selected = selectionVm.selectedIds.value.orEmpty()
        val targets = loadedEntities().filter { it.illustID.toLong() in selected }
        deleteHistory(targets) { deleted ->
            selectionVm.setSelectionMode(false)
            onComplete(deleted)
        }
    }

    companion object {
        private const val ARG_TYPE = "history_type"
        private const val TYPE_NOVEL = 1

        fun newInstance(type: Int): FragmentHistoryList = FragmentHistoryList().apply {
            arguments = Bundle().apply { putInt(ARG_TYPE, type) }
        }
    }
}
