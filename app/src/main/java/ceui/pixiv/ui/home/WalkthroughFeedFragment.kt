package ceui.pixiv.ui.home

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import androidx.viewbinding.ViewBinding
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.activities.VActivity
import ceui.lisa.core.Container
import ceui.lisa.core.PageData
import ceui.lisa.databinding.FragmentWalkthroughFeedBinding
import ceui.lisa.databinding.ItemWalkthroughIllustBinding
import ceui.lisa.download.IllustDownload
import ceui.lisa.dialogs.MuteDialog
import ceui.lisa.helper.IllustNovelFilter
import ceui.lisa.model.ListIllust
import ceui.lisa.models.IllustsBean
import ceui.lisa.utils.GlideUrlChild
import ceui.lisa.utils.Params
import ceui.lisa.utils.PixivOperate
import ceui.loxia.Client
import ceui.loxia.Illust
import ceui.loxia.ObjectPool
import ceui.loxia.requireEntityWrapper
import ceui.pixiv.feeds.FeedCell
import ceui.pixiv.feeds.FeedFragment
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.FeedRenderer
import ceui.pixiv.feeds.PixivFeedSource
import ceui.pixiv.feeds.feedRenderer
import ceui.pixiv.feeds.feedViewModels
import ceui.pixiv.ui.common.setUpToolbar
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.ui.detail.showV3Menu
import ceui.pixiv.ui.slideshow.SlideshowLauncher
import ceui.pixiv.utils.setOnClick
import com.bumptech.glide.Glide
import kotlinx.coroutines.launch
import java.util.UUID

/** 收藏状态局部重绑的 payload 标记（按引用识别）。 */
private val PAYLOAD_LIKE_CHANGED = Any()

/**
 * 「画廊」页（发现 tab · 其他分类入口），feeds 框架的首个线上页面，替代 legacy FragmentWalkThrough。
 *
 * 宿主是 TemplateActivity（非 NavHost），点击开详情必须走 PageData + Container + VActivity，
 * 不能用 findNavController（同稍后再看页的约束）。与 legacy 页的行为对齐点：
 * - 内容过滤链（is_visible / 屏蔽标签·画师·作品 / R-18 / 屏蔽 AI）在 mapper 里应用，
 *   整页被滤空时由 FeedViewModel 的空页追载继续翻（#729 语义）；
 * - 卡片收藏爱心（私密收藏设置、收藏后自动下载 #880）+ LIKED_ILLUST 广播双向同步；
 * - 长按菜单（屏蔽 / 评论 / 下载 / 幻灯片 / 稍后再看）；
 * - 详情 pager 续拉的页经 FRAGMENT_ADD_DATA 追加回列表并用 adoptCursor 接管翻页进度，
 *   FRAGMENT_SCROLL_TO_POSITION 让返回时列表跟到正在看的作品。
 */
class WalkthroughFeedFragment : FeedFragment(R.layout.fragment_walkthrough_feed) {

    private val binding by viewBinding(FragmentWalkthroughFeedBinding::bind)

    /** 页面协同状态归 VM：Fragment 重建（旋转/深色切换）后详情回传链路不断。 */
    private val syncViewModel: WalkthroughSyncViewModel by viewModels()

    override val feedViewModel by feedViewModels {
        PixivFeedSource({ Client.appApi.getWalkthroughWorks() }) { resp, _ ->
            resp.displayList.mapNotNull { GalleryIllustItem.from(it) }
        }
    }

