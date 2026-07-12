package ceui.pixiv.ui.home

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.annotation.LayoutRes
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import androidx.viewbinding.ViewBinding
import ceui.lisa.BuildConfig
import ceui.lisa.R
import ceui.lisa.activities.ColdStartSplashGate
import ceui.lisa.activities.RankActivity
import ceui.lisa.activities.Shaft
import ceui.lisa.activities.VActivity
import ceui.lisa.adapters.RAdapter
import ceui.lisa.core.Container
import ceui.lisa.core.PageData
import ceui.lisa.databinding.RecyRecmdHeaderBinding
import ceui.lisa.helper.StaggeredManager
import ceui.lisa.model.ListIllust
import ceui.lisa.models.IllustsBean
import ceui.lisa.utils.DensityUtil
import ceui.lisa.utils.Params
import ceui.lisa.view.LinearItemHorizontalDecoration
import ceui.lisa.view.SpacesItemWithHeadDecoration
import ceui.loxia.Client
import ceui.loxia.Illust
import ceui.pixiv.db.discovery.DiscoveryPool
import ceui.pixiv.feeds.FeedCell
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.FeedLoadPhase
import ceui.pixiv.feeds.FeedRenderer
import ceui.pixiv.feeds.LoadState
import ceui.pixiv.feeds.cachedPixivFeedSource
import ceui.pixiv.feeds.feedRenderer
import ceui.pixiv.feeds.feedViewModels
import ceui.pixiv.ui.common.IllustFeedFragment
import ceui.pixiv.ui.common.IllustFeedItem
import ceui.pixiv.utils.setOnClick
import com.airbnb.lottie.LottieAnimationView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 首页「推荐插画」tab / 推荐漫画页（feeds 框架版，替代 legacy FragmentRecmdIllust +
 * IAdapterWithHeadView + RecmdModel）。异构列表 = 横向排行榜预览头（整行）+ 插画瀑布流。
 *
 * 与 legacy 的行为对齐点：
 * - 首屏响应的 ranking_illusts 渲染横向排行榜预览（RAdapter hero 卡），
 *   「查看更多」进 RankActivity，卡片点击开 VActivity（一次性 PageData，同 legacy IllustHeader）；
 * - 排行榜预览的 bean 同样合入 ObjectPool + 灌关注状态（poolableBeansOf 覆盖）；
 * - 每页数据过滤前整页喂 DiscoveryPool（对齐 RecmdIllustRepo）；
 * - 「收藏时显示相关作品」：收藏成功后 FRAGMENT_ADD_RELATED_DATA 回流，
 *   截前 5 条打 NEW 角标插到被收藏作品后面（feeds 版按作品 id 锚定 + 身份去重，
 *   替代 legacy 不可靠的 adapter 位置语义）；
 * - GAP_HANDLING_NONE + SpacesItemWithHeadDecoration（带头瀑布流的间距规则）。
 */
