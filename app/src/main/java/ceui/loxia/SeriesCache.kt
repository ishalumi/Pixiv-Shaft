package ceui.loxia

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 系列（漫画 illust-series / 小说 novel-series）的进程内缓存，是 [ObjectPool] 在「系列」
 * 维度上的搭档：单话对象（Illust / Novel / 作者 User）照旧进 ObjectPool，本缓存只记
 * 「这条系列有哪些话、什么顺序、总话数、第1话是谁」。
 *
 * 目的：阅读器左右翻页找相邻话、左下角选话 sheet、详情页，原本各自每次都把整条系列
 * （最多 ~10 页 / 300 篇）重新拉一遍。有了这层缓存，第一个用到的人拉一次、其余全部命中，
 * 翻页 / 开 sheet 都不再打网络。
 *
 * 语义对齐 ObjectPool：进程存活期缓存，不做过期失效；单话内容有更新会靠 ObjectPool 的
 * merge 就地补齐，本缓存只在 [invalidate] / [forceRefresh] 时重建顺序。
 */
object SeriesCache {

    /**
     * @param orderedIds 单话 id，按接口返回顺序（漫画降序=最新在前 / 小说升序）。
     * @param total 总话数：漫画取 series_work_count，小说取 content_count。
     * @param firstEpisodeId 第1话 id（illust_series_first_illust / novel_series_first_novel），判列表方向用。
     * @param fullyLoaded 是否翻到 next_url==null；false 表示撞了 [DEFAULT_MAX_PAGES] 上限，可能还有更旧的话没进来。
     */
    data class Entry(
        val seriesId: Long,
        val detail: NovelSeriesDetail?,
        val orderedIds: List<Long>,
        val total: Int,
        val firstEpisodeId: Long?,
        val fullyLoaded: Boolean,
    )

    private const val DEFAULT_MAX_PAGES = 10

    private val illustStore = HashMap<Long, Entry>()
    private val novelStore = HashMap<Long, Entry>()
    private val illustMutex = Mutex()
    private val novelMutex = Mutex()

    fun peekIllustSeries(seriesId: Long): Entry? = synchronized(illustStore) { illustStore[seriesId] }

    fun peekNovelSeries(seriesId: Long): Entry? = synchronized(novelStore) { novelStore[seriesId] }

    /**
     * 拿漫画系列（含单话已灌进 ObjectPool）。命中缓存直接返回；否则接力翻页拉全，缓存后返回。
     * mutex 让并发调用（翻页 + sheet 同时触发）串行化，后到者等前者拉完直接复用，不重复打网络。
     */
    suspend fun loadIllustSeries(
        seriesId: Long,
        maxPages: Int = DEFAULT_MAX_PAGES,
        forceRefresh: Boolean = false,
    ): Entry {
        if (!forceRefresh) peekIllustSeries(seriesId)?.let { return it }
        return illustMutex.withLock {
            if (!forceRefresh) peekIllustSeries(seriesId)?.let { return@withLock it }
            val ids = mutableListOf<Long>()
            var detail: NovelSeriesDetail? = null
            var firstId: Long? = null
            var total = 0
            var fully = false
            var lastOrder: Int? = null
            for (page in 0 until maxPages) {
                val resp = Client.appApi.getIllustSeries(seriesId, lastOrder)
                if (page == 0) {
                    detail = resp.illust_series_detail
                    total = detail?.series_work_count ?: 0
                    firstId = resp.illust_series_first_illust?.id
                    detail?.user?.let { ObjectPool.update(it) }
                }
                resp.illusts?.forEach { illust ->
                    ObjectPool.update(illust)
                    ids.add(illust.id)
                }
                if (resp.next_url == null) {
                    fully = true
                    break
                }
                lastOrder = ids.size
            }
            val entry = Entry(seriesId, detail, ids.toList(), total, firstId, fully)
            synchronized(illustStore) { illustStore[seriesId] = entry }
            entry
        }
    }

    /** 小说系列同 [loadIllustSeries]；总话数取 content_count，第1话取 novel_series_first_novel。 */
    suspend fun loadNovelSeries(
        seriesId: Long,
        maxPages: Int = DEFAULT_MAX_PAGES,
        forceRefresh: Boolean = false,
    ): Entry {
        if (!forceRefresh) peekNovelSeries(seriesId)?.let { return it }
        return novelMutex.withLock {
            if (!forceRefresh) peekNovelSeries(seriesId)?.let { return@withLock it }
            val ids = mutableListOf<Long>()
            var detail: NovelSeriesDetail? = null
            var firstId: Long? = null
            var total = 0
            var fully = false
            var lastOrder: Int? = null
            for (page in 0 until maxPages) {
                val resp = Client.appApi.getNovelSeries(seriesId, lastOrder)
                if (page == 0) {
                    detail = resp.novel_series_detail
                    total = detail?.content_count ?: 0
                    firstId = resp.novel_series_first_novel?.id
                    detail?.user?.let { ObjectPool.update(it) }
                }
                resp.novels?.forEach { novel ->
                    ObjectPool.update(novel)
                    ids.add(novel.id)
                }
                if (resp.next_url == null) {
                    fully = true
                    break
                }
                lastOrder = ids.size
            }
            val entry = Entry(seriesId, detail, ids.toList(), total, firstId, fully)
            synchronized(novelStore) { novelStore[seriesId] = entry }
            entry
        }
    }

    /** 已加载过的漫画系列单话（有序，从 ObjectPool 取实体）。缓存未命中返回 null，命中但个别单话
     *  不在 ObjectPool 里则跳过（正常不会发生，load 时每个都 update 了）。 */
    fun illustsOf(entry: Entry): List<Illust> =
        entry.orderedIds.mapNotNull { ObjectPool.get<Illust>(it).value }

    fun novelsOf(entry: Entry): List<Novel> =
        entry.orderedIds.mapNotNull { ObjectPool.get<Novel>(it).value }

    fun invalidate(seriesId: Long) {
        synchronized(illustStore) { illustStore.remove(seriesId) }
        synchronized(novelStore) { novelStore.remove(seriesId) }
    }
}
