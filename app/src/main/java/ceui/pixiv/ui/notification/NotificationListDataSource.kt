package ceui.pixiv.ui.notification

import ceui.loxia.Client
import ceui.loxia.NotificationItem
import ceui.loxia.NotificationListResponse
import ceui.pixiv.ui.common.DataSource

/**
 * /v1/notification/list 没有 next_url(实测整段返回,服务端自己截断),
 * 复用 DataSource 是为了拿统一的 RefreshState/error 流。
 */
class NotificationListDataSource : DataSource<NotificationItem, NotificationListResponse>(
    dataFetcher = { Client.appApi.getNotificationList() },
    itemMapper = { item -> listOf(NotificationHolder(item)) },
)

/**
 * 通知"展开全部"子页:用 notification_id 拉同组的完整列表。
 * 服务端不返回 next_url。
 */
class NotificationViewMoreDataSource(
    private val notificationId: Long,
) : DataSource<NotificationItem, NotificationListResponse>(
    dataFetcher = { Client.appApi.getNotificationViewMore(notificationId) },
    itemMapper = { item -> listOf(NotificationHolder(item)) },
)
