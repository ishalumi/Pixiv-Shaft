package ceui.pixiv.ui.comments

import android.content.Intent
import android.os.Bundle
import android.transition.ChangeBounds
import android.transition.Slide
import android.transition.TransitionManager
import android.transition.TransitionSet
import android.view.Gravity
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.viewModels
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import ceui.lisa.R
import ceui.lisa.activities.UActivity
import ceui.lisa.databinding.FragmentCommentsFeedBinding
import ceui.lisa.utils.ClipBoardUtils
import ceui.lisa.utils.Params
import ceui.lisa.utils.V3Palette
import ceui.lisa.view.LinearItemDecoration
import ceui.loxia.Client
import ceui.loxia.Comment
import ceui.loxia.ObjectPool
import ceui.loxia.ObjectType
import ceui.loxia.ProgressTextButton
import ceui.loxia.hideKeyboard
import ceui.loxia.launchSuspend
import ceui.loxia.showKeyboard
import ceui.pixiv.feeds.FeedFragment
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.FeedRenderer
import ceui.pixiv.feeds.PixivFeedSource
import ceui.pixiv.feeds.feedViewModels
import ceui.pixiv.session.SessionManager
import ceui.pixiv.ui.detail.showV3Menu
import ceui.pixiv.ui.user.UserActionReceiver
import ceui.pixiv.utils.ppppx
import ceui.pixiv.utils.setOnClick
import com.blankj.utilcode.util.BarUtils

/**
 * 评论页(feeds 框架版):数据全部住在 feedViewModel（FeedViewModel<String>，见
 * [ceui.pixiv.feeds.FeedViewModel]）；发评论 / 删评论 / 展开更多回复都是本地一次性网络调用
 * （见 [CommentsComposerViewModel]），「网络结果如何编辑列表」的分支判断收口在那个 VM
 * 的 apply* 纯函数里——本 Fragment 只做编排：调网络、把返回值转手扔给
 * feedViewModel.mutateItems。卡片怎么画在 [commentCardRenderer]（CommentCardRenderer.kt）。
 * items 是否为空天然驱动 [ceui.pixiv.feeds.FeedUiState.showEmptyState]，不需要额外的
 * 空态标记：空列表发第一条评论会立刻从空态切成有内容，删光最后一条评论会自动回落空态。
 */
class CommentsFragment : FeedFragment(R.layout.fragment_comments_feed), CommentActionReceiver {

    private val args by navArgs<CommentsFragmentArgs>()

    private val composer by viewModels<CommentsComposerViewModel> {
        viewModelFactory { initializer { CommentsComposerViewModel(args) } }
    }

    override val feedViewModel by feedViewModels {
        // 零捕获约定：先取局部值，避免 mapper/initialFetch 捕获 Fragment 实例
        val objectId = args.objectId
        val objectType = args.objectType
        val illustArthurId = args.objectArthurId
        PixivFeedSource(
            initialFetch = {
                if (objectType == ObjectType.ILLUST) {
                    Client.appApi.getIllustComments(objectId)
                } else {
                    Client.appApi.getNovelComments(objectId)
                }
            },
        ) { resp, phase -> mapCommentsPage(resp, illustArthurId, phase) }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = FragmentCommentsFeedBinding.bind(view)

        // 标准 feeds toolbar 打法(对齐 PixivFragment.setUpToolbar(FragmentToolbarFeedBinding,…)):
        // 顶部状态栏高度一次性 padding,不用实时 insets(本页不再浮在模糊图上,无需每帧跟随)
        binding.toolbar.updatePadding(top = BarUtils.getStatusBarHeight())
        binding.toolbar.setNavigationOnClickListener { requireActivity().finish() }
        binding.toolbarTitle.text = getString(R.string.comments)

        setUpComposer(binding)

        binding.touchOutside.setOnTouchListener { _, _ ->
            hideKeyboard()
            false
        }

        ViewCompat.setOnApplyWindowInsetsListener(view) { _, windowInsets ->
            val imeInsets = windowInsets.getInsets(WindowInsetsCompat.Type.ime())
            val systemBarsInsets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.touchOutside.isVisible = imeInsets.bottom > 0
            // 系统键盘弹出时收起表情面板,两者不共存(同 pixez MediaQuery.viewInsets.bottom==0 判断)
            if (imeInsets.bottom > 0) {
                binding.emojiPanel.isVisible = false
            }
            val bottomInsets = if (imeInsets.bottom > 0) imeInsets.bottom else systemBarsInsets.bottom
            binding.bottomBar.updatePadding(bottom = bottomInsets)
            WindowInsetsCompat.CONSUMED
        }
    }

    /** 「小表情」选择面板:38 个 pixiv 内置表情,点击插入输入框光标处;与系统键盘互斥显示,
     * 打开面板前先收键盘,关闭面板则重新唤起键盘(对齐点了输入框的直觉)。 */
    private fun setUpEmojiPanel(binding: FragmentCommentsFeedBinding) {
        binding.emojiPanel.layoutManager = GridLayoutManager(requireContext(), 7)
        binding.emojiPanel.adapter = CommentEmojiPickerAdapter { code ->
            val editable = binding.commentInput.text ?: return@CommentEmojiPickerAdapter
            val start = binding.commentInput.selectionStart.coerceIn(0, editable.length)
            val end = binding.commentInput.selectionEnd.coerceIn(0, editable.length)
            editable.replace(minOf(start, end), maxOf(start, end), code)
            binding.commentInput.setSelection(minOf(start, end) + code.length)
        }
        binding.emojiToggle.setOnClick {
            val opening = !binding.emojiPanel.isVisible
            binding.emojiPanel.isVisible = opening
            if (opening) hideKeyboard() else showKeyboard(binding.commentInput)
        }
        binding.commentInput.setOnClickListener { binding.emojiPanel.isVisible = false }
    }

