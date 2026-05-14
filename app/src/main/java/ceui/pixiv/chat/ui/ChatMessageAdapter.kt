package ceui.pixiv.chat.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import ceui.lisa.R
import ceui.lisa.utils.GlideUrlChild
import ceui.pixiv.chat.base.BaseListAdapter
import ceui.pixiv.chat.data.ChatMessageEntity

/**
 * RecyclerView adapter for chat messages.
 *
 * Two view types dispatched on sender uid:
 *  - **Sent**     (msg.uid == selfUid) → right-aligned primary bubble + self avatar
 *  - **Received** (msg.uid != selfUid) → left-aligned surface bubble + peer avatar
 *
 * Avatars are routed by view type rather than per-message: self avatar
 * for sent rows, [peerAvatarUrl] for every received row. That holds for
 * 1v1 (only one peer) and degrades gracefully in the global room
 * (everyone gets the placeholder; per-uid avatars would require
 * threading URLs through [ChatMessageEntity]).
 *
 * DiffUtil keyed on [ChatMessageEntity.localKey] (`clientMsgId ??
 * "server:$serverId"`). Optimistic-send rows and their WS-echo overwrites
 * share the same localKey, so DiffUtil sees them as one item changing
 * state rather than two separate rows.
 */
class ChatMessageAdapter(
    private val selfUid: Long,
    private val onLongClick: ((ChatMessageEntity) -> Unit)? = null,
    private val onAvatarClick: ((uid: Long) -> Unit)? = null,
) : BaseListAdapter<ChatMessageEntity, ChatMessageAdapter.BubbleHolder>(
    diffCallback(keySelector = { it.localKey })
) {

    /** Self avatar URL — set once the logged-in user's profile is known. */
    var selfAvatarUrl: String? = null
        set(value) {
            if (field == value) return
            field = value
            notifyAvatarChanged(VIEW_TYPE_SENT)
        }

    /** Peer avatar URL — set after the peer's profile fetch completes (1v1 only). */
    var peerAvatarUrl: String? = null
        set(value) {
            if (field == value) return
            field = value
            notifyAvatarChanged(VIEW_TYPE_RECEIVED)
        }

    private fun notifyAvatarChanged(viewType: Int) {
        // Repaint only the rows whose avatar slot just acquired a URL.
        // Cheaper than notifyDataSetChanged and avoids flicker on the other side.
        for (i in 0 until itemCount) {
            if (getItemViewType(i) == viewType) notifyItemChanged(i, PAYLOAD_AVATAR)
        }
    }

    override fun getDataItemViewType(item: ChatMessageEntity, position: Int): Int =
        if (item.uid == selfUid) VIEW_TYPE_SENT else VIEW_TYPE_RECEIVED

    override fun onCreateDataViewHolder(parent: ViewGroup, viewType: Int): BubbleHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_SENT -> BubbleHolder(inflater, parent, R.layout.chat_bubble_sent)
            else -> BubbleHolder(inflater, parent, R.layout.chat_bubble_received)
        }
    }

    override fun onBindDataViewHolder(holder: BubbleHolder, item: ChatMessageEntity) {
        val avatarUrl = if (item.uid == selfUid) selfAvatarUrl else peerAvatarUrl
        holder.bind(item, avatarUrl, onAvatarClick)
        holder.itemView.setOnLongClickListener {
            onLongClick?.invoke(item)
            true
        }
    }

    class BubbleHolder(
        inflater: LayoutInflater,
        parent: ViewGroup,
        layoutRes: Int,
    ) : RecyclerView.ViewHolder(inflater.inflate(layoutRes, parent, false)) {

        private val tvContent: TextView = itemView.findViewById(R.id.tv_content)
        private val ivAvatar: ImageView? = itemView.findViewById(R.id.iv_avatar)

        fun bind(
            msg: ChatMessageEntity,
            avatarUrl: String?,
            onAvatarClick: ((uid: Long) -> Unit)?,
        ) {
            tvContent.text = msg.text.orEmpty()
            bindAvatar(avatarUrl)
            ivAvatar?.setOnClickListener(
                onAvatarClick?.let { cb -> View.OnClickListener { cb(msg.uid) } }
            )
            ivAvatar?.isClickable = onAvatarClick != null
        }

        private fun bindAvatar(avatarUrl: String?) {
            val iv = ivAvatar ?: return
            if (avatarUrl.isNullOrBlank()) {
                Glide.with(iv).clear(iv)
                iv.setImageResource(R.drawable.chat_avatar_placeholder)
                return
            }
            Glide.with(iv)
                .load(GlideUrlChild(avatarUrl))
                .placeholder(R.drawable.chat_avatar_placeholder)
                .circleCrop()
                .into(iv)
        }
    }

    companion object {
        const val VIEW_TYPE_SENT = 0
        const val VIEW_TYPE_RECEIVED = 1
        private const val PAYLOAD_AVATAR = "avatar"
    }
}
