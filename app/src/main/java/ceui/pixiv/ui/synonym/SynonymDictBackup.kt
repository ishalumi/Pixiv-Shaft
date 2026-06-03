package ceui.pixiv.ui.synonym

import android.content.Context
import ceui.lisa.activities.Shaft
import ceui.lisa.database.AppDatabase
import ceui.pixiv.db.synonym.SynonymTagEntity
import ceui.pixiv.db.synonym.SynonymTargetEntity

/**
 * 同义词词典 JSON 导入/导出（issue #904）。
 *
 * 格式设计为后续「预生成词典」（从 shaft-api-v2/pixshaft-api 数据统计 + LLM 合并产出）
 * 下发时也走同一条导入链路：
 * ```json
 * {
 *   "version": 1,
 *   "targets": [
 *     { "name": "EVA", "synonyms": [ { "name": "新世紀エヴァンゲリオン", "remark": "新世纪福音战士" } ] }
 *   ]
 * }
 * ```
 */
object SynonymDictBackup {

    const val FILE_NAME = "Shaft-SynonymDict.json"

    data class SynonymJson(val name: String?, val remark: String?)
    data class TargetJson(val name: String?, val synonyms: List<SynonymJson>?)
    data class DictJson(val version: Int = 1, val targets: List<TargetJson>?)

    /** @return (json 字符串, 目标标签数) */
    fun exportToJson(context: Context): Pair<String, Int> {
        val entries = AppDatabase.getAppDatabase(context).synonymDao().getAllWithSynonyms()
        val dict = DictJson(
            version = 1,
            targets = entries.map { entry ->
                TargetJson(
                    name = entry.target.name,
                    synonyms = entry.synonyms.map { SynonymJson(it.name, it.remark) },
                )
            },
        )
        return Shaft.sGson.toJson(dict) to entries.size
    }

    /**
     * 合并导入：同名目标标签合并同义词、跳过已存在的同义词，不会清掉已有数据。
     *
     * 重复检测大小写不敏感（issue #905）：匹配引擎本身 lowercase 归一，
     * "Genshin" 与 "genshin" 是同一个词条，存两份只会撑大列表没有任何匹配收益；
     * 同理跳过与目标标签同名的同义词（目标标签名自身已参与匹配）。
     *
     * 整个导入包在单个事务里 —— 预生成词典可能有数千组/数万条同义词，
     * 逐条独立事务（每条 fsync）会慢 1-2 个数量级。
     *
     * @return (导入目标标签数, 导入同义词数)，json 非法时抛异常由调用方处理
     */
    fun importFromJson(context: Context, json: String): Pair<Int, Int> {
        val db = AppDatabase.getAppDatabase(context)
        val dao = db.synonymDao()
        val dict = Shaft.sGson.fromJson(json, DictJson::class.java)
            ?: throw IllegalArgumentException("empty json")
        val targets = dict.targets ?: throw IllegalArgumentException("missing targets")

        var targetCount = 0
        var synonymCount = 0
        db.runInTransaction {
            targets.forEach { targetJson ->
                val name = targetJson.name?.trim().orEmpty()
                if (name.isEmpty() || name.contains(' ') || name.contains('　')) return@forEach

                val existing = dao.getTargetByName(name)
                val targetId: Long
                if (existing != null) {
                    targetId = existing.id
                } else {
                    var id = dao.insertTarget(SynonymTargetEntity(name = name))
                    if (id == -1L) {
                        // IGNORE 策略下唯一索引冲突兜底：复用已存在的行，避免产生 targetId=-1 的孤儿同义词
                        id = dao.getTargetByName(name)?.id ?: return@forEach
                    } else {
                        targetCount++
                    }
                    targetId = id
                }

                // 该目标下已有同义词的归一名集合（一次查询），导入中新增的也加入，
                // 同时挡住「与 DB 已有项重复」和「导入包内部自重复」两种情况
                val seenNorms = dao.getSynonymsOfTarget(targetId)
                    .mapTo(HashSet()) { it.name.trim().lowercase() }
                val targetNorm = name.lowercase()

                targetJson.synonyms.orEmpty().forEach synonymLoop@{ synJson ->
                    val synName = synJson.name?.trim().orEmpty()
                    if (synName.isEmpty()) return@synonymLoop
                    val norm = synName.lowercase()
                    if (norm == targetNorm) return@synonymLoop
                    if (!seenNorms.add(norm)) return@synonymLoop
                    dao.insertSynonym(
                        SynonymTagEntity(
                            targetId = targetId,
                            name = synName,
                            remark = synJson.remark?.trim()?.ifEmpty { null },
                        )
                    )
                    synonymCount++
                }
            }
        }
        return targetCount to synonymCount
    }
}
