package ceui.pixiv.chat.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import ceui.lisa.R
import ceui.lisa.databinding.ChatFragmentDemoListBinding
import ceui.pixiv.events.EventReporter
import ceui.pixiv.chat.api.HttpChatHistorySource
import ceui.pixiv.chat.api.ShaftChatWsClient
import ceui.pixiv.chat.api.WsChatMessageStream
import ceui.pixiv.chat.base.PagingFooterAdapter
import ceui.pixiv.chat.base.PagingState
import ceui.pixiv.chat.base.launchSuspend
import ceui.pixiv.chat.base.panel.PanelHost
import ceui.pixiv.chat.base.panel.attachBottomPanel
import ceui.pixiv.chat.base.setupToolbar
import ceui.pixiv.chat.base.viewBinding
import ceui.pixiv.chat.base.viewModels
import ceui.pixiv.chat.data.ChatDatabase
import ceui.pixiv.chat.data.ChatMessageEntity
import ceui.pixiv.chat.data.RoomChatMessageStore
import ceui.pixiv.chat.vm.ShaftChatListViewModel
import kotlinx.coroutines.launch

/**
 * Chat screen wired to the **real** shaft-api-v2 chat WebSocket. The
 * local-first pipeline (Room as single source of truth, with HTTP backfill
 * + WS push both feeding Room) is preserved from the original Peanut demo
 * — only the sources are swapped:
 *
 *  - history: [HttpChatHistorySource] → `GET /api/v1/chat/history`
 *  - live:    [WsChatMessageStream]   → WS `msg` frames
 *  - send:    [ShaftChatListViewModel.sendText] → WS `msg` frame,
 *             render on echo (no optimistic Room insert)
 *
 * ## RecyclerView orientation
 *
 * `reverseLayout = true` puts position 0 at the **bottom** of the
 * screen (newest message). Scrolling up reveals older messages.
 * [PagingFooterAdapter] sits at the END of the [ConcatAdapter], which
 * maps to the **top** of the screen with reverse layout — exactly
 * where "loading older…" should appear.
 */
class DemoChatListFragment : Fragment(R.layout.chat_fragment_demo_list) {

    private val viewModel: ShaftChatListViewModel by viewModels {
        val appCtx = requireContext().applicationContext
        val wsClient = ShaftChatWsClient.create(appCtx)
        ShaftChatListViewModel(
            wsClient = wsClient,
            wsStream = WsChatMessageStream(wsClient),
            historySource = HttpChatHistorySource(),
            store = RoomChatMessageStore(
                ChatDatabase.getInstance(appCtx).chatMessageDao()
            ),
        )
    }

    private val binding by viewBinding(ChatFragmentDemoListBinding::bind)

    private var chatAdapter: ChatMessageAdapter? = null
    private var scrollToBottomOnNextUpdate = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupToolbar(title = "Chat Demo")

        // ── Bottom panel (emoji ↔ keyboard) ──────────────────────────
        attachBottomPanel(
            host = object : PanelHost {
                override val panelRoot get() = binding.root
                override val panelView get() = binding.emojiPanel
                override val panelInputView get() = binding.etInput
                override val panelContentView get() = binding.recyclerView
                override val panelToggleButton get() = binding.btnEmoji
                override val panelToggleIconRes get() = R.drawable.chat_ic_emoji
                override val keyboardToggleIconRes get() = R.drawable.chat_ic_keyboard
                override fun onAnchorContent() {
                    binding.recyclerView.scrollToPosition(0)
                }
            },
        )
        binding.emojiPanel.onEmojiClick = { emoji ->
            binding.etInput.text?.insert(binding.etInput.selectionStart, emoji)
        }

        // ── Input ────────────────────────────────────────────────────
        setupInput()

        // ── RecyclerView ─────────────────────────────────────────────
        // Adapter needs to know which messages are "mine" so it right-aligns
        // them. The chat protocol identifies us by `client_id` (hex); we use
        // the same `client_id → uid` mapping HttpChatHistorySource +
        // WsChatMessageStream use, so the round-trip stays consistent.
        val selfClientId = EventReporter.currentClientId()
        val selfUid = if (selfClientId.isNotEmpty())
            HttpChatHistorySource.clientIdToUid(selfClientId) else 0L
        val chatAdapter = ChatMessageAdapter(
            selfUid = selfUid,
            onLongClick = { msg -> showMessageActions(msg) },
        ).also { this.chatAdapter = it }
        val footerAdapter = PagingFooterAdapter().apply {
            onRetry = { viewModel.retryPaging() }
        }

        val layoutManager = LinearLayoutManager(requireContext()).apply {
            reverseLayout = true
            stackFromEnd = true
        }

