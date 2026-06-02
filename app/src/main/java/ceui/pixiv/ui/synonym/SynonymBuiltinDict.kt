package ceui.pixiv.ui.synonym

import android.content.Context
import com.google.gson.JsonSyntaxException
import com.tencent.mmkv.MMKV
import timber.log.Timber
import java.io.FileNotFoundException

/**
 * 内置同义词词典的导入（issue #904）。自动（启动后台）/ 手动（管理页菜单）两个入口共用 [importNow]。
 *
 * 「无伤」设计：
 * - App 启动后延迟触发（避开启动高峰），后台线程执行，UI 零感知
 * - 只导一次：flag 记在 MMKV（设备本地）—— 不能放 Settings，Settings 会跨设备同步，
 *   同步过去会导致另一台设备跳过导入但本地 DB 是空的
 * - 合并导入：不覆盖用户已有词典内容（同名目标合并、同义词去重）
 * - 用户退路：管理页可清空词典；清空后 flag 仍为 true，不会再次自动导入
 * - 失败语义：不可恢复错误（assets 缺失 / JSON 损坏）记 flag 止损，不再每次启动白跑；
 *   瞬时错误（DB 锁等）不记 flag，下次启动重试
 *
 * 词典内容按渠道差异化（flavor assets）：
 * github 完整版（全量含成人标签）/ google lite 版（砍成人标签，Google Play 合规）。
 */
object SynonymBuiltinDict {

    const val ASSET_NAME = "synonym_dict_builtin.json"

    /** 设备本地标记（MMKV），不随 Settings 跨设备同步。命名遵循项目 snake_case 惯例 */
    private const val KEY_IMPORTED = "builtin_synonym_dict_imported"

    // 注意：依赖 Shaft.onCreate 里 MMKV.initialize() 先执行（实际上 SessionManager.initialize
    // 已在更早的位置触碰过 defaultMMKV，这里只是 lazy 兜底）。不要在 Application 初始化前调用本类。
    private val store: MMKV by lazy { MMKV.defaultMMKV() }

    /**
     * 是否已导入过。给 [ceui.lisa.activities.Shaft] 启动时做外层判断 ——
     * 已导入的设备不再每次启动都排定时任务/起线程。
     */
    @JvmStatic
    fun isImported(): Boolean = store.decodeBool(KEY_IMPORTED, false)

    /** 标记已导入（清空词典时也调用 —— 用户主动清空后不要再偷偷自动导回来） */
    @JvmStatic
    fun markImported() {
        store.encode(KEY_IMPORTED, true)
    }

    /**
     * 读 assets 并合并导入词典，成功后设置已导入标记。
     * 阻塞调用，必须在后台线程；异常向上抛由调用方决定提示方式。
     *
     * @return (导入目标标签数, 导入同义词数)
     */
    @JvmStatic
    fun importNow(context: Context): Pair<Int, Int> {
        val json = context.assets.open(ASSET_NAME).use {
            it.readBytes().toString(Charsets.UTF_8)
        }
        val result = SynonymDictBackup.importFromJson(context, json)
        markImported()
        return result
    }

    /** 启动自动导入（只导一次，全程静默）。必须在后台线程调用。 */
    @JvmStatic
    fun autoImportIfNeeded(context: Context) {
        try {
            // 内层再查一次（外层判断到实际执行隔了 15 秒，期间用户可能已手动导入）
            if (isImported()) {
                return
            }
            val (targets, synonyms) = importNow(context)
            Timber.d("Builtin synonym dict auto-imported: %d targets, %d synonyms", targets, synonyms)
        } catch (e: FileNotFoundException) {
            // assets 缺失：该 flavor 没打包词典，不可恢复 → 记 flag 止损
            Timber.e(e, "Builtin synonym dict asset missing, giving up")
            markImported()
        } catch (e: JsonSyntaxException) {
            // JSON 损坏：同样不可恢复 → 止损
            Timber.e(e, "Builtin synonym dict asset corrupted, giving up")
            markImported()
        } catch (e: Exception) {
            // DB 锁等瞬时错误：不记 flag，下次启动重试
            Timber.e(e, "Builtin synonym dict auto-import failed, will retry next launch")
        }
    }
}
