package ceui.pixiv.db

object RecordType {
    const val VIEW_ILLUST_HISTORY = 1
    const val VIEW_NOVEL_HISTORY = 2
    const val VIEW_USER_HISTORY = 3

    const val BLOCK_ILLUST = 4
    const val BLOCK_NOVEL = 5
    const val BLOCK_USER = 6

    // 稍后再看:本地收藏一批想稍后浏览的插画。仅本地(不上报云端),复用 general_table
    // 存 ceui.loxia.Illust JSON,渲染走 IllustCardHolder,与浏览历史同一套。
    const val WATCH_LATER = 7
}