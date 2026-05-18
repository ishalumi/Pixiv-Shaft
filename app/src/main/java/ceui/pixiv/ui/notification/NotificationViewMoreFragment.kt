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
 * "展开全部" 子页:从某条 group 通知点开,拉同 group 的完整流水。
 * 独立 fragment_pixiv_list 自带 toolbar,标题用 group 自身的 view_more.title。
 */
class NotificationViewMoreFragment : PixivFragment(R.layout.fragment_pixiv_list), NotificationActionReceiver {

    companion object {
        const val ROUTE_KEY = "通知展开"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
        const val EXTRA_TITLE = "notification_view_more_title"
    }

    private val binding by viewBinding(FragmentPixivListBinding::bind)

    private val notificationId: Long by lazy {
        requireActivity().intent.getLongExtra(EXTRA_NOTIFICATION_ID, 0L)
    }

    private val viewModel by pixivListViewModel { NotificationViewMoreDataSource(notificationId) }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val title = requireActivity().intent.getStringExtra(EXTRA_TITLE).orEmpty()
        binding.toolbarLayout.naviTitle.text =
            title.ifEmpty { getString(R.string.tab_notifications) }
        setUpRefreshState(binding, viewModel, ListMode.VERTICAL_NO_MARGIN)
        binding.listView.addItemDecoration(LinearItemDecoration(12.ppppx))
    }

    override fun onClickNotification(item: NotificationItem) {
        requireContext().routeNotificationTargetUrl(item.target_url)
    }

    override fun onClickViewMore(item: NotificationItem) {
        // 子页里的 cell 理论上不再有 view_more,但兜底也跳一次自身。
        if (item.id <= 0L || item.id == notificationId) return
        val intent = Intent(requireContext(), TemplateActivity::class.java).apply {
            putExtra(TemplateActivity.EXTRA_FRAGMENT, ROUTE_KEY)
            putExtra(EXTRA_NOTIFICATION_ID, item.id)
            putExtra(EXTRA_TITLE, item.view_more?.title.orEmpty())
        }
        startActivity(intent)
    }
}
