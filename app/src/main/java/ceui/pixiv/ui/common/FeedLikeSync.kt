package ceui.pixiv.ui.common

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import ceui.lisa.utils.Params
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.FeedViewModel

/**
 * 「收藏 / 关注态在别处被改了 → 广播回流本列表」的通用协作件。
 *
 * legacy 用 `LIKED_ILLUST` / `LIKED_NOVEL` / `LIKED_USER` 三条 LocalBroadcast 做跨列表同步：
 * 详情页、另一个还没迁 feeds 的 legacy 列表、乃至本页自己发出的收藏成功回执，都经这里回流。
 * 三条广播共用同一份载荷契约（[Params.ID] + [Params.IS_LIKED]）和同一套处理形状
 * ——「按 id 找到条目 → 翻个 boolean → [FeedViewModel.updateItems] 落地」——所以收成一件。
 *
 * 收之前是三份各自为政的实现：插画那份住在 [IllustFeedDetailSync]，小说和用户那两份则是
 * [NovelFeedFragment] / [UserFeedFragment] 上的 `likedReceiver` 字段，靠 onViewCreated 注册、
 * onDestroyView 注销。同一件事三种写法、两种生命周期，且只有继承了那三个基类的页面才享受得到。
 * 做成协作件后不依赖继承，混排页 / 任何 feeds 页面都能挂。
 *
 * [transform] 必须幂等：本页自己发起的收藏会把广播绕回自己，回流时条目往往已是目标态
 *（`withBookmarked` / `withFollowed` 在无变化时原样返回，[FeedViewModel.updateItems] 也就不会
 * 制造无谓的重绑）。
 *
 * 生命周期随 viewLifecycleOwner：DESTROYED 自动注销，调用方无需清理。
 */
class FeedLikeSync<T : FeedItem>(
    private val feedViewModel: FeedViewModel<*>,
    /** 广播 action：[Params.LIKED_ILLUST] / [Params.LIKED_NOVEL] / [Params.LIKED_USER]。 */
    private val action: String,
    private val itemClass: Class<T>,
    /** 条目对应的作品 / 画师 id，用于与广播里的 id 匹配。 */
    private val idOf: (T) -> Long?,
    /** 命中条目的新状态（必须幂等，见类文档）。 */
    private val transform: (item: T, liked: Boolean) -> T,
) {

    fun bind(context: Context, viewLifecycleOwner: LifecycleOwner) {
        val broadcastManager = LocalBroadcastManager.getInstance(context)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val extras = intent?.extras ?: return
                // 载荷 id 是 int：legacy 发送方（PixivOperate / CommonReceiver / 各 Bean）一直按
                // int 存取，插画 / 小说 / 画师 id 目前都在 int 范围内。此处沿用该契约，不自行加宽——
                // 要改得连同所有发送方一起改，否则读到 0。
                val id = extras.getInt(Params.ID).toLong()
                val liked = extras.getBoolean(Params.IS_LIKED)
                feedViewModel.updateItems(itemClass) { item ->
                    if (idOf(item) == id) transform(item, liked) else item
                }
            }
        }
        broadcastManager.registerReceiver(receiver, IntentFilter(action))
        viewLifecycleOwner.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                broadcastManager.unregisterReceiver(receiver)
            }
        })
    }
}

/** [FeedLikeSync] 的 reified 便捷构造：`feedLikeSync<NovelFeedItem>(vm, Params.LIKED_NOVEL, ...)`。 */
inline fun <reified T : FeedItem> feedLikeSync(
    feedViewModel: FeedViewModel<*>,
    action: String,
    noinline idOf: (T) -> Long?,
    noinline transform: (item: T, liked: Boolean) -> T,
): FeedLikeSync<T> = FeedLikeSync(feedViewModel, action, T::class.java, idOf, transform)
