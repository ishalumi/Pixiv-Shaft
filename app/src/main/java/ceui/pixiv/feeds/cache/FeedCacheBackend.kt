package ceui.pixiv.feeds.cache

import ceui.lisa.activities.Shaft
import ceui.lisa.database.AppDatabase
import ceui.pixiv.db.FeedCacheDao
import ceui.pixiv.db.FeedCacheEntity
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * 一条首屏快照记录（存储无关的中间表示）：信封版本 + 原始响应 JSON + 下一页游标 + 落盘时间。
 * [FeedFirstPageCache] 只认这个结构，不认底层是 Room 还是别的——换存储 / 单测替身都靠它解耦。
 */
data class FeedCacheRecord(
    val schemaVersion: Int,
    val payloadJson: String,
    val nextCursor: String?,
    val savedAt: Long,
)

/**
 * 首屏快照的底层字节存取端口。只认「键 → 记录」，序列化 / 过期 / 命名空间都在上层
 * （[FeedFirstPageCache]）。抽出接口是为了可测：单测注入内存假实现，不碰 Room / Android。
 * 实现自行保证 main-safe（本仓默认实现内部切 IO）。
 */
interface FeedCacheBackend {
    suspend fun load(key: String): FeedCacheRecord?
    suspend fun save(key: String, record: FeedCacheRecord)
    suspend fun remove(key: String)
}

/**
 * Room 落地实现：写进 `feed_cache_table`，每次写后做一次 LRU 淘汰。
 * 所有 DAO 调用切到 [Dispatchers.IO]（DAO 本身是阻塞式）。
 */
internal class RoomFeedCacheBackend(private val dao: FeedCacheDao) : FeedCacheBackend {

    override suspend fun load(key: String): FeedCacheRecord? = withContext(Dispatchers.IO) {
        dao.find(key)?.let {
            FeedCacheRecord(it.schemaVersion, it.payloadJson, it.nextCursor, it.savedAt)
        }
    }

    override suspend fun save(key: String, record: FeedCacheRecord) = withContext(Dispatchers.IO) {
        dao.upsert(
            FeedCacheEntity(
                cacheKey = key,
                schemaVersion = record.schemaVersion,
                payloadJson = record.payloadJson,
                nextCursor = record.nextCursor,
                savedAt = record.savedAt,
            )
        )
        dao.trimToNewest(MAX_CACHED_SLOTS)
    }

    override suspend fun remove(key: String) {
        withContext(Dispatchers.IO) { dao.delete(key) }
    }

    private companion object {
        /** 保活的快照槽位上限（几个 feed × 几个账号，足量且有界）。 */
        private const val MAX_CACHED_SLOTS = 24
    }
}

/** 进程级默认存储：本仓单一 Room 库的 feedCacheDao。内部使用，惰性建。 */
internal val defaultFeedCacheBackend: FeedCacheBackend by lazy {
    RoomFeedCacheBackend(AppDatabase.getAppDatabase(Shaft.getContext()).feedCacheDao())
}

/**
 * 首屏落盘用的 app 级 scope（fire-and-forget）。落盘是给「下次冷启」用的，与当前刷新的展示、
 * 也与页面 / 请求生命周期都无关——即便用户在写盘途中离开页面，这次新首屏也应落盘成功，
 * 所以不挂 viewModelScope。SupervisorJob：单次写失败不牵连其它。
 *
 * [CoroutineExceptionHandler] 是兜底：write 内部已 catch(Exception)，但这是个游离 scope，
 * 万一序列化抛出非 Exception 的 Throwable（大响应 OOM / 深图 StackOverflowError / Room Error），
 * 没有 handler 会走线程默认处理器直接崩进程。有它则一律吞掉留痕，落盘失败绝不牵连 UI。
 */
internal val feedCacheWriteScope: CoroutineScope = CoroutineScope(
    SupervisorJob() +
        Dispatchers.IO +
        CoroutineExceptionHandler { _, t -> Timber.w(t, "feed cache 写入协程异常，忽略") }
)
