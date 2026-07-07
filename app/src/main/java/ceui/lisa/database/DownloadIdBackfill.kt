package ceui.lisa.database

import android.content.Context
import ceui.lisa.activities.Shaft
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * v38 的 illustId 索引列一次性存量回填。
 *
 * v38 迁移只给 illust_download_table 加了 illustId 列（ADD COLUMN 是 O(1)，存量行先留
 * 0）。这里在启动后后台把所有 illustId=0 的老行补上真实 id（[DownloadIdExtractor] 从
 * illustGson 顶层 "id" 抽），跑完置 MMKV 标志。跑完前 [hasDownloadRecord] 对回填不到的
 * 老行仍退回旧 LIKE 兜底保证徽标不误判；跑完后永久走索引，彻底摆脱 2GB blob 全表扫描。
 *
 * 分批 + 每批一个事务；中途被杀不置标志，下次启动从剩余 illustId=0 续跑（幂等）。
 * {@code WHERE illustId=0} 走的是新建的 illustId 索引取一小撮，不是全表扫描。
 */
object DownloadIdBackfill {

    private const val DONE_KEY = "download_illustid_backfill_done_v1"
    private const val BATCH = 100

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @JvmStatic
    fun isComplete(): Boolean =
        runCatching { Shaft.getMMKV().decodeBool(DONE_KEY, false) }.getOrDefault(false)

    /** 启动时调一次；已完成直接返回，否则后台分批回填。 */
    @JvmStatic
    fun runIfNeeded(context: Context) {
        if (isComplete()) return
        val app = context.applicationContext
        scope.launch {
            try {
                val db = AppDatabase.getAppDatabase(app)
                val dao = db.downloadDao()
                var total = 0
                while (true) {
                    val batch = dao.getDownloadsNeedingIdBackfill(BATCH)
                    if (batch.isEmpty()) break
                    db.runInTransaction {
                        for (row in batch) {
                            var id = DownloadIdExtractor.extractIllustId(row.illustGson)
                            // 抽到 0（理论上 id 恒为正，防御）或 -1（解析失败）都写成 -1，
                            // 保证 illustId != 0 → 下一批不再取到它，避免死循环。
                            if (id == 0L) id = -1L
                            dao.setDownloadIllustId(row.fileName, id)
                        }
                    }
                    total += batch.size
                    if (batch.size < BATCH) break
                }
                Shaft.getMMKV().encode(DONE_KEY, true)
                Timber.tag("DL-BACKFILL").i("illustId backfill done, rows=%d", total)
            } catch (t: Throwable) {
                // 未置标志 → 下次启动续跑；期间 hasDownloadRecord 仍走 LIKE 兜底，正确性不受影响。
                Timber.tag("DL-BACKFILL").w(t, "illustId backfill interrupted, resume next launch")
            }
        }
    }
}