    /** 收藏状态双向同步：详情页/其他列表点的收藏，经广播回流本列表。 */
    private val likedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val extras = intent?.extras ?: return
            val illustId = extras.getInt(Params.ID).toLong()
            val isLiked = extras.getBoolean(Params.IS_LIKED)
            feedViewModel.updateItems(GalleryIllustItem::class.java) { item ->
                if (item.illust.id == illustId) item.withBookmarked(isLiked) else item
            }
        }
    }

    /** 详情 pager 用 nextUrl 续拉的页，追加回列表并接管游标。 */
    private val detailAddDataReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val extras = intent?.extras ?: return
            if (extras.getString(Params.PAGE_UUID) != syncViewModel.listPageUuid) return
            val listIllust = extras.getSerializable(Params.CONTENT) as? ListIllust ?: return
            val fresh = listIllust.list.orEmpty().mapNotNull { GalleryIllustItem.fromBean(it) }
            feedViewModel.appendItems(fresh)
            feedViewModel.adoptCursor(listIllust.nextUrl?.takeIf { it.isNotEmpty() })
        }
    }

    /** 返回时列表跟到详情页正在看的那张。 */
    private val detailScrollReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val extras = intent?.extras ?: return
            if (extras.getString(Params.PAGE_UUID) != syncViewModel.listPageUuid) return
            val index = extras.getInt(Params.INDEX)
            feedBinding.feedListView.postDelayed({
                val adapter = feedAdapter ?: return@postDelayed
                if (view != null && index in 0 until adapter.itemCount) {
                    feedBinding.feedListView.smoothScrollToPosition(index)
                }
            }, 200L)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpToolbar(binding.toolbarLayout, feedBinding.feedListView)
        binding.toolbarLayout.naviTitle.setText(R.string.walkthrough)

        LocalBroadcastManager.getInstance(requireContext()).apply {
            registerReceiver(likedReceiver, IntentFilter(Params.LIKED_ILLUST))
            registerReceiver(detailAddDataReceiver, IntentFilter(Params.FRAGMENT_ADD_DATA))
            registerReceiver(detailScrollReceiver, IntentFilter(Params.FRAGMENT_SCROLL_TO_POSITION))
        }

        // 列表数据落地后把最新 bean 合入 ObjectPool（主线程；对齐 legacy Mapper 的池同步职责，
        // 否则 V3 详情命中旧池条目会渲染过期的收藏数/爱心）。按 bean 实例去重：同一实例只合一次，
        // 刷新产出的同 id 新实例携带更新的服务端数据，必须重新合入——按 id 永久去重会把它挡在池外。
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                var scannedItems: List<FeedItem>? = null
                feedViewModel.uiState.collect { state ->
                    // 只有条目列表本身换了才扫描；纯加载态变化（append Loading/Idle）直接跳过
                    if (state.items === scannedItems) return@collect
                    scannedItems = state.items
                    state.items.forEach { item ->
                        if (item is GalleryIllustItem &&
                            syncViewModel.pooledBeans.put(item.illust.id, item.bean) !== item.bean
                        ) {
                            ObjectPool.updateIllust(item.bean)
                        }
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        LocalBroadcastManager.getInstance(requireContext()).apply {
            unregisterReceiver(likedReceiver)
            unregisterReceiver(detailAddDataReceiver)
            unregisterReceiver(detailScrollReceiver)
        }
        super.onDestroyView()
    }

    override fun onCreateRenderers(): List<FeedRenderer<out FeedItem, out ViewBinding>> {
        return listOf(illustRenderer())
    }

    override fun onCreateLayoutManager(): RecyclerView.LayoutManager {
        return StaggeredGridLayoutManager(
            Shaft.sSettings.lineCount,
            StaggeredGridLayoutManager.VERTICAL,
        )
    }

    private fun illustRenderer() = feedRenderer<GalleryIllustItem, ItemWalkthroughIllustBinding>(
        inflate = ItemWalkthroughIllustBinding::inflate,
        create = { cell ->
            cell.binding.root.setOnClick { openDetail(cell.item) }
            cell.binding.root.setOnLongClickListener {
                showCardMenu(cell.item)
                true
            }
            cell.binding.likeButton.setOnClick { toggleLike(cell) }
        },
        recycle = { cell ->
            Glide.with(cell.binding.illustImage).clear(cell.binding.illustImage)
        },
        // 只有收藏状态变了 → 局部重绑爱心，不再重发 Glide 请求、不再改 layoutParams
        //（layoutParams 赋值会 requestLayout，在瀑布流里可能引发整屏 relayout）
        changePayload = { old, new ->
            if (old.illust.copy(is_bookmarked = new.illust.is_bookmarked) == new.illust) {
                PAYLOAD_LIKE_CHANGED
            } else {
                null
            }
        },
        bindPayloads = { cell, payloads ->
            if (payloads.all { it === PAYLOAD_LIKE_CHANGED }) {
                renderLikeState(cell.binding.likeButton, cell.item.illust.is_bookmarked == true)
                true
            } else {
                false
            }
        },
    ) { cell ->
        val illust = cell.item.illust
        // 卡片比例来自作品元数据（square_medium 恒为方图，不能等 Glide 量像素）；极端长/扁图钳到 1:1.8 / 1:0.5
        val width = illust.width
        val height = illust.height
        val params = cell.binding.illustImage.layoutParams as ConstraintLayout.LayoutParams
        val ratio = if (width > 0 && height > 0) {
            "$width:${height.coerceIn((width * 0.5f).toInt(), (width * 1.8f).toInt())}"
        } else {
            "1:1"
        }
        if (params.dimensionRatio != ratio) {
            params.dimensionRatio = ratio
            cell.binding.illustImage.layoutParams = params
        }

        val urls = illust.image_urls
        val url = if (Shaft.sSettings.isShowLargeThumbnailImage()) urls?.large else urls?.medium
        if (url.isNullOrEmpty()) {
            // 受限/打码作品可能缺缩略图，留圆角占位底色，不能把 null 交给 GlideUrlChild（NPE）
            Glide.with(cell.binding.illustImage).clear(cell.binding.illustImage)
        } else {
            Glide.with(cell.binding.illustImage)
                .load(GlideUrlChild(url))
                .into(cell.binding.illustImage)
        }
        cell.binding.illustTitle.text = illust.title
        renderLikeState(cell.binding.likeButton, illust.is_bookmarked == true)
    }

    private fun renderLikeState(button: ImageView, liked: Boolean) {
        val context = button.context
        button.imageTintList = ColorStateList.valueOf(
            ContextCompat.getColor(
                context,
                if (liked) R.color.has_bookmarked else R.color.not_bookmarked,
            )
        )
    }

    private fun toggleLike(cell: FeedCell<GalleryIllustItem, ItemWalkthroughIllustBinding>) {
        val item = cell.item
        // 单一真源是 item.illust（UI 由它渲染）。bean 可能因上次请求失败与 UI 背离——
        // PixivOperate.postLike 按 bean 当前值决定收藏还是取消，先把 bean 校准回 UI 状态，
        // 保证发出的请求永远与用户看到的操作一致。
        val uiLiked = item.illust.is_bookmarked == true
        item.bean.setIs_bookmarked(uiLiked)
        val willBookmark = !uiLiked
        PixivOperate.postLike(
            item.bean,
            if (Shaft.sSettings.isPrivateStar()) Params.TYPE_PRIVATE else Params.TYPE_PUBLIC,
        )
        // 乐观状态写进列表条目而不是只写按钮：滑走再滑回不闪色；成功广播回流后是幂等 no-op
        feedViewModel.updateItems(GalleryIllustItem::class.java) {
            if (it.illust.id == item.illust.id) it.withBookmarked(willBookmark) else it
        }
        // 收藏后自动下载只在主动收藏（非取消）时触发，避免与「下载时自动收藏」循环联动（#880）
        if (willBookmark && Shaft.sSettings.isAutoDownloadAfterStar()) {
            IllustDownload.downloadIllustAllPages(item.bean)
        }
    }

    private fun showCardMenu(item: GalleryIllustItem) {
        val bean = item.bean
        val entityWrapper = requireEntityWrapper()
        val inWatchLater = entityWrapper.isInWatchLater(item.illust.id)
        showV3Menu("GalleryCardMenu") {
            item(getString(R.string.string_111), R.drawable.ic_not_interested_black_24dp) {
                MuteDialog.newInstance(bean).show(childFragmentManager, "MuteDialog")
            }
            item(getString(R.string.string_112), R.drawable.ic_baseline_comment_24) {
                startActivity(Intent(requireContext(), TemplateActivity::class.java).apply {
                    putExtra(TemplateActivity.EXTRA_FRAGMENT, "相关评论")
                    putExtra(Params.ILLUST_ID, bean.id)
                    putExtra(Params.ILLUST_TITLE, bean.title)
                })
            }
            item(getString(R.string.string_339), R.drawable.ic_file_download_black_24dp) {
                IllustDownload.downloadIllustAllPages(bean)
                if (Shaft.sSettings.isAutoPostLikeWhenDownload() && !bean.isIs_bookmarked) {
                    PixivOperate.postLikeDefaultStarType(bean)
                }
            }
            item(getString(R.string.slideshow_play), R.drawable.ic_baseline_play_arrow_24) {
                val beans = currentGalleryItems().map { it.bean }
                val position = beans.indexOfFirst { it.id == bean.id }.coerceAtLeast(0)
                SlideshowLauncher.launchFromIllustsBeans(
                    requireContext(), ArrayList(beans), position, true,
                )
            }
            val watchLaterLabel = getString(
                if (inWatchLater) R.string.watch_later_remove else R.string.watch_later_add
            )
            item(watchLaterLabel, R.drawable.ic_watch_later_24) {
                val appContext = requireContext().applicationContext
                if (inWatchLater) {
                    entityWrapper.removeFromWatchLater(appContext, item.illust.id)
                } else {
                    entityWrapper.addToWatchLater(appContext, item.illust)
                }
            }
        }
    }

    private fun currentGalleryItems(): List<GalleryIllustItem> {
        return feedViewModel.uiState.value.items.filterIsInstance<GalleryIllustItem>()
    }

    private fun openDetail(item: GalleryIllustItem) {
        val galleryItems = currentGalleryItems()
        val position = galleryItems.indexOfFirst { it.illust.id == item.illust.id }
        // uuid 用 VM 里的稳定值：Container 的 map 永不清理，稳定 key 让本列表最多占一个坑
        //（每次打开覆盖上一份快照，对齐 legacy），Fragment 重建后回传广播也仍能认领。
        val pageData = if (position >= 0) {
            // nextUrl 一并交接给 VActivity，详情页 pager 划到底可以继续加载
            PageData(
                syncViewModel.listPageUuid,
                feedViewModel.currentCursor,
                galleryItems.map { it.bean },
            )
        } else {
            // 点击项已不在当前列表（刷新竞态等）：单开该作品，绝不错开成第一张。
            // 故意用一次性 uuid：这份单作品 PageData 的 ADD_DATA/SCROLL_TO（index 0）
            // 和主列表无关，不能被上面的接收器认领去把列表滚回顶部。
            PageData(UUID.randomUUID().toString(), null, listOf(item.bean))
        }
        Container.get().addPageToMap(pageData)
        startActivity(Intent(requireContext(), VActivity::class.java).apply {
            putExtra(Params.POSITION, position.coerceAtLeast(0))
            putExtra(Params.PAGE_UUID, pageData.getUUID())
        })
    }
}

