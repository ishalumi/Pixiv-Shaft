package ceui.pixiv.ui.search

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import androidx.lifecycle.ViewModelProvider
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import ceui.lisa.R
import ceui.lisa.databinding.RecyUserPreviewBinding
import ceui.lisa.utils.GlideUtil
import ceui.lisa.utils.Params
import ceui.lisa.utils.PixivOperate
import ceui.lisa.view.LinearItemDecoration
import ceui.lisa.viewmodel.SearchModel
import ceui.loxia.Client
import ceui.loxia.User
import ceui.loxia.UserPreview
import ceui.loxia.UserPreviewResponse
import ceui.pixiv.feeds.FeedCell
import ceui.pixiv.feeds.FeedFragment
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.FeedPage
import ceui.pixiv.feeds.FeedRenderer
import ceui.pixiv.feeds.FeedSource
import ceui.pixiv.feeds.FeedViewModel
import ceui.pixiv.feeds.feedRenderer
import ceui.pixiv.feeds.feedViewModels
import ceui.pixiv.ui.common.openUserActivity
import ceui.pixiv.ui.common.replayNextUrl
import ceui.pixiv.utils.ppppx
import ceui.pixiv.utils.setOnClick
import com.bumptech.glide.Glide
import com.google.gson.Gson

/** 关注态局部重绑的 payload 标记（按引用识别）。 */
private val PAYLOAD_USER_FOLLOW = Any()

/**
 * 搜索「用户」tab（feeds 框架版，替代 legacy FragmentSearchUser + UAdapter）。feeds 框架此前
 * 没有用户列表基建，这是第一份：[UserFeedItem] + recy_user_preview 渲染器 + 关注切换 + 点击进画师页。
 *
 * 响应式重搜：数据源读 activity-scoped [SearchModel] 的最新 keyword（不快照，见 [SearchUserFeedSource]），
 * fragment observe nowGo → keyword 非空就 refresh（一比一换掉 legacy 的 repo.update+autoRefresh）。
 * 关注复用 legacy [PixivOperate] 的 follow op（发 LIKED_USER 广播 + ObjectPool + toast，无损），
 * 并 observe LIKED_USER 回流同步（对齐 legacy UAdapter 的 CommonReceiver）。
 */
class SearchUserFeedFragment : FeedFragment() {

    private val searchModel: SearchModel by lazy(LazyThreadSafetyMode.NONE) {
        ViewModelProvider(requireActivity())[SearchModel::class.java]
    }

    override val feedViewModel by feedViewModels(autoLoad = false) {
        // 零捕获：捕获的是 activity-scoped SearchModel（生命周期 ≥ Activity，不是 Fragment），先取局部 val
        val searchModel = ViewModelProvider(requireActivity())[SearchModel::class.java]
        SearchUserFeedSource(searchModel)
    }

    /** 关注态跨列表同步：其它页/本页关注某用户 → LIKED_USER 广播回流。 */
    private val likedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val extras = intent?.extras ?: return
            val userId = extras.getInt(Params.ID).toLong()
            val followed = extras.getBoolean(Params.IS_LIKED)
            feedViewModel.updateItems(UserFeedItem::class.java) { item ->
                if (item.user?.id == userId) item.withFollowed(followed) else item
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        LocalBroadcastManager.getInstance(requireContext())
            .registerReceiver(likedReceiver, IntentFilter(Params.LIKED_USER))
        // 搜索触发：keyword 非空才重搜（对齐 legacy FragmentSearchUser 的 guard）。
        searchModel.nowGo.observe(viewLifecycleOwner) {
            if (!searchModel.keyword.value.isNullOrBlank()) {
                feedViewModel.refresh()
            }
        }
    }

    override fun onDestroyView() {
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(likedReceiver)
        super.onDestroyView()
    }

    override fun onListReady(listView: RecyclerView) {
        listView.addItemDecoration(LinearItemDecoration(12.ppppx))
    }

    override fun onCreateRenderers(): List<FeedRenderer<out FeedItem, out ViewBinding>> {
        return listOf(userRenderer())
    }

