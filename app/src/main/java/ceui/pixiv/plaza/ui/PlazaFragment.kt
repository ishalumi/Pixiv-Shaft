package ceui.pixiv.plaza.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ceui.lisa.R
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.databinding.FragmentPlazaBinding
import ceui.pixiv.chat.base.PagingFooterAdapter
import ceui.pixiv.chat.base.PagingState
import ceui.pixiv.chat.base.launchSuspend
import ceui.pixiv.chat.base.setupToolbar
import ceui.pixiv.chat.base.viewBinding
import ceui.pixiv.chat.base.viewModels
import ceui.pixiv.chat.core.AppError
import ceui.pixiv.plaza.api.PlazaPost
import ceui.pixiv.session.SessionManager
import com.scwang.smart.refresh.layout.api.RefreshLayout
import com.scwang.smart.refresh.layout.listener.OnRefreshListener

/**
 * 广场。Toolbar 右上 + → 进发帖页;右下 FAB 备份入口 (移动端 reach 友好)。
 *
 * 列表用 ConcatAdapter(feedAdapter, footerAdapter):
 *   - feedAdapter:数据
 *   - footerAdapter:loading more / error 状态
 * 这套跟 chat 一致,UI 一致 + 复用 chat 的状态机。
 *
 * 删帖入口在每条卡片右上 ⋯ 菜单里,且仅自己发的帖子才显示 (卡片 onMore
 * 时判断 post.uid == SessionManager.loggedInUid)。
 */
class PlazaFragment : Fragment(R.layout.fragment_plaza) {

    private val binding by viewBinding(FragmentPlazaBinding::bind)
    private val viewModel by viewModels { PlazaViewModel() }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupToolbar(getString(R.string.plaza_title), showBack = true)
        binding.appBarLayout.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_plaza_compose) {
                openCompose(); true
            } else false
        }
        binding.fabCompose.setOnClickListener { openCompose() }

        // 列表 + 分页
        val feedAdapter = PlazaFeedAdapter(
            selfUid = SessionManager.loggedInUid,
            onMore = ::onPostMore,
        )
        val footerAdapter = PagingFooterAdapter().apply {
            onRetry = { viewModel.loadMore(requireContext()) }
        }
        val concatAdapter = androidx.recyclerview.widget.ConcatAdapter(feedAdapter, footerAdapter)

        val layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.layoutManager = layoutManager
        binding.recyclerView.adapter = concatAdapter
        binding.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0) return
                val total = layoutManager.itemCount
                val lastVisible = layoutManager.findLastVisibleItemPosition()
                if (lastVisible >= total - 4) viewModel.loadMore(requireContext())
            }
        })

        binding.refreshLayout.setOnRefreshListener(object : OnRefreshListener {
            override fun onRefresh(refreshLayout: RefreshLayout) {
                viewModel.load(requireContext(), isSwipeRefresh = true)
            }
        })
        binding.errorRetry.setOnClickListener { viewModel.load(requireContext()) }

        // 状态订阅 + 事件
        launchSuspend {
            viewModel.state.collect { s ->
                feedAdapter.submitList(s.items)
                val whiteScreen = !s.isInitialLoading && s.items.isEmpty()
                binding.emptyView.isVisible = whiteScreen && s.initialError == null
                binding.errorLayout.isVisible = whiteScreen && s.initialError != null
                if (s.initialError != null) {
                    binding.errorText.text = getString(R.string.plaza_load_failed, s.initialError)
                }
                if (!s.isRefreshing) binding.refreshLayout.finishRefresh()
                footerAdapter.setPagingState(
                    when (val p = s.paging) {
                        PlazaViewModel.PlazaPagingState.Idle -> PagingState.Idle
                        PlazaViewModel.PlazaPagingState.LoadingMore -> PagingState.LoadingMore
                        PlazaViewModel.PlazaPagingState.EndReached -> PagingState.EndReached
                        is PlazaViewModel.PlazaPagingState.Error ->
                            // wrap into AppError.Unknown so footerAdapter renders the message
                            PagingState.Error(AppError.Unknown(p.message))
                    }
                )
            }
        }
        launchSuspend {
            viewModel.events.collect { ev ->
                when (ev) {
                    is PlazaViewModel.Event.Toast ->
                        android.widget.Toast.makeText(
                            requireContext(), ev.message, android.widget.Toast.LENGTH_SHORT
                        ).show()
                }
            }
        }

        // 首次进来加载
        if (savedInstanceState == null) viewModel.load(requireContext())
    }

    private fun openCompose() {
        if (SessionManager.loggedInUid <= 0L) {
            android.widget.Toast.makeText(
                requireContext(), R.string.plaza_login_required, android.widget.Toast.LENGTH_SHORT
            ).show()
            return
        }
        val intent = Intent(requireContext(), TemplateActivity::class.java)
        intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "发帖")
        startActivity(intent)
    }

    private fun onPostMore(post: PlazaPost, anchor: View) {
        // 只有自己的帖子才会到这里 (PlazaFeedAdapter 已按 selfUid 隐藏 ⋯ 按钮)。
        // MVP 只有「删除」一项,Bottom-sheet 改造留给下版,先 AlertDialog 简化。
        if (post.uid != SessionManager.loggedInUid) return
        AlertDialog.Builder(requireContext())
            .setMessage(R.string.plaza_delete_confirm)
            .setNegativeButton(R.string.plaza_delete_cancel, null)
            .setPositiveButton(R.string.plaza_delete_confirm_yes) { _, _ ->
                viewModel.deletePost(requireContext(), post, SessionManager.loggedInUid)
            }
            .show()
    }
}
