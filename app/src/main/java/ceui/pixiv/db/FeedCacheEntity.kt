package ceui.pixiv.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * feeds 框架「本地优先」的首屏快照（v39 建表）。
 *
 * 一个可缓存的 feed 一行、自我覆盖：只存首屏原始响应的 JSON，往后翻页仍走网络
 * （RemoteMediator 语义）。[cacheKey] 已含账号命名空间（`slot#uid`），切号不串味。
 * [schemaVersion] 是信封版本，读时不符即视为未命中（升级快照结构无需迁移）。
 * [savedAt] 兼作过期判定与 LRU 淘汰（见 FeedCacheDao.trimToNewest）的排序键。
 */
@Entity(tableName = "feed_cache_table", indices = [Index("savedAt")])
data class FeedCacheEntity(
    @PrimaryKey val cacheKey: String,
    val schemaVersion: Int,
    val payloadJson: String,
    val nextCursor: String?,
    val savedAt: Long,
)
