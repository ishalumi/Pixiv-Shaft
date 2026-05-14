package ceui.pixiv.chat.api

import com.google.gson.JsonParser
import timber.log.Timber

/**
 * Server → client WS frames per `docs/ws-chat-integration.md` §3.2.
 *
 * Four real kinds (`hello`/`msg`/`err`/`pong`) plus a fallback [Unknown]
 * for "got valid envelope but unrecognised kind" so the decoder never
 * silently drops or crashes the stream.
 *
 * **Identity model**: uid (`Long` pixiv user id) is the only identity.
 * The legacy 64-hex `client_id` is gone (`switch from room-subscribe model
 * to uid-routing`).
 */
sealed interface ChatFrame {

    /** `{ "kind": "hello", "uid", "display_name", "server_ts" }` — first frame after handshake. */
    data class Hello(
        val uid: Long,
        val displayName: String?,
        val serverTs: Long,
    ) : ChatFrame

    /**
     * `{ "kind": "msg", "room", "uid", "display_name", "client_msg_id",
     *    "text", "illust_id"?, "ts" }`
     *
     * Server populates `room` based on routing:
     *  - `"global"` for public broadcasts
     *  - `pairRoomId(uid_a, uid_b)` decimal string for 1v1
     *
     * `client_msg_id` is the dedup anchor — see doc §4. UPSERT into the
     * local store keyed by this id so duplicate broadcasts (broker may
     * deliver the same msg twice between deliverToUid + DB INSERT OR IGNORE)
     * collapse to one row.
     */
    data class Msg(
        val room: String,
        val uid: Long,
        val displayName: String?,
        val clientMsgId: String?,
        val text: String?,
        val illustId: Long?,
        val ts: Long,
    ) : ChatFrame

    /**
     * `{ "kind": "err", "code", "client_msg_id"? }` — protocol / rate-limit
     * / validation error. Connection stays open.
     *
     * When the offending inbound frame carried a `client_msg_id`, server
     * echoes it on the err so the client can anchor the failure to that
     * exact optimistic row (per doc §3.2 / §12). Frame-level errors that
     * happen BEFORE per-msg parsing (`bad_json`, `bad_envelope`,
     * `frame_too_large` of unparseable bytes, …) leave [clientMsgId]
     * `null` — callers fall back to "most recent Sending" heuristic.
     */
    data class Err(val code: String, val clientMsgId: String?) : ChatFrame

    /** `{ "kind": "pong", "server_ts" }` — reply to client app-level `ping`. */
    data class Pong(val serverTs: Long) : ChatFrame

    /** Valid JSON but unknown / malformed envelope. Logged, dead-lettered, stream stays alive. */
    data class Unknown(val raw: String) : ChatFrame
}

object ChatFrameDecoder {

    private const val TAG = "Chat-Frame"

    fun decode(raw: String): ChatFrame = try {
        decodeOrThrow(raw)
    } catch (t: Throwable) {
        // Per docs §9.1 "onMessage 解析失败 → IncomingMessage.Unknown(raw)
        // 落到 dead-letter, 不挂掉流". Any decode-time exception (malformed
        // JSON, non-numeric ts, weird type, …) gets logged and downgraded.
        Timber.tag(TAG).w(t, "decode failed, dead-lettered: %s", raw.take(120))
        ChatFrame.Unknown(raw)
    }

    private fun decodeOrThrow(raw: String): ChatFrame {
        val root = JsonParser.parseString(raw)
        if (!root.isJsonObject) return ChatFrame.Unknown(raw)
        val obj = root.asJsonObject
        val kind = obj.get("kind")?.asStringOrNull()?.takeIf { it.isNotEmpty() }
            ?: run {
                Timber.tag(TAG).w("envelope missing 'kind': %s", raw.take(120))
                return ChatFrame.Unknown(raw)
            }
        return when (kind) {
            "hello" -> {
                val uid = obj.get("uid")?.asLongOrNull()
                    ?: run {
                        Timber.tag(TAG).w("hello frame missing uid: %s", raw.take(120))
                        return ChatFrame.Unknown(raw)
                    }
                ChatFrame.Hello(
                    uid = uid,
                    displayName = obj.get("display_name")?.asStringOrNull(),
                    serverTs = obj.get("server_ts")?.asLongOrNull() ?: 0L,
                )
            }
            "msg" -> {
                // Required: ts, uid, room. Missing any → can't dedup / route → dead letter.
                val ts = obj.get("ts")?.asLongOrNull()
                val uid = obj.get("uid")?.asLongOrNull()
                val room = obj.get("room")?.asStringOrNull()
                if (ts == null || uid == null || room == null) {
                    Timber.tag(TAG).w("msg frame missing ts/uid/room: %s", raw.take(120))
                    ChatFrame.Unknown(raw)
                } else ChatFrame.Msg(
                    room = room,
                    uid = uid,
                    displayName = obj.get("display_name")?.asStringOrNull(),
                    clientMsgId = obj.get("client_msg_id")?.asStringOrNull(),
                    text = obj.get("text")?.asStringOrNull(),
                    illustId = obj.get("illust_id")?.asLongOrNull(),
                    ts = ts,
                )
            }
            "err" -> ChatFrame.Err(
                code = obj.get("code")?.asStringOrNull() ?: "unknown",
                clientMsgId = obj.get("client_msg_id")?.asStringOrNull(),
            )
            "pong" -> ChatFrame.Pong(serverTs = obj.get("server_ts")?.asLongOrNull() ?: 0L)
            else -> ChatFrame.Unknown(raw).also {
                Timber.tag(TAG).d("unknown kind=%s — ignored", kind)
            }
        }
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
     * Build a public-room `msg` frame:
     * `{"kind":"msg","room":"global","client_msg_id":<id>,"text":<text>,"illust_id":<id?>}`
     */
    fun msgGlobal(clientMsgId: String, text: String, illustId: Long? = null): String {
        val esc = escapeJsonString(text)
        val idEsc = escapeJsonString(clientMsgId)
        return buildString {
            append("""{"kind":"msg","room":"global","client_msg_id":"""")
            append(idEsc)
            append("""","text":"""")
            append(esc)
            append('"')
            if (illustId != null) append(""","illust_id":""").append(illustId)
            append('}')
        }
    }

    /**
     * Build a 1v1 `msg` frame:
     * `{"kind":"msg","to_uid":<long>,"client_msg_id":<id>,"text":<text>,"illust_id":<id?>}`
     *
     * Doc §5: never send a numeric `room` from the client. Server derives
     * the room from `(self_uid, to_uid)` via `pairRoomId` (weaver ReverseXOR),
     * and ACL is enforced by handshake-authed `self_uid`. Sending raw
     * numeric `room` is rejected as `err.code = "room_forbidden"`.
     */
    fun msg1v1(toUid: Long, clientMsgId: String, text: String, illustId: Long? = null): String {
        val esc = escapeJsonString(text)
        val idEsc = escapeJsonString(clientMsgId)
        return buildString {
            append("""{"kind":"msg","to_uid":""")
            append(toUid)
            append(""","client_msg_id":"""")
            append(idEsc)
            append("""","text":"""")
            append(esc)
            append('"')
            if (illustId != null) append(""","illust_id":""").append(illustId)
            append('}')
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