        binding.recyclerView.apply {
            this.layoutManager = layoutManager
            adapter = ConcatAdapter(chatAdapter, footerAdapter)
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                    if (dy != 0) paginateIfNeeded()
                }
                override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                    if (newState != RecyclerView.SCROLL_STATE_IDLE) return
                    if (layoutManager.findFirstVisibleItemPosition() <= 1) {
                        viewModel.trimWindow()
                    } else {
                        paginateIfNeeded()
                    }
                }
            })
        }

        binding.stateLayout.setOnRetryClickListener { viewModel.retry() }
        setupMessageActionResult()

        launchSuspend {
            launch { observePageState() }
            launch { observePagingFooter(footerAdapter) }
            launch { observeMessages(chatAdapter, footerAdapter, layoutManager) }
        }
    }

    // ── Message actions ─────────────────────────────────────────────────

    private fun showMessageActions(msg: ChatMessageEntity) {
        MessageActionsSheet.newInstance(msg.messageId, msg.content)
            .show(childFragmentManager, MessageActionsSheet.TAG)
    }

    private fun setupMessageActionResult() {
        childFragmentManager.setFragmentResultListener(
            MessageActionsSheet.REQUEST_KEY, viewLifecycleOwner
        ) { _, bundle ->
            val action = bundle.getString(MessageActionsSheet.RESULT_ACTION) ?: return@setFragmentResultListener
            val messageId = bundle.getLong(MessageActionsSheet.RESULT_MESSAGE_ID)
            handleMessageAction(action, messageId)
        }
    }

    private fun handleMessageAction(action: String, messageId: Long) {
        when (action) {
            MessageActionsSheet.ACTION_COPY -> copyMessage(messageId)
            MessageActionsSheet.ACTION_REPLY -> {
                Toast.makeText(requireContext(), "回复（TODO）", Toast.LENGTH_SHORT).show()
            }
            MessageActionsSheet.ACTION_FORWARD -> {
                Toast.makeText(requireContext(), "转发（TODO）", Toast.LENGTH_SHORT).show()
            }
            MessageActionsSheet.ACTION_DELETE -> confirmDeleteMessage(messageId)
        }
    }

    private fun copyMessage(messageId: Long) {
        val text = viewModel.messages.value
            .find { it.messageId == messageId }
            ?.content ?: return
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("message", text))
        Toast.makeText(requireContext(), "已复制", Toast.LENGTH_SHORT).show()
    }

    private fun confirmDeleteMessage(messageId: Long) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("删除消息")
            .setMessage("确定要删除这条消息吗？")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ -> deleteMessage(messageId) }
            .show()
    }

    private fun deleteMessage(messageId: Long) {
        val dao = ChatDatabase.getInstance(requireContext()).chatMessageDao()
        viewLifecycleOwner.lifecycleScope.launch {
            dao.deleteById(messageId)
            Toast.makeText(requireContext(), "已删除", Toast.LENGTH_SHORT).show()
        }
    }

    // ── Input bar ───────────────────────────────────────────────────────

    private fun setupInput() {
        binding.etInput.doAfterTextChanged { text ->
            binding.btnSend.isEnabled = !text.isNullOrBlank()
        }
        binding.btnSend.setOnClickListener { sendMessage() }
    }

    private fun sendMessage() {
        val text = binding.etInput.text?.toString()?.trim() ?: return
        if (text.isEmpty()) return

        // Server echoes the message back over WS — that echo is what renders.
        // No local Room insert here; rendering on the echo prevents the
        // "shown locally but never landed on the server" failure mode.
        val accepted = viewModel.sendText(text)
        if (!accepted) {
            Toast.makeText(requireContext(), "发送失败,请稍后重试", Toast.LENGTH_SHORT).show()
            return
        }
        binding.etInput.text?.clear()
        scrollToBottomOnNextUpdate = true
    }

    // ── Observers ───────────────────────────────────────────────────────

    private suspend fun observePageState() {
        viewModel.pageState.collect { state ->
            binding.stateLayout.setState(state)
        }
    }

    private suspend fun observePagingFooter(footerAdapter: PagingFooterAdapter) {
        viewModel.pagingState.collect { state ->
            if (state is PagingState.LoadingMore || state is PagingState.Error) {
                footerAdapter.setPagingState(state)
            }
        }
    }

    private suspend fun observeMessages(
        chatAdapter: ChatMessageAdapter,
        footerAdapter: PagingFooterAdapter,
        layoutManager: LinearLayoutManager,
    ) {
        viewModel.messages.collect { messages ->
            val wasAtBottom = layoutManager.findFirstVisibleItemPosition() <= 1

            chatAdapter.submitList(messages) {
                footerAdapter.setPagingState(viewModel.pagingState.value)

                if ((wasAtBottom || scrollToBottomOnNextUpdate) && messages.isNotEmpty()) {
                    binding.recyclerView.scrollToPosition(0)
                    scrollToBottomOnNextUpdate = false
                }

                binding.recyclerView.post { paginateIfNeeded() }
            }
        }
    }

    private fun paginateIfNeeded() {
        val lm = binding.recyclerView.layoutManager as? LinearLayoutManager ?: return
        val total = chatAdapter?.itemCount ?: return
        if (total == 0) return
        val lastVisible = lm.findLastVisibleItemPosition()
        if (lastVisible >= 0 && total - 1 - lastVisible <= PREFETCH_THRESHOLD) {
            viewModel.loadOlder()
        }
    }

    companion object {
        private const val PREFETCH_THRESHOLD = 5
    }

    override fun onDestroyView() {
        view?.findViewById<RecyclerView>(R.id.recycler_view)?.adapter = null
        chatAdapter = null
        super.onDestroyView()
    }
}
