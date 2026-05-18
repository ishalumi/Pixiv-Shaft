package ceui.pixiv.ui.notification

import android.text.Html
import androidx.core.text.HtmlCompat
import androidx.core.view.isVisible
import ceui.lisa.R
import ceui.lisa.annotations.ItemHolder
import ceui.lisa.databinding.CellNotificationBinding
import ceui.lisa.utils.GlideUrlChild
import ceui.loxia.DateParse
import ceui.loxia.NotificationItem
import ceui.loxia.findActionReceiverOrNull
import ceui.pixiv.ui.common.ListItemHolder
import ceui.pixiv.ui.common.ListItemViewHolder
import ceui.pixiv.utils.setOnClick
import com.bumptech.glide.Glide

class NotificationHolder(val item: NotificationItem) : ListItemHolder() {
    override fun getItemId(): Long = item.id

    override fun areItemsTheSame(other: ListItemHolder): Boolean {
        return item.id == (other as? NotificationHolder)?.item?.id
    }

    override fun areContentsTheSame(other: ListItemHolder): Boolean {
        val o = (other as? NotificationHolder)?.item ?: return false
        return item.id == o.id &&
            item.is_read == o.is_read &&
            item.content?.text == o.content?.text &&
            item.view_more?.unread_exists == o.view_more?.unread_exists
    }
}

@ItemHolder(NotificationHolder::class)
class NotificationViewHolder(bd: CellNotificationBinding) :
    ListItemViewHolder<CellNotificationBinding, NotificationHolder>(bd) {

    override fun onBindViewHolder(holder: NotificationHolder, position: Int) {
        super.onBindViewHolder(holder, position)
        val item = holder.item
        val content = item.content

        // 文本:服务端给的是带 <b> 的 HTML,直接交给 HtmlCompat。
        // 用户名加粗就是这条 <b>;不另算 span。
        binding.notificationText.text = HtmlCompat.fromHtml(
            content?.text.orEmpty(),
            HtmlCompat.FROM_HTML_MODE_COMPACT,
        )

        // 时间:复用 DateParse 的"x 分钟前 / N 天前 / yyyy-MM-dd",通知一般是近期事件,
        // getTimeAgo 输出更友好。
        binding.notificationTime.text = DateParse.getTimeAgo(context, item.created_datetime)

        // 头像:优先 left_image(作品方图),其次 left_icon(头像),都没有时挂占位。
        val avatarUrl = content?.left_image ?: content?.left_icon
        if (!avatarUrl.isNullOrEmpty()) {
            Glide.with(context).load(GlideUrlChild(avatarUrl))
                .placeholder(R.drawable.chat_avatar_placeholder)
                .into(binding.leftAvatar)
        } else {
            binding.leftAvatar.setImageResource(R.drawable.chat_avatar_placeholder)
        }

        // 右侧缩略图:right_image 是作品方图,有就显示
        val rightUrl = content?.right_image ?: content?.right_icon
        if (!rightUrl.isNullOrEmpty()) {
            binding.rightThumb.isVisible = true
            Glide.with(context).load(GlideUrlChild(rightUrl))
                .placeholder(R.drawable.bg_loading_placeholder)
                .into(binding.rightThumb)
        } else {
            binding.rightThumb.isVisible = false
        }

        // 未读 accent:item.is_read=false 或 group 内有未读时显示
        val isUnread = !item.is_read || item.view_more?.unread_exists == true
        binding.unreadAccent.isVisible = isUnread

        // view_more chip:分组通知才有,文案直接用服务端 title(如 "有新粉丝")
        val groupTitle = item.view_more?.title
        if (groupTitle != null) {
            binding.viewMoreButton.isVisible = true
            binding.viewMoreButton.text = context.getString(R.string.notification_view_more_chip, groupTitle)
            binding.viewMoreButton.setOnClick { sender ->
                sender.findActionReceiverOrNull<NotificationActionReceiver>()?.onClickViewMore(item)
            }
        } else {
            binding.viewMoreButton.isVisible = false
        }

        binding.notificationRoot.setOnClick { sender ->
            sender.findActionReceiverOrNull<NotificationActionReceiver>()?.onClickNotification(item)
        }
    }
}
