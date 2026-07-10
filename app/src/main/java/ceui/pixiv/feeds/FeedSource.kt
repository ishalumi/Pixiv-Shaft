package ceui.pixiv.feeds

/**
 * 一页数据：条目 + 下一页游标。
 *
 * [nextCursor] 为 null 表示已经到底。
 */
data class FeedPage<Cursor : Any>(
    val items: List<FeedItem>,
    val nextCursor: Cursor?,
)

/**
 * 数据源的全部契约：一个纯 suspend 函数。
 *
 * - `cursor == null` 表示加载第一页（首屏 / 刷新都会走这里）；
 * - 否则加载游标指向的下一页。
 *
 * 设计取舍：
 * - 不用继承体系承载数据逻辑，fun interface 让最简单的数据源可以直接写成 lambda；
 * - 游标是泛型：pixiv 的 nextUrl 是 String，本地库可以用页码 Int 或 Room 的 offset；
 * - 实现必须 main-safe（Retrofit suspend 天然满足；自己做重 IO / 解析要切 Dispatchers）。
 *
 * 抛出的异常由 [FeedViewModel] 统一转成 [LoadState.Error]，实现内不要自行吞错。
 */
fun interface FeedSource<Cursor : Any> {

    suspend fun load(cursor: Cursor?): FeedPage<Cursor>
}
