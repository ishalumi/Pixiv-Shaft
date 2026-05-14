package ceui.pixiv.chat.api

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import timber.log.Timber

/**
 * Server → client WS frames per `docs/ws-chat-integration.md` §1.3.
 *
 * Five kinds: `hello`, `msg`, `err`, `pong`, plus the fallback for "we got
 * valid JSON we don't recognise" so the decoder never silently drops frames.
 */
sealed interface ChatFrame {

    /** `{ "kind": "hello", "client_id", "display_name", "room", "server_ts" }` */
    data class Hello(
        val clientId: String,
        val displayName: String?,
        val room: String,
        val serverTs: Long,
    ) : ChatFrame

    /**
     * `{ "kind": "msg", "client_id", "display_name", "text", "illust_id", "ts" }`
     *
     * Broadcast for new messages. **No server-assigned `id`** — `ts` is the
     * de-facto unique key (millisecond resolution + sender's client_id makes
     * collision negligible). Sender's own message is echoed back through this
     * same path; UI should render on echo, not optimistically.
     */
    data class Msg(
        val clientId: String,
        val displayName: String?,
        val text: String?,
        val illustId: Long?,
        val ts: Long,
    ) : ChatFrame

    /** `{ "kind": "err", "code": "rate_limited" }` — connection stays open. */
    data class Err(val code: String) : ChatFrame

    /** `{ "kind": "pong", "server_ts": ... }` — reply to client app-level ping. */
    data class Pong(val serverTs: Long) : ChatFrame

    /** Valid JSON but unknown `kind`, or malformed envelope. Logged, not crashed. */
    data class Unknown(val raw: String) : ChatFrame
}

object ChatFrameDecoder {

    private const val TAG = "ChatFrame"

    fun decode(raw: String): ChatFrame {
        val root = try {
            JsonParser.parseString(raw)
        } catch (t: Throwable) {
            Timber.tag(TAG).w("malformed JSON dropped: %s", raw.take(120))
            return ChatFrame.Unknown(raw)
        }
        if (!root.isJsonObject) return ChatFrame.Unknown(raw)
        val obj = root.asJsonObject
        return when (val kind = obj.get("kind")?.asStringOrNull()) {
            "hello" -> ChatFrame.Hello(
                clientId   = obj.req("client_id"),
                displayName = obj.get("display_name")?.asStringOrNull(),
                room       = obj.get("room")?.asStringOrNull() ?: "global",
                serverTs   = obj.get("server_ts")?.asLongOrNull() ?: 0L,
            )
            "msg" -> ChatFrame.Msg(
                clientId   = obj.req("client_id"),
                displayName = obj.get("display_name")?.asStringOrNull(),
                text       = obj.get("text")?.asStringOrNull(),
                illustId   = obj.get("illust_id")?.asLongOrNull(),
                ts         = obj.req("ts").toLong(),
            )
            "err"  -> ChatFrame.Err(code = obj.get("code")?.asStringOrNull() ?: "unknown")
            "pong" -> ChatFrame.Pong(serverTs = obj.get("server_ts")?.asLongOrNull() ?: 0L)
            null, "" -> ChatFrame.Unknown(raw).also {
                Timber.tag(TAG).w("envelope missing 'kind': %s", raw.take(120))
            }
            else -> ChatFrame.Unknown(raw).also {
                Timber.tag(TAG).d("unknown kind=%s — ignored", kind)
            }
        }
    }

    private fun JsonObject.req(key: String): String {
        val el = get(key) ?: error("missing field: $key")
        return el.asStringOrNull() ?: el.toString()
    }

    private fun com.google.gson.JsonElement.asStringOrNull(): String? = when {
        isJsonNull -> null
        isJsonPrimitive && asJsonPrimitive.isString -> asString
        isJsonPrimitive -> asString
        else -> null
    }

    private fun com.google.gson.JsonElement.asLongOrNull(): Long? = when {
        isJsonNull -> null
        isJsonPrimitive && asJsonPrimitive.isNumber -> asLong
        isJsonPrimitive && asJsonPrimitive.isString -> asString.toLongOrNull()
        else -> null
    }
}

// ── Client → Server frame builders ─────────────────────────────────────────

object ChatFrameEncoder {

    /**
     * `{ "kind": "msg", "text": "...", "illust_id": ... }`.
     * Returns a compact JSON string suitable for [WebSocketClient.send].
     * Caller is responsible for client-side `text.length` cap (≤ 2048
     * UTF-16 units per docs §1.2).
     */
    fun msg(text: String, illustId: Long? = null): String {
        val esc = escapeJsonString(text)
        return if (illustId != null) {
            "{\"kind\":\"msg\",\"text\":\"$esc\",\"illust_id\":$illustId}"
        } else {
            "{\"kind\":\"msg\",\"text\":\"$esc\"}"
        }
    }

    private fun escapeJsonString(s: String): String {
        val sb = StringBuilder(s.length + 16)
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c.code < 0x20) {
                    sb.append("\\u").append(String.format("%04x", c.code))
                } else {
                    sb.append(c)
                }
            }
        }
        return sb.toString()
    }
}
