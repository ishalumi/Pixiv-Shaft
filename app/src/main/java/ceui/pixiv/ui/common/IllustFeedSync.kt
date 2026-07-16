package ceui.pixiv.ui.common

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import ceui.lisa.helper.AppLevelViewModelHelper
import ceui.lisa.model.ListIllust
import ceui.lisa.models.IllustsBean
import ceui.lisa.utils.Params
import ceui.loxia.ObjectPool
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.FeedUiState
import ceui.pixiv.feeds.FeedViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 插画列表与 legacy 详情链路的广播协同，从 [IllustFeedFragment] 拆出的独立协作件
 * （不依赖 Fragment 继承，混排页等任何 feeds 页面都能挂）：
 *
 * - FRAGMENT_ADD_DATA：详情 pager 用 nextUrl 续拉的页追加回列表并接管游标；
 * - FRAGMENT_SCROLL_TO_POSITION：返回时列表跟到详情页正在看的那张。
 *
 * 收藏态回流（LIKED_ILLUST）不在这里：它与小说 / 画师那两条广播是同一件事，统一走
 * [FeedLikeSync]（本类 [bind] 内一并挂上，调用方无感）。
 *
 * ADD_DATA 的 bean→条目映射是整页 gson 往返，不允许占主线程（广播恰恰在详情页
 * 滑动动画进行中到达）；这里经 [Channel] 单消费者搬到 Default 线程执行——
 * 单消费者保证按广播到达顺序追加 + 交接游标，晚到的旧页不会覆盖新游标。
 *
 * 生命周期随 viewLifecycleOwner：DESTROYED 时注销接收器并关闭队列，无需手动清理。
 */
class IllustFeedDetailSync(
    private val feedViewModel: FeedViewModel<String>,
    private val listPageUuid: String,
    private val itemFromBean: (IllustsBean?) -> IllustFeedItem?,
    /** 详情页正看到某张作品：illustId 按 id 锚定（缺省 0），pagerIndex 是快照下标兜底。 */
    private val onDetailScrolledTo: (illustId: Long, pagerIndex: Int) -> Unit,
) {

    fun bind(context: Context, viewLifecycleOwner: LifecycleOwner) {
        val broadcastManager = LocalBroadcastManager.getInstance(context)
        val addDataQueue = Channel<ListIllust>(Channel.UNLIMITED)

        // 收藏态回流与小说 / 画师两条广播同形，收口在 FeedLikeSync（自带注销）
        feedLikeSync<IllustFeedItem>(
            feedViewModel = feedViewModel,
            action = Params.LIKED_ILLUST,
            idOf = { it.illust.id },
            transform = { item, liked -> item.withBookmarked(liked) },
        ).bind(context, viewLifecycleOwner)

        val addDataReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val extras = intent?.extras ?: return
                if (extras.getString(Params.PAGE_UUID) != listPageUuid) return
                val listIllust = extras.getSerializable(Params.CONTENT) as? ListIllust ?: return
                addDataQueue.trySend(listIllust)
            }
        }
        val scrollReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val extras = intent?.extras ?: return
                if (extras.getString(Params.PAGE_UUID) != listPageUuid) return
                onDetailScrolledTo(
                    extras.getInt(Params.ID).toLong(),
                    extras.getInt(Params.INDEX, -1),
                )
            }
        }

        broadcastManager.registerReceiver(addDataReceiver, IntentFilter(Params.FRAGMENT_ADD_DATA))
        broadcastManager.registerReceiver(
            scrollReceiver, IntentFilter(Params.FRAGMENT_SCROLL_TO_POSITION)
        )

        // 用 lifecycleScope 而不是 repeatOnLifecycle：广播到达时本页通常在详情页背后
        // （STOPPED），追加要照做，返回列表时数据已就位
        viewLifecycleOwner.lifecycleScope.launch {
            for (listIllust in addDataQueue) {
                val fresh = withContext(Dispatchers.Default) {
                    listIllust.list.orEmpty().mapNotNull(itemFromBean)
                }
                feedViewModel.appendItems(fresh)
                feedViewModel.adoptCursor(listIllust.nextUrl?.takeIf { it.isNotEmpty() })
            }
        }

        viewLifecycleOwner.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                broadcastManager.unregisterReceiver(addDataReceiver)
                broadcastManager.unregisterReceiver(scrollReceiver)
                addDataQueue.close()
            }
        })
    }
}

/**
 * 列表数据落地后把最新 bean 合入 ObjectPool（主线程；对齐 legacy Mapper 的池同步职责，
 * 否则 V3 详情命中旧池条目会渲染过期的收藏数/爱心），并把作者关注状态灌进
 * AppLevelViewModel（对齐 legacy NetListFragment 每页 tidyAppViewModel，
 * UActivity/UserActivityV3 的关注按钮消费它）。
 *
 * 按 bean 实例去重（[IllustFeedSyncViewModel.pooledBeans]）：同一实例只合一次，
 * 刷新产出的同 id 新实例携带更新的服务端数据，必须重新合入——按 id 永久去重会把它挡在池外。
 *
 * 扫描范围借 [FeedUiState.structureVersion] 做增量：无限滚动场景下 loadMore 每次都是
 * `existing + fresh`（旧前缀条目引用不变，早就合过池），版本不变时只需扫描新追加的尾部；
 * 否则（refresh 整代替换、mutateItems 结构性编辑）没法假设任何位置的实例没变，退回全量重扫。
 * 不这样做的话，每次 loadMore 都会把从头到尾的旧条目重新扫一遍，翻页越深单次扫描越贵。
 */
class IllustFeedPoolSync(
    private val syncViewModel: IllustFeedSyncViewModel,
    private val poolableBeansOf: (FeedItem) -> List<IllustsBean>,
) {

    fun bind(viewLifecycleOwner: LifecycleOwner, uiState: StateFlow<FeedUiState>) {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                var scannedItems: List<FeedItem>? = null
                var scannedSize = 0
                var scannedVersion = -1
                uiState.collect { state ->
                    // 只有条目列表本身换了才扫描；纯加载态变化（append Loading/Idle）直接跳过
                    if (state.items === scannedItems) return@collect
                    val canScanTailOnly = state.structureVersion == scannedVersion &&
                            state.items.size >= scannedSize
                    val itemsToScan = if (canScanTailOnly) {
                        state.items.subList(scannedSize, state.items.size)
                    } else {
                        state.items
                    }
                    scannedItems = state.items
                    scannedSize = state.items.size
                    scannedVersion = state.structureVersion
                    val freshBeans = mutableListOf<IllustsBean>()
                    itemsToScan.forEach { item ->
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
}
