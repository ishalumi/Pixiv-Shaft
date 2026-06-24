package ceui.pixiv.ui.novel.local

import com.tencent.mmkv.MMKV

/**
 * 本地小说书库的设备本地状态（MMKV，不随 Settings 跨设备同步）。
 *
 * - [rootUri] 是用户通过 SAF 选定的书库根目录 tree URI。它是设备专属的
 *   content:// 授权，换设备无效，所以必须落 MMKV 而不是 Settings —— 同步过去
 *   会让另一台设备拿到一个无权限的 URI。
 * - [novelIdFor] 把文件在书库内的相对路径折成稳定的负数 id，给阅读器复用
 *   既有的进度 / 标注 / 书签存储（都按 Long novelId 建键）。负数空间永远不会
 *   和真实 pixiv 小说 id（正数）相撞；同一相对路径每次都得到同一个 id，
 *   所以换根目录前缀、重进 app 进度都不丢。
 */
object LocalLibraryStore {

    private const val MMKV_ID = "local_novel_library"
    private const val KEY_ROOT = "root_tree_uri"

    private val store: MMKV by lazy { MMKV.mmkvWithID(MMKV_ID) }

    var rootUri: String?
        get() = store.decodeString(KEY_ROOT, null)
        set(value) {
            if (value.isNullOrEmpty()) store.removeValueForKey(KEY_ROOT)
            else store.encode(KEY_ROOT, value)
        }

    /**
     * 由文件在书库内的相对路径派生稳定 id。取字符串 hash 折成 Long，清掉符号位
     * 后取负、并保证非零（`or 1L` 再取负），落进 pixiv 永远用不到的负数区间。
     */
    fun novelIdFor(relativePath: String): Long {
        var h = 1125899906842597L // 大质数起点，降低短串碰撞
        for (c in relativePath) h = 31 * h + c.code
        val v = h and Long.MAX_VALUE // 清符号位 -> 非负
        return -(v or 1L)            // 非零负数
    }
}
