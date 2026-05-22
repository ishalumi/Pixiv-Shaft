package ceui.lisa.fragments

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import ceui.lisa.R
import ceui.lisa.databinding.FragmentHistoryListBinding
import ceui.pixiv.db.GeneralEntity
import ceui.pixiv.ui.common.CommonAdapter
import ceui.pixiv.ui.common.viewBinding
import com.qmuiteam.qmui.widget.dialog.QMUIDialog
import com.qmuiteam.qmui.widget.dialog.QMUIDialogAction
import com.scwang.smart.refresh.footer.ClassicsFooter
import com.scwang.smart.refresh.header.MaterialHeader

class FragmentHistoryUserList : Fragment(R.layout.fragment_history_list) {

    private val binding by viewBinding(FragmentHistoryListBinding::bind)
    private val viewModel: HistoryUserViewModel by viewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val adapter = CommonAdapter(viewLifecycleOwner)
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
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
            // 远端:初次加载用居中 ProgressBar 当加载态(避免下拉头压到 toolbar)。
            binding.loadingBar.isVisible = true
            viewModel.loadFirst { binding.loadingBar.isVisible = false }
        }
    }

    /** host 一键清空全部历史 (#886) 后调一下，让本 tab 重新拉 DAO。 */
    fun reloadFromDao() {
        if (view == null) return
        viewModel.loadFirst()
    }

    private fun confirmDelete(entity: GeneralEntity) {
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
}
