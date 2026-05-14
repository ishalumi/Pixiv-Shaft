package ceui.pixiv.chat.ui

import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import ceui.lisa.R
import ceui.lisa.databinding.ChatItemRoomBinding
import ceui.lisa.utils.GlideUrlChild
import ceui.pixiv.chat.base.BaseListAdapter
import com.bumptech.glide.Glide

/**
 * Renders [ChatRoomEntry] rows in the chat conversation list (Figma 3201:18523
 * adapted to AppCompat host: chat module's existing Material3 theme overlay
 * is applied by the fragment layout so M3 attrs work safely).
 *
 * Two row variants flow through the same VH because they share the same
 * layout — the difference is purely in how display name is derived
 * (Global vs peer uid). Putting that branch inside [bind] keeps the
 * RecyclerView's view-type set trivial.
 */
class ChatRoomListAdapter(
    private val onClick: (ChatRoomEntry) -> Unit,
) : BaseListAdapter<ChatRoomEntry, ChatRoomListAdapter.VH>(diffCallback(keySelector = { it.room })) {

    override fun onCreateDataViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ChatItemRoomBinding.inflate(
            LayoutInflater.from(parent.context), parent, false,
        )
        return VH(binding)
    }

    override fun onBindDataViewHolder(holder: VH, item: ChatRoomEntry) {
        holder.bind(item, onClick)
    }

    class VH(private val binding: ChatItemRoomBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: ChatRoomEntry, onClick: (ChatRoomEntry) -> Unit) {
            // Avatar: peer's pixiv image for 1v1 once the fragment has
            // looked it up; otherwise the chat placeholder. Loading goes
            // through GlideUrlChild + the .pximg.net referer headers Pixiv
            // requires, so it works without a separate proxy.
            val url = item.avatarUrl
            if (url.isNullOrBlank()) {
                Glide.with(binding.ivAvatar).clear(binding.ivAvatar)
                binding.ivAvatar.setImageResource(R.drawable.chat_avatar_placeholder)
            } else {
                Glide.with(binding.ivAvatar)
                    .load(GlideUrlChild(url))
                    .placeholder(R.drawable.chat_avatar_placeholder)
                    .into(binding.ivAvatar)
            }

            binding.tvName.text = item.title
            binding.tvPreview.text = buildPreview(item).ifBlank { "—" }
            binding.tvTime.text = if (item.lastTs > 0L) formatRelative(item.lastTs) else ""
            // Unread badge: hidden when 0; shows 1-2 digit count; "99+" for
            // anything 100+ so the pill stays narrow.
            if (item.unreadCount > 0) {
                binding.tvUnread.visibility = android.view.View.VISIBLE
                binding.tvUnread.text =
                    if (item.unreadCount > 99) "99+" else item.unreadCount.toString()
            } else {
                binding.tvUnread.visibility = android.view.View.GONE
            }
            binding.root.setOnClickListener { onClick(item) }
        }

        /**
         * Show "<sender>: <text>" for global rows where the sender isn't
         * the current user, plain text for DMs (the row title already
         * identifies the peer, so prefixing with their name on every line
         * is just noise). "你: " prefix for own messages on either kind so
         * the user can tell at a glance whether the last line was inbound
         * or outbound.
         */
        private fun buildPreview(item: ChatRoomEntry): String {
            val body = item.previewText
            if (body.isBlank()) return ""
            val ctx = binding.root.context
            val selfUid = ceui.pixiv.session.SessionManager.loggedInUid
            return when {
                item.previewSenderUid != null && item.previewSenderUid == selfUid ->
                    "${ctx.getString(R.string.chat_preview_you_prefix)} $body"
                item.kind == ChatRoomEntry.Kind.GLOBAL &&
                    !item.previewSenderDisplayName.isNullOrBlank() ->
                    "${item.previewSenderDisplayName}: $body"
                else -> body
            }
        }

        private fun formatRelative(ts: Long): CharSequence =
            DateUtils.getRelativeTimeSpanString(
                ts, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS,
                DateUtils.FORMAT_ABBREV_RELATIVE,
            )
    }
}

/**
 * Display-ready row. Source-agnostic — populated either from the API
 * `ConversationItem` projection (production) or from local-DB previews
 * (offline fallback / pre-API smoke).
 */
data class ChatRoomEntry(
    val room: String,
    val kind: Kind,
    val title: String,
    val previewText: String,
    /** Last message's sender uid — null when the room has no message yet. */
    val previewSenderUid: Long? = null,
    /** Last message's display_name (server-resolved). Used for "X: 内容" prefix. */
    val previewSenderDisplayName: String? = null,
    /** Server autoincrement id of the last message. Needed to POST /read. */
    val lastMessageId: Long? = null,
    val lastTs: Long,
    /** Only present for 1v1 rooms — null for Global. Used to launch the
     *  per-conversation chat fragment with the correct peer. */
    val peerUid: Long? = null,
    /** Server-authoritative unread count for DM; 0 for global (server returns null,
     *  we coerce). */
    val unreadCount: Int = 0,
    /** Peer's pixiv profile-image URL for 1v1 rows; filled in lazily by the
     *  fragment after a `getUserProfile` lookup, null until then. Always null
     *  for the Global row (no single peer). */
    val avatarUrl: String? = null,
) {
    enum class Kind { GLOBAL, ONE_ON_ONE }
}
