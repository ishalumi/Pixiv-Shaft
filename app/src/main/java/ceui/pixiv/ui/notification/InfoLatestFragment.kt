package ceui.pixiv.ui.notification

import android.content.Intent
import android.os.Bundle
import android.view.View
import ceui.lisa.R
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.databinding.FragmentPixivListBinding
import ceui.lisa.utils.Params
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
 * "公告" tab 首屏:/v1/info/latest 聚合视图,header + 4-5 个 entry / 分类。
 * 点 entry → 走 WebView 看公告页。点 "查看更多" → 下钻该分类的分页列表。
 */
class InfoLatestFragment : PixivFragment(R.layout.fragment_pixiv_list), InfoActionReceiver {

    private val binding by viewBinding(FragmentPixivListBinding::bind)
    private val viewModel by pixivListViewModel { InfoLatestDataSource() }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpRefreshState(binding, viewModel, ListMode.VERTICAL_NO_MARGIN)
        binding.listView.addItemDecoration(LinearItemDecoration(12.ppppx))
    }

    override fun onClickInfo(item: InfoItem) {
        openInfoUrl(requireContext(), item)
    }

    override fun onClickInfoCategoryMore(category: CategorizedInfo) {
        val intent = Intent(requireContext(), TemplateActivity::class.java).apply {
            putExtra(TemplateActivity.EXTRA_FRAGMENT, InfoCategoryListFragment.ROUTE_KEY)
            putExtra(InfoCategoryListFragment.EXTRA_CATEGORY_ID, category.category_id)
            putExtra(InfoCategoryListFragment.EXTRA_CATEGORY_TITLE, category.category_title.orEmpty())
        }
        startActivity(intent)
    }
}

/** info entry 跳 WebView (走 TemplateActivity 现有"网页链接"路由)。 */
internal fun openInfoUrl(ctx: android.content.Context, item: InfoItem) {
    val url = item.url ?: return
    val intent = Intent(ctx, TemplateActivity::class.java).apply {
        putExtra(TemplateActivity.EXTRA_FRAGMENT, "网页链接")
        putExtra(Params.URL, url)
        putExtra(Params.TITLE, item.title.orEmpty())
    }
    ctx.startActivity(intent)
}
