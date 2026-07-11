package ceui.pixiv.feeds

import ceui.loxia.KListShow
import ceui.pixiv.feeds.cache.CachedFirstPage
import ceui.pixiv.feeds.cache.DEFAULT_FEED_CACHE_MAX_AGE
import ceui.pixiv.feeds.cache.FeedFirstPageCache
import ceui.pixiv.feeds.cache.feedCacheWriteScope
import ceui.pixiv.feeds.cache.feedFirstPageCache
import ceui.pixiv.ui.common.replayNextUrl
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import kotlin.time.Duration

/**
 * pixiv nextUrl 翻页协议到 [FeedSource] 的桥接（core 框架本身不认识 pixiv）。
 *
 * - 第一页走类型安全的 Retrofit suspend 接口；
 * - 后续页复用 [replayNextUrl]（与 common/DataSource 同一份协议实现）；
 * - [mapper] 拿到整个响应做条目映射（在 Default 线程执行，禁止碰 View），并按 [FeedLoadPhase]
 *   区分首屏 / 翻页 / 缓存恢复——插 section 头、按响应字段分组、逐条转换/过滤都表达得出来；
 * - [mapper] 允许携带每页副作用（喂 DiscoveryPool、写推荐浏览历史等），但必须容忍
 *   重复执行：刷新会对第一页重放，空页追载会连续调用——副作用要幂等或重放无害；
 *   **缓存恢复（[FeedLoadPhase.CacheRestore]）时别做「拉取成功」副作用**（拿旧数据重放会污染下游）；
 * - 传了 [cache] 即开「本地优先」：首屏网络成功后落盘，冷启时 [loadFromCache] 秒显旧首屏
 *   （见 [cachedPixivFeedSource] 便捷构造器）；不传则退化为纯网络（现状）；
 * - 本类实例被 [FeedViewModel] 持有到页面最终销毁：[initialFetch] / [mapper] / [cache] 不要捕获
 *   Fragment / View / Context，需要的值先取成局部变量（零捕获约定见 [feedViewModels] 文档）。
 */
class PixivFeedSource<Resp : KListShow<*>>(
    private val initialFetch: suspend () -> Resp,
    private val cache: FeedFirstPageCache<Resp>? = null,
    /** 首屏落盘的 scope（fire-and-forget，见 [feedCacheWriteScope]）。单测注入可控 scope 以便等待。 */
    private val cacheWriteScope: CoroutineScope = feedCacheWriteScope,
    private val mapper: (response: Resp, phase: FeedLoadPhase) -> List<FeedItem>,
) : FeedSource<String> {

    // 仅用于 replayNextUrl 的翻页反序列化（沿用 legacy DataSource 的 vanilla Gson）；缓存快照另走
    // Shaft.sGson（见 feedFirstPageCache）。两者都是无自定义适配器的普通 Gson，行为一致，各自独立。
    private val gson = Gson()

    @Volatile
    private var responseClass: Class<Resp>? = null

    override suspend fun load(cursor: String?): FeedPage<String> {
        val phase: FeedLoadPhase
        val response: Resp = if (cursor == null) {
            phase = FeedLoadPhase.FirstPage
            initialFetch().also {
                @Suppress("UNCHECKED_CAST")
                responseClass = it.javaClass as Class<Resp>
            }
        } else {
            phase = FeedLoadPhase.NextPage
            val clazz = requireNotNull(responseClass) { "首页尚未加载，不该有 nextUrl" }
            replayNextUrl(gson, cursor, clazz)
        }
        val nextCursor = response.nextPageUrl?.takeIf { it.isNotEmpty() }
        // 条目映射可能不便宜（转换、过滤、逐条建模），挪到后台保住 main-safe 契约
        val items = withContext(Dispatchers.Default) { mapper(response, phase) }
        // 落盘首屏：只在映射出内容时缓存（首页整页被滤空 #729 时不存无用快照，否则每次冷启都
        // 读回它、映射成空再丢弃）。fire-and-forget——落盘只服务「下次冷启」，与本次刷新的展示无关，
        // 不能让 gson 序列化 + Room 写延迟新内容上屏（虽都在后台，但会推后 load 返回→UI 更新）。
        val store = cache
        if (cursor == null && store != null && items.isNotEmpty()) {
            cacheWriteScope.launch { store.write(response, nextCursor) }
        }
        return FeedPage(items = items, nextCursor = nextCursor)
    }

    /** 本地优先：磁盘快照恢复上次首屏；无缓存 / 未命中 / 恢复后整页被滤空 / 恢复出错都返回 null。 */
    override suspend fun loadFromCache(): FeedPage<String>? {
        val store = cache ?: return null
        val cached: CachedFirstPage<Resp> = store.read() ?: return null
        return try {
            // 先立起 responseClass：万一缓存展示期间（网络刷新失败留在 Error 而非 Loading）用户翻页，
            // replayNextUrl 也有类可用（正常路径 loadMore 被 refresh=Loading 守卫挡住，不会走到）
            @Suppress("UNCHECKED_CAST")
            responseClass = cached.payload.javaClass as Class<Resp>
            val items = withContext(Dispatchers.Default) {
                mapper(cached.payload, FeedLoadPhase.CacheRestore)
            }
            if (items.isEmpty()) {
                null
            } else {
                FeedPage(items = items, nextCursor = cached.nextCursor?.takeIf { it.isNotEmpty() })
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (ex: Throwable) {
            // 自包含兜底：坏快照 / mapper 在 gson 还原出的边界数据上抛错，一律视为未命中，不外抛——
            // 契约「bad cache = miss」在此闭合，调用方（FeedViewModel）无需再依赖外层 try 兜这个崩溃。
            Timber.w(ex, "feed cache 恢复映射失败，视为未命中")
            null
        }
    }
}

/**
 * 「本地优先」版 [PixivFeedSource] 便捷构造器：给一个稳定 [slot] 即开磁盘缓存。
 *
 * `Resp` 由 [initialFetch] 的返回类型推断，内部据此建 [feedFirstPageCache] 做 gson 序列化。
 * 用法（零捕获：先把 Fragment 属性取成局部 val 再进 lambda）：
 * ```
 * cachedPixivFeedSource("recmd-$apiType",
 *     initialFetch = { Client.appApi.getRecommendedWorksWithRanking(apiType) },
 * ) { resp, phase -> mapRecmdPage(resp.illusts, resp.ranking_illusts, phase, dataType) }
 * ```
 */
inline fun <reified Resp : KListShow<*>> cachedPixivFeedSource(
    slot: String,
    maxAge: Duration = DEFAULT_FEED_CACHE_MAX_AGE,
    noinline initialFetch: suspend () -> Resp,
    noinline mapper: (response: Resp, phase: FeedLoadPhase) -> List<FeedItem>,
): PixivFeedSource<Resp> = PixivFeedSource(
    initialFetch = initialFetch,
    cache = feedFirstPageCache(slot, Resp::class.java, maxAge),
    mapper = mapper,
)