    /** MD3-E 输入栏:圆角胶囊输入框 + 主题色实心发送按钮(V3Palette 现算,AppCompat host 不认
     * MaterialButton/TextInputLayout 的 Material3 attrs，见 feedback_pixivshaft_textview_inflate)。
     * 回复目标改成可见 + 可取消的提示条，替代旧版藏在 EditText hint 里的隐晦提示。 */
    private fun setUpComposer(binding: FragmentCommentsFeedBinding) {
        val density = resources.displayMetrics.density
        val palette = V3Palette.from(requireContext())
        binding.commentInput.background = palette.settingsCardBg(24f * density, (1 * density).toInt())
        binding.sendButton.background = palette.pillPrimary()
        setUpEmojiPanel(binding)

        fun updateSendEnabled(text: CharSequence?) {
            val enabled = !text.isNullOrBlank()
            binding.sendButton.isEnabled = enabled
            binding.sendButton.alpha = if (enabled) 1f else 0.4f
        }
        updateSendEnabled(binding.commentInput.text)
        binding.commentInput.addTextChangedListener { editable ->
            if (editable != null) {
                // 就地刷新表情图 span,做到输入框内实时渲染((heaven) 打完就立刻变小图),
                // 不是重新 set 文本(那样会打断输入法组词/丢光标)。span 变更不触发
                // TextWatcher 递归——只有文字内容变化才会。
                CommentEmojiSpanner.clearSpans(editable)
                CommentEmojiSpanner.applySpans(requireContext(), editable, binding.commentInput.textSize.toInt())
            }
            composer.editingComment.value = editable?.toString().orEmpty()
            updateSendEnabled(editable)
        }

        composer.replyToComment.observe(viewLifecycleOwner) { comment ->
            val visible = comment != null
            if (binding.replyBanner.isVisible != visible) {
                // 开启回复=从底部浮上来,取消回复=沉回去;ChangeBounds 顺带把因此挪位的
                // 输入框行也一起补间,不然banner一冒出/收回,下面输入框会硬生生跳一下
                TransitionManager.beginDelayedTransition(
                    binding.bottomBar,
                    TransitionSet()
                        .addTransition(Slide(Gravity.BOTTOM).addTarget(binding.replyBanner))
                        .addTransition(ChangeBounds())
                )
            }
            binding.replyBanner.isVisible = visible
            if (comment != null) {
                binding.replyBannerText.text = "${getString(R.string.string_176)} @${comment.user.name}"
            }
        }
        binding.replyBannerClose.setOnClick { composer.cancelReply() }

        binding.sendButton.setOnClick { sender ->
            launchSuspend(sender) {
                val result = composer.sendComment()
                binding.commentInput.setText(composer.editingComment.value)
                binding.commentInput.setSelection(binding.commentInput.text?.length ?: 0)
                applySendResult(result)
            }
        }
    }

    private fun applySendResult(result: Pair<Long, Comment>?) {
        val (parentCommentId, comment) = result ?: return
        feedViewModel.mutateItems { composer.applySentComment(it, parentCommentId, comment, args.objectArthurId) }
    }

    private suspend fun performDelete(commentId: Long, parentCommentId: Long) {
        composer.deleteComment(commentId)
        feedViewModel.mutateItems { composer.applyDeletedComment(it, commentId, parentCommentId) }
    }

    override fun onListReady(listView: RecyclerView) {
        listView.addItemDecoration(LinearItemDecoration(10.ppppx))
    }

    override fun onCreateRenderers(): List<FeedRenderer<out FeedItem, out ViewBinding>> {
        return listOf(commentCardRenderer())
    }

    override fun onClickReply(comment: Comment, parentCommentId: Long) {
        composer.startReply(comment, parentCommentId)
    }

    override fun onClickShowMoreReply(sender: ProgressTextButton, commentId: Long) {
        launchSuspend(sender) {
            val children = composer.showMoreReply(commentId)
            feedViewModel.mutateItems { composer.applyExpandedReplies(it, commentId, children) }
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
                    launchSuspend { performDelete(comment.id, parentCommentId) }
                }
            }
        }
    }

    override fun onClickDeleteComment(sender: ProgressTextButton, comment: Comment, parentCommentId: Long) {
        launchSuspend(sender) {
            performDelete(comment.id, parentCommentId)
        }
    }

    // classic 分支的 TemplateActivity 是裸 FragmentManager,没有 NavHostFragment——
    // findNavController()/pushFragment 必炸,直接走 Intent(master 分支才有 Navigation 那套)
    override fun onClickUser(id: Long) {
        val userIntent = Intent(requireContext(), UActivity::class.java)
        userIntent.putExtra(Params.USER_ID, id.toInt())
        startActivity(userIntent)
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
