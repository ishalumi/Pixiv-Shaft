package ceui.pixiv.ui.comments

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import ceui.lisa.databinding.CellCommentEmojiBinding
import ceui.lisa.model.EmojiItem
import ceui.lisa.utils.Emoji

/**
 * 评论输入框「小表情」选择面板:pixiv 内置的 38 个 `(normal)`/`(heart)`/… 表情(见 [Emoji]),
 * 点击把 code 插入输入框光标处;渲染出的图取自 assets,复用 [CommentEmojiSpanner] 已有的
 * 解码 + LruCache,不再另起一份 Glide/assets 加载逻辑。
 */
class CommentEmojiPickerAdapter(
    private val onPick: (String) -> Unit,
) : RecyclerView.Adapter<CommentEmojiPickerAdapter.VH>() {

    private val items: List<EmojiItem> = Emoji.getEmojis()

    class VH(val binding: CellCommentEmojiBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = CellCommentEmojiBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val bitmap = CommentEmojiSpanner.loadBitmap(holder.itemView.context, item.resource)
        holder.binding.emojiImage.setImageBitmap(bitmap)
        holder.itemView.setOnClickListener { onPick(item.name) }
    }

    override fun getItemCount(): Int = items.size
}
