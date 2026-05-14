package ceui.pixiv.chat.api

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query

/**
 * HTTP companion for the shaft-api-v2 chat WebSocket. See
 * `docs/ws-chat-integration.md` §8 for the wire format.
 *
 * Identity field is `uid: Long` (pixiv user id). The legacy 64-hex
 * `client_id` is gone — server switched to uid-routing.
 *
 * The base URL is the same `BuildConfig.SHAFT_EVENTS_BASE_URL` used by the
 * events stack — server hosts events and chat under `/api/v1/` on one port.
 */
interface ShaftChatApi {

    /**
     * Pull history. `before` is the `id` of the **oldest** item in the
     * previous page (= `items[0].id`); omit for the most recent page.
     * Empty `items` means top reached. `limit` caps server-side at 200.
     *
     * @param room `"global"` for public broadcasts, or a decimal uint64
     *   string for a 1v1 thread id (from
     *   [ChatThreadId.oneOnOneThreadId]). ⚠️ 1v1 history has no ACL today
     *   — see doc §10.
     */
    @GET("/api/v1/chat/history")
    suspend fun history(
        @Query("room") room: String = "global",
        @Query("limit") limit: Int = 50,
        @Query("before") before: Long? = null,
    ): ChatHistoryResponse

    /** Public lookup of any uid's current display name (for back-filling history rows with unfamiliar uids). */
    @GET("/api/v1/chat/profile")
    suspend fun getProfile(@Query("uid") uid: Long): ChatProfileResponse

    /**
     * Rename self. `sig` = `HMAC_SHA256(secret_ascii, "${uid}|${ts}")` hex
     * lowercase — same scheme as the WS upgrade URL.
     *
     * `display_name`: 1–32 UTF-16 units, UTF-8 byte length ≤ 96, no ASCII
     * control chars. Server `trim()`s leading/trailing whitespace before
     * validating, so the client can be permissive.
     */
    @POST("/api/v1/chat/profile")
    suspend fun setProfile(
        @Header("X-Shaft-Sign") sig: String,
        @Body body: SetProfileRequest,
    ): SetProfileResponse

    /** Debug/observability — current room online count + total message count. */
    @GET("/api/v1/chat/stats")
    suspend fun stats(@Query("room") room: String = "global"): ChatStatsResponse
}

// ── DTOs (1:1 with server wire format) ─────────────────────────────────────

data class ChatHistoryResponse(
    val room: String,
    val limit: Int,
    val items: List<ChatHistoryItem>,
)

/**
 * Server-side row from `/chat/history`. `id` is the server autoincrement
 * (only history items have it; WS broadcasts of new messages don't —
 * see doc §3.2 Msg). `client_msg_id` is the dedup anchor; server-direct
 * inserts predating the column may carry `null`, in which case fall back
 * to `"server:$id"` as the local key.
 */
data class ChatHistoryItem(
    val id: Long,
    val uid: Long,
    val client_msg_id: String?,
    val display_name: String?,
    val text: String?,
    val illust_id: Long?,
    val ts: Long,
)

data class ChatProfileResponse(
    val uid: Long,
    val display_name: String?,
)

data class SetProfileRequest(
    val uid: Long,
    val ts: Long,
    val display_name: String,
)

data class SetProfileResponse(
    val ok: Boolean,
    val display_name: String?,
)

data class ChatStatsResponse(
    val room: String,
    val online: Int,
    val total_connections: Int? = null,
    val total_messages: Long,
)
