package ceui.lisa.fragments

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import ceui.lisa.R
import ceui.lisa.database.IllustHistoryEntity
import ceui.lisa.databinding.FragmentHistoryListBinding
import ceui.pixiv.ui.common.CommonAdapter
import ceui.pixiv.ui.common.viewBinding
import com.qmuiteam.qmui.widget.dialog.QMUIDialog
import com.qmuiteam.qmui.widget.dialog.QMUIDialogAction
import com.scwang.smart.refresh.footer.ClassicsFooter
import com.scwang.smart.refresh.header.MaterialHeader
import kotlinx.coroutines.launch

class FragmentHistoryList : Fragment(R.layout.fragment_history_list) {

    private val binding by viewBinding(FragmentHistoryListBinding::bind)
    private val historyType: Int by lazy { arguments?.getInt(ARG_TYPE, 0) ?: 0 }
    private val viewModel: HistoryListViewModel by viewModels { HistoryListViewModel.factory(historyType) }
    private val searchVm: HistorySearchSharedViewModel by activityViewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val adapter = CommonAdapter(viewLifecycleOwner)

        val spanCount = if (historyType == TYPE_NOVEL) 1 else 2
        binding.recyclerView.layoutManager = StaggeredGridLayoutManager(spanCount, StaggeredGridLayoutManager.VERTICAL)
        binding.recyclerView.itemAnimator = null
        binding.recyclerView.adapter = adapter

        binding.refreshLayout.setRefreshHeader(MaterialHeader(requireContext()))
        binding.refreshLayout.setRefreshFooter(ClassicsFooter(requireContext()))
        binding.refreshLayout.setOnRefreshListener {
            viewModel.loadFirst { binding.refreshLayout.finishRefresh() }
        }
        binding.refreshLayout.setOnLoadMoreListener {
            viewModel.loadMore { binding.refreshLayout.finishLoadMore() }
        }

        viewModel.setDeleteCallback { entity -> confirmDelete(entity) }
        viewModel.holders.observe(viewLifecycleOwner) { holders ->
            adapter.submitList(holders)
        }
        viewModel.isEmpty.observe(viewLifecycleOwner) { empty ->
            binding.emptyLayout.isVisible = empty
        }

        if (viewModel.holders.value.isNullOrEmpty()) {
            viewModel.loadFirst()
        }

        // host toolbar 上 SearchView 的输入通过 activity-scope SharedVM 下发到这里，
        // 切换 DAO 数据源；query 空则恢复默认 paginated 列表。三个 tab 共用同一个
        // sharedVm，但各自只过滤自己 type 对应的行（applySearch 内按 historyType 走 DAO）。
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                searchVm.query.collect { q -> viewModel.applySearch(q) }
            }
        }
    }

    /** host 一键清空全部历史 (#886) 后调一下，让本 tab 重新拉 DAO。 */
    fun reloadFromDao() {
        if (view == null) return
        viewModel.loadFirst()
    }

    private fun confirmDelete(entity: IllustHistoryEntity) {
        val act = activity ?: return
        QMUIDialog.MessageDialogBuilder(act)
            .setTitle(R.string.string_143)
            .setMessage(R.string.string_352)
            .addAction(R.string.string_142) { d, _ -> d.dismiss() }
            .addAction(0, R.string.string_141, QMUIDialogAction.ACTION_PROP_NEGATIVE) { d, _ ->
                d.dismiss()
                viewModel.delete(entity)
            }
            .show()
    }

    companion object {
        private const val ARG_TYPE = "history_type"
        private const val TYPE_NOVEL = 1

        fun newInstance(type: Int): FragmentHistoryList = FragmentHistoryList().apply {
            arguments = Bundle().apply { putInt(ARG_TYPE, type) }
        }
    }
}
