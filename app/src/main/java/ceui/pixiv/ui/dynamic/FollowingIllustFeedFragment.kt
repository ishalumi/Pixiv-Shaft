package ceui.pixiv.ui.dynamic

import android.os.Bundle
import android.view.View
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModel
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import androidx.viewbinding.ViewBinding
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.utils.Params
import ceui.loxia.Client
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.FeedRenderer
import ceui.pixiv.feeds.FeedSkeletonView
import ceui.pixiv.feeds.FeedTimelineSkeletonView
import ceui.pixiv.feeds.PixivFeedSource
import ceui.pixiv.feeds.feedViewModels
import ceui.pixiv.ui.common.IllustFeedFragment
import ceui.pixiv.ui.common.IllustFeedItem

/**
 * 「动态」页的插画/漫画列表（feeds 框架版，替代 legacy FragmentRight 自身的
 * NetListFragment + RightRepo + IAdapter/TimelineAdapter 那一套）。
 * 宿主是 [ceui.lisa.fragments.FragmentRight]，装在它的 `illust_list_container` 里。
 *
 * 两件本页特有的事，其余（收藏、长按菜单、详情 pager 续拉回流、合池）全部继承自
 * [IllustFeedFragment]：
 *
 * 1. **筛选范围**（全部 / 公开 / 私人）：宿主的 GlareLayout 通过 [setRestrict] 推进来。
 *    值存在 [RestrictViewModel] 而不是 Fragment 字段——数据源归 FeedViewModel 长期持有、
 *    比 Fragment 实例活得久，按零捕获约定不能读 Fragment 字段；而 [RestrictViewModel] 与
 *    FeedViewModel 同一个 store、同生共死，捕获它既不漏也不会读到上一代的值。
 * 2. **两种排布**：时间线（单列大卡）/ 瀑布流，由设置「关注动态布局模式」持久化，
 *    宿主的按钮切换后调 [applyLayoutMode] —— 只重装 Renderer + LayoutManager，
 *    数据留在 VM 里**不重拉**（对齐 legacy 换 adapter 的语义）。
 */
class FollowingIllustFeedFragment : IllustFeedFragment() {

    /**
     * 本列表当前（或即将）加载所用的筛选范围。归 VM 的两个理由：数据源要现读它（见类文档），
     * 以及它必须与列表数据一起跨视图重建存活——否则旋转后数据还是「私人」而这里复位成
     * 「全部」，下次 [setRestrict] 就会误判成「没变」而不重拉，筛选条和内容当场对不上。
     *
     * 写在主线程（GlareLayout 回调）。读发生在数据源里：目前 `load` 跑在 viewModelScope
     * （Main.immediate），但这是 FeedViewModel 的内部实现细节、不是本类能依赖的契约，
     * 所以标 @Volatile 让「换个调度器也不会读到陈旧值」这件事不依赖别处的实现。
     */
    class RestrictViewModel : ViewModel() {
        @Volatile
        var restrict: String = Params.TYPE_ALL
    }

    private val restrictViewModel: RestrictViewModel by viewModels()

    override val feedViewModel by feedViewModels {
        // 取成局部 val:捕获的是 VM 实例(与 FeedViewModel 同 store、同寿命),不是 Fragment
        val holder = restrictViewModel
        PixivFeedSource({ Client.appApi.getFollowingIllusts(holder.restrict) }) { resp, _ ->
            resp.displayList.mapNotNull { IllustFeedItem.from(it) }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // 裸 fragment_feed 会被基类刷上 legacy 的 fragment_center(夜间 #2A2A2A)，但本列表是嵌在
        // 动态页那张 v3_menu_bg(#1A1A2E) 圆角 sheet 里的——不改就会在筛选条下方出现一道灰/藏青
        // 撞色的横向接缝(legacy 的 RecyclerView 没有底色，透出来的一直是 sheet 本身)。
        // 与 PivisionFeedFragment 同一手法：宿主底色归宿主，这里跟着宿主刷。
        view.setBackgroundResource(R.color.v3_menu_bg)
    }

    /** 时间线模式 = 单列大卡；关掉就是瀑布流。真源是设置项，设置页「关注动态布局模式」同一个开关。 */
    private val isTimelineMode: Boolean
        get() = !Shaft.sSettings.isUseStaggeredLayout()

    override fun onCreateLayoutManager(): RecyclerView.LayoutManager {
        return if (isTimelineMode) {
            LinearLayoutManager(requireContext())
        } else {
            super.onCreateLayoutManager()
        }
    }

    override fun onCreateRenderers(): List<FeedRenderer<out FeedItem, out ViewBinding>> {
        return if (isTimelineMode) {
            listOf(timelineIllustRenderer { item -> openDetail(item) })
        } else {
            super.onCreateRenderers()
        }
    }

    override fun onListReady(listView: RecyclerView) {
        // 瀑布流卡自身无 margin,靠基类的 8dp SpacesItemDecoration 拉开;
        // 时间线大卡自带内边距和分隔线,再加 decoration 会双份留白
        if (!isTimelineMode) {
            super.onListReady(listView)
        }
    }

    override fun onCreateSkeletonView(layoutManager: RecyclerView.LayoutManager): FeedSkeletonView? {
        // 按 LayoutManager 判而不是再读一次设置:骨架必须和这次装配出来的列表长得一样,
        // 不能有第二个真源(哪怕理论上读不出不一致,也别留这种耦合)
        return if (layoutManager is StaggeredGridLayoutManager) {
            super.onCreateSkeletonView(layoutManager)
        } else {
            FeedTimelineSkeletonView(requireContext())
        }
    }

    /**
     * 切筛选范围（宿主 GlareLayout 选中另一项时调）。对齐 legacy 的
     * `RightRepo.setRestrict + forceRefresh`：**变了才重拉**，没变是 no-op。
     */
    fun setRestrict(restrict: String) {
        if (restrictViewModel.restrict == restrict) return
        restrictViewModel.restrict = restrict
        // forceRefresh 在 view 未创建时安全 no-op:此时数据也还没拉过,
        // 首屏会自然用上面刚写进去的新 restrict
        forceRefresh()
    }

    /** 时间线 / 瀑布流切换后重装列表（宿主写完设置项再调）。数据不重拉。 */
    fun applyLayoutMode() {
        rebuildList()
    }
}
