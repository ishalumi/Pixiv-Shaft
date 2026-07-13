package ceui.pixiv.ui.comments

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.transition.ChangeBounds
import android.transition.Slide
import android.transition.TransitionManager
import android.transition.TransitionSet
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.viewModels
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import androidx.viewpager2.widget.ViewPager2
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
import ceui.loxia.launchSuspend
import ceui.pixiv.chat.base.panel.BottomPanelCoordinator
import ceui.pixiv.chat.base.panel.PanelHost
import ceui.pixiv.chat.base.panel.attachBottomPanel
import ceui.pixiv.feeds.FeedFragment
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.FeedRenderer
import ceui.pixiv.feeds.FeedUiState
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

    /** softInputMode 现场备份,见 [onResume]。 */
    private var previousSoftInputMode: Int = INVALID_SOFT_INPUT_MODE

    /** 「绘文字」/「表情贴图」面板的状态机,[setUpEmojiPanel] 里创建;贴纸选中即发前用它收起面板。 */
    private lateinit var panelCoordinator: BottomPanelCoordinator

    /** 刚发出、还没等到它上屏的顶层新评论 id——见 [applySendResult] / [onListCommitted]。 */
    private var pendingScrollHighlightCommentId: Long? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = FragmentCommentsFeedBinding.bind(view)

        // 标准 feeds toolbar 打法(对齐 PixivFragment.setUpToolbar(FragmentToolbarFeedBinding,…)):
        // 顶部状态栏高度一次性 padding,不用实时 insets(本页不再浮在模糊图上,无需每帧跟随)
        binding.toolbar.updatePadding(top = BarUtils.getStatusBarHeight())
        binding.toolbar.setNavigationOnClickListener { requireActivity().finish() }
        binding.toolbarTitle.text = getString(R.string.comments)

        setUpComposer(binding)
    }

    /**
     * TemplateActivity 在 manifest 里声明 `windowSoftInputMode="adjustPan"`——对大多数页面
     * 没问题,但会破坏本页「面板严格跟键盘等高、来回切换零抖动」的效果:adjustPan 平移整个
     * 窗口而不是收缩内容区,[ceui.pixiv.chat.base.panel.BottomPanelCoordinator] 依赖的
     * WindowInsetsAnimationCompat 回调在 adjustPan 下拿到的高度会失真。这里现场切到
     * adjustResize,离开页面再恢复,不影响 TemplateActivity 承载的其它页面
     * (同 DemoChatListFragment 已验证过的打法)。
     */
    override fun onResume() {
        super.onResume()
        val window = requireActivity().window
        previousSoftInputMode = window.attributes.softInputMode
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }

    override fun onPause() {
        super.onPause()
        if (previousSoftInputMode != INVALID_SOFT_INPUT_MODE) {
            requireActivity().window.setSoftInputMode(previousSoftInputMode)
            previousSoftInputMode = INVALID_SOFT_INPUT_MODE
        }
    }

    /** 「绘文字」/「表情贴图」双 tab 面板,装进 ViewPager2 支持左右滑动切页(点 tab 同步跳页)。
     * 前者 38 个 pixiv 内置文字表情,点击插入输入框光标处;后者官方常驻 40 个插画贴纸
     * ([StampCatalog]),点击直接单发一条纯贴纸评论(对齐官方 App 抓包行为:发贴纸时 comment
     * 恒为空,不经过输入框文字)。面板跟系统键盘的互斥 + 等高切换交给 [attachBottomPanel]
     * (聊天页同款 BottomPanelCoordinator),不再自己手写 insets 监听。 */
    private fun setUpEmojiPanel(binding: FragmentCommentsFeedBinding) {
        val kaomojiAdapter = CommentEmojiPickerAdapter { code ->
            val editable = binding.commentInput.text ?: return@CommentEmojiPickerAdapter
            val start = binding.commentInput.selectionStart.coerceIn(0, editable.length)
            val end = binding.commentInput.selectionEnd.coerceIn(0, editable.length)
            editable.replace(minOf(start, end), maxOf(start, end), code)
            binding.commentInput.setSelection(minOf(start, end) + code.length)
        }
        // 贴纸点击即单发,不像文字发送有 ProgressImageButton 天然挡连点——手动加一个
        // 发送中标记,网络往返期间连点同一张/不同张贴纸都直接丢弃,防止重复发出评论。
        var isSendingStamp = false
        val stampAdapter = CommentStampPickerAdapter { stamp ->
            if (isSendingStamp) return@CommentStampPickerAdapter
            isSendingStamp = true
            // 贴纸是选中即发,不是主动点发送键——发送前先收起表情/贴纸面板(这一刻软键盘本就是收起的,
            // 面板跟它互斥),不然网络往返/列表刷新期间面板会一直挡住底下「滚回顶部高亮新评论」的结果。
            panelCoordinator.dismiss()
            launchSuspend {
                try {
                    val result = composer.sendStamp(stamp.stamp_id)
                    applySendResult(result)
                } finally {
                    isSendingStamp = false
                }
            }
        }
        binding.emojiPager.adapter = CommentEmojiStampPagerAdapter(kaomojiAdapter, stampAdapter)
        var stampsLoaded = false

        fun styleTabs(stampSelected: Boolean) {
            binding.tabKaomoji.setTextColor(ContextCompat.getColor(requireContext(), if (stampSelected) R.color.v3_text_2 else R.color.v3_text_1))
            binding.tabKaomoji.setTypeface(null, if (stampSelected) Typeface.NORMAL else Typeface.BOLD)
            binding.tabStamp.setTextColor(ContextCompat.getColor(requireContext(), if (stampSelected) R.color.v3_text_1 else R.color.v3_text_2))
            binding.tabStamp.setTypeface(null, if (stampSelected) Typeface.BOLD else Typeface.NORMAL)
        }
        binding.emojiPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                val stampSelected = position == 1
                styleTabs(stampSelected)
                if (stampSelected && !stampsLoaded) {
                    stampsLoaded = true
                    launchSuspend { stampAdapter.submit(StampCatalog.get()) }
                }
            }
        })
        // ViewPager2 不会为初始页(position 0)回调 onPageSelected,显式调一次让高亮状态
        // 由代码本身定,不依赖「XML 默认值刚好等于 styleTabs(false) 的结果」这个隐性约定。
        styleTabs(false)
        binding.tabKaomoji.setOnClick { binding.emojiPager.setCurrentItem(0, true) }
        binding.tabStamp.setOnClick { binding.emojiPager.setCurrentItem(1, true) }

        panelCoordinator = attachBottomPanel(
            host = object : PanelHost {
                override val panelRoot get() = binding.root
                override val panelView get() = binding.emojiPanelContainer
                override val panelInputView get() = binding.commentInput
                override val panelContentView get() = feedBinding.feedListView
                override val panelToggleButton get() = binding.emojiToggle
                override val panelToggleIconRes get() = R.drawable.chat_ic_emoji
                override val keyboardToggleIconRes get() = R.drawable.chat_ic_keyboard
            },
        )
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
        // 顶层新评论固定插进 index 0(见 CommentsComposerViewModel.applySentComment);回复是挂进
        // 已有卡片内部,不在列表最前——只有顶层发送才需要「滚回最前 + 高亮」。mutateItems 只是同步
        // 改了 VM 里的 StateFlow,adapter 要等 [onListCommitted] 回调才真的吃到这份新数据——这里
        // 不能立刻滚,立刻滚会拿着 adapter 还没更新的旧 itemCount/内容起步,滚到错误位置。
        if (parentCommentId <= 0L) {
            pendingScrollHighlightCommentId = comment.id
        }
    }

    /** [ceui.pixiv.feeds.FeedFragment] 的 diff-提交完成回调:此时 adapter 才真正反映了
     * [applySendResult] 里 mutateItems 之后的那份 state,滚动目标位置才是准的。 */
    override fun onListCommitted(state: FeedUiState) {
        val targetId = pendingScrollHighlightCommentId ?: return
        val top = state.items.firstOrNull()
        if (top is CommentFeedItem && top.comment.id == targetId) {
            pendingScrollHighlightCommentId = null
            scrollToAndHighlightNewComment()
        }
    }

    /** submitList 的 diff 提交完成后,新插入的 0 号 ViewHolder 仍可能还没排布出来(布局是下一帧的
     * 事)——短延迟重试拿到手再高亮(同 [ceui.pixiv.ui.user.UserIllustFeedFragment] 的
     * highlightItemAt 打法);这里额外叠了平滑滚动的耗时,重试预算相应放宽,盖住一次完整的
     * smoothScroll。 */
    private fun scrollToAndHighlightNewComment() {
        if (view == null) return
        val listView = feedBinding.feedListView
        listView.smoothScrollToPosition(0)
        highlightItemAt(listView, 0, HIGHLIGHT_MAX_RETRIES)
    }

    private fun highlightItemAt(listView: RecyclerView, adapterPos: Int, triesLeft: Int) {
        if (view == null) return
        val holder = listView.findViewHolderForAdapterPosition(adapterPos)
        if (holder == null) {
            if (triesLeft > 0) {
                listView.postDelayed(
                    { highlightItemAt(listView, adapterPos, triesLeft - 1) },
                    HIGHLIGHT_RETRY_DELAY_MS,
                )
            }
            return
        }
        val target = holder.itemView
        target.animate().cancel()
        target.scaleX = 1f
        target.scaleY = 1f
        target.animate().scaleX(1.05f).scaleY(1.05f).setDuration(200L)
            .withEndAction { target.animate().scaleX(1f).scaleY(1f).setDuration(200L).start() }
            .start()
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
        /** Sentinel — softInputMode 是打包 int,-1 不会是合法组合(同 DemoChatListFragment)。 */
        private const val INVALID_SOFT_INPUT_MODE = -1

        /** 新评论高亮重试预算:100ms × 30 ≈ 3s,盖住一次从列表底部到顶部的完整 smoothScroll。 */
        private const val HIGHLIGHT_MAX_RETRIES = 30
        private const val HIGHLIGHT_RETRY_DELAY_MS = 100L

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
