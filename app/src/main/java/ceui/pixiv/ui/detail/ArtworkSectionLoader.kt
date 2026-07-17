package ceui.pixiv.ui.detail

import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import ceui.lisa.models.IllustsBean
import ceui.loxia.ObjectPool
import ceui.pixiv.feeds.FeedViewModel
import ceui.pixiv.feeds.updateItems
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * 详情页的懒加载区块(评论 / 作者其他作品 / 相关作品)。每个枚举常量声明**怎么拉自己 + 拉到后
 * 怎么把数据落回 [FeedViewModel] 里的条目**;至于**何时拉**——各自区块首次滚到可见——统一由
 * [SectionLoader] 触发。
 *
 * 关键语义:进页时这些区块都还没滚到,一律不触发。所以「池里已有完整 illust」时点进详情页
 * 不会发任何多余请求(评论 / 作者作品 / 相关都等真正滚到那一块才拉)。抓取纯函数在
 * [ArtworkV3FeedSource] 文件里(data layer),本枚举只做编排,不碰 View / Fragment。
 */
enum class ArtworkSection {

    /** 评论预览:前 3 条塞进 [ArtworkCommentsItem]。 */
    COMMENTS {
        override suspend fun load(illustId: Long, vm: FeedViewModel<String>) {
            val comments = fetchArtworkComments(illustId)
            vm.updateItems<ArtworkCommentsItem> { it.withComments(comments) }
        }
    },

    /** 作者其他作品:横向卡片塞进 [ArtworkAuthorWorksItem]。 */
    AUTHOR_WORKS {
        override suspend fun load(illustId: Long, vm: FeedViewModel<String>) {
            val userId = ObjectPool.get<IllustsBean>(illustId).value?.user?.id ?: return
            val works = fetchAuthorWorks(userId, illustId)
            vm.updateItems<ArtworkAuthorWorksItem> { it.copy(works = works) }
        }
    },

    /**
     * 相关作品:第 1 页卡片 append 到列表尾(相关头之后)、[FeedViewModel.adoptCursor] 交接游标
     * 开启后续分页(滚到底再 loadMore 拉第 2 页起),并把相关头 [ArtworkRelatedHeaderItem.state]
     * 从 null 翻成有 / 无。
     */
    RELATED {
        override suspend fun load(illustId: Long, vm: FeedViewModel<String>) {
            val (related, nextUrl) = fetchArtworkRelated(illustId, null)
            vm.appendItems(related)
            vm.adoptCursor(nextUrl)
            vm.updateItems<ArtworkRelatedHeaderItem> { it.copy(state = related.isNotEmpty()) }
        }
    };

    abstract suspend fun load(illustId: Long, vm: FeedViewModel<String>)
}

/**
 * 懒加载区块触发器:同一区块在本视图生命周期内只跑一次(单飞去重)。协程挂在 [owner]
 *(viewLifecycleOwner)的作用域,视图销毁自动取消;换视图重建一个新实例即自然重置去重集——
 * 不用在 Fragment 里为每个区块各攒一个布尔 flag。
 */
class SectionLoader(
    private val illustId: Long,
    private val feedViewModel: FeedViewModel<String>,
    private val owner: LifecycleOwner,
) {
    private val triggered = HashSet<ArtworkSection>()

    /** 区块首次可见(renderer onBind 里,数据仍空)时调用。 */
    fun onVisible(section: ArtworkSection) {
        if (triggered.add(section)) {
            Timber.tag(ARTWORK_LAZY_TAG).d("区块滚到可见,首次触发懒加载: %s illustId=%d", section, illustId)
            owner.lifecycleScope.launch { section.load(illustId, feedViewModel) }
        } else {
            Timber.tag(ARTWORK_LAZY_TAG).v("区块再次可见(已加载/加载中,跳过): %s", section)
        }
    }
}
