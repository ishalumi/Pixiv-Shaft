package ceui.pixiv.ui.search

import ceui.loxia.User
import ceui.loxia.UserPreview
import ceui.pixiv.feeds.FeedItem

/**
 * 用户 feed 条目：持 loxia [UserPreview]（含 [User] + 预览插画）。feeds 框架此前没有用户列表
 * 基建，这是第一份（对齐插画侧 [ceui.pixiv.ui.common.IllustFeedItem] / 小说侧 NovelFeedItem）。
 *
 * 内容相等性看整个 [UserPreview]（data class 深比较）：关注态（user.is_followed）或预览图变了
 * 都重绑。关注乐观切态走 [withFollowed]。
 */
class UserFeedItem(val preview: UserPreview) : FeedItem {

    val user: User? get() = preview.user

    override val feedKey: Any get() = preview.user?.id ?: 0L

    override fun equals(other: Any?): Boolean {
        return other is UserFeedItem && other.preview == preview
    }

    override fun hashCode(): Int = preview.hashCode()

    /** 关注态变更：copy 出新实例驱动 DiffUtil 重绑关注按钮。user 为 null 时原样返回。 */
    fun withFollowed(followed: Boolean): UserFeedItem {
        val u = preview.user ?: return this
        if (u.is_followed == followed) return this
        return UserFeedItem(preview.copy(user = u.copy(is_followed = followed)))
    }
}
