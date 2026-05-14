package ceui.pixiv.chat.api

import ceui.pixiv.chat.core.AppError
import ceui.pixiv.chat.core.AppResult
import ceui.pixiv.chat.core.ChatHistorySource
import ceui.pixiv.chat.core.MessagePage
import ceui.pixiv.chat.data.ChatMessageEntity
import com.google.gson.Gson
import retrofit2.HttpException
import timber.log.Timber
import java.io.IOException
import javax.net.ssl.SSLException

/**
 * Production [ChatHistorySource] backed by [ShaftChatApi].
 *
 * `pageToken` is the previous page's **oldest item id** stringified — the
 * [ChatHistorySource] interface uses opaque string tokens but the server's
 * `before` parameter is a `Long`. First page: pass `null`.
 *
 * Field mapping ([ChatHistoryItem] → [ChatMessageEntity]):
 *
 * - `ts`        → `messageId` AND `createdTime` (ts is the stable dedup key;
 *                  WS broadcasts don't carry `id` so using `ts` consistently
 *                  is the only way to dedup HTTP backfill against WS push;
 *                  see `docs/ws-chat-integration.md` §1.3)
 * - `client_id` → `uid` (first 16 hex chars parsed as unsigned `Long`;
 *                  opaque stable mapping)
 * - `text`      → `content`
 * - `type`      = 1 (regular message)
 * - `extensions` = JSON `{ server_id, client_id, display_name, illust_id }`
 *                  for fields the entity schema doesn't have first-class
 *                  columns for
 */
class HttpChatHistorySource(
    private val api: ShaftChatApi = ShaftChatHttpClient.api,
    private val threadId: Long = GLOBAL_THREAD_ID,
    private val room: String = "global",
    private val gson: Gson = Gson(),
) : ChatHistorySource<ChatMessageEntity> {

    override suspend fun loadPage(
        threadId: Long,
        pageToken: String?,
        pageSize: Int,
    ): AppResult<MessagePage<ChatMessageEntity>> {
        val before = pageToken?.toLongOrNull()
        return runCatching {
            api.history(
                room = room,
                limit = pageSize.coerceIn(1, 200),
                before = before,
            )
        }.fold(
            onSuccess = { resp ->
                // Server returns ts-ascending (oldest→newest); MessagePage contract is
                // descending. Reverse here so downstream code can trust it.
                val entities = resp.items.asReversed().map { toEntity(it) }
                val nextCursor = resp.items.firstOrNull()?.id?.toString()
                AppResult.Success(MessagePage(entities, nextCursor))
            },
            onFailure = { t ->
                Timber.tag(TAG).w(t, "history failed (before=%s)", before)
                AppResult.Error(t.toAppError())
            },
        )
    }

    private fun toEntity(item: ChatHistoryItem): ChatMessageEntity {
        val ext = mutableMapOf<String, Any?>(
            "server_id" to item.id,
            "client_id" to item.client_id,
        )
        item.display_name?.let { ext["display_name"] = it }
        item.illust_id?.let { ext["illust_id"] = it }

        return ChatMessageEntity(
            messageId = item.ts,
            threadId = threadId,
            uid = clientIdToUid(item.client_id),
            createdTime = item.ts,
            type = TYPE_USER_MESSAGE,
            content = item.text,
            extensions = gson.toJson(ext),
        )
    }

    companion object {
        /** Single-room chat for now (`global`). schema reserves room_id for future. */
        const val GLOBAL_THREAD_ID = 1L
        const val TYPE_USER_MESSAGE = 1
        private const val TAG = "Chat-History"

        /**
         * Stable mapping `64-hex-char client_id → Long uid`. Takes the first
         * 16 hex chars (≈64 bits of entropy from a random sha256 source —
         * vanishingly low collision risk for a community-scale chat).
         */
        fun clientIdToUid(clientId: String): Long {
            val head = clientId.padEnd(16, '0').substring(0, 16)
            return java.lang.Long.parseUnsignedLong(head, 16)
        }
    }
}

/**
 * Translate transport-level throwables into [AppError]s the UI layer can
 * render via `AppError.toUserMessage()` extension.
 */
internal fun Throwable.toAppError(): AppError = when (this) {
    is HttpException -> when (code()) {
        400 -> AppError.BadRequest(cause = this)
        401 -> AppError.Unauthorized(cause = this)
        403 -> AppError.Forbidden(cause = this)
        404 -> AppError.NotFound(cause = this)
        409 -> AppError.Conflict(cause = this)
        410 -> AppError.Gone(cause = this)
        422 -> AppError.Unprocessable(cause = this)
        429 -> AppError.RateLimited(retryAfterSeconds = null, cause = this)
        503 -> AppError.ServiceUnavailable(cause = this)
        in 500..599 -> AppError.Server(code(), message ?: "Server error", this)
        else -> AppError.Unknown(message ?: "Unknown HTTP error", this)
    }
    is SSLException -> AppError.SecurityError(this)
    is java.net.SocketTimeoutException -> AppError.RequestTimeout(this)
    is IOException -> AppError.NetworkUnavailable(this)
    is com.google.gson.JsonParseException -> AppError.Serialization(this)
    else -> AppError.Unknown(message ?: "Unknown error", this)
}
