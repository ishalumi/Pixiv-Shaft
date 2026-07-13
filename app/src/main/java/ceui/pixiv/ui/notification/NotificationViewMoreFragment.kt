package ceui.pixiv.ui.notification

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import ceui.lisa.R
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.databinding.CellNotificationBinding
import ceui.lisa.databinding.FragmentToolbarFeedBinding
import ceui.lisa.view.LinearItemDecoration
import ceui.loxia.Client
import ceui.loxia.NotificationItem
import ceui.pixiv.feeds.FeedFragment
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.FeedRenderer
import ceui.pixiv.feeds.PixivFeedSource
import ceui.pixiv.feeds.feedRenderer
import ceui.pixiv.feeds.feedViewModels
import ceui.pixiv.ui.common.setUpToolbar
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.utils.ppppx

/**
 * "展开全部" 子页（feeds 框架版）:从某条 group 通知点开,拉同 group 的完整流水。
 * 标题用 group 自身的 view_more.title,通过宿主 Activity 的 intent extra 传入
 * ——TemplateActivity 用无参构造创建这个 Fragment,不走 Fragment arguments。
 */
class NotificationViewMoreFragment : FeedFragment(R.layout.fragment_toolbar_feed) {

    companion object {
        const val ROUTE_KEY = "通知展开"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
        const val EXTRA_TITLE = "notification_view_more_title"
    }

    private val binding by viewBinding(FragmentToolbarFeedBinding::bind)

    private val notificationId: Long by lazy {
        requireActivity().intent.getLongExtra(EXTRA_NOTIFICATION_ID, 0L)
    }

    override val feedViewModel by feedViewModels<String> {
        // 零捕获约定:先取成局部 val 再给 PixivFeedSource 用,不捕获 Fragment 本身。
        val id = notificationId
        PixivFeedSource(initialFetch = { Client.appApi.getNotificationViewMore(id) }) { resp, _ ->
            resp.displayList.map { NotificationFeedItem(it) }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpToolbar(binding, feedBinding.feedListView)
        val title = requireActivity().intent.getStringExtra(EXTRA_TITLE).orEmpty()
        binding.toolbarTitle.text = title.ifEmpty { getString(R.string.tab_notifications) }
    }

    override fun onListReady(listView: RecyclerView) {
        // 对齐旧版 ListMode.VERTICAL_NO_MARGIN + 手动挂的 12dp decoration。
        listView.addItemDecoration(LinearItemDecoration(12.ppppx))
    }

    override fun onCreateRenderers(): List<FeedRenderer<out FeedItem, out ViewBinding>> {
        return listOf(notificationRenderer())
    }

    private fun notificationRenderer() = feedRenderer<NotificationFeedItem, CellNotificationBinding>(
        inflate = CellNotificationBinding::inflate,
    ) { cell ->
        cell.binding.bindNotification(
            cell.item.item,
            onClickNotification = { item -> requireContext().routeNotificationTargetUrl(item.target_url) },
            onClickViewMore = ::onClickViewMore,
        )
    }

    private fun onClickViewMore(item: NotificationItem) {
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
