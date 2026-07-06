package ceui.pixiv.ui.watchlater

/**
 * 由卡片长按菜单的「移出稍后再看」向上冒泡:仅当宿主是 [WatchLaterFragment] 时实现,
 * 用于移除后即时把该项从当前列表拿掉(纯内存操作,不用等 DB 删除再回查,避免竞态)。
 * 其它页面不实现 -> findActionReceiverOrNull 返回 null -> 无副作用。
 */
interface WatchLaterActionReceiver {
    fun onWatchLaterRemoved(illustId: Long)
}
