package ceui.pixiv.ui.notification

import ceui.loxia.NotificationItem

/**
 * 通知 cell 的回调接口:
 *  - [onClickNotification] —— 普通点击,路由 target_url(pixiv://illusts/novels/users)
 *  - [onClickViewMore] —— "展开全部" chip,跳子页 NotificationViewMoreFragment
 */
interface NotificationActionReceiver {
    fun onClickNotification(item: NotificationItem)
    fun onClickViewMore(item: NotificationItem)
}
