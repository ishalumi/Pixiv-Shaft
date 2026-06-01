package ceui.pixiv.download.aria2

import ceui.lisa.activities.Shaft
import ceui.lisa.core.DownloadItem
import ceui.lisa.utils.Params
import ceui.pixiv.download.config.DownloadItems
import java.io.IOException

/**
 * 把 [DownloadItem] 转发给远端 aria2 的胶水层（#692）。
 *
 * 启用条件：Settings.aria2Enabled 且 RPC 地址非空。启用后
 * [ceui.lisa.core.Manager] 的 downloadOne 不再做本地下载，改调 [dispatch]，
 * RPC 成功即视为该任务完成（实际下载由远端 aria2 执行）。
 */
object Aria2Dispatcher {

    private val client = Aria2Client()

    @JvmStatic
    fun isEnabled(): Boolean {
        val settings = Shaft.sSettings ?: return false
        return settings.isAria2Enabled && settings.aria2RpcUrl.isNotBlank()
    }

    /**
     * 同步把 [item] 发给 aria2，返回 aria2 任务 GID。失败抛 [IOException]。
     * 必须在 IO 线程调用。
     */
    @JvmStatic
    @Throws(IOException::class)
    fun dispatch(item: DownloadItem): String {
        val settings = Shaft.sSettings
        return client.addUri(
            rpcUrl = settings.aria2RpcUrl.trim(),
            secret = settings.aria2RpcSecret.trim(),
            fileUrl = item.url,
            out = renderOutPath(item),
            dir = settings.aria2RemoteDir.trim(),
            // pixiv 图片 CDN 必须带 Referer，否则 403 —— 跟本地下载用同一个值
            headers = listOf("${Params.MAP_KEY}: ${Params.IMAGE_REFERER}"),
        )
    }

    /** 设置页「测试连接」：返回 aria2 版本号，失败抛 [IOException]。必须在 IO 线程调用。 */
    fun testConnection(rpcUrl: String, secret: String): String =
        client.getVersion(rpcUrl.trim(), secret.trim())

    /**
     * out = 用户当前命名模板渲染出的完整相对路径（目录 + 文件名），
     * 让 NAS 上的目录结构 / 文件名与本地下载完全一致。
     *
     * 动图（ugoira）发送的是帧 zip 而不是合成后的 GIF，沿用 [DownloadItem]
     * 构造时算好的 zip 文件名，不套 gif 模板。
     */
    private fun renderOutPath(item: DownloadItem): String {
        return if (item.illust.isGif) {
            item.name
        } else {
            DownloadItems.illustRelativePath(item.illust, item.index).joinTo("/")
        }
    }
}
