package ceui.pixiv.chat.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import ceui.lisa.R
import ceui.lisa.databinding.ChatFragmentDemoListBinding
import ceui.pixiv.chat.api.HttpChatHistorySource
import ceui.pixiv.chat.api.ShaftChatGateway
import ceui.pixiv.chat.base.PagingFooterAdapter
import ceui.pixiv.chat.base.launchSuspend
import ceui.pixiv.chat.base.panel.PanelHost
import ceui.pixiv.chat.base.panel.attachBottomPanel
import ceui.pixiv.chat.base.setupToolbar
import ceui.pixiv.chat.base.viewBinding
import ceui.pixiv.chat.base.viewModels
import ceui.pixiv.chat.data.ChatDatabase
import ceui.pixiv.chat.data.ChatMessageEntity
import ceui.pixiv.chat.data.RoomChatMessageStore
import ceui.pixiv.chat.vm.ChatListViewModel
import ceui.pixiv.session.SessionManager
import ceui.pixiv.websocket.WebSocketState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Chat screen wired to shaft-api-v2's uid-routing chat WebSocket.
 *
 * The WS itself is app-scoped — owned by [ShaftChatGateway] on top of
 * [ceui.pixiv.websocket.WebSocketManager]. This fragment opens a per-room
 * view onto the existing connection:
 *
 *  - **Global room** (no [ARG_PEER_UID] argument)
 *  - **1v1 room** with peer uid passed in via [ARG_PEER_UID] — room id
 *    derived locally via `ChatThreadId.oneOnOneThreadId(selfUid, peerUid)`
 *
 *  - history: [HttpChatHistorySource] → `GET /api/v1/chat/history?room=...`
 *  - live:    [ShaftChatGateway.chatStream] (filtered by computed room)
 *  - send:    optimistic local write → [ShaftChatGateway.send] → WS echo
 *             flips local row Sending → Delivered (doc §4.3)
 *
 * `reverseLayout = true` puts position 0 at the bottom of the screen
 * (newest message). [PagingFooterAdapter] sits at the END of the
 * [ConcatAdapter], which maps to the TOP of the screen with reverse layout
 * — where "loading older…" belongs.
 */
class DemoChatListFragment : Fragment(R.layout.chat_fragment_demo_list) {

    /** `null` → global room. Long > 0 → peer uid for 1v1. */
    private val peerUidArg: Long? by lazy {
        val v = arguments?.getLong(ARG_PEER_UID, 0L) ?: 0L
        if (v > 0L) v else null
    }

    private val viewModel: ChatListViewModel by viewModels {
        val appCtx = requireContext().applicationContext
        ChatListViewModel(
            selfUid = SessionManager.loggedInUid,
            toUid = peerUidArg,
            store = RoomChatMessageStore(
                ChatDatabase.getInstance(appCtx).chatMessageDao()
            ),
            historySource = HttpChatHistorySource(),
            stream = ShaftChatGateway.chatStream,
            sender = ShaftChatGateway::send,
        )
    }

    private val binding by viewBinding(ChatFragmentDemoListBinding::bind)

    private var chatAdapter: ChatMessageAdapter? = null
    private var scrollToBottomOnNextUpdate = false

