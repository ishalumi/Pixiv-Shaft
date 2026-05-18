package ceui.pixiv.ui.notification

import android.content.Intent
import android.os.Bundle
import android.view.View
import ceui.lisa.R
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.databinding.FragmentPixivListBinding
import ceui.lisa.view.LinearItemDecoration
import ceui.loxia.NotificationItem
import ceui.pixiv.ui.common.ListMode
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.setUpRefreshState
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.ui.list.pixivListViewModel
import ceui.pixiv.utils.ppppx

/**
 * "通知" tab 内容页。父 NotificationPagerFragment 实现 ViewPagerFragment 时
 * 内部 toolbar 自动隐藏(setUpToolbar 里的分支)。
 */
class NotificationListFragment : PixivFragment(R.layout.fragment_pixiv_list), NotificationActionReceiver {

    private val binding by viewBinding(FragmentPixivListBinding::bind)
    private val viewModel by pixivListViewModel { NotificationListDataSource() }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpRefreshState(binding, viewModel, ListMode.VERTICAL_NO_MARGIN)
        // 12dp = 卡片左右 inset + 卡片间纵向间距,统一走 ItemDecoration,
        // cell layout 不再背 margin。
        binding.listView.addItemDecoration(LinearItemDecoration(12.ppppx))
    }

    override fun onClickNotification(item: NotificationItem) {
        requireContext().routeNotificationTargetUrl(item.target_url)
    }

    override fun onClickViewMore(item: NotificationItem) {
        if (item.id <= 0L) return
        val intent = Intent(requireContext(), TemplateActivity::class.java).apply {
            putExtra(TemplateActivity.EXTRA_FRAGMENT, NotificationViewMoreFragment.ROUTE_KEY)
            putExtra(NotificationViewMoreFragment.EXTRA_NOTIFICATION_ID, item.id)
            putExtra(NotificationViewMoreFragment.EXTRA_TITLE, item.view_more?.title.orEmpty())
        }
        startActivity(intent)
    }
}
