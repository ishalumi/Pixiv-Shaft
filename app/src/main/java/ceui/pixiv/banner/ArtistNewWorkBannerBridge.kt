package ceui.pixiv.banner

import ceui.pixiv.chat.api.ChatFrame
import ceui.pixiv.chat.api.ShaftChatGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.UUID

/**
 * 把每条 [ChatFrame.ArtistNewWork] (来自 [ShaftChatGateway.artistNewWork])
 * 推一条 banner。app-scoped 单例,模仿 [ChatBannerBridge] 的形态:同源
 * gateway flow,同套 BannerManager,同 lifecycle。
 *
 * dedupKey = `(target_type, target_id)`:同一作品反复触发 (server 重启后
 * 重新发现) 只 Replace,不堆栈。
 *
 * deepLink 走 `pixiv://illusts/<id>` / `pixiv://novels/<id>`,
 * [InAppBanners.handleTap] 的 pixiv:// 分支命中后委托给
 * `routeNotificationTargetUrl`,跳转到对应作品 / 小说详情。manga 跟 illust
 * 同走 illusts 路径(routeNotificationTargetUrl 内部由 ArtworkV3Fragment 处理
 * 两者),user/其它 target_type 当前不出 deepLink。
 */
class ArtistNewWorkBannerBridge(
    private val bannerManager: BannerManager,
    private val scope: CoroutineScope,
) {
    private var job: Job? = null

    fun start() {
        if (job != null) return
        job = scope.launch {
            ShaftChatGateway.artistNewWork.collect { frame ->
                bannerManager.enqueue(toBannerRequest(frame))
            }
        }
        Timber.tag(TAG).i("ArtistNewWorkBannerBridge started")
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private fun toBannerRequest(frame: ChatFrame.ArtistNewWork): BannerRequest.Text {
        val title = "画师 ${frame.userId} 新作"
        val body = (frame.title?.takeIf { it.isNotBlank() } ?: "(no title)") +
            "  [${frame.targetType}/${frame.targetId}]"
        return BannerRequest.Text(
            id = UUID.randomUUID().toString(),
            title = title,
            message = body,
            dedupKey = "artist-new-work-${frame.targetType}-${frame.targetId}",
            priority = BannerPriority.NORMAL,
            category = BannerCategory.System,
            policy = BannerDisplayPolicy.Replace,
            autoDismissMillis = 5000L,
            // deepLink 走 pixiv:// 协议,复用 routeNotificationTargetUrl 已知的
            // illusts/novels/users 路由(InAppBanners.handleTap 加了 pixiv:// 分支)。
            // manga 跟 illust 同走 "illusts" 路径 — routeNotificationTargetUrl 内部
            // 用 "Plaza打开作品" / ArtworkV3Fragment,illust 和 manga 都吃。
            deepLink = deepLinkFor(frame),
            metadata = mapOf(
                "user_id" to frame.userId.toString(),
                "target_type" to frame.targetType,
                "target_id" to frame.targetId.toString(),
            ),
        )
    }

    private fun deepLinkFor(frame: ChatFrame.ArtistNewWork): String? = when (frame.targetType) {
        "illust", "manga" -> "pixiv://illusts/${frame.targetId}"
        "novel" -> "pixiv://novels/${frame.targetId}"
        else -> null
    }

    companion object {
        private const val TAG = "Banner-ArtistNewWork"
    }
}
