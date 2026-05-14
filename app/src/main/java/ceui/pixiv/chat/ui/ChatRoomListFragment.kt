package ceui.pixiv.chat.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ceui.lisa.R
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.databinding.ChatFragmentRoomListBinding
import ceui.pixiv.chat.api.ChatConversationsRepository
import ceui.pixiv.chat.api.ChatFrame
import ceui.pixiv.chat.api.ChatFrameDecoder
import ceui.pixiv.chat.api.ChatThreadId
import ceui.pixiv.chat.api.ShaftChatGateway
import ceui.pixiv.chat.base.setupToolbar
import ceui.pixiv.chat.base.viewBinding
import ceui.pixiv.session.SessionManager
import ceui.pixiv.websocket.IncomingMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Conversation list (Figma 3201:18523 adapted). Data flow:
 *
 *   onResume / pull-to-refresh
 *      └─→ GET /api/v1/chat/conversations (page 1)
 *           └─→ replace [state] items
 *
 *   scroll-near-bottom
 *      └─→ GET /api/v1/chat/conversations?cursor=<next>
 *           └─→ append to [state] items
 *
 *   WS msg frame arrives for a known room
 *      └─→ optimistic: update preview + bump unread_count locally
 *
 *   WS msg arrives for an unknown room (a stranger DM'd us)
 *      └─→ trigger a debounced refresh on next resume
 *
 * Local-only stuff (peer reverse-XOR, DAO scans) is gone — server is the
 * authoritative source of "what conversations does this user have".
 */
class ChatRoomListFragment : Fragment(R.layout.chat_fragment_room_list) {

    private val binding by viewBinding(ChatFragmentRoomListBinding::bind)
    private val repo = ChatConversationsRepository()

    private val adapter by lazy { ChatRoomListAdapter(onClick = ::openRoom) }

    private data class UiState(
        val items: List<ChatRoomEntry> = emptyList(),
        val nextCursor: String? = null,
        val loadingMore: Boolean = false,
        val hasUnknownRoomSinceLastRefresh: Boolean = false,
    )

    private val state = MutableStateFlow(UiState())
    private var loadJob: Job? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupToolbar(getString(R.string.chat_drawer_entry), showBack = true)

        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter
        binding.recyclerView.addOnScrollListener(loadMoreOnScroll())

        // Render whenever the state changes.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                state.collect { s -> adapter.submitList(s.items) }
            }
        }

        // WS-driven optimistic updates while the list is visible. Reuses the
        // already-open gateway socket; no extra connection.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                ShaftChatGateway.incoming
                    .filterIsInstance<IncomingMessage.Text>()
                    .map { ChatFrameDecoder.decode(it.text) }
                    .filterIsInstance<ChatFrame.Msg>()
                    .collect(::onWsMsg)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Authoritative refresh on every resume — picks up unread changes
        // from /read calls made by other devices, new DMs, expired entries.
        refresh()
    }

    private fun refresh() {
        val uid = SessionManager.loggedInUid
        if (uid <= 0L) {
            Timber.tag(TAG).w("refresh: not logged in (uid=%d) — skip", uid)
            state.update { UiState() }
            return
        }
        loadJob?.cancel()
        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val page = repo.load(uid = uid, cursor = null, limit = 50)
                state.update {
                    UiState(
                        items = page.items.map(::localizeTitle),
                        nextCursor = page.nextCursor,
                        loadingMore = false,
                        hasUnknownRoomSinceLastRefresh = false,
                    )
                }
            } catch (t: Throwable) {
                Timber.tag(TAG).w(t, "refresh failed")
            }
        }
    }

    private fun loadMore() {
        val current = state.value
        if (current.loadingMore || current.nextCursor == null) return
        val uid = SessionManager.loggedInUid
        if (uid <= 0L) return
        state.update { it.copy(loadingMore = true) }
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val page = repo.load(uid = uid, cursor = current.nextCursor, limit = 50)
                state.update {
                    // Server contract: page 2+ never includes 'global'. Plain
                    // append; the adapter's diffCallback keys on room so any
                    // accidental dup would be collapsed.
                    it.copy(
                        items = it.items + page.items.map(::localizeTitle),
                        nextCursor = page.nextCursor,
                        loadingMore = false,
                    )
                }
            } catch (t: Throwable) {
                Timber.tag(TAG).w(t, "loadMore failed")
                state.update { it.copy(loadingMore = false) }
            }
        }
    }

    private fun loadMoreOnScroll() = object : RecyclerView.OnScrollListener() {
        override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
            if (dy <= 0) return
            val lm = rv.layoutManager as? LinearLayoutManager ?: return
            val last = lm.findLastVisibleItemPosition()
            val total = lm.itemCount
            // Trigger ~5 rows before the bottom so the user doesn't see the
            // scroll deck.
            if (last >= total - 5) loadMore()
        }
    }

    private fun onWsMsg(frame: ChatFrame.Msg) {
        val current = state.value
        val idx = current.items.indexOfFirst { it.room == frame.room }
        if (idx < 0) {
            // Stranger room we don't have locally yet. We could insert a
            // synthetic row, but server's `peer_display_name` resolution +
            // proper `last_message.id` (we don't get an `id` for WS pushes,
            // only for /history rows) are best done by re-fetching. Flag the
            // state and let the next resume / refresh pull a fresh page.
            state.update { it.copy(hasUnknownRoomSinceLastRefresh = true) }
            return
        }
        val selfUid = SessionManager.loggedInUid
        val existing = current.items[idx]
        val bumpUnread = existing.kind == ChatRoomEntry.Kind.ONE_ON_ONE &&
            frame.uid != selfUid
        val updated = existing.copy(
            previewText = frame.text.orEmpty(),
            previewSenderUid = frame.uid,
            previewSenderDisplayName = frame.displayName,
            lastTs = frame.ts,
            unreadCount = if (bumpUnread) existing.unreadCount + 1 else existing.unreadCount,
        )
        // Move the bumped row to the top (after Global which is index 0 if present).
        val rest = current.items.toMutableList().apply { removeAt(idx) }
        val insertAt = if (rest.firstOrNull()?.kind == ChatRoomEntry.Kind.GLOBAL) 1 else 0
        rest.add(insertAt, updated)
        state.update { it.copy(items = rest) }
    }

    /**
     * Server returns the title as a sentinel for the global room ("global"
     * has no human display name) — swap for the locale-aware string here so
     * the adapter stays UI-dumb.
     */
    private fun localizeTitle(entry: ChatRoomEntry): ChatRoomEntry =
        if (entry.title == ChatConversationsRepository.CONVENTION_GLOBAL_TITLE) {
            entry.copy(title = getString(R.string.chat_room_global_title))
        } else {
            entry
        }

    private fun openRoom(entry: ChatRoomEntry) {
        val intent = Intent(requireContext(), TemplateActivity::class.java).apply {
            when (entry.kind) {
                ChatRoomEntry.Kind.GLOBAL -> {
                    putExtra(TemplateActivity.EXTRA_FRAGMENT, "聊天-全员公屏")
                }
                ChatRoomEntry.Kind.ONE_ON_ONE -> {
                    putExtra(TemplateActivity.EXTRA_FRAGMENT, "聊天室")
                    entry.peerUid?.takeIf { it > 0L }?.let {
                        putExtra(TemplateActivity.EXTRA_CHAT_PEER_UID, it)
                    }
                }
            }
        }
        startActivity(intent)
        // Optimistic: clear the local unread for this row so the badge
        // doesn't linger while /read is in flight (next refresh will be
        // authoritative).
        val idx = state.value.items.indexOfFirst { it.room == entry.room }
        if (idx >= 0 && state.value.items[idx].unreadCount > 0) {
            val updated = state.value.items[idx].copy(unreadCount = 0)
            state.update { s ->
                s.copy(items = s.items.toMutableList().apply { set(idx, updated) })
            }
        }
    }

    companion object {
        private const val TAG = "Chat-RoomList"
    }
}