open class RecmdIllustFeedFragment(
    @LayoutRes contentLayoutId: Int = R.layout.fragment_feed,
) : IllustFeedFragment(contentLayoutId) {

    protected val dataType: String by lazy(LazyThreadSafetyMode.NONE) {
        requireArguments().getString(ARG_DATA_TYPE) ?: TYPE_ILLUST
    }

    override val feedViewModel by feedViewModels {
        // 零捕获约定（见 feedViewModels 文档）：source/mapper 归 VM 长期持有，
        // 只捕获局部值、映射走伴生函数，不把 Fragment 实例钉进 VM
        val dataType = dataType
        val apiType = if (dataType == TYPE_MANGA) "manga" else "illust"
        // 本地优先（哔哩哔哩 / 新闻首页语义）：给稳定 slot 即开磁盘缓存，冷启秒显上次首屏
        // 再拉最新覆盖。slot 已由框架自动拼账号命名空间，切号不串味。
        cachedPixivFeedSource(
            slot = "recmd-$apiType",
            initialFetch = { Client.appApi.getRecommendedWorksWithRanking(apiType) },
        ) { resp, phase ->
            mapRecmdPage(resp.illusts, resp.ranking_illusts, phase, dataType)
        }
    }

    /** 收藏时顺带拉相关作品插入列表（本页专属设置）。 */
    override val showRelatedOnStar: Boolean
        get() = Shaft.sSettings.isShowRelatedWhenStar

    /** 排行榜预览头携带的 bean 也要合池 + 灌关注状态（对齐 legacy onFirstLoaded）。 */
    override fun poolableBeansOf(item: FeedItem): List<IllustsBean> {
        return if (item is RankPreviewHeaderItem) item.rankBeans else super.poolableBeansOf(item)
    }

    /** 收藏成功回流的相关作品：按被收藏作品 id 锚定，截前 5 条打 NEW 角标插到它后面。 */
    private val relatedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val extras = intent?.extras ?: return
            // 只认本列表发起的收藏；legacy 发送方不带 uuid，退回宽松的按 id 锚定兜底
            val sourceUuid = extras.getString(Params.PAGE_UUID)
            if (sourceUuid != null && sourceUuid != syncViewModel.listPageUuid) return
            val listIllust = extras.getSerializable(Params.CONTENT) as? ListIllust ?: return
            val anchorId = extras.getInt(Params.ID).toLong()
            if (anchorId <= 0) return
            viewLifecycleOwner.lifecycleScope.launch {
                // bean→条目是逐条 gson 往返，不占主线程
                val related = withContext(Dispatchers.Default) {
                    listIllust.list.orEmpty().take(5)
                        .onEach { it.isRelated = true }
                        .mapNotNull { feedItemFromBean(it) }
                }
                if (related.isEmpty()) return@launch
                feedViewModel.mutateItems { items ->
                    val anchor =
                        items.indexOfFirst { it is IllustFeedItem && it.illust.id == anchorId }
                    if (anchor < 0) return@mutateItems items
                    val existing =
                        items.mapNotNullTo(HashSet()) { (it as? IllustFeedItem)?.illust?.id }
                    val fresh = related.filter { it.illust.id !in existing }
                    if (fresh.isEmpty()) {
                        items
                    } else {
                        items.subList(0, anchor + 1) + fresh + items.subList(anchor + 1, items.size)
                    }
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        LocalBroadcastManager.getInstance(requireContext())
            .registerReceiver(relatedReceiver, IntentFilter(Params.FRAGMENT_ADD_RELATED_DATA))
        // 本类在两处被复用：FragmentLeft（首页推荐 tab，TYPE_ILLUST）和独立的
        // RecmdMangaFeedFragment（TemplateActivity 里的「推荐漫画」页，TYPE_MANGA）——
        // 后者跟 MainActivity 冷启动无关，只有 TYPE_ILLUST 这份实例的裁决才代表
        // MainActivity.getNavigationInitPosition()==0 时开屏该不该放行。
        // 只等 refresh 走完首个决定（refresh 不再是初始 Idle，或已经 hasLoadedOnce）：
        // 即命中缓存 / 未命中都算数，不等网络，避免开屏被无界的网络延迟焊死。
        if (dataType == TYPE_ILLUST) {
            viewLifecycleOwner.lifecycleScope.launch {
                feedViewModel.uiState.first { it.refresh !is LoadState.Idle || it.hasLoadedOnce }
                ColdStartSplashGate.markResolved()
            }
        }
        if (BuildConfig.DEBUG) {
            addHapticDemoButton(view)
        }
    }

    /**
     * debug 包限定的悬浮「触感测试」按钮：点一下播收藏触感 + 在可见卡片上放爆发
     * 动画，反复调手感不用真点收藏。release 包不存在，试完随手删也行。
     */
    private fun addHapticDemoButton(root: View) {
        val demo = TextView(requireContext()).apply {
            text = "❤ 触感测试"
            setTextColor(Color.WHITE)
            textSize = 13f
            val h = DensityUtil.dp2px(16.0f)
            val v = DensityUtil.dp2px(10.0f)
            setPadding(h, v, h, v)
            background = GradientDrawable().apply {
                cornerRadius = DensityUtil.dp2px(22.0f).toFloat()
                setColor(0xCC101014.toInt())
            }
            setOnClickListener {
                playLikePressHaptic(it)
                playDemoBurst()
            }
        }
        (root as ViewGroup).addView(
            demo,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            ).apply { bottomMargin = DensityUtil.dp2px(96.0f) },
        )
    }

    /** 在第一张带爆发层的可见卡片上播动画（复用 cell 自己的播完自动收起监听）。 */
    private fun playDemoBurst() {
        val listView = feedBinding.feedListView
        for (i in 0 until listView.childCount) {
            val child = listView.getChildAt(i)
            val anim = child.findViewById<LottieAnimationView>(R.id.like_anim) ?: continue
            // 演示不改收藏状态，但和真收藏一样先把静态心切红：白色空心心在动画
            // 开头几帧盖不住，会从爆发红心边缘漏出来。播完按真实绑定状态恢复
            //（一次性监听，自摘除，不污染真收藏路径的动画回调）
            val button = child.findViewById<android.widget.ImageView>(R.id.like_button)
            if (button != null) {
                renderLikeState(button, true)
                anim.addAnimatorListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        anim.removeAnimatorListener(this)
                        val item = (listView.getChildViewHolder(child) as? FeedCell<*, *>)
                            ?.itemOrNull as? IllustFeedItem
                        renderLikeState(button, item?.illust?.is_bookmarked == true)
                    }
                })
            }
            anim.isVisible = true
            anim.progress = 0f
            anim.playAnimation()
            return
        }
    }

    override fun onDestroyView() {
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(relatedReceiver)
        super.onDestroyView()
    }

    override fun onCreateLayoutManager(): RecyclerView.LayoutManager {
        // GAP_HANDLING_NONE 对齐 legacy：带整行 header 的瀑布流开 gap 策略会在回滚时重排跳动
        return StaggeredManager(Shaft.sSettings.lineCount, RecyclerView.VERTICAL).apply {
            gapStrategy = StaggeredGridLayoutManager.GAP_HANDLING_NONE
        }
    }

    override fun onListReady(listView: RecyclerView) {
        listView.addItemDecoration(SpacesItemWithHeadDecoration(DensityUtil.dp2px(8.0f)))
    }

    override fun onCreateRenderers(): List<FeedRenderer<out FeedItem, out ViewBinding>> {
        return listOf(rankHeaderRenderer(), staggerIllustRenderer())
    }

    private fun rankHeaderRenderer() = feedRenderer<RankPreviewHeaderItem, RecyRecmdHeaderBinding>(
        inflate = RecyRecmdHeaderBinding::inflate,
        fullSpan = true,
        create = { cell ->
            cell.binding.seeMore.setOnClick {
                startActivity(Intent(requireContext(), RankActivity::class.java).apply {
                    putExtra("dataType", cell.item.dataType)
                })
            }
            cell.binding.ranking.layoutManager =
                LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
            cell.binding.ranking.addItemDecoration(
                LinearItemHorizontalDecoration(DensityUtil.dp2px(8.0f))
            )
            cell.binding.ranking.setHasFixedSize(true)
        },
    ) { cell ->
        val item = cell.item
        // 同一份数据重复 bind（滚动回收再回来）不重设 adapter，保留横向滚动位置
        //（对齐 legacy 单例 header 只在数据到达时 show 一次的语义）
        if (cell.binding.ranking.tag != item) {
            cell.binding.ranking.tag = item
            val adapter = RAdapter(item.rankBeans, requireContext())
            adapter.setOnItemClickListener { _, position, _ ->
                // 一次性 PageData（同 legacy IllustHeader）：排行榜预览无 nextUrl，与主列表互不认领
                val pageData = PageData(item.rankBeans)
                Container.get().addPageToMap(pageData)
                startActivity(Intent(requireContext(), VActivity::class.java).apply {
                    putExtra(Params.POSITION, position)
                    putExtra(Params.PAGE_UUID, pageData.getUUID())
                })
            }
            cell.binding.ranking.adapter = adapter
        }
    }

    companion object {
        internal const val ARG_DATA_TYPE = "recmd_data_type"

        /** dataType 是路由字面量（RankActivity 按 "插画"/"漫画" 分支），不是展示文案，别本地化。 */
        const val TYPE_ILLUST = "插画"
        const val TYPE_MANGA = "漫画"

        @JvmStatic
        fun newInstance(dataType: String): RecmdIllustFeedFragment {
            return RecmdIllustFeedFragment().apply {
                arguments = Bundle().apply { putString(ARG_DATA_TYPE, dataType) }
            }
        }

        /**
         * 页响应 → 条目。跑在 Default 线程、被 VM 长期持有，放伴生对象保证零捕获。
         *
         * [phase] 为 [FeedLoadPhase.CacheRestore]（缓存恢复）时只做纯映射：不喂画像池——
         * 那是「拉取成功」的副作用，拿旧数据重放会污染下游。映射结构（含排行榜预览头）
         * 与首屏保持一致，靠 [FeedLoadPhase.isFirstPage] 判定。
         */
        private fun mapRecmdPage(
            illusts: List<Illust>,
            rankingIllusts: List<Illust>,
            phase: FeedLoadPhase,
            dataType: String,
        ): List<FeedItem> {
            val pairs = illusts.mapNotNull { illust ->
                IllustFeedItem.beanOf(illust)?.let { bean -> illust to bean }
            }
            // 对齐 legacy RecmdIllustRepo：过滤前整页喂 DiscoveryPool（排行榜预览不算）。
            // 缓存恢复不喂（旧数据画像无意义、且违反重放安全）。
            if (phase.isFreshFetch) {
                DiscoveryPool.collect(
                    pairs.map { it.second },
                    if (phase.isFirstPage) "recmd:$dataType" else "recmd_next:$dataType",
                )
            }
            val listItems = pairs.mapNotNull { (illust, bean) -> IllustFeedItem.of(illust, bean) }
            if (!phase.isFirstPage) {
                return listItems
            }
            // 排行榜预览头不做内容过滤（对齐 legacy 直接展示 ranking_illusts）
            val rankBeans = rankingIllusts.mapNotNull { IllustFeedItem.beanOf(it) }
            return if (rankBeans.isEmpty()) {
                listItems
            } else {
                listOf(RankPreviewHeaderItem(rankBeans, dataType)) + listItems
            }
        }
    }
}

/**
 * 横向排行榜预览头（整行）。内容相等性按作品 id 序列：刷新拉到同一批榜单时零重绑，
 * 换了批次才重设内部 RAdapter。
 */
class RankPreviewHeaderItem(
    val rankBeans: List<IllustsBean>,
    val dataType: String,
) : FeedItem {

    private val rankIds: List<Int> = rankBeans.map { it.id }

    override val feedKey: Any get() = "recmd_rank_header"

    override fun equals(other: Any?): Boolean {
        return other is RankPreviewHeaderItem && other.rankIds == rankIds
    }

    override fun hashCode(): Int = rankIds.hashCode()
}
