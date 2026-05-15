package ceui.pixiv.plaza.ui

import android.os.Bundle
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import ceui.lisa.R
import ceui.lisa.databinding.CellPlazaPostBinding
import ceui.lisa.databinding.FragmentPlazaPostDetailBinding
import ceui.pixiv.chat.base.launchSuspend
import ceui.pixiv.chat.base.setupToolbar
import ceui.pixiv.chat.base.viewBinding
import ceui.pixiv.chat.base.viewModels
import ceui.pixiv.session.SessionManager
import com.qmuiteam.qmui.widget.dialog.QMUIDialog
import com.qmuiteam.qmui.widget.dialog.QMUIDialogAction

/**
 * 单帖详情页。从 plaza feed 卡片点进来,或从分享深链 / 通知打开。
 *
 * - init:用 ShaftApiV2Client.cachedPlazaPost(id) 提供快照(从 feed 进来一般已缓存),
 *   同时 launch GET /posts/:id 拉权威版本覆盖 meta / display_name
 * - 404:显示 plaza_post_gone 占位
 * - 自己的帖子:底部「删除」按钮可见;成功后通过 ShaftApiV2Client.plazaPostsDeleted
 *   广播让 plaza feed 自动 filter,然后 finish detail
 */
class PlazaPostDetailFragment : Fragment(R.layout.fragment_plaza_post_detail) {

    private val binding by viewBinding(FragmentPlazaPostDetailBinding::bind)
    private val postId: Long by lazy { requireArguments().getLong(EXTRA_POST_ID, 0L) }
    private val viewModel by viewModels { PlazaPostDetailViewModel(postId) }

    /** post_card 是 <include layout=cell_plaza_post>,绑这个 binding 上去复用 bindPlazaPostCard。 */
    private val postCardBinding by lazy { CellPlazaPostBinding.bind(binding.postCard.root) }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupToolbar(getString(R.string.plaza_post_detail_title), showBack = true)

        // safe area: bottom_bar bottomPadding = XML 基础值 + navBar inset
        val baseBottomPadding = binding.bottomBar.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.bottomBar) { v, insets ->
            val nav = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            v.updatePadding(bottom = baseBottomPadding + nav)
            insets
        }

        binding.btnDelete.setOnClickListener { confirmDelete() }

        launchSuspend {
            viewModel.state.collect { s ->
                if (s.isGone) {
                    binding.postCard.root.isVisible = false
                    binding.bottomBar.isVisible = false
                    binding.goneText.isVisible = true
                    return@collect
                }
                binding.goneText.isVisible = false
                val post = s.post
                if (post != null) {
                    binding.postCard.root.isVisible = true
                    bindPlazaPostCard(
                        binding = postCardBinding,
                        post = post,
                        selfUid = SessionManager.loggedInUid,
                        onMore = null,  // detail 页底部已有删除按钮,不再走 ⋯ 菜单
                        onCardClick = null,  // 已经在 detail 里,卡片不再可点
                    )
                    // 仅自己的帖子才显示底部删除条
                    val isMine = post.uid == SessionManager.loggedInUid && SessionManager.loggedInUid > 0L
                    binding.bottomBar.isVisible = isMine
                    binding.btnDelete.isEnabled = !s.isDeleting
                } else {
                    binding.postCard.root.isVisible = false
                    binding.bottomBar.isVisible = false
                }
            }
        }
        launchSuspend {
            viewModel.events.collect { ev ->
                when (ev) {
                    is PlazaPostDetailViewModel.Event.Toast -> android.widget.Toast
                        .makeText(requireContext(), ev.message, android.widget.Toast.LENGTH_SHORT)
                        .show()
                    PlazaPostDetailViewModel.Event.DeletedAndClose -> requireActivity().finish()
                }
            }
        }
    }

    private fun confirmDelete() {
        QMUIDialog.MessageDialogBuilder(requireContext())
            .setMessage(R.string.plaza_delete_confirm)
            .addAction(R.string.plaza_delete_cancel) { d, _ -> d.dismiss() }
            .addAction(
                0, R.string.plaza_delete_confirm_yes, QMUIDialogAction.ACTION_PROP_NEGATIVE
            ) { d, _ ->
                d.dismiss()
                viewModel.delete(requireContext(), SessionManager.loggedInUid)
            }
            .show()
    }

    companion object {
        const val EXTRA_POST_ID = "plaza_post_id"

        fun newInstance(postId: Long): PlazaPostDetailFragment {
            return PlazaPostDetailFragment().apply {
                arguments = Bundle().apply { putLong(EXTRA_POST_ID, postId) }
            }
        }
    }
}
