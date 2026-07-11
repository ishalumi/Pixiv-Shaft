package ceui.pixiv.chat.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
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
import ceui.pixiv.chat.api.ShaftChatGateway
import ceui.pixiv.chat.base.viewBinding
import ceui.pixiv.chat.base.viewModels
import ceui.pixiv.chat.vm.ChatRoomListViewModel
import ceui.pixiv.websocket.IncomingMessage
import com.blankj.utilcode.util.BarUtils
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Conversation list view (chat home). The hard part — paging, avatar
 * resolution, WS-driven optimistic updates, unread bookkeeping — lives in
 * [ChatRoomListViewModel]. This fragment is purely the binding layer:
 * inflate, observe, render, dispatch user events.
 *
 * V3 chrome: a large-title header on `v3_bg` (back + 聊天室 + conversation
 * count) instead of the classic brand toolbar, and a divider-less list whose
 * one public room is a theme-gradient hero card (see [ChatRoomListAdapter]).
 */
class ChatRoomListFragment : Fragment(R.layout.chat_fragment_room_list) {

    private val binding by viewBinding(ChatFragmentRoomListBinding::bind)

    private val viewModel: ChatRoomListViewModel by viewModels {
        ChatRoomListViewModel(repo = ChatConversationsRepository())
    }

    private val adapter by lazy { ChatRoomListAdapter(onClick = ::openRoom) }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Header chrome. BaseActivity is edge-to-edge, so the header owns its
        // own status-bar inset (the `head` spacer) and the list owns its own
        // navigation-bar inset (bottom padding). fitsSystemWindows can't be
        // used here — it would inhale both insets at once (see NavExt notes).
        binding.head.updateLayoutParams { height = BarUtils.getStatusBarHeight() }
        binding.tvTitle.text = getString(R.string.chat_drawer_entry)
        binding.btnBack.setOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }

        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter
        binding.recyclerView.addOnScrollListener(loadMoreOnScroll())

        val basePaddingBottom = binding.recyclerView.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.recyclerView) { v, insets ->
            val bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            v.updatePadding(bottom = basePaddingBottom + bottom)
            insets
        }
        ViewCompat.requestApplyInsets(binding.recyclerView)

        // Render VM state. localizeTitle swaps the "global" sentinel for the
        // locale-aware "公屏闲聊" string — done here (not in VM) because
        // string resources need a Context.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { s ->
                    adapter.submitList(s.items.map(::localizeTitle))
                    if (s.items.isEmpty()) {
                        binding.tvSubtitle.visibility = View.GONE
                    } else {
                        binding.tvSubtitle.visibility = View.VISIBLE
                        binding.tvSubtitle.text =
                            getString(R.string.chat_room_count, s.items.size)
                    }
                }
            }
        }

        // WS-driven optimistic updates while the list is visible. Decode
        // here (not in the VM) so the VM stays decoupled from the wire
        // format — easier to unit-test by feeding it ChatFrame.Msg directly.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                ShaftChatGateway.incoming
                    .filterIsInstance<IncomingMessage.Text>()
                    .map { ChatFrameDecoder.decode(it.text) }
                    .filterIsInstance<ChatFrame.Msg>()
                    .collect(viewModel::onWsMsg)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Authoritative refresh on every resume — picks up unread changes
        // from /read calls made by other devices, new DMs, expired entries.
        viewModel.refresh()
    }

    private fun loadMoreOnScroll() = object : RecyclerView.OnScrollListener() {
        override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
            if (dy <= 0) return
            val lm = rv.layoutManager as? LinearLayoutManager ?: return
            val last = lm.findLastVisibleItemPosition()
            val total = lm.itemCount
            // Trigger ~5 rows before the bottom so the user doesn't see the
            // scroll deck.
            if (last >= total - 5) viewModel.loadMore()
        }
    }

    /**
     * Swap the VM's "__global__" sentinel for the locale-aware title. Kept
     * in the fragment because string resource lookup needs a Context.
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
        viewModel.onRoomTapped(entry.room)
    }
}