    private fun userRenderer() = feedRenderer<UserFeedItem, RecyUserPreviewBinding>(
        inflate = RecyUserPreviewBinding::inflate,
        create = { cell ->
            cell.binding.root.setOnClick { cell.item.user?.id?.let { openUserActivity(it) } }
            cell.binding.postLikeUser.setOnClick { toggleFollow(cell) }
            cell.binding.postLikeUser.setOnLongClickListener {
                privateFollow(cell)
                true
            }
        },
        recycle = { cell ->
            Glide.with(cell.binding.userHead).clear(cell.binding.userHead)
            listOf(cell.binding.userShowOne, cell.binding.userShowTwo, cell.binding.userShowThree)
                .forEach { Glide.with(it).clear(it) }
        },
        changePayload = { old, new ->
            // 只有关注态变 → 局部重绑关注按钮，不重跑 3 张预览图 + 头像 Glide（对齐插画/小说卡）。
            val ou = old.user
            val nu = new.user
            if (ou != null && nu != null &&
                old.preview.copy(user = ou.copy(is_followed = nu.is_followed)) == new.preview
            ) PAYLOAD_USER_FOLLOW else null
        },
        bindPayloads = { cell, payloads ->
            if (payloads.all { it === PAYLOAD_USER_FOLLOW }) {
                renderFollow(cell.binding, cell.item.user?.is_followed == true)
                true
            } else {
                false
            }
        },
    ) { cell -> bindUser(cell) }

    private fun bindUser(cell: FeedCell<UserFeedItem, RecyUserPreviewBinding>) {
        val b = cell.binding
        val preview = cell.item.preview
        val user = preview.user
        val ctx = b.root.context

        // 3 张方形预览图，边长 = 屏宽/3（对齐 legacy UAdapter）。只显示插画预览（用户裁决：不足留空）。
        val size = ctx.resources.displayMetrics.widthPixels / 3
        val slots = listOf(b.userShowOne, b.userShowTwo, b.userShowThree)
        slots.forEach { iv ->
            iv.layoutParams = iv.layoutParams.apply { width = size; height = size }
        }
        val illusts = preview.illusts
        slots.forEachIndexed { i, iv ->
            val illust = illusts.getOrNull(i)
            val url = illust?.image_urls?.let { it.square_medium ?: it.medium }
            Glide.with(ctx).load(GlideUtil.getUrl(url)).placeholder(R.color.light_bg).into(iv)
        }

        b.userName.text = user?.name ?: ""
        Glide.with(ctx).load(GlideUtil.getUrl(user?.profile_image_urls?.medium))
            .error(R.drawable.no_profile).into(b.userHead)
        renderFollow(b, user?.is_followed == true)
    }

    /** 关注按钮文案：已关注 / 关注。 */
    private fun renderFollow(b: RecyUserPreviewBinding, followed: Boolean) {
        b.postLikeUser.text = getString(if (followed) R.string.post_unfollow else R.string.post_follow)
    }

    private fun toggleFollow(cell: FeedCell<UserFeedItem, RecyUserPreviewBinding>) {
        val user = cell.item.user ?: return
        val target = user.is_followed != true
        renderFollow(cell.binding, target) // 当帧翻文案（异步 updateItems 落地兜底）
        applyFollow(user.id, target)
        // 复用 legacy follow op：发 LIKED_USER 广播 + ObjectPool + toast + 埋点（无损）。
        if (target) {
            PixivOperate.postFollowUser(user.id.toInt(), Params.TYPE_PUBLIC)
        } else {
            PixivOperate.postUnFollowUser(user.id.toInt())
        }
    }

    /** 长按 = 私密关注（对齐 legacy UAdapter.onItemLongClick）。 */
    private fun privateFollow(cell: FeedCell<UserFeedItem, RecyUserPreviewBinding>) {
        val user = cell.item.user ?: return
        renderFollow(cell.binding, true)
        applyFollow(user.id, true)
        PixivOperate.postFollowUser(user.id.toInt(), Params.TYPE_PRIVATE)
    }

    private fun applyFollow(userId: Long, followed: Boolean) {
        feedViewModel.updateItems(UserFeedItem::class.java) { item ->
            if (item.user?.id == userId) item.withFollowed(followed) else item
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
