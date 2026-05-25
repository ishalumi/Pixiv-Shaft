package ceui.pixiv.ui.history

import android.content.Context
import ceui.lisa.activities.Shaft
import ceui.lisa.database.AppDatabase
import ceui.lisa.database.IllustHistoryEntity
import ceui.lisa.models.IllustsBean
import ceui.loxia.Client
import ceui.loxia.HistoryReportBody
import ceui.loxia.HistoryReportItem
import ceui.pixiv.db.GeneralEntity
import ceui.pixiv.db.RecordType
import ceui.pixiv.session.SessionManager
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * 浏览记录的本地备份导出 / 导入(issue #890)。
 *
 * 历史已迁到云端(pixshaft-api)后,设置页那份 Shaft-Backup.json 还原进的是**本地**
 * illust_table,而开了云同步时历史页是**读云端**的,导入的本地行因此看不见。这里给浏览
 * 历史一个独立的导入/导出入口(对齐屏蔽记录那套 FragmentViewPager),并在导入后:
 * 关云同步 → 本地直接可见;开云同步 → 顺手把导入的条目推一份到云端,云端读取才显示得出来。
 *
 * 文件覆盖三类:插画/漫画 + 小说(都在 illust_table,靠 [IllustHistoryEntity.type] 区分)
 * 和用户(general_table 里 [RecordType.VIEW_USER_HISTORY] 那批)。
 */
object BrowseHistoryBackup {

    /** 云端批量 upsert 上限是 100(见 pixshaft-api server.js HISTORY_MAX_BATCH)。 */
    private const val MAX_CLOUD_BATCH = 100

    /** 本页导出的文件结构:两张表各一段,Gson 直接序列化 entity。 */
    data class Payload(
        val illustHistory: List<IllustHistoryEntity> = emptyList(),
        val userHistory: List<GeneralEntity> = emptyList(),
    )

    /**
     * 导入时的兼容壳:既吃本页导出的 [Payload],也吃设置页那份 Shaft-Backup.json
     * (BackupUtils.BackupEntity,历史字段名是 `illustHistoryEntityList`,且从不含用户历史)。
     * 用户填 #890 时手里多半是后者,读不了旧格式等于对存量用户没修。其余 settings/mute
     * 字段不映射,Gson 自动忽略——导历史绝不顺手覆盖别的配置。
     */
    private data class RawBackup(
        val illustHistory: List<IllustHistoryEntity>? = null,
        val illustHistoryEntityList: List<IllustHistoryEntity>? = null, // 旧 Shaft-Backup.json
        val userHistory: List<GeneralEntity>? = null,
    )

    /** 读全部本地浏览历史 → JSON 串 + 总条数。空时返回 count=0,调用方据此提示「无可导出」。 */
    fun exportToJson(context: Context): Pair<String, Int> {
        val db = AppDatabase.getAppDatabase(context)
        val illust = db.downloadDao().getAllViewHistoryEntities() ?: emptyList()
        val users = db.generalDao()
            .getByRecordType(RecordType.VIEW_USER_HISTORY, 0, Int.MAX_VALUE)
        val payload = Payload(illust, users)
        return Shaft.sGson.toJson(payload) to (illust.size + users.size)
    }

    /**
     * 解析 + 写本地库,返回导入条数。JSON 解析失败抛异常(调用方提示「格式不正确」)。
     * 导入成功且开了云同步则顺手推一份到云端([pushToCloudIfEnabled])。
     */
    suspend fun importFromJson(context: Context, json: String): Int = withContext(Dispatchers.IO) {
        val raw = Shaft.sGson.fromJson(json, RawBackup::class.java)
            ?: return@withContext 0
        val payload = Payload(
            illustHistory = raw.illustHistory ?: raw.illustHistoryEntityList ?: emptyList(),
            userHistory = raw.userHistory ?: emptyList(),
        )
        val db = AppDatabase.getAppDatabase(context)
        var imported = 0
        payload.illustHistory.forEach { e ->
            if (!e.illustJson.isNullOrEmpty() && e.illustID != 0) {
                db.downloadDao().insert(e)
                imported++
            }
        }
        payload.userHistory.forEach { e ->
            if (e.json.isNotEmpty() && e.id != 0L) {
                // recordType 兜底,防手改文件把它写歪导致这批不进「用户」tab。
                db.generalDao().insert(e.copy(recordType = RecordType.VIEW_USER_HISTORY))
                imported++
            }
        }
        if (imported > 0) pushToCloudIfEnabled(payload)
        imported
    }

    /**
     * 开了云同步(且已登录、已弹过同意框 —— 与 HistoryListViewModel.useRemote() 同条件)时,
     * 把导入的条目批量 upsert 到云端,否则云端模式下导入的本地行依然显示不出来。
     * 失败只 warn:本地已经写好,云端推送是尽力而为。
     */
    private suspend fun pushToCloudIfEnabled(payload: Payload) {
        val uid = SessionManager.loggedInUid
        if (uid <= 0L) return
        if (!Shaft.sSettings.isCloudHistorySync || !Shaft.sSettings.isCloudHistoryConsentShown) return

        val items = ArrayList<HistoryReportItem>()
        payload.illustHistory.forEach { e ->
            val tree = runCatching { JsonParser.parseString(e.illustJson) }.getOrNull() ?: return@forEach
            val targetType = if (e.type == 1) {
                "novel"
            } else {
                val ib = runCatching { Shaft.sGson.fromJson(e.illustJson, IllustsBean::class.java) }.getOrNull()
                if (ib?.type == "manga") "manga" else "illust"
            }
            items.add(HistoryReportItem(targetType, e.illustID.toLong(), tree))
        }
        payload.userHistory.forEach { e ->
            val tree = runCatching { JsonParser.parseString(e.json) }.getOrNull() ?: return@forEach
            items.add(HistoryReportItem("user", e.id, tree))
        }
        items.chunked(MAX_CLOUD_BATCH).forEach { batch ->
            runCatching { Client.pixshaft.reportHistory(uid, HistoryReportBody(batch)) }
                .onFailure { Timber.w(it, "history import cloud push failed (${batch.size} items)") }
        }
    }
}
