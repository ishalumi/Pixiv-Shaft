package ceui.pixiv.db.queue

/**
 * 队列列表 UI 用的轻量行投影 —— 故意**不**包含 [DownloadQueueEntity.illustGson]。
 *
 * UI 上限 5000 行，单条 illustGson 5–30KB JSON，全量 entity 让 ListAdapter
 * 的 currentList 拖着 50–150MB 字符串不放，把 256MB heap 撑爆。这里只取
 * 列表展示用得到的列；点击 row 进 VActivity 时再按 id 单独 [DownloadQueueDao.getById]
 * 拉一行解析 JSON。
 */
data class DownloadQueueRow(
    val id: Long,
    val illustId: Long,
    val type: String,
    val seq: Long,
    val status: String,
    val retryCount: Int,
)
