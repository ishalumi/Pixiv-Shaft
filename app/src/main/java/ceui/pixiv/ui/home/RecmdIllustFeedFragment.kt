package ceui.pixiv.ui.home

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.View
import androidx.annotation.LayoutRes
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import androidx.viewbinding.ViewBinding
import ceui.lisa.R
import ceui.lisa.activities.RankActivity
import ceui.lisa.activities.Shaft
import ceui.lisa.activities.VActivity
import ceui.lisa.adapters.RAdapter
import ceui.lisa.core.Container
import ceui.lisa.core.PageData
import ceui.lisa.database.AppDatabase
import ceui.lisa.database.IllustRecmdEntity
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
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.FeedRenderer
import ceui.pixiv.feeds.PixivFeedSource
import ceui.pixiv.feeds.feedRenderer
import ceui.pixiv.feeds.feedViewModels
import ceui.pixiv.ui.common.IllustFeedFragment
import ceui.pixiv.ui.common.IllustFeedItem
import ceui.pixiv.utils.setOnClick

/**
 * 首页「推荐插画」tab / 推荐漫画页（feeds 框架版，替代 legacy FragmentRecmdIllust +
 * IAdapterWithHeadView + RecmdModel）。异构列表 = 横向排行榜预览头（整行）+ 插画瀑布流。
 *
 * 与 legacy 的行为对齐点：
 * - 首屏响应的 ranking_illusts 渲染横向排行榜预览（RAdapter hero 卡），
 *   「查看更多」进 RankActivity，卡片点击开 VActivity（一次性 PageData，同 legacy IllustHeader）；
 * - 排行榜预览的 bean 同样合入 ObjectPool + 灌关注状态（poolableBeansOf 覆盖）；
 * - 每页数据过滤前整页喂 DiscoveryPool（对齐 RecmdIllustRepo）；
 * - 首屏前 20 条写入推荐浏览历史（recmdDao，离线模式/推荐用户页的数据源）；
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
        val apiType = if (dataType == TYPE_MANGA) "manga" else "illust"
        PixivFeedSource({ Client.appApi.getRecommendedWorksWithRanking(apiType) }) { resp, isFirstPage ->
            mapRecmdPage(resp.illusts, resp.ranking_illusts, isFirstPage)
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
            val listIllust = extras.getSerializable(Params.CONTENT) as? ListIllust ?: return
            val anchorId = extras.getInt(Params.ID).toLong()
            if (anchorId <= 0) return
            val related = listIllust.list.orEmpty().take(5)
                .onEach { it.isRelated = true }
                .mapNotNull { feedItemFromBean(it) }
            if (related.isEmpty()) return
            feedViewModel.mutateItems { items ->
                val anchor = items.indexOfFirst { it is IllustFeedItem && it.illust.id == anchorId }
                if (anchor < 0) return@mutateItems items
                val existing = items.mapNotNullTo(HashSet()) { (it as? IllustFeedItem)?.illust?.id }
                val fresh = related.filter { it.illust.id !in existing }
                if (fresh.isEmpty()) {
                    items
                } else {
                    items.subList(0, anchor + 1) + fresh + items.subList(anchor + 1, items.size)
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        LocalBroadcastManager.getInstance(requireContext())
            .registerReceiver(relatedReceiver, IntentFilter(Params.FRAGMENT_ADD_RELATED_DATA))
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

    private fun mapRecmdPage(
        illusts: List<Illust>,
        rankingIllusts: List<Illust>,
        isFirstPage: Boolean,
    ): List<FeedItem> {
        val pairs = illusts.mapNotNull { illust ->
            IllustFeedItem.beanOf(illust)?.let { bean -> illust to bean }
        }
        // 对齐 legacy RecmdIllustRepo：过滤前整页喂 DiscoveryPool（排行榜预览不算）
        DiscoveryPool.collect(
            pairs.map { it.second },
            if (isFirstPage) "recmd:$dataType" else "recmd_next:$dataType",
        )
        val listItems = pairs.mapNotNull { (illust, bean) -> IllustFeedItem.of(illust, bean) }
        if (!isFirstPage) {
            return listItems
        }
        // 首屏前 20 条写入推荐浏览历史（对齐 legacy onFirstLoaded；离线模式/推荐用户页读它）。
        // mapper 跑在 Default 线程，Room 直接写安全
        runCatching {
            val dao = AppDatabase.getAppDatabase(Shaft.getContext()).recmdDao()
            listItems.take(20).forEach { item ->
                dao.insert(IllustRecmdEntity().apply {
                    illustID = item.bean.id
                    illustJson = Shaft.sGson.toJson(item.bean)
                    time = System.currentTimeMillis()
                })
            }
        }
        // 排行榜预览头不做内容过滤（对齐 legacy 直接展示 ranking_illusts）
        val rankBeans = rankingIllusts.mapNotNull { IllustFeedItem.beanOf(it) }
        return if (rankBeans.isEmpty()) {
            listItems
        } else {
            listOf(RankPreviewHeaderItem(rankBeans, dataType)) + listItems
        }
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
