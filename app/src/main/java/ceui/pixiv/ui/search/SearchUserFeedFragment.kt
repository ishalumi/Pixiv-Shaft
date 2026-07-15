package ceui.pixiv.ui.search

import android.os.Bundle
import android.view.View
import androidx.lifecycle.ViewModelProvider
import ceui.lisa.viewmodel.SearchModel
import ceui.loxia.Client
import ceui.loxia.UserPreviewResponse
import ceui.pixiv.feeds.FeedPage
import ceui.pixiv.feeds.FeedSource
import ceui.pixiv.feeds.feedViewModels
import ceui.pixiv.ui.common.UserFeedFragment
import ceui.pixiv.ui.common.UserFeedItem
import ceui.pixiv.ui.common.replayNextUrl
import com.google.gson.Gson

/**
 * 搜索「用户」tab（feeds 框架版，替代 legacy FragmentSearchUser + UAdapter）。复用 [UserFeedFragment]
 * 的用户卡渲染 / 关注切换 / LIKED_USER 广播同步，只加搜索特有逻辑：读 activity-scoped [SearchModel]
 * 最新 keyword 响应式重搜（observe nowGo → keyword 非空就 refresh，一比一换掉 legacy 的
 * repo.update + autoRefresh）。数据源不快照 keyword，见 [SearchUserFeedSource]。
 */
class SearchUserFeedFragment : UserFeedFragment() {

    private val searchModel: SearchModel by lazy(LazyThreadSafetyMode.NONE) {
        ViewModelProvider(requireActivity())[SearchModel::class.java]
    }

    override val feedViewModel by feedViewModels(autoLoad = false) {
        // 零捕获：捕获的是 activity-scoped SearchModel（生命周期 ≥ Activity，不是 Fragment），先取局部 val
        val searchModel = ViewModelProvider(requireActivity())[SearchModel::class.java]
        SearchUserFeedSource(searchModel)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // 搜索触发：keyword 非空才重搜（对齐 legacy FragmentSearchUser 的 guard）。
        searchModel.nowGo.observe(viewLifecycleOwner) {
            if (!searchModel.keyword.value.isNullOrBlank()) {
                feedViewModel.refresh()
            }
        }
    }

    companion object {
        @JvmStatic
        fun newInstance(): SearchUserFeedFragment = SearchUserFeedFragment()
    }
}

/**
 * 搜索用户数据源：读 [SearchModel] 最新 keyword（不快照），keyword 空返回空页（不打服务端，
 * 对齐 legacy 的 keyword 非空 guard）。翻页走 [replayNextUrl]（UserPreviewResponse 实现 KListShow）。
 */
class SearchUserFeedSource(private val searchModel: SearchModel) : FeedSource<String> {

    private val gson = Gson()

    override suspend fun load(cursor: String?): FeedPage<String> {
        val resp: UserPreviewResponse = if (cursor == null) {
            val word = searchModel.keyword.value?.trim().orEmpty()
            if (word.isEmpty()) return FeedPage(emptyList(), null)
            Client.appApi.searchUser(word)
        } else {
            replayNextUrl(gson, cursor, UserPreviewResponse::class.java)
        }
        val items = resp.user_previews.map { UserFeedItem(it) }
        return FeedPage(items, resp.next_url?.takeIf { it.isNotEmpty() })
    }
}
