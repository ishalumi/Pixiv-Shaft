package ceui.pixiv.feeds

import ceui.loxia.KListShow
import ceui.pixiv.ui.common.replayNextUrl
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * pixiv nextUrl 翻页协议到 [FeedSource] 的桥接（core 框架本身不认识 pixiv）。
 *
 * - 第一页走类型安全的 Retrofit suspend 接口；
 * - 后续页复用 [replayNextUrl]（与 common/DataSource 同一份协议实现）；
 * - [mapper] 拿到整个响应做条目映射（在 Default 线程执行，禁止碰 View），
 *   所以插 section 头、按响应字段分组、逐条转换/过滤都表达得出来。
 */
class PixivFeedSource<Resp : KListShow<*>>(
    private val initialFetch: suspend () -> Resp,
    private val mapper: (response: Resp, isFirstPage: Boolean) -> List<FeedItem>,
) : FeedSource<String> {

    private val gson = Gson()
    private var responseClass: Class<Resp>? = null

    override suspend fun load(cursor: String?): FeedPage<String> {
        val response: Resp = if (cursor == null) {
            initialFetch().also {
                @Suppress("UNCHECKED_CAST")
                responseClass = it.javaClass as Class<Resp>
            }
        } else {
            val clazz = requireNotNull(responseClass) { "首页尚未加载，不该有 nextUrl" }
            replayNextUrl(gson, cursor, clazz)
        }
        // 条目映射可能不便宜（转换、过滤、逐条建模），挪到后台保住 main-safe 契约
        val items = withContext(Dispatchers.Default) { mapper(response, cursor == null) }
        return FeedPage(
            items = items,
            nextCursor = response.nextPageUrl?.takeIf { it.isNotEmpty() },
        )
    }
}
