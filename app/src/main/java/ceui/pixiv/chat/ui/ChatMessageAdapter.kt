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
 * Three view types dispatched on [ChatMessageEntity.type] and sender:
 *  - **Sent**     (type=1, uid matches self) → right-aligned primary bubble
 *  - **Received** (type=1, uid differs)      → left-aligned surface bubble
 *  - **System**   (type=11)                  → centered small text
 *
 * Uses [BaseListAdapter.diffCallback] with `messageId` as the stable
 * key, so DiffUtil only re-binds items whose content actually changed.
 */
class ChatMessageAdapter(
    private val selfUid: Long,
    private val onLongClick: ((ChatMessageEntity) -> Unit)? = null,
) : BaseListAdapter<ChatMessageEntity, ChatMessageAdapter.BubbleHolder>(
    diffCallback(keySelector = { it.messageId })
) {

    override fun getDataItemViewType(item: ChatMessageEntity, position: Int): Int = when {
        item.type == TYPE_SYSTEM -> VIEW_TYPE_SYSTEM
        item.uid == selfUid      -> VIEW_TYPE_SENT
        else                     -> VIEW_TYPE_RECEIVED
    }

    override fun onCreateDataViewHolder(parent: ViewGroup, viewType: Int): BubbleHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_SENT -> BubbleHolder(inflater, parent, R.layout.chat_bubble_sent)
            VIEW_TYPE_RECEIVED -> BubbleHolder(inflater, parent, R.layout.chat_bubble_received)
            else -> BubbleHolder(inflater, parent, R.layout.chat_bubble_system)
        }
    }

    override fun onBindDataViewHolder(holder: BubbleHolder, item: ChatMessageEntity) {
        holder.bind(item)
        if (item.type != TYPE_SYSTEM) {
            holder.itemView.setOnLongClickListener {
                onLongClick?.invoke(item)
                true
            }
        } else {
            holder.itemView.setOnLongClickListener(null)
        }
    }

    class BubbleHolder(
        inflater: LayoutInflater,
        parent: ViewGroup,
        layoutRes: Int,
    ) : RecyclerView.ViewHolder(inflater.inflate(layoutRes, parent, false)) {

        private val tvContent: TextView = itemView.findViewById(R.id.tv_content)

        fun bind(msg: ChatMessageEntity) {
            tvContent.text = msg.content ?: ""
        }
    }

    companion object {
        private const val TYPE_SYSTEM = 11

        const val VIEW_TYPE_SENT = 0
        const val VIEW_TYPE_RECEIVED = 1
        const val VIEW_TYPE_SYSTEM = 2
    }
}