    /** Send button gating (doc §12): WS Connected + has text + not rate-limited. */
    private var wsConnected = false
    private var rateLimitCoolDown = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Title: 1v1 shows peer uid until we wire profile lookup; global shows the canonical drawer entry.
        val title = if (peerUidArg != null) "1v1 · uid=$peerUidArg"
                    else getString(R.string.chat_drawer_entry)
        setupToolbar(title = title, showBack = true)

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
        // Adapter compares msg.uid against selfUid for right-vs-left bubble.
        // selfUid = pixiv SessionManager.loggedInUid — same identity the WS
        // handshake authenticates with, so echoes line up.
        val chatAdapter = ChatMessageAdapter(
            selfUid = SessionManager.loggedInUid,
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
            launch { observeConnection() }
            launch { observeServerErrors() }
            launch { observeReplacedByOtherDevice() }
            launch { observeFatalAuth() }
        }
    }

    // ── Message actions ─────────────────────────────────────────────────

    private fun showMessageActions(msg: ChatMessageEntity) {
        MessageActionsSheet.newInstance(msg.localKey, msg.text)
            .show(childFragmentManager, MessageActionsSheet.TAG)
    }

    private fun setupMessageActionResult() {
        childFragmentManager.setFragmentResultListener(
            MessageActionsSheet.REQUEST_KEY, viewLifecycleOwner
        ) { _, bundle ->
            val action = bundle.getString(MessageActionsSheet.RESULT_ACTION) ?: return@setFragmentResultListener
            val localKey = bundle.getString(MessageActionsSheet.RESULT_LOCAL_KEY) ?: return@setFragmentResultListener
            handleMessageAction(action, localKey)
        }
    }

    private fun handleMessageAction(action: String, localKey: String) {
        when (action) {
            MessageActionsSheet.ACTION_COPY -> copyMessage(localKey)
            MessageActionsSheet.ACTION_REPLY -> {
                Toast.makeText(requireContext(), "回复（TODO）", Toast.LENGTH_SHORT).show()
            }
            MessageActionsSheet.ACTION_FORWARD -> {
                Toast.makeText(requireContext(), "转发（TODO）", Toast.LENGTH_SHORT).show()
            }
            MessageActionsSheet.ACTION_DELETE -> confirmDeleteMessage(localKey)
        }
    }

    private fun copyMessage(localKey: String) {
        val text = viewModel.messages.value
            .find { it.localKey == localKey }
            ?.text ?: return
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("message", text))
        Toast.makeText(requireContext(), "已复制", Toast.LENGTH_SHORT).show()
    }

    private fun confirmDeleteMessage(localKey: String) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("删除消息")
            .setMessage("确定要删除这条消息吗？")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ -> deleteMessage(localKey) }
            .show()
    }

    private fun deleteMessage(localKey: String) {
        val dao = ChatDatabase.getInstance(requireContext()).chatMessageDao()
        viewLifecycleOwner.lifecycleScope.launch {
            dao.deleteByLocalKey(localKey)
            Toast.makeText(requireContext(), "已删除", Toast.LENGTH_SHORT).show()
        }
    }

    // ── Input bar ───────────────────────────────────────────────────────

    private fun setupInput() {
        binding.etInput.doAfterTextChanged { refreshSendEnabled() }
        binding.btnSend.setOnClickListener { sendMessage() }
        refreshSendEnabled()
    }

    private fun refreshSendEnabled() {
        val hasText = !binding.etInput.text.isNullOrBlank()
        binding.btnSend.isEnabled = hasText && wsConnected && !rateLimitCoolDown
    }

    /**
     * VM owns the full optimistic-send lifecycle (doc §4.3):
     * generates `client_msg_id`, writes the local row `state=Sending`,
     * dispatches via gateway, and listens for the WS echo to flip
     * `state=Delivered`. UI here just collects the boolean accept signal
     * for the immediate Toast + input clear.
     */
    private fun sendMessage() {
        val text = binding.etInput.text?.toString()?.trim() ?: return
        if (text.isEmpty()) return
        viewLifecycleOwner.lifecycleScope.launch {
            val accepted = viewModel.sendText(text)
            if (!accepted) {
                Toast.makeText(requireContext(), "发送失败,请稍后重试", Toast.LENGTH_SHORT).show()
                return@launch
            }
            binding.etInput.text?.clear()
            scrollToBottomOnNextUpdate = true
        }
    }

    // ── Observers ───────────────────────────────────────────────────────

    private suspend fun observePageState() {
        viewModel.pageState.collect { state ->
            binding.stateLayout.setState(state)
        }
    }

    private suspend fun observePagingFooter(footerAdapter: PagingFooterAdapter) {
        viewModel.pagingState.collect { state ->
            footerAdapter.setPagingState(state)
        }
    }

    private suspend fun observeConnection() {
        ShaftChatGateway.state.collect { state ->
            wsConnected = state is WebSocketState.Connected
            refreshSendEnabled()
        }
    }

    /**
     * Per doc §3.2 / §12: server `err` frames with `client_msg_id` anchor
     * to a specific local row (mark Failed); without cmid, the VM
     * falls back to "most recent Sending". Additionally, `rate_limited`
     * triggers the 1-second input cool-down.
     *
     * `collectLatest` cancels the prior body on each new err so a back-to-
     * back rate_limited resets the cool-down timer rather than stacking.
     */
    private suspend fun observeServerErrors() {
        ShaftChatGateway.errorFrames.collectLatest { err ->
            // Always: anchor the failure to the local row so UI shows Failed
            // instead of stuck-Sending. cmid==null falls back to "most recent
            // Sending" inside the VM.
            viewModel.markFailedByClientMsgId(err.clientMsgId)

            if (err.code == "rate_limited") {
                rateLimitCoolDown = true
                refreshSendEnabled()
                Toast.makeText(requireContext(), "发送太频繁,请稍候", Toast.LENGTH_SHORT).show()
                delay(1_000)
                rateLimitCoolDown = false
                refreshSendEnabled()
            } else {
                Toast.makeText(requireContext(), "发送失败: ${err.code}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Doc §1.1 "同 uid 顶号": server sent close(1008, "replaced") because
     * the same uid logged in on another device and pushed the max-5
     * per-uid connection cap over. Show a dedicated toast — do NOT
     * trigger reconnect (`DEFAULT_SHOULD_RECONNECT` already excludes 1008
     * from FATAL_CLOSE_CODES; user must explicitly come back).
     */
    private suspend fun observeReplacedByOtherDevice() {
        ShaftChatGateway.replacedByOtherDevice.collect {
            Toast.makeText(
                requireContext(),
                "账号在其它设备登录,聊天已断开",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    /**
     * Doc §2.2 / §7.3: 401 on the handshake (`bad_sig` / `bad_ts` /
     * `ts_skew` / `bad_uid`) is **fatal** — retrying with the same
     * credentials won't help. Surface a concrete user-actionable message
     * so they aren't left wondering why the input is greyed out forever.
     */
    private suspend fun observeFatalAuth() {
        ShaftChatGateway.fatalAuth.collect {
            Toast.makeText(
                requireContext(),
                "聊天认证失败 — 请检查系统时间是否正确,或重新登录",
                Toast.LENGTH_LONG,
            ).show()
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

    override fun onDestroyView() {
        view?.findViewById<RecyclerView>(R.id.recycler_view)?.adapter = null
        chatAdapter = null
        super.onDestroyView()
    }

    companion object {
        private const val PREFETCH_THRESHOLD = 5

        /** Fragment-arg key: peer pixiv uid for 1v1; absent / 0 → global room. */
        const val ARG_PEER_UID = "peerUid"

        /** Builder for the global chat fragment (default). */
        fun newInstanceGlobal(): DemoChatListFragment = DemoChatListFragment()

        /** Builder for a 1v1 chat fragment with the given peer pixiv uid. */
        fun newInstanceForPeer(peerUid: Long): DemoChatListFragment =
            DemoChatListFragment().apply { arguments = bundleOf(ARG_PEER_UID to peerUid) }
    }
}
