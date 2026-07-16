package ceui.lisa.fragments

import ceui.loxia.UserPreview

/**
 * In-memory handoff for the recommended-user list that the Feed tab's
 * horizontal preview has already fetched, so that the 推荐用户 full page
 * ([ceui.pixiv.ui.user.RecmdUserFeedFragment]) can show the same set of
 * users the user just saw on the Feed page — without a second round trip
 * (/v1/user/recommended returns a different batch every call, so re-fetching
 * would silently swap out the artist the user was just looking at), and
 * without packing the multi-MB preview graph through Intent extras (which
 * easily exceeds the ~1MB binder transaction limit and crashes on
 * Android 15, #820).
 *
 * Matches the [ceui.pixiv.ui.detail.ArtworksMap] pattern: producer
 * ([FragmentRight.openRecmdUserPage]) drops a snapshot under a unique key and
 * passes the key via Intent; the consumer removes it once on the other side.
 */
object RecmdUserMap {
    @JvmField
    val store = hashMapOf<String, RecmdUserSnapshot>()
}

/**
 * 快照持 loxia [UserPreview] 而不是 legacy `UserPreviewsBean`:交接两端(货架
 * [ceui.pixiv.ui.user.RecmdUserRailFeedFragment] 与整页 [ceui.pixiv.ui.user.RecmdUserFeedFragment])
 * 现在都是 feeds 版、内部本来就拿 loxia data class,中间再垫一层 legacy bean 等于让同一份数据
 * 白白 gson 往返两次(UserPreview→bean→UserPreview)。legacy 消费方
 * ([FragmentRecmdUser]) 已不在路由上,那层转换没有存在理由了。
 */
class RecmdUserSnapshot(
    @JvmField val items: List<UserPreview>,
    @JvmField val nextUrl: String?,
)
