package ceui.pixiv.feeds

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.annotation.LayoutRes
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import ceui.lisa.R
import ceui.lisa.databinding.FragmentFeedBinding
import kotlinx.coroutines.launch

/**
 * 列表页基类。子类只声明两件事：数据从哪来（[feedViewModel]）、每种条目怎么画（[onCreateRenderers]），
 * 刷新 / 翻页 / 空态 / 错误态 / 防重入全部由框架负责。
 *
 * ```
 * class XxxFragment : FeedFragment() {
 *     override val feedViewModel by feedViewModels { XxxFeedSource() }
 *     override fun onCreateRenderers() = listOf(xxxRenderer(), yyyRenderer())
 * }
 * ```
 *
 * ⚠️ 数据源 lambda（initialFetch / mapper）不要捕获 Fragment——它归 VM 长期持有，
 * 会把旋转前的旧 Fragment 实例钉在内存里（零捕获约定详见 [feedViewModels] 文档）。
 *
 * 需要 toolbar 等额外骨架时传自定义 [contentLayoutId]，布局里
 * `<include layout="@layout/fragment_feed"/>`（id 保持 feed_root）即可。
 */
abstract class FeedFragment(
    @LayoutRes contentLayoutId: Int = R.layout.fragment_feed,
) : Fragment(contentLayoutId) {

    protected abstract val feedViewModel: FeedViewModel<*>

    /** 每种条目类型一个 Renderer；只在 onViewCreated 时调用一次。 */
    protected abstract fun onCreateRenderers(): List<FeedRenderer<out FeedItem, out ViewBinding>>

    /**
     * 裸 fragment_feed 形态下,是否给列表底部补 systemBars inset(手势条/导航栏)。
     * 全屏铺到屏幕底、底部无 BottomNavigation 的宿主(UserActivityV3 的各 tab 等)覆写为 true;
     * 首页那种底部有导航栏的宿主保持 false。带 toolbar 的骨架不受此开关影响(setUpToolbar 自理)。
     */
    protected open val applyBottomSafeInset: Boolean = false

    private var _binding: FragmentFeedBinding? = null
    protected val feedBinding: FragmentFeedBinding
        get() = checkNotNull(_binding) { "view 尚未创建或已销毁" }

    protected var feedAdapter: FeedAdapter? = null
        private set

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val feedRoot = requireNotNull(view.findViewById<View>(R.id.feed_root)) {
            "自定义布局必须 <include layout=\"@layout/fragment_feed\"/>（id 保持 feed_root）"
        }
        val binding = FragmentFeedBinding.bind(feedRoot)
        _binding = binding

        // 裸 fragment_feed 自带列表底色（对齐 legacy fragment_base_list 的内容区背景），
        // 否则宿主布局的装饰背景（如 activity_multi_view_pager 根上的 ?attr/colorPrimary）
        // 会从透明列表后面整页透出来；自定义 contentLayoutId 的页面（fragment_toolbar_feed
        // 等 V3 页用 v3_bg）背景归自己的根布局管，这里不越权覆盖。
        if (view === feedRoot) {
            feedRoot.setBackgroundResource(R.color.fragment_center)
        }

        // 底部 safe-area:裸 fragment_feed 铺到屏幕底(UserActivityV3 tab 等无底栏宿主),列表末尾
        // 会被手势条/导航栏挡住。给列表补底部 systemBars inset(clipToPadding=false 已使末条能上滚)。
        // 只在裸 feed 形态生效:带 toolbar 的骨架由 setUpToolbar 自己吃 inset,重复套会多留一截。
        // 底部有 BottomNavigation 的宿主(首页 MainActivity)保持 applyBottomSafeInset=false,免得在
        // 导航栏上方留空。
        if (view === feedRoot && applyBottomSafeInset) {
            val listView = binding.feedListView
            val basePaddingBottom = listView.paddingBottom
            ViewCompat.setOnApplyWindowInsetsListener(listView) { v, windowInsets ->
                val bottom = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
                v.updatePadding(bottom = basePaddingBottom + bottom)
                windowInsets
            }
            ViewCompat.requestApplyInsets(listView)
        }

        val adapter = FeedAdapter(
            renderers = onCreateRenderers() + AppendFooterRenderer { feedViewModel.retryAppend() },
            onNearEnd = { feedViewModel.loadMore() },
        )
        feedAdapter = adapter

        binding.feedListView.apply {
            layoutManager = onCreateLayoutManager()
            this.adapter = adapter
            onListReady(this)
        }
        binding.feedRefreshLayout.setOnRefreshListener { feedViewModel.refresh() }
        binding.feedStateText.setOnClickListener { feedViewModel.refresh() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                feedViewModel.uiState.collect { state -> render(adapter, state) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 懒加载 VM（feedViewModels(autoLoad = false)）的首屏在这里补：
        // 只有真正可见的 tab 会走到 RESUMED（宿主 pager 需 BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT），
        // ensureLoaded 幂等，失败后再次可见自动重试
        if (!feedViewModel.autoLoad) {
            feedViewModel.ensureLoaded()
        }
    }

    override fun onDestroyView() {
        feedAdapter = null
        _binding = null
        super.onDestroyView()
    }

    /** 默认竖排线性；网格用 [gridLayoutManager]，瀑布流直接返回 StaggeredGridLayoutManager。 */
    protected open fun onCreateLayoutManager(): RecyclerView.LayoutManager {
        return LinearLayoutManager(requireContext())
    }

    /** 列表就绪回调：加 ItemDecoration、共享 RecycledViewPool 等。 */
    protected open fun onListReady(listView: RecyclerView) {}

    /** 屏幕上有内容时刷新失败的提示，默认 Toast；子类可覆盖。 */
    protected open fun onRefreshFailedWithContent(throwable: Throwable) {
        Toast.makeText(
            requireContext(),
            getString(R.string.list_load_failed_tap_retry),
            Toast.LENGTH_SHORT,
        ).show()
    }

    /** 回到列表顶部（tab 双击、toolbar 点击等场景）。view 未创建时安全 no-op。 */
    fun scrollToTop() {
        _binding?.feedListView?.smoothScrollToPosition(0)
    }

    /** 回顶 + 重新刷新（底栏当前 tab 再点等场景，对齐 legacy ListFragment.forceRefresh）。 */
    fun forceRefresh() {
        // 宿主（FragmentLeft/FragmentHolder 等）视图重建时会重新 new 一份 fragments 数组，
        // 而 pager 复用的是 FragmentManager 恢复的旧实例——数组里这份孤儿实例永不 attach。
        // 无 view 时安全 no-op：孤儿实例上碰 feedViewModel 会在取 ViewModelStore 时抛 ISE
        if (_binding == null) return
        scrollToTop()
        feedViewModel.refresh()
    }

    /** 已自动接好 [FeedRenderer.spanSize] 的 GridLayoutManager。 */
    protected fun gridLayoutManager(spanCount: Int): GridLayoutManager {
        return GridLayoutManager(requireContext(), spanCount).also { manager ->
            manager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int {
                    return feedAdapter?.spanSizeAt(position, spanCount) ?: 1
                }
            }
        }
    }

    /** [state.items] 的 diff 已经全部提交给 RecyclerView 之后回调——此时 adapter 的
     * itemCount/内容才真正反映这份 state，[RecyclerView.findViewHolderForAdapterPosition]
     * 才可能对新插入的条目命中。diff 本身在后台线程算，submitList 调用完立刻查 ViewHolder
     * 大概率还是旧数据（构造函数级的时间竞争）；需要「精确感知新条目已经上屏」的场景
     * （如新评论发出后滚回顶部高亮）在子类覆写，而不是自己拍延迟猜时机。默认 no-op。 */
    protected open fun onListCommitted(state: FeedUiState) {}

    private fun render(adapter: FeedAdapter, state: FeedUiState) {
        val displayList = when (val append = state.append) {
            is LoadState.Loading, is LoadState.Error -> state.items + AppendFooterItem(append)
            else -> state.items
        }
        adapter.submitList(displayList) { onListCommitted(state) }

        val binding = feedBinding
        binding.feedRefreshLayout.isRefreshing =
            state.refresh is LoadState.Loading && state.hasLoadedOnce
        binding.feedLoading.isVisible = state.showFullscreenLoading

        val stateText = when {
            state.showFullscreenError -> getString(R.string.list_load_failed_tap_retry)
            state.showEmptyState -> getString(R.string.empty_list_1)
            else -> null
        }
        binding.feedStateText.isVisible = stateText != null
        binding.feedStateText.text = stateText
        binding.feedStateContainer.isVisible = state.showFullscreenLoading || stateText != null

        // 有内容兜底时的刷新失败只提示一次，不打断浏览；已消费标记在 VM（旋转重建不重复提示）
        val refreshError = state.refresh as? LoadState.Error
        if (refreshError != null && state.items.isNotEmpty() &&
            feedViewModel.shouldNotifyRefreshError(refreshError)
        ) {
            onRefreshFailedWithContent(refreshError.throwable)
        }
    }
}
