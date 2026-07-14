package ceui.pixiv.ui.common

import java.io.File
import java.util.Locale

/**
 * 从已删的 ImgDisplayFragment.kt 抽出的存活工具:FetchAllTask 仍用 [getFileSize] 展示缓存体积。
 * ImgDisplayFragment 及其它只服务已删图片页的 helper(setUpFullScreen / getImageDimensions /
 * setUpWithTaskStatus)无存活引用,未保留。
 */
fun getFileSize(file: File): String {
    val fileSizeInBytes = file.length()

    return when {
        fileSizeInBytes < 1000 -> "${fileSizeInBytes}B" // 小于 1KB
        fileSizeInBytes < 1000 * 1000 -> String.format(
            Locale.getDefault(),
            "%.2f KB",
            fileSizeInBytes / 1000f
        ) // 小于 1MB
        fileSizeInBytes < 1000 * 1000 * 1000 -> String.format(
            Locale.getDefault(),
            "%.2f MB",
            fileSizeInBytes / (1000f * 1000)
        ) // 小于 1GB
        else -> String.format(
            Locale.getDefault(),
            "%.2f GB",
            fileSizeInBytes / (1000f * 1000 * 1000)
        ) // 大于等于 1GB
    }
}