/**
 * 画廊页跨 Fragment 重建存活的协同状态（数据归 ViewModel 约定）。
 */
class WalkthroughSyncViewModel : ViewModel() {

    /**
     * 本列表在 Container 里的稳定 PageData key：每次打开详情覆盖同一个坑（Container
     * 永不清理，随机 uuid 会积攒整列表快照）；Fragment 重建后详情回传广播仍能认领。
     */
    val listPageUuid: String = UUID.randomUUID().toString()

    /**
     * 已合入 ObjectPool 的 bean 实例（id → 当前代实例）。刷新会产出同 id 的新实例、
     * 携带更新的服务端数据，实例变了就要重新合入；同一实例重复扫描则跳过。
     */
    val pooledBeans = HashMap<Long, IllustsBean>()
}

/**
 * 内容相等性只看 [illust]（immutable data class，深比较）：bean 是 legacy 可变对象、
 * 没有 equals，参与比较会让每次刷新的同内容条目都被判成「变了」而整列表白白重绑。
 */
private class GalleryIllustItem(
    val illust: Illust,
    val bean: IllustsBean,
) : FeedItem {

    override val feedKey: Any get() = illust.id

    override fun equals(other: Any?): Boolean {
        return other is GalleryIllustItem && other.illust == illust
    }

    override fun hashCode(): Int = illust.hashCode()

    /**
     * 收藏状态变更：bean 是可变对象且与详情页 pager 共享同一实例，就地写；
     * illust 走 copy 让相等性变化，驱动 DiffUtil 原地重绑爱心。
     */
    fun withBookmarked(liked: Boolean): GalleryIllustItem {
        bean.setIs_bookmarked(liked)
        return GalleryIllustItem(illust.copy(is_bookmarked = liked), bean)
    }

    companion object {
        /** loxia.Illust 与 IllustsBean 字段名完全一致，gson 直转（同稍后再看页的做法）。 */
        fun from(illust: Illust): GalleryIllustItem? {
            val bean = runCatching {
                Shaft.sGson.fromJson(Shaft.sGson.toJson(illust), IllustsBean::class.java)
            }.getOrNull() ?: return null
            if (!passesContentFilters(bean)) return null
            return GalleryIllustItem(illust, bean)
        }

        /** 详情 pager 回传的是 legacy bean，反向转一次。 */
        fun fromBean(bean: IllustsBean?): GalleryIllustItem? {
            if (bean == null || !passesContentFilters(bean)) return null
            val illust = runCatching {
                Shaft.sGson.fromJson(Shaft.sGson.toJson(bean), Illust::class.java)
            }.getOrNull() ?: return null
            return GalleryIllustItem(illust, bean)
        }

        /**
         * 与 legacy Mapper 对齐的内容过滤链（搜索专属的 R18 三态/仅看 AI 不适用本页）。
         * 整页被滤空时由 FeedViewModel 空页追载兜住，不会翻页停摆。
         */
        private fun passesContentFilters(bean: IllustsBean): Boolean {
            if (!bean.isVisible) return false
            if (IllustNovelFilter.judgeTag(bean)) return false
            if (IllustNovelFilter.judgeID(bean)) return false
            if (IllustNovelFilter.judgeUserID(bean)) return false
            if (IllustNovelFilter.judgeR18Filter(bean)) return false
            if (Shaft.sSettings.isDeleteAIIllust() && bean.isCreatedByAI) return false
            return true
        }
    }
}
