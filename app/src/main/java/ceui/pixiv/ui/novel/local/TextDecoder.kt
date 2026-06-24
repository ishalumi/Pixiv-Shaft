package ceui.pixiv.ui.novel.local

import java.nio.charset.Charset

/**
 * 本地 txt 解码。Pixiv-Shaft 自己导出的 txt 是 UTF-8，但用户文件夹里那批老文件
 * （「手机自带小说软件时代」下的）很可能是 GBK / GB2312。直接按 UTF-8 解会整篇
 * 乱码，所以这里做一次轻量探测：
 *
 *   1. BOM 优先 —— UTF-8 / UTF-16 LE / UTF-16 BE 都有明确字节签名。
 *   2. 无 BOM 时严格校验是否为合法 UTF-8 字节序列；是就按 UTF-8。
 *   3. 否则回退 GB18030（GBK / GB2312 的超集，能吃下绝大多数简中老文本）。
 *
 * 不引第三方 chardet 库：上面三步覆盖了本地中文 txt 的真实分布，零依赖。
 */
object TextDecoder {

    private val GB18030: Charset =
        runCatching { Charset.forName("GB18030") }.getOrDefault(Charsets.UTF_8)

    fun decode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        when {
            bytes.size >= 3 &&
                bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() ->
                return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
                return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
                return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        return if (isValidUtf8(bytes)) String(bytes, Charsets.UTF_8) else String(bytes, GB18030)
    }

    /** 严格 UTF-8 字节序列校验。命中任何非法续字节 / 过短截断 / overlong 即判否。 */
    private fun isValidUtf8(bytes: ByteArray): Boolean {
        var i = 0
        val n = bytes.size
        while (i < n) {
            val b = bytes[i].toInt() and 0xFF
            val extra = when {
                b and 0x80 == 0x00 -> 0
                b and 0xE0 == 0xC0 -> 1
                b and 0xF0 == 0xE0 -> 2
                b and 0xF8 == 0xF0 -> 3
                else -> return false
            }
            if (b == 0xC0 || b == 0xC1) return false // overlong 2-byte
            for (k in 1..extra) {
                if (i + k >= n) return false
                if (bytes[i + k].toInt() and 0xC0 != 0x80) return false
            }
            i += extra + 1
        }
        return true
    }
}
