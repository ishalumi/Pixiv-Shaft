package ceui.pixiv.ui.comments

import android.content.Context
import android.content.res.ColorStateList
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import ceui.lisa.R
import ceui.lisa.annotations.ItemHolder
import ceui.lisa.databinding.CellChildCommentBinding
import ceui.lisa.utils.GlideUrlChild
import ceui.lisa.utils.V3Palette
import ceui.loxia.Comment
import ceui.loxia.DateParse
import ceui.loxia.ObjectPool
import ceui.loxia.findActionReceiverOrNull
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.ListItemViewHolder
import ceui.pixiv.ui.user.UserActionReceiver
import ceui.pixiv.utils.setOnClick
import com.bumptech.glide.Glide
import de.hdodenhof.circleimageview.CircleImageView

/**
 * V3 评论卡片的主题强调色收口:强调色一律取自 [V3Palette.from]（跟随主题 colorPrimary + 日夜自适应）。
 * - 回复/查看回复文字 → 强调色
 * - 「作者」角标 → 强调色 15% 填充 + 强调色文字
 * - 头像描边 → 作者用强调色环,普通评论用极淡中性边
 * 删除按钮保持 v3_danger（危险语义,不跟随主题）。
 */
internal fun applyV3CommentAccents(
    context: Context,
    isAuthor: Boolean,
    avatar: CircleImageView,
    badge: TextView,
    reply: TextView,
    showReply: TextView? = null,
) {
    val palette = V3Palette.from(context)
    val accent = palette.textAccent
    reply.setTextColor(accent)
    showReply?.setTextColor(accent)
    badge.backgroundTintList = ColorStateList.valueOf(palette.alpha15)
    badge.setTextColor(accent)
    avatar.borderColor = if (isAuthor) accent
        else ContextCompat.getColor(context, R.color.v3_border_2)
}

class CommentChildHolder(val parentCommentId: Long, val comment: Comment, val illustArthurId: Long) : ListItemHolder() {

    override fun areItemsTheSame(other: ListItemHolder): Boolean {
        return comment.id == (other as? CommentChildHolder)?.comment?.id
    }

    override fun areContentsTheSame(other: ListItemHolder): Boolean {
        return comment == (other as? CommentChildHolder)?.comment
    }

    override fun getItemId(): Long {
        return comment.id
    }

    val isArthurCommented: Boolean
        get() {
            return illustArthurId == comment.user.id
        }
}

@ItemHolder(CommentChildHolder::class)
class CellChildCommentViewHolder(bd: CellChildCommentBinding) :
    ListItemViewHolder<CellChildCommentBinding, CommentChildHolder>(bd) {
    override fun onBindViewHolder(holder: CommentChildHolder, position: Int) {
        super.onBindViewHolder(holder, position)

        binding.holder = holder
        binding.root.setOnClickListener { sender ->
            sender.findActionReceiverOrNull<CommentActionReceiver>()?.onClickComment(holder.comment)
        }
        binding.root.setOnLongClickListener { sender ->
            sender.findActionReceiverOrNull<CommentActionReceiver>()
                ?.onLongClickComment(sender, holder.comment, holder.parentCommentId)
            true
        }
        val hasStamp = holder.comment.stamp != null
        binding.commentStamp.isVisible = hasStamp
        if (hasStamp) {
            Glide.with(context).load(GlideUrlChild(holder.comment.stamp.stamp_url))
                .placeholder(R.drawable.bg_loading_placeholder)
                .into(binding.commentStamp)
        }
        binding.commentContent.isVisible = !hasStamp
        if (!hasStamp) {
            binding.commentContent.text = CommentEmojiSpanner.format(
                context,
                holder.comment.comment,
                binding.commentContent.textSize.toInt(),
            )
        }

        binding.arthurLabel.isVisible = holder.isArthurCommented
        applyV3CommentAccents(
            context = context,
            isAuthor = holder.isArthurCommented,
            avatar = binding.userIcon,
            badge = binding.arthurLabel,
            reply = binding.reply,
        )

        binding.userIcon.setOnClick {
            ObjectPool.update(holder.comment.user)
            it.findActionReceiverOrNull<UserActionReceiver>()?.onClickUser(holder.comment.user.id)
        }
        binding.userName.setOnClick {
            ObjectPool.update(holder.comment.user)
            it.findActionReceiverOrNull<UserActionReceiver>()?.onClickUser(holder.comment.user.id)
        }

        binding.reply.setOnClick { sender ->
            holder.comment.user.let {
                sender.findActionReceiverOrNull<CommentActionReceiver>()?.onClickReply(holder.comment, holder.parentCommentId)
            }
        }

        binding.delete.setOnClick { sender ->
            sender.findActionReceiverOrNull<CommentActionReceiver>()
                ?.onClickDeleteComment(sender, holder.comment, holder.parentCommentId)
        }

        binding.commentTime.text = DateParse.displayCreateDate(holder.comment.date)
    }
}