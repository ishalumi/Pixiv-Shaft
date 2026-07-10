package ceui.pixiv.ui.common

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import androidx.annotation.LayoutRes
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.activities.VActivity
import ceui.lisa.core.Container
import ceui.lisa.core.PageData
import ceui.lisa.databinding.RecyIllustStaggerBinding
import ceui.lisa.dialogs.MuteDialog
import ceui.lisa.download.IllustDownload
import ceui.lisa.helper.AppLevelViewModelHelper
import ceui.lisa.helper.IllustNovelFilter
import ceui.lisa.helper.StaggeredManager
import ceui.lisa.model.ListIllust
import ceui.lisa.models.IllustsBean
import ceui.lisa.utils.DensityUtil
import ceui.lisa.utils.GlideUtil
import ceui.lisa.utils.Params
import ceui.lisa.utils.PixivOperate
import ceui.lisa.view.SpacesItemDecoration
import ceui.loxia.Illust
import ceui.loxia.ObjectPool
import ceui.loxia.requireEntityWrapper
import ceui.pixiv.feeds.FeedFragment
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.FeedRenderer
import ceui.pixiv.feeds.FeedViewModel
import ceui.pixiv.feeds.feedRenderer
import ceui.pixiv.ui.bulk.BulkSelectStorage
import ceui.pixiv.ui.detail.showV3Menu
import ceui.pixiv.ui.recommend.bindTrendingScore
import ceui.pixiv.ui.slideshow.SlideshowLauncher
import ceui.pixiv.utils.setOnClick
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID

/** 收藏状态局部重绑的 payload 标记（按引用识别），插画 feed 卡片共用。 */
val PAYLOAD_ILLUST_LIKE_CHANGED = Any()

/** 瀑布流卡片高度钳制（对齐 legacy IAdapter）：高 = 宽的 0.6~2.0 倍。 */
private const val MIN_HEIGHT_RATIO = 0.6f
private const val MAX_HEIGHT_RATIO = 2.0f

/**
 * 只有收藏状态变了 → 给出局部重绑 payload；其他字段有变化则回退全量绑定。
 * 各插画卡 Renderer 的 changePayload 直接引用本函数。
 */
fun illustLikeChangePayload(old: IllustFeedItem, new: IllustFeedItem): Any? {
    return if (old.illust.copy(is_bookmarked = new.illust.is_bookmarked) == new.illust) {
        PAYLOAD_ILLUST_LIKE_CHANGED
    } else {
        null
    }
}

/**
 * 插画瀑布流列表页的共享基类（feeds 框架 + legacy 详情/收藏/菜单协同的桥接层）。
 * 子类只需声明数据源（feedViewModels + mapper）和卡片 Renderer；本类统一提供：
 *
 * - 宿主是 TemplateActivity / 普通 Activity（非 NavHost）：点击开详情走
 *   PageData + Container + VActivity，不能用 findNavController；
 * - 卡片收藏爱心（私密收藏设置、收藏后自动下载 #880）+ LIKED_ILLUST 广播双向同步；
 * - 长按菜单（屏蔽 / 评论 / 批量下载 / 单作品下载 / 幻灯片 / 稍后再看）；
 * - 详情 pager 续拉的页经 FRAGMENT_ADD_DATA 追加回列表并用 adoptCursor 接管翻页进度，
 *   FRAGMENT_SCROLL_TO_POSITION 让返回时列表跟到正在看的作品；
 * - 列表数据落地后把 bean 合入 ObjectPool（按实例去重，刷新的新一代实例会重新合入）；
 * - StaggeredManager 瀑布流（吞 SGLM predictive-layout 在 fling+插页同帧时的 AOSP 内部崩溃）。
 */
