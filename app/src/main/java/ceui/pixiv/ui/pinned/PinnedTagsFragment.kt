package ceui.pixiv.ui.pinned

import android.os.Bundle
import android.view.View
import androidx.appcompat.widget.Toolbar
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.RecyclerView
import ceui.lisa.R
import ceui.loxia.RefreshHint
import ceui.pixiv.ui.common.CommonAdapter
import ceui.pixiv.ui.common.ListMode
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.common.setUpLayoutManager
import com.blankj.utilcode.util.BarUtils
import kotlinx.coroutines.launch

/**
 * 「侧边栏 → 置顶标签」入口对应的列表页。
 *
 * 入口在 [ceui.lisa.activities.MainActivity.handleDrawerAction] 通过
 * `EXTRA_FRAGMENT = "PinnedTagsList"` 路由到这里。
 *
 * 显示样式参考 [ceui.pixiv.ui.prime.PrimeTagsFragment] 里的 `PrimeTagItemHolder`；
 * 数据来源是 [ceui.lisa.utils.PixivOperate.insertPinnedSearchHistory] 写入的 search_table。
 *
 * Toolbar 用 fragment_settings / fragment_webview 同款 5 件套（[layout/fragment_pinned_tags]），
 * 不复用 fragment_pixiv_list.xml 的 layout_toolbar —— 因此也不能复用 [setUpRefreshState]
 * （硬绑 `FragmentPixivListBinding`），把它那点 list 装配逻辑手摘出来即可：
 * LinearLayoutManager + CommonAdapter + 观察 holders + 切换 empty view。本地 DB 查询毫秒级，
 * 不需要下拉刷新；新置顶通过 [onResume] 重新查一次 DB 自动出现。
 */
class PinnedTagsFragment : PixivFragment(R.layout.fragment_pinned_tags) {

    private val viewModel by viewModels<PinnedTagsViewModel>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<Toolbar>(R.id.toolbar).apply {
            // BaseActivity 走 EdgeToEdge,顶部状态栏 inset 由 runtime padding 处理,不用
            // fitsSystemWindows(那个在 EdgeToEdge host 下会和 bottom inset 一起算导致额外空白)。
            updatePadding(top = BarUtils.getStatusBarHeight())
            setNavigationOnClickListener { activity?.finish() }
        }

        val listView = view.findViewById<RecyclerView>(R.id.list_view)
        val emptyView = view.findViewById<View>(R.id.empty_view)

        // 走项目标准 setUpLayoutManager:VERTICAL 模式会挂上 LinearItemDecoration(18dp),
        // 给每个 item 上下左右各 18dp 间距 + 第一项额外 top —— 不挂 cell 会顶到屏幕边、
        // 上下也无空隙。`fragment_pixiv_list` 体系通过 setUpRefreshState 间接调用这个,
        // 我们没复用 helper 就要手动挂上。
        setUpLayoutManager(listView, ListMode.VERTICAL)
        val adapter = CommonAdapter(viewLifecycleOwner)
        listView.adapter = adapter

        viewModel.holders.observe(viewLifecycleOwner) { holders ->
            adapter.submitList(holders)
            emptyView.isVisible = holders.isEmpty()
        }

        // 用户从详情页长按置顶 / 取消置顶后回到本页,要看到列表更新。
        // STARTED-aware 协程:只在 fragment 可见时才触发 refresh,避免后台 churn。
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.refresh(RefreshHint.PullToRefresh)
            }
        }
    }
}
