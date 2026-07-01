package ceui.pixiv.imageloader

/**
 * 一次状态观察/绑定的句柄,可主动取消。
 *
 * 把观察的生命周期显式交回调用方(或交给生命周期自动管理),是对 ui/task 里
 * 「observer 挂上去却从不摘除、随 ViewHolder 复用无上限堆积」(issue #912)那类泄漏的结构性根治。
 */
fun interface Disposable {
    fun dispose()
}
