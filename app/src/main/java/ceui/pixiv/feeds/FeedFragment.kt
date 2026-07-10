package ceui.pixiv.feeds

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.annotation.LayoutRes
import androidx.core.view.isVisible
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
 * 需要 toolbar 等额外骨架时传自定义 [contentLayoutId]，布局里
 * `<include layout="@layout/fragment_feed"/>`（id 保持 feed_root）即可。
 */
abstract class FeedFragment(
    @LayoutRes contentLayoutId: Int = R.layout.fragment_feed,
) : Fragment(contentLayoutId) {

    protected abstract val feedViewModel: FeedViewModel<*>

    /** 每种条目类型一个 Renderer；只在 onViewCreated 时调用一次。 */
    protected abstract fun onCreateRenderers(): List<FeedRenderer<out FeedItem, out ViewBinding>>

    private var _binding: FragmentFeedBinding? = null
    protected val feedBinding: FragmentFeedBinding
        get() = checkNotNull(_binding) { "view 尚未创建或已销毁" }

    protected var feedAdapter: FeedAdapter? = null
        private set

    /** 刷新出错但屏幕上仍有内容时的轻提示，子类可换成自己的错误处理。 */
    private var consumedRefreshError: LoadState.Error? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val feedRoot = requireNotNull(view.findViewById<View>(R.id.feed_root)) {
            "自定义布局必须 <include layout=\"@layout/fragment_feed\"/>（id 保持 feed_root）"
        }
        val binding = FragmentFeedBinding.bind(feedRoot)
        _binding = binding

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

    private fun render(adapter: FeedAdapter, state: FeedUiState) {
        val displayList = when (val append = state.append) {
            is LoadState.Loading, is LoadState.Error -> state.items + AppendFooterItem(append)
            else -> state.items
        }
        adapter.submitList(displayList)

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

        // 有内容兜底时的刷新失败只提示一次，不打断浏览
        val refreshError = state.refresh as? LoadState.Error
        if (refreshError != null && refreshError !== consumedRefreshError && state.items.isNotEmpty()) {
            consumedRefreshError = refreshError
            onRefreshFailedWithContent(refreshError.throwable)
        }
    }
}
