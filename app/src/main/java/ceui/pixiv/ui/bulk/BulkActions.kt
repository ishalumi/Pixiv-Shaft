@file:JvmName("BulkActions")

package ceui.pixiv.ui.bulk

import androidx.fragment.app.FragmentActivity

/**
 * 给 Java 调用方用的薄壳：从 java 直接构造 [PaginatedObjectSource] / 操作 `Flow<FetchEvent>`
 * 太别扭，统一收口在这里。
 *
 * 命名约定：`startXxxBulkDownload(...)` —— 一调即起 dialog。
 */

/**
 * 我的（或指定用户的）收藏插画批量下载。restrict 取自 [ceui.lisa.utils.Params.TYPE_PUBLIC]
 * / [ceui.lisa.utils.Params.TYPE_PRIVATE]。
 */
fun startBookmarkIllustBulkDownload(
    activity: FragmentActivity,
    userId: Long,
    restrict: String,
    taskName: String,
) {
    val source = MyBookmarksSource(userId = userId, restrict = restrict)
    FetchProgressDialog.show(
        activity.supportFragmentManager,
        bulkEnqueueIllusts(source, taskName),
    )
}
