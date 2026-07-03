package ceui.pixiv.ui.comments

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.view.updatePaddingRelative
import androidx.databinding.DataBindingUtil
import androidx.navigation.fragment.navArgs
import ceui.lisa.R
import ceui.lisa.databinding.CellEditingCommentBinding
import ceui.lisa.databinding.FragmentPixivListBinding
import ceui.lisa.utils.ClipBoardUtils
import ceui.loxia.Comment
import ceui.loxia.ObjectPool
import ceui.loxia.ObjectType
import ceui.loxia.ProgressTextButton
import ceui.loxia.hideKeyboard
import ceui.loxia.launchSuspend
import ceui.pixiv.session.SessionManager
import ceui.pixiv.ui.common.ListMode
import ceui.pixiv.ui.common.PixivFragment
import ceui.pixiv.ui.detail.showV3Menu
import ceui.pixiv.ui.common.setUpRefreshState
import ceui.pixiv.ui.list.pixivListViewModel
import ceui.pixiv.ui.user.UserActionReceiver
import ceui.pixiv.utils.setOnClick
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.ui.works.blurBackground

class CommentsFragment : PixivFragment(R.layout.fragment_pixiv_list), CommentActionReceiver {

    private val binding by viewBinding(FragmentPixivListBinding::bind)
    private val args by navArgs<CommentsFragmentArgs>()
    private val viewModel by pixivListViewModel { CommentsDataSource(args) }
    private val dataSource: CommentsDataSource by lazy { viewModel.typedDataSource() }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.toolbarLayout.naviTitle.text = getString(R.string.comments)
        setUpRefreshState(binding, viewModel, ListMode.VERTICAL_COMMENT)
        binding.bottomLayout.isVisible = true
        binding.bottomLayout.background = ColorDrawable(Color.parseColor("#66000000"))
        val childBinding = DataBindingUtil.inflate<CellEditingCommentBinding>(
            layoutInflater,
            R.layout.cell_editing_comment,
            binding.bottomLayout,
            true
        )
        // 设置根布局的点击监听
        binding.touchOutside.setOnTouchListener { _, _ ->
            // 隐藏键盘
            hideKeyboard()
            false
        }
        childBinding.lifecycleOwner = viewLifecycleOwner
        childBinding.viewModel = dataSource
        childBinding.send.setOnClick {
            launchSuspend(it) {
                dataSource.sendComment()
            }
        }
        if (args.objectType == ObjectType.ILLUST) {
            blurBackground(binding, args.objectId)
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, windowInsets ->
            val imeInsets = windowInsets.getInsets(WindowInsetsCompat.Type.ime()) // 获取输入法的 insets
            val systemBarsInsets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars()) // 获取系统栏的 insets

            // 更新 Toolbar 的顶部 padding
            binding.toolbarLayout.root.updatePaddingRelative(top = systemBarsInsets.top)

            // 确定底部 inset
            binding.touchOutside.isVisible = imeInsets.bottom > 0
            val bottomInsets = if (imeInsets.bottom > 0) imeInsets.bottom else systemBarsInsets.bottom
            binding.bottomLayout.updatePadding(bottom = bottomInsets)

            WindowInsetsCompat.CONSUMED
        }
    }

    override fun onClickReply(comment: Comment, parentCommentId: Long) {
        dataSource.replyToComment.value = comment
        dataSource.replyParentComment.value = parentCommentId
    }

    override fun onClickShowMoreReply(sender: ProgressTextButton, commentId: Long) {
        launchSuspend(sender) {
            dataSource.showMoreReply(commentId)
        }
    }

    override fun onClickComment(comment: Comment) {

    }

    override fun onLongClickComment(anchor: View, comment: Comment, parentCommentId: Long) {
        val isOwn = SessionManager.loggedInUid == comment.user.id
        val commentText = comment.comment
        // 社交软件式长按操作:复制评论 / 回复 / 查看用户 / 删除(仅自己),统一用 V3MenuDialog
        showV3Menu("CommentMenu") {
            if (!commentText.isNullOrBlank()) {
                item(getString(R.string.string_173), R.drawable.baseline_content_copy_24) {
                    ClipBoardUtils.putTextIntoClipboard(requireContext(), commentText)
                }
                item(getString(R.string.comment_translate_to_zh), R.drawable.ic_baseline_translate_24) {
                    translateCommentToChinese(commentText)
                }
            }
            if (!isOwn) {
                item(getString(R.string.string_176), R.drawable.chat_ic_reply) {
                    onClickReply(comment, parentCommentId)
                }
            }
            item(getString(R.string.string_174), R.drawable.ic_supervisor_account_black_24dp) {
                ObjectPool.update(comment.user)
                onClickUser(comment.user.id)
            }
            if (isOwn) {
                item(getString(R.string.string_219), R.drawable.ic_delete_black_24dp) {
                    launchSuspend { dataSource.deleteComment(comment.id, parentCommentId) }
                }
            }
        }
    }

    override fun onClickDeleteComment(sender: ProgressTextButton, comment: Comment, parentCommentId: Long) {
        launchSuspend(sender) {
            dataSource.deleteComment(comment.id, parentCommentId)
        }
    }

    companion object {
        fun newInstance(
            objectId: Long,
            objectArthurId: Long,
            objectType: String
        ): CommentsFragment {
            return CommentsFragment().apply {
                arguments = CommentsFragmentArgs(objectId, objectArthurId, objectType).toBundle()
            }
        }
    }
}

interface CommentActionReceiver : UserActionReceiver {

    fun onClickReply(comment: Comment, parentCommentId: Long)

    fun onClickShowMoreReply(sender: ProgressTextButton, commentId: Long)

    fun onClickComment(comment: Comment)

    fun onLongClickComment(anchor: View, comment: Comment, parentCommentId: Long)

    fun onClickDeleteComment(sender: ProgressTextButton, comment: Comment, parentCommentId: Long)
}