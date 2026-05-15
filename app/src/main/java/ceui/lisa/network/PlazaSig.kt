package ceui.lisa.network

import ceui.pixiv.shaftapi.ShaftHmac
import java.security.MessageDigest

// Plaza 专用签名工具。
//
// 跟 chat 的签名形式不同 —— chat 只签 uid|ts,plaza 要把请求 body 也绑进
// sig (防重放 + 防 body 改写)。spec 见 docs shaft-plaza-api-android.md §1。
//
// 不要直接复用 org.json.JSONObject.quote / Gson 来拼 canonical body:
//   - org.json 会把 forward slash 转成 backslash-slash,跟 Node 不一致
//   - Gson 会把非 ASCII 转成 backslash-u 形式,跟服务端逐字节 hash 不一致
// 所以这里手写 jsonEscape + intArrayJson,对齐 V8 行为。
object PlazaSig {

    private val HEX = "0123456789abcdef".toCharArray()

    // V8 JSON.stringify 兼容的字符串 escape:
    //   - 转 双引号 和 反斜杠
    //   - C0 控制字符: backspace / tab / newline / form-feed / CR 走短转义,
    //     其它走六字符 backslash-u 形式
    //   - forward slash 不转义 (org.json 会转,这里必须不转)
    //   - 非 ASCII 保留原 UTF-8 字节
    fun jsonEscape(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> {
                    if (c.code < 0x20) {
                        sb.append("\\u").append(String.format("%04x", c.code))
                    } else {
                        sb.append(c)
                    }
                }
            }
        }
        sb.append('"')
        return sb.toString()
    }

    // [1,2,3] 形态,无空格;空 -> []。
    private fun intArrayJson(ids: List<Long>): String {
        if (ids.isEmpty()) return "[]"
        val sb = StringBuilder("[")
        for ((i, n) in ids.withIndex()) {
            if (i > 0) sb.append(',')
            sb.append(n)
        }
        sb.append(']')
        return sb.toString()
    }

    // 拼 canonical body —— 必须跟服务端逐字节一致。key 顺序固定为
    // text -> refs.illust -> refs.novel -> refs.user,缺的 kind 用 [] 补,
    // 不要换序、不要塞空格。
    //
    // 自检:text="看看这几张图", illust=[123,456], novel=[], user=[11] ->
    // {"text":"看看这几张图","refs":{"illust":[123,456],"novel":[],"user":[11]}}
    fun canonicalPostBody(
        text: String,
        illust: List<Long>,
        novel: List<Long>,
        user: List<Long>,
    ): String {
        return "{\"text\":" + jsonEscape(text) +
                ",\"refs\":{" +
                "\"illust\":" + intArrayJson(illust) +
                ",\"novel\":" + intArrayJson(novel) +
                ",\"user\":" + intArrayJson(user) +
                "}}"
    }

    private fun sha256Hex(s: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val b = bytes[i].toInt() and 0xff
            out[i * 2] = HEX[b ushr 4]
            out[i * 2 + 1] = HEX[b and 0x0f]
        }
        return String(out)
    }

    // POST /api/v1/plaza/posts 的签名。message = "plaza.post|{uid}|{ts}|{sha256(canonical)}"。
    fun signPost(
        secret: String,
        uid: String,
        ts: String,
        text: String,
        illust: List<Long>,
        novel: List<Long>,
        user: List<Long>,
    ): String {
        val bodyHash = sha256Hex(canonicalPostBody(text, illust, novel, user))
        return ShaftHmac.signHex("plaza.post|$uid|$ts|$bodyHash", secret)
    }

    // DELETE /api/v1/plaza/posts/:id 的签名。message = "plaza.delete|{uid}|{ts}|{postId}"。
    fun signDelete(secret: String, uid: String, ts: String, postId: Long): String {
        return ShaftHmac.signHex("plaza.delete|$uid|$ts|$postId", secret)
    }
}
