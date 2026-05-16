package ceui.lisa.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.SearchView
import androidx.core.view.MenuItemCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.database.AppDatabase
import ceui.lisa.database.IllustHistoryEntity
import ceui.lisa.databinding.FragmentHistoryV3Binding
import ceui.lisa.models.IllustsBean
import ceui.lisa.utils.Common
import ceui.lisa.utils.V3Palette
import ceui.loxia.ObjectPool
import com.qmuiteam.qmui.skin.QMUISkinManager
import com.qmuiteam.qmui.widget.dialog.QMUIDialog
import com.qmuiteam.qmui.widget.dialog.QMUIDialogAction
import com.scwang.smart.refresh.footer.ClassicsFooter
import com.scwang.smart.refresh.header.MaterialHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FragmentHistoryV3 : Fragment() {

    private var _binding: FragmentHistoryV3Binding? = null
    private val binding get() = _binding!!

    private val items: MutableList<IllustHistoryEntity> = mutableListOf()
    private val illusts: MutableList<IllustsBean> = mutableListOf()
    private var totalCount: Int = 0

    /** When non-null we're in search mode — list is filtered, paging disabled. */
    private var searchQuery: String? = null
    private var searchJob: Job? = null

    private lateinit var listAdapter: HistoryV3Adapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHistoryV3Binding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val palette = V3Palette.from(requireContext())
        binding.bannerPlaceholder.background = palette.bannerPlaceholder()
        binding.historySubtitle.setTextColor(palette.textAccent)

        binding.toolbar.setPadding(0, Shaft.statusHeight, 0, 0)
        binding.toolbar.setNavigationOnClickListener { activity?.finish() }
        binding.toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_delete) {
                showClearAllDialog()
                true
            } else false
        }
        setupSearch()

        listAdapter = HistoryV3Adapter(
            context = requireContext(),
            items = items,
            allIllustsProvider = { illusts },
            onRequestDelete = { pos, entity -> showDeleteDialog(pos, entity) },
        )

        binding.recyclerView.layoutManager =
            StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL)
        binding.recyclerView.itemAnimator = null
        binding.recyclerView.adapter = listAdapter

        binding.refreshLayout.setRefreshHeader(MaterialHeader(requireContext()))
        binding.refreshLayout.setRefreshFooter(ClassicsFooter(requireContext()))
        binding.refreshLayout.setOnRefreshListener { loadFirst() }
        binding.refreshLayout.setOnLoadMoreListener { loadMore() }

        loadFirst()
    }

    private fun loadFirst() {
        viewLifecycleOwner.lifecycleScope.launch {
            val (data, count) = withContext(Dispatchers.IO) {
                val dao = AppDatabase.getAppDatabase(requireContext()).downloadDao()
                dao.getAllViewHistory(PAGE_SIZE, 0) to dao.getViewHistoryCount()
            }
            items.clear()
            items.addAll(data)
            totalCount = count
            rebuildIllustList()
            listAdapter.submit(data)
            binding.refreshLayout.finishRefresh()
            updateSubtitleAndEmpty()
        }
    }

    private fun loadMore() {
        viewLifecycleOwner.lifecycleScope.launch {
            val offset = items.size
            val (data, count) = withContext(Dispatchers.IO) {
                val dao = AppDatabase.getAppDatabase(requireContext()).downloadDao()
                dao.getAllViewHistory(PAGE_SIZE, offset) to dao.getViewHistoryCount()
            }
            if (data.isNotEmpty()) {
                listAdapter.append(data)
                appendIllusts(data)
            }
            totalCount = count
            binding.refreshLayout.finishLoadMore()
            updateSubtitleAndEmpty()
        }
    }

    private fun rebuildIllustList() {
        illusts.clear()
        items.asSequence()
            .filter { it.type == 0 }
            .mapNotNull { Shaft.sGson.fromJson(it.illustJson, IllustsBean::class.java) }
            .forEach {
                ObjectPool.updateIllust(it)
                illusts.add(it)
            }
    }

    private fun appendIllusts(newItems: List<IllustHistoryEntity>) {
        newItems.asSequence()
            .filter { it.type == 0 }
            .mapNotNull { Shaft.sGson.fromJson(it.illustJson, IllustsBean::class.java) }
            .forEach {
                ObjectPool.updateIllust(it)
                illusts.add(it)
            }
    }

    private fun updateSubtitleAndEmpty() {
        val q = searchQuery
        if (q.isNullOrBlank()) {
            binding.historySubtitle.text = getString(R.string.history_count_format, totalCount)
            binding.emptyLayout.isVisible = items.isEmpty()
        } else {
            binding.historySubtitle.text =
                getString(R.string.history_search_result_count, items.size)
            binding.emptyLayout.isVisible = items.isEmpty()
        }
    }

    /**
     * Wire the SearchView attached to the search MenuItem. Expand collapses
     * the AppBar (so the keyboard has room) and disables pagination — paging
     * is meaningless once the visible list is the LIKE result. Collapse
     * restores [loadFirst] so the user lands back on the freshest 30 items.
     *
     * Query is debounced 200ms — Room call is cheap but rebuilds the adapter
     * + illust pool on every keystroke, which janks visibly without debounce.
     */
    private fun setupSearch() {
        val searchItem: MenuItem = binding.toolbar.menu.findItem(R.id.action_search) ?: return
        val searchView = MenuItemCompat.getActionView(searchItem) as? SearchView ?: return
        searchView.queryHint = getString(R.string.history_search_hint)
        searchView.maxWidth = Int.MAX_VALUE

        MenuItemCompat.setOnActionExpandListener(searchItem, object : MenuItemCompat.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: MenuItem): Boolean {
                binding.appBar.setExpanded(false, true)
                binding.refreshLayout.setEnableLoadMore(false)
                binding.refreshLayout.setEnableRefresh(false)
                return true
            }

            override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                searchQuery = null
                searchJob?.cancel()
                binding.refreshLayout.setEnableLoadMore(true)
                binding.refreshLayout.setEnableRefresh(true)
                loadFirst()
                return true
            }
        })

        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                applySearch(query.orEmpty())
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                searchJob?.cancel()
                searchJob = viewLifecycleOwner.lifecycleScope.launch {
                    delay(200)
                    applySearch(newText.orEmpty())
                }
                return true
            }
        })
    }

    private fun applySearch(raw: String) {
        val query = raw.trim()
        searchQuery = query
        if (query.isEmpty()) {
            // Empty query inside an expanded SearchView: show an empty state
            // rather than reloading the whole history — the user is mid-edit.
            items.clear()
            illusts.clear()
            listAdapter.clear()
            updateSubtitleAndEmpty()
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val results = withContext(Dispatchers.IO) {
                AppDatabase.getAppDatabase(requireContext()).downloadDao()
                    .searchViewHistory(query)
            }
            // Bail out if the query changed while we were in IO.
            if (searchQuery != query) return@launch
            items.clear()
            items.addAll(results)
            rebuildIllustList()
            listAdapter.submit(results)
            updateSubtitleAndEmpty()
        }
    }

    private fun showDeleteDialog(position: Int, entity: IllustHistoryEntity) {
        val activity = activity ?: return
        QMUIDialog.MessageDialogBuilder(activity)
            .setTitle(getString(R.string.string_143))
            .setMessage(getString(R.string.string_352))
            .setSkinManager(QMUISkinManager.defaultInstance(activity))
            .addAction(getString(R.string.string_142)) { dialog, _ -> dialog.dismiss() }
            .addAction(
                0,
                getString(R.string.string_141),
                QMUIDialogAction.ACTION_PROP_NEGATIVE
            ) { dialog, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        AppDatabase.getAppDatabase(requireContext()).downloadDao().delete(entity)
                    }
                    val currentPos = items.indexOfFirst {
                        it.illustID == entity.illustID && it.type == entity.type
                    }
                    if (currentPos >= 0) {
                        items.removeAt(currentPos)
                        listAdapter.removeAt(currentPos)
                        if (entity.type == 0) {
                            illusts.removeAll { it.id == entity.illustID }
                        }
                    }
                    totalCount = (totalCount - 1).coerceAtLeast(0)
                    Common.showToast(getString(R.string.string_220))
                    dialog.dismiss()
                    updateSubtitleAndEmpty()
                }
            }
            .show()
    }

    private fun showClearAllDialog() {
        val activity = activity ?: return
        if (items.isEmpty()) {
            Common.showToast(getString(R.string.string_254))
            return
        }
        QMUIDialog.MessageDialogBuilder(activity)
            .setTitle(getString(R.string.string_143))
            .setMessage(getString(R.string.string_255))
            .setSkinManager(QMUISkinManager.defaultInstance(activity))
            .addAction(getString(R.string.string_142)) { dialog, _ -> dialog.dismiss() }
            .addAction(
                0,
                getString(R.string.string_141),
                QMUIDialogAction.ACTION_PROP_NEGATIVE
            ) { dialog, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        AppDatabase.getAppDatabase(requireContext()).downloadDao().deleteAllHistory()
                    }
                    items.clear()
                    illusts.clear()
                    listAdapter.clear()
                    totalCount = 0
                    Common.showToast(getString(R.string.string_220))
                    dialog.dismiss()
                    updateSubtitleAndEmpty()
                }
            }
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val PAGE_SIZE = 30
    }
}
