package ceui.pixiv.chat.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import ceui.lisa.R
import ceui.pixiv.chat.base.BaseListAdapter
import ceui.pixiv.chat.data.ChatMessageEntity

/**
 * RecyclerView adapter for chat messages.
 *
 * Two view types dispatched on sender uid:
 *  - **Sent**     (msg.uid == selfUid) → right-aligned primary bubble
 *  - **Received** (msg.uid != selfUid) → left-aligned surface bubble
 *
 * The new wire protocol (doc §3.2) has no "system" msg kind — every
 * incoming `msg` frame is a user message attributable to a uid.
 *
 * DiffUtil keyed on [ChatMessageEntity.localKey] (`clientMsgId ??
 * "server:$serverId"`). Optimistic-send rows and their WS-echo overwrites
 * share the same localKey, so DiffUtil sees them as one item changing
 * state rather than two separate rows.
 */
class ChatMessageAdapter(
    private val selfUid: Long,
    private val onLongClick: ((ChatMessageEntity) -> Unit)? = null,
) : BaseListAdapter<ChatMessageEntity, ChatMessageAdapter.BubbleHolder>(
    diffCallback(keySelector = { it.localKey })
) {

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
        holder.bind(item)
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

        fun bind(msg: ChatMessageEntity) {
            tvContent.text = msg.text.orEmpty()
        }
    }

    companion object {
        const val VIEW_TYPE_SENT = 0
        const val VIEW_TYPE_RECEIVED = 1
    }
}
