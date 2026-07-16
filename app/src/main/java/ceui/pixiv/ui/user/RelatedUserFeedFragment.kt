package ceui.pixiv.ui.user

import android.os.Bundle
import android.view.View
import ceui.lisa.R
import ceui.lisa.databinding.FragmentToolbarFeedBinding
import ceui.lisa.utils.Params
import ceui.loxia.Client
import ceui.loxia.UserPreviewResponse
import ceui.pixiv.feeds.FeedPage
import ceui.pixiv.feeds.FeedSource
import ceui.pixiv.feeds.feedViewModels
import ceui.pixiv.ui.common.UserFeedFragment
import ceui.pixiv.ui.common.UserFeedItem
import ceui.pixiv.feeds.pixiv.replayNextUrl
import ceui.pixiv.ui.common.setUpToolbar
import ceui.pixiv.ui.common.viewBinding
import com.google.gson.Gson

/**
 * 「相关用户」页（feeds 框架版，替代 legacy FragmentRelatedUser + RelatedUserRepo + UAdapter）。
 * 某画师的相关画师推荐；TemplateActivity 宿主、自带 toolbar（fragment_toolbar_feed），复用
 * [UserFeedFragment] 的用户卡渲染 / 关注切换 / LIKED_USER 广播同步。
 */
class RelatedUserFeedFragment : UserFeedFragment(R.layout.fragment_toolbar_feed) {

    private val binding by viewBinding(FragmentToolbarFeedBinding::bind)

    // legacy 用 int USER_ID（TemplateActivity 路由 getIntExtra）。
    private val userId: Long by lazy(LazyThreadSafetyMode.NONE) {
        requireArguments().getInt(Params.USER_ID).toLong()
    }

    override val feedViewModel by feedViewModels {
        // VM 只长期持有 source；RelatedUserFeedSource 只吃一个 Long，天然零 Fragment 捕获。
        RelatedUserFeedSource(userId)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpToolbar(binding, feedBinding.feedListView)
        binding.toolbarTitle.text = getString(R.string.string_436)
    }

    companion object {
        @JvmStatic
        fun newInstance(userId: Int): RelatedUserFeedFragment {
            return RelatedUserFeedFragment().apply {
                arguments = Bundle().apply {
                    putInt(Params.USER_ID, userId)
                }
            }
        }
    }
}

/**
 * 相关用户数据源：v1/user/related（suspend [ceui.loxia.API.getRelatedUsers]，对齐 legacy
 * AppApi.getRelatedUsers）。翻页走 [replayNextUrl]（UserPreviewResponse 实现 KListShow）。
 */
class RelatedUserFeedSource(private val userId: Long) : FeedSource<String> {

    private val gson = Gson()

    override suspend fun load(cursor: String?): FeedPage<String> {
        val resp: UserPreviewResponse = if (cursor == null) {
            Client.appApi.getRelatedUsers(userId)
        } else {
            replayNextUrl(gson, cursor, UserPreviewResponse::class.java)
        }
        val items = resp.user_previews.map { UserFeedItem(it) }
        return FeedPage(items, resp.next_url?.takeIf { it.isNotEmpty() })
    }
}
