package ceui.pixiv.ui.notification

import android.os.Bundle
import android.view.View
import ceui.lisa.R
import ceui.lisa.databinding.FragmentPixivListBinding
import ceui.lisa.view.LinearItemDecoration
import ceui.loxia.CategorizedInfo
import ceui.loxia.InfoItem
import ceui.pixiv.ui.common.ListMode
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.setUpRefreshState
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.ui.list.pixivListViewModel
import ceui.pixiv.utils.ppppx

/**
 * /v1/info/list?cid=N 的下钻页:某分类完整列表,带分页(走 KListShow.next_url)。
 * 独立 fragment_pixiv_list 自带 toolbar。
 */
class InfoCategoryListFragment : PixivFragment(R.layout.fragment_pixiv_list), InfoActionReceiver {

    companion object {
        const val ROUTE_KEY = "公告分类"
        const val EXTRA_CATEGORY_ID = "info_category_id"
        const val EXTRA_CATEGORY_TITLE = "info_category_title"
    }

    private val binding by viewBinding(FragmentPixivListBinding::bind)

    private val categoryId: Int by lazy {
        requireActivity().intent.getIntExtra(EXTRA_CATEGORY_ID, 0)
    }

    private val viewModel by pixivListViewModel { InfoCategoryListDataSource(categoryId) }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val title = requireActivity().intent.getStringExtra(EXTRA_CATEGORY_TITLE).orEmpty()
        binding.toolbarLayout.naviTitle.text =
            title.ifEmpty { getString(R.string.tab_info) }
        setUpRefreshState(binding, viewModel, ListMode.VERTICAL_NO_MARGIN)
        binding.listView.addItemDecoration(LinearItemDecoration(12.ppppx))
    }

    override fun onClickInfo(item: InfoItem) {
        openInfoUrl(requireContext(), item)
    }

    override fun onClickInfoCategoryMore(category: CategorizedInfo) {
        // 子页里没有 header 的"更多"按钮,这是 safety net。
    }
}