abstract class IllustFeedFragment(
    @LayoutRes contentLayoutId: Int = R.layout.fragment_feed,
) : FeedFragment(contentLayoutId) {

    abstract override val feedViewModel: FeedViewModel<String>

    /** 页面协同状态归 VM：Fragment 重建（旋转/深色切换）后详情回传链路不断。 */
    protected val syncViewModel: IllustFeedSyncViewModel by viewModels()

    /**
     * 详情 pager 回传的 bean 建条目的钩子。R18 专属榜单等「本页语义就是看 R18」的
     * 子类覆盖此方法透传 skipR18Filter，否则续拉的页会被全局 R18 过滤整页清空。
     */
    protected open fun feedItemFromBean(bean: IllustsBean?): IllustFeedItem? {
        return IllustFeedItem.fromBean(bean)
    }

    /**
     * 收藏时是否顺带拉取相关作品（FRAGMENT_ADD_RELATED_DATA 广播回流插入列表）。
     * 只有首页推荐 tab 覆盖为「收藏时显示相关作品」设置，其他列表保持关闭。
     */
    protected open val showRelatedOnStar: Boolean
        get() = false

    /**
     * 条目里需要合入 ObjectPool / 灌关注状态的 bean。默认取插画条目自身的 bean；
     * 携带附属 bean 的条目（排行榜预览头等）由子类覆盖补充。
     */
    protected open fun poolableBeansOf(item: FeedItem): List<IllustsBean> {
        return if (item is IllustFeedItem) listOf(item.bean) else emptyList()
    }

    /** 收藏状态双向同步：详情页/其他列表点的收藏，经广播回流本列表。 */
    private val likedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val extras = intent?.extras ?: return
            val illustId = extras.getInt(Params.ID).toLong()
            val isLiked = extras.getBoolean(Params.IS_LIKED)
            feedViewModel.updateItems(IllustFeedItem::class.java) { item ->
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
            val fresh = listIllust.list.orEmpty().mapNotNull { feedItemFromBean(it) }
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

        LocalBroadcastManager.getInstance(requireContext()).apply {
            registerReceiver(likedReceiver, IntentFilter(Params.LIKED_ILLUST))
            registerReceiver(detailAddDataReceiver, IntentFilter(Params.FRAGMENT_ADD_DATA))
            registerReceiver(detailScrollReceiver, IntentFilter(Params.FRAGMENT_SCROLL_TO_POSITION))
        }

        // 列表数据落地后把最新 bean 合入 ObjectPool（主线程；对齐 legacy Mapper 的池同步职责，
        // 否则 V3 详情命中旧池条目会渲染过期的收藏数/爱心），并把作者关注状态灌进
        // AppLevelViewModel（对齐 legacy NetListFragment 每页 tidyAppViewModel，
        // UActivity/UserActivityV3 的关注按钮消费它）。按 bean 实例去重：同一实例只合一次，
        // 刷新产出的同 id 新实例携带更新的服务端数据，必须重新合入——按 id 永久去重会把它挡在池外。
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                var scannedItems: List<FeedItem>? = null
                feedViewModel.uiState.collect { state ->
                    // 只有条目列表本身换了才扫描；纯加载态变化（append Loading/Idle）直接跳过
                    if (state.items === scannedItems) return@collect
                    scannedItems = state.items
                    val freshBeans = mutableListOf<IllustsBean>()
                    state.items.forEach { item ->
                        poolableBeansOf(item).forEach { bean ->
                            if (syncViewModel.pooledBeans.put(bean.id.toLong(), bean) !== bean) {
                                ObjectPool.updateIllust(bean)
                                freshBeans.add(bean)
                            }
                        }
                    }
                    if (freshBeans.isNotEmpty()) {
                        AppLevelViewModelHelper.fill(freshBeans)
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

    override fun onCreateLayoutManager(): RecyclerView.LayoutManager {
        return StaggeredManager(
            Shaft.sSettings.lineCount,
            RecyclerView.VERTICAL,
        )
    }

    override fun onListReady(listView: RecyclerView) {
        // recy_illust_stagger 卡片自身无 margin，间距对齐 legacy staggerRecyclerView
        listView.addItemDecoration(SpacesItemDecoration(DensityUtil.dp2px(8.0f)))
    }

    /** 默认就是标准瀑布流插画卡；需要混排其他条目类型的子类自行覆盖再拼上。 */
    override fun onCreateRenderers(): List<FeedRenderer<out FeedItem, out ViewBinding>> {
        return listOf(staggerIllustRenderer())
    }

    /**
     * 标准瀑布流插画卡（recy_illust_stagger，与 legacy IAdapter 同一张布局同一套行为）：
     * NP 页数 / GIF / R-18 / AI / NEW 角标、爱心收藏（局部重绑）、爱心长按进「按标签收藏」、
     * 点击开详情、长按弹卡片菜单，比例钳制 0.6~2.0。
     */
    protected fun staggerIllustRenderer() = feedRenderer<IllustFeedItem, RecyIllustStaggerBinding>(
        inflate = RecyIllustStaggerBinding::inflate,
        create = { cell ->
            cell.binding.root.setOnClick { openDetail(cell.item) }
            cell.binding.root.setOnLongClickListener {
                showCardMenu(cell.item)
                true
            }
            cell.binding.likeButton.setOnClick { toggleLike(cell.item) }
            // 爱心长按 → 按标签收藏（对齐 IAdapter）
            cell.binding.likeButton.setOnLongClickListener {
                val bean = cell.item.bean
                startActivity(Intent(requireContext(), TemplateActivity::class.java).apply {
                    putExtra(Params.ILLUST_ID, bean.id)
                    putExtra(Params.DATA_TYPE, Params.TYPE_ILLUST)
                    putExtra(Params.TAG_NAMES, bean.tagNames)
                    putExtra(TemplateActivity.EXTRA_FRAGMENT, "按标签收藏")
                })
                true
            }
        },
        recycle = { cell ->
            Glide.with(cell.binding.illustImage).clear(cell.binding.illustImage)
        },
        changePayload = ::illustLikeChangePayload,
        bindPayloads = { cell, payloads ->
            if (payloads.all { it === PAYLOAD_ILLUST_LIKE_CHANGED }) {
                renderLikeState(cell.binding.likeButton, cell.item.illust.is_bookmarked == true)
                true
            } else {
                false
            }
        },
    ) { cell ->
        val bean = cell.item.bean
        // 只按元数据驱动宽高比（钳到宽的 0.6~2.0 倍，对齐 IAdapter），不等 Glide 量像素；
        // 宽度交给瀑布流列自身，DynamicHeightImageView 在 onMeasure 用真实列宽算高——
        // 绝不写死像素尺寸，否则复用卡片在横竖屏切换后揣着旧方向的尺寸把整列搞乱
        val ratio = if (bean.width > 0 && bean.height > 0) {
            (bean.height.toFloat() / bean.width.toFloat())
                .coerceIn(MIN_HEIGHT_RATIO, MAX_HEIGHT_RATIO)
        } else {
            1f
        }
        cell.binding.illustImage.setHeightRatio(ratio)

        val imgUrl = if (Shaft.sSettings.isShowLargeThumbnailImage()) {
            GlideUtil.getLargeImage(bean)
        } else {
            GlideUtil.getMediumImg(bean)
        }
        // 请求尺寸必须显式 override：into(ImageView) 对 centerCrop 会在解码阶段按「请求尺寸」
        // 的宽高比裁位图，而默认请求尺寸取复用卡片上一次布局残留的旧宽高（旧方向的列宽 ×
        // 上一张图的比例），横竖屏来回切后图会被裁得只剩一小块还发糊，且 view 重新量高后
        // Glide 不会重发请求。override 成当前列宽 × 钳制后比例，请求宽高比恒等于展示宽高比。
        // 列宽取 LayoutManager 实时宽度（measure 先于绑定，旋转后已是新方向的值），首帧兜底屏宽。
        val listWidth = feedBinding.feedListView.layoutManager?.width?.takeIf { it > 0 }
            ?: resources.displayMetrics.widthPixels
        val columnWidth = (listWidth / Shaft.sSettings.lineCount).coerceAtLeast(1)
        val columnHeight = (columnWidth * ratio).toInt()
        Glide.with(cell.binding.illustImage)
            .load(imgUrl)
            .override(columnWidth, columnHeight)
            .placeholder(R.color.second_light_bg)
            .transition(DrawableTransitionOptions.withCrossFade())
            .error(
                Glide.with(cell.binding.illustImage)
                    .load(imgUrl)
                    .override(columnWidth, columnHeight)
                    .placeholder(R.color.second_light_bg)
                    .transition(DrawableTransitionOptions.withCrossFade())
            )
            .into(cell.binding.illustImage)

        cell.binding.pSize.isVisible = bean.page_count > 1
        if (bean.page_count > 1) {
            cell.binding.pSize.text = String.format(Locale.getDefault(), "%dP", bean.page_count)
        }
        cell.binding.pGif.isVisible = bean.isGif
        cell.binding.r18Badge.isVisible = bean.isR18File
        cell.binding.createdByAi.isVisible = bean.isCreatedByAI
        cell.binding.pRelated.isVisible = bean.isRelated
        // 只有 trending repo 注入 trendingScore，其他页 null 走 GONE（对齐 IAdapter 复用语义）
        cell.binding.trendingScore.bindTrendingScore(bean.trendingScore)
        renderLikeState(cell.binding.likeButton, cell.item.illust.is_bookmarked == true)
    }

    protected fun renderLikeState(button: ImageView, liked: Boolean) {
        val context = button.context
        button.imageTintList = ColorStateList.valueOf(
            ContextCompat.getColor(
                context,
                if (liked) R.color.has_bookmarked else R.color.not_bookmarked,
            )
        )
    }

    protected fun toggleLike(item: IllustFeedItem) {
        // 单一真源是 item.illust（UI 由它渲染）。bean 可能因上次请求失败与 UI 背离——
        // PixivOperate.postLike 按 bean 当前值决定收藏还是取消，先把 bean 校准回 UI 状态，
        // 保证发出的请求永远与用户看到的操作一致。
        val uiLiked = item.illust.is_bookmarked == true
        item.bean.setIs_bookmarked(uiLiked)
        val willBookmark = !uiLiked
        // showRelatedOnStar 时 postLike 收藏成功后会拉相关作品并广播 FRAGMENT_ADD_RELATED_DATA；
        // index 是 legacy 位置语义（feeds 接收器改按广播里的作品 id 锚定），照传兼容
        PixivOperate.postLike(
            item.bean,
            if (Shaft.sSettings.isPrivateStar()) Params.TYPE_PRIVATE else Params.TYPE_PUBLIC,
            showRelatedOnStar,
            feedViewModel.uiState.value.items.indexOf(item),
        )
        // 乐观状态写进列表条目而不是只写按钮：滑走再滑回不闪色；成功广播回流后是幂等 no-op
        feedViewModel.updateItems(IllustFeedItem::class.java) {
            if (it.illust.id == item.illust.id) it.withBookmarked(willBookmark) else it
        }
        // 收藏后自动下载只在主动收藏（非取消）时触发，避免与「下载时自动收藏」循环联动（#880）
        if (willBookmark && Shaft.sSettings.isAutoDownloadAfterStar()) {
            IllustDownload.downloadIllustAllPages(item.bean)
        }
    }

    protected fun showCardMenu(item: IllustFeedItem) {
        val bean = item.bean
        val entityWrapper = requireEntityWrapper()
        val inWatchLater = entityWrapper.isInWatchLater(item.illust.id)
        showV3Menu("IllustFeedCardMenu") {
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
            item(getString(R.string.string_113), R.drawable.ic_file_download_black_24dp) {
                // 批量下载：整个列表交给 V3 多选页勾选（对齐 legacy IAdapter popup / MultiDownload）
                val beans = currentIllustItems().map { it.bean }
                if (beans.isNotEmpty()) {
                    BulkSelectStorage.put(beans)
                    startActivity(Intent(requireContext(), TemplateActivity::class.java).apply {
                        putExtra(TemplateActivity.EXTRA_FRAGMENT, "批量选择")
                    })
                }
            }
            item(getString(R.string.string_339), R.drawable.ic_file_download_black_24dp) {
                IllustDownload.downloadIllustAllPages(bean)
                if (Shaft.sSettings.isAutoPostLikeWhenDownload() && !bean.isIs_bookmarked) {
                    PixivOperate.postLikeDefaultStarType(bean)
                }
            }
            item(getString(R.string.slideshow_play), R.drawable.ic_baseline_play_arrow_24) {
                val beans = currentIllustItems().map { it.bean }
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

    protected fun currentIllustItems(): List<IllustFeedItem> {
        return feedViewModel.uiState.value.items.filterIsInstance<IllustFeedItem>()
    }

    protected fun openDetail(item: IllustFeedItem) {
        val illustItems = currentIllustItems()
        val position = illustItems.indexOfFirst { it.illust.id == item.illust.id }
        // uuid 用 VM 里的稳定值：Container 的 map 永不清理，稳定 key 让本列表最多占一个坑
        //（每次打开覆盖上一份快照，对齐 legacy），Fragment 重建后回传广播也仍能认领。
        val pageData = if (position >= 0) {
            // nextUrl 一并交接给 VActivity，详情页 pager 划到底可以继续加载
            PageData(
                syncViewModel.listPageUuid,
                feedViewModel.currentCursor,
                illustItems.map { it.bean },
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
 * 插画 feed 页跨 Fragment 重建存活的协同状态（数据归 ViewModel 约定）。
 */
class IllustFeedSyncViewModel : ViewModel() {

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
 * 插画 feed 条目：loxia [Illust]（immutable，驱动 UI 与 DiffUtil）+ legacy [IllustsBean]
 * （可变，供详情页 pager / 下载 / 收藏等 legacy 链路共享同一实例）。
 *
 * 内容相等性只看 [illust]（immutable data class，深比较）：bean 是 legacy 可变对象、
 * 没有 equals，参与比较会让每次刷新的同内容条目都被判成「变了」而整列表白白重绑。
 */
class IllustFeedItem(
    val illust: Illust,
    val bean: IllustsBean,
) : FeedItem {

    override val feedKey: Any get() = illust.id

    override fun equals(other: Any?): Boolean {
        return other is IllustFeedItem && other.illust == illust
    }

    override fun hashCode(): Int = illust.hashCode()

    /**
     * 收藏状态变更：bean 是可变对象且与详情页 pager 共享同一实例，就地写；
     * illust 走 copy 让相等性变化，驱动 DiffUtil 原地重绑爱心。
     */
    fun withBookmarked(liked: Boolean): IllustFeedItem {
        bean.setIs_bookmarked(liked)
        return IllustFeedItem(illust.copy(is_bookmarked = liked), bean)
    }

    companion object {
        /** loxia.Illust 与 IllustsBean 字段名完全一致，gson 直转（同稍后再看页的做法）。 */
        fun beanOf(illust: Illust): IllustsBean? {
            return runCatching {
                Shaft.sGson.fromJson(Shaft.sGson.toJson(illust), IllustsBean::class.java)
            }.getOrNull()
        }

        /** 过滤 + 建条目；[beanOf] 拆开单独暴露是给「过滤前整页喂 DiscoveryPool」的场景。 */
        fun of(illust: Illust, bean: IllustsBean, skipR18Filter: Boolean = false): IllustFeedItem? {
            if (!passesContentFilters(bean, skipR18Filter)) return null
            return IllustFeedItem(illust, bean)
        }

        fun from(illust: Illust, skipR18Filter: Boolean = false): IllustFeedItem? {
            val bean = beanOf(illust) ?: return null
            return of(illust, bean, skipR18Filter)
        }

        /** 详情 pager 回传的是 legacy bean，反向转一次。 */
        fun fromBean(bean: IllustsBean?, skipR18Filter: Boolean = false): IllustFeedItem? {
            if (bean == null || !passesContentFilters(bean, skipR18Filter)) return null
            val illust = runCatching {
                Shaft.sGson.fromJson(Shaft.sGson.toJson(bean), Illust::class.java)
            }.getOrNull() ?: return null
            return IllustFeedItem(illust, bean)
        }

        /**
         * 与 legacy Mapper 对齐的内容过滤链（搜索专属的 R18 三态/仅看 AI 不适用）。
         * [skipR18Filter]：R18 专属榜单端点本身就是用来看 R18 的，不用全局 R18 过滤清空内容
         * （对齐 RankIllustRepo.enableSkipR18Filter）。整页被滤空时由 FeedViewModel
         * 空页追载兜住，不会翻页停摆。
         */
        private fun passesContentFilters(bean: IllustsBean, skipR18Filter: Boolean): Boolean {
            if (!bean.isVisible) return false
            if (IllustNovelFilter.judgeTag(bean)) return false
            if (IllustNovelFilter.judgeID(bean)) return false
            if (IllustNovelFilter.judgeUserID(bean)) return false
            if (!skipR18Filter && IllustNovelFilter.judgeR18Filter(bean)) return false
            if (Shaft.sSettings.isDeleteAIIllust() && bean.isCreatedByAI) return false
            return true
        }
    }
}
