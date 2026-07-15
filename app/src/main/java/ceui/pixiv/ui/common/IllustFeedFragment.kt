package ceui.pixiv.ui.common

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import androidx.annotation.LayoutRes
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModel
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
import ceui.lisa.helper.IllustNovelFilter
import ceui.lisa.helper.StaggeredManager
import ceui.lisa.models.IllustsBean
import ceui.lisa.utils.Common
import ceui.lisa.utils.DensityUtil
import ceui.lisa.utils.GlideUtil
import ceui.lisa.utils.Params
import ceui.lisa.utils.PixivOperate
import ceui.lisa.view.SpacesItemDecoration
import ceui.loxia.Illust
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
import ceui.pixiv.utils.playLikePressHaptic
import ceui.pixiv.utils.setOnClick
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
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
    return if (old.bean.trendingScore == new.bean.trendingScore &&
        old.illust.copy(is_bookmarked = new.illust.is_bookmarked) == new.illust
    ) {
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

    /**
     * 隐藏卡片上的收藏爱心（自己的收藏页 + 「收藏页隐藏收藏按钮」设置，对齐 legacy
     * IAdapterWithStar）。每次 bind 动态读：设置变更后新绑定的卡片即生效（滑动复用 /
     * 下拉刷新），屏幕上已绑定的卡片不会主动重绑——legacy 是建 adapter 时读死，更迟钝。
     */
    protected open val hideLikeButton: Boolean
        get() = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 广播协同（收藏回流 / 详情 pager 续拉 / 返回跟滚）与 ObjectPool 合池
        // 拆在独立协作件里，生命周期随 viewLifecycleOwner 自理
        IllustFeedDetailSync(
            feedViewModel = feedViewModel,
            listPageUuid = syncViewModel.listPageUuid,
            itemFromBean = ::feedItemFromBean,
            onDetailScrolledTo = ::scrollToDetailPosition,
        ).bind(requireContext(), viewLifecycleOwner)
        IllustFeedPoolSync(syncViewModel, ::poolableBeansOf)
            .bind(viewLifecycleOwner, feedViewModel.uiState)
    }

    /** 返回时列表跟到详情页正在看的那张（延迟一拍等转场结束）。 */
    private fun scrollToDetailPosition(illustId: Long, pagerIndex: Int) {
        feedBinding.feedListView.postDelayed({
            val adapter = feedAdapter ?: return@postDelayed
            if (view == null) return@postDelayed
            val items = feedViewModel.uiState.value.items
            // pager 的 index 是交接快照里纯插画列表的下标，列表带 header（推荐页排行榜头）
            // 或收藏后相关作品插到中段时不能直接当 adapter 位置用，按作品 id 锚定
            var position = if (illustId > 0) {
                items.indexOfFirst { it is IllustFeedItem && it.illust.id == illustId }
            } else {
                -1
            }
            if (position < 0 && pagerIndex >= 0) {
                // 广播不带 id 时的兜底：第 pagerIndex 个插画条目（跳过混排的非插画条目）
                var remaining = pagerIndex
                position = items.indexOfFirst { it is IllustFeedItem && remaining-- == 0 }
            }
            if (position in 0 until adapter.itemCount) {
                feedBinding.feedListView.smoothScrollToPosition(position)
            }
        }, 200L)
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
            cell.binding.likeButton.setOnClick {
                val willBookmark = cell.item.illust.is_bookmarked != true
                toggleLike(cell.item)
                // 乐观重绑要等 ListAdapter 后台 diff 落地（至少 1~2 帧，大列表更久），
                // 静态爱心必须当帧先切到目标态：否则爆发动画开头几帧红心还小，
                // 底下过期的白色空心心会从边缘漏出来；随后的 payload 重绑是幂等兜底
                renderLikeState(cell.binding.likeButton, willBookmark)
                if (willBookmark) {
                    playLikeBurst(cell.binding)
                } else {
                    playUnlikeShrink(cell.binding.likeButton)
                }
            }
            // 动画播完/被取消都收起爆发层，露出下面已经变红的静态爱心
            cell.binding.likeAnim.addAnimatorListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    cell.binding.likeAnim.isVisible = false
                }
            })
            // lottie 默认 failure listener 会直接抛 IllegalStateException 崩 app；
            // 动画资源解析失败就静默降级成无动画（静态爱心链路完全不受影响）
            cell.binding.likeAnim.setFailureListener { }
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
            cell.binding.illustImage.tag = null
            resetLikeAnim(cell.binding)
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
        // GlideUrlChild 每次构造都带当前时间戳请求头（PixivHeaders.x-client-time/hash），
        // 而 GlideUrl.equals() 要求 headers 也相等才算「同一请求」——这里的 headers 又是个
        // 没重写 equals 的 lambda，Glide 自己的活跃资源缓存永远认不出「这张图已经在显示」。
        // 本地优先冷启（缓存快照 → 网络新数据）时 Illust.total_view/total_bookmarks 几乎
        // 必然变了（这两个字段卡片根本不展示），触发全量重绑，重绑一律重新发 Glide 请求，
        // 于是每张卡片的图都要闪一次占位色再淡入回来——图其实没变。用请求 URL（不含
        // headers 的 cacheKey）+ 目标尺寸当 tag，真没变时跳过这次重新加载；recycle 清图时
        // 一并清 tag，保证真正复用到新条目时不会因为 tag 恰好没变而漏加载。
        val imageRequestKey = Triple(imgUrl?.cacheKey, columnWidth, columnHeight)
        if (cell.binding.illustImage.tag != imageRequestKey) {
            cell.binding.illustImage.tag = imageRequestKey
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
        }

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
        // 全量绑定可能是复用的卡换了条目：上一条残留的爆发动画/缩放必须清干净
        resetLikeAnim(cell.binding)
        cell.binding.likeButton.isVisible = !hideLikeButton
        renderLikeState(cell.binding.likeButton, cell.item.illust.is_bookmarked == true)
    }

    /** 未收藏 = 白色空心描边爱心，已收藏 = 红色实心爱心（图上永远配深色圆底座）。 */
    protected fun renderLikeState(button: ImageView, liked: Boolean) {
        button.setImageResource(
            if (liked) R.drawable.ic_like_heart_fill else R.drawable.ic_like_heart_outline
        )
        button.imageTintList = ColorStateList.valueOf(
            if (liked) {
                ContextCompat.getColor(button.context, R.color.has_bookmarked)
            } else {
                Color.WHITE
            }
        )
    }

    /**
     * 收藏爆发动画：静态爱心由点击处当帧切红（异步乐观重绑只是兜底，等它就晚了），
     * 上层 72dp Lottie 播弹性爱心 + 白闪 + 冲击波圆环 + 三色粒子向作品图上炸开，
     * 播完自动收起。动画层非 clickable，播放中不挡按钮点击；局部重绑只动静态爱心，
     * 不打断动画。
     */
    private fun playLikeBurst(binding: RecyIllustStaggerBinding) {
        playLikePressHaptic(binding.likeButton)
        binding.likeAnim.apply {
            isVisible = true
            progress = 0f
            playAnimation()
        }
    }

    /** 取消收藏：静态爱心一个干脆的回弹缩放，不放烟花；触感只给单下轻 tick。 */
    private fun playUnlikeShrink(button: ImageView) {
        button.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        button.animate().cancel()
        button.scaleX = 0.6f
        button.scaleY = 0.6f
        button.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(280L)
            .setInterpolator(OvershootInterpolator(2.5f))
            .start()
    }

    private fun resetLikeAnim(binding: RecyIllustStaggerBinding) {
        binding.likeAnim.cancelAnimation()
        binding.likeAnim.isVisible = false
        binding.likeButton.animate().cancel()
        binding.likeButton.scaleX = 1f
        binding.likeButton.scaleY = 1f
    }

    protected fun toggleLike(item: IllustFeedItem) {
        // 单一真源是 item.illust（UI 由它渲染）。bean 可能因上次请求失败与 UI 背离——
        // PixivOperate.postLike 按 bean 当前值决定收藏还是取消，先把 bean 校准回 UI 状态，
        // 保证发出的请求永远与用户看到的操作一致。
        val uiLiked = item.illust.is_bookmarked == true
        item.bean.setIs_bookmarked(uiLiked)
        val willBookmark = !uiLiked
        // showRelatedOnStar 时 postLike 收藏成功后会拉相关作品并广播 FRAGMENT_ADD_RELATED_DATA；
        // index 是 legacy 位置语义（feeds 接收器改按广播里的作品 id 锚定），照传兼容；
        // 带上本列表 uuid，广播只被发起收藏的列表认领（多个推荐页同时存活时不串扰）
        PixivOperate.postLike(
            item.bean,
            if (Shaft.sSettings.isPrivateStar()) Params.TYPE_PRIVATE else Params.TYPE_PUBLIC,
            showRelatedOnStar,
            feedViewModel.uiState.value.items.indexOfFirst {
                it is IllustFeedItem && it.illust.id == item.illust.id
            },
            syncViewModel.listPageUuid,
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
                    Common.showToast(R.string.watch_later_removed)
                } else {
                    entityWrapper.addToWatchLater(appContext, item.illust)
                    Common.showToast(R.string.watch_later_added)
                }
            }
        }
    }

    protected fun currentIllustItems(): List<IllustFeedItem> {
        return feedViewModel.uiState.value.items.filterIsInstance<IllustFeedItem>()
    }

    /**
     * 交接给详情页 pager 的续读游标。默认就是列表当前游标——pixiv 列表的游标本身就是 nextUrl，
     * 详情页划到底可以照着它继续请求。
     *
     * **本地数据源必须覆写成 null**：详情页 pager 只会把它当 URL 直接请求下一页，喂页号 /
     * offset 进去只会请求失败（对齐 legacy——LocalListFragment 从不 setNextUrl，本地列表
     * 开详情时 PageData.nextUrl 一直是 null）。
     */
    protected open val detailContinuationCursor: String?
        get() = feedViewModel.currentCursor

    protected fun openDetail(item: IllustFeedItem) {
        val illustItems = currentIllustItems()
        val position = illustItems.indexOfFirst { it.illust.id == item.illust.id }
        // uuid 用 VM 里的稳定值：Container 的 map 永不清理，稳定 key 让本列表最多占一个坑
        //（每次打开覆盖上一份快照，对齐 legacy），Fragment 重建后回传广播也仍能认领。
        val pageData = if (position >= 0) {
            // nextUrl 一并交接给 VActivity，详情页 pager 划到底可以继续加载
            PageData(
                syncViewModel.listPageUuid,
                detailContinuationCursor,
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
 * 内容相等性看 [illust]（immutable data class，深比较）+ [bean] 的 trendingScore：bean 整体是
 * legacy 可变对象、没有 equals，参与比较会让每次刷新的同内容条目都被判成「变了」而整列表白白重绑，
 * 所以只额外比 trendingScore 这一个 Float 字段（本月收藏/当前最热的热度分 pill，不在 illust 上）——
 * 刷新时同一作品热度分变了要重绑更新 pill；普通列表两侧都 null，退化成只比 illust（无副作用）。
 */
class IllustFeedItem(
    val illust: Illust,
    val bean: IllustsBean,
) : FeedItem {

    override val feedKey: Any get() = illust.id

    override fun equals(other: Any?): Boolean {
        return other is IllustFeedItem && other.illust == illust &&
                other.bean.trendingScore == bean.trendingScore
    }

    override fun hashCode(): Int = illust.hashCode() * 31 + (bean.trendingScore?.hashCode() ?: 0)

    /**
     * 收藏状态变更：bean 是可变对象且与详情页 pager 共享同一实例，就地写；
     * illust 走 copy 让相等性变化，驱动 DiffUtil 原地重绑爱心。
     */
    fun withBookmarked(liked: Boolean): IllustFeedItem {
        bean.setIs_bookmarked(liked)
        return IllustFeedItem(illust.copy(is_bookmarked = liked), bean)
    }

    companion object {
        /**
         * loxia.Illust 与 IllustsBean 字段名完全一致，gson 直转（同稍后再看页的做法）。
         * 走 toJsonTree/fromJson(JsonElement) 而不是 toJson/fromJson(String)：省掉字符串
         * 编码/解析那一趟 IO，只留字段级反射转换——这个函数是本地优先冷启路径的热点
         * （单页几十条，每条一次全字段转换，字符串往返在这里是纯浪费）。
         */
        fun beanOf(illust: Illust): IllustsBean? {
            return runCatching {
                Shaft.sGson.fromJson(Shaft.sGson.toJsonTree(illust), IllustsBean::class.java)
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
                Shaft.sGson.fromJson(Shaft.sGson.toJsonTree(bean), Illust::class.java)
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
