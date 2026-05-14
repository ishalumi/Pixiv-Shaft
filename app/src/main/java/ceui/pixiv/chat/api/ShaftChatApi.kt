package ceui.pixiv.chat.api

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query

/**
 * HTTP companion for the shaft-api-v2 chat WebSocket. See
 * `docs/ws-chat-integration.md` §1.4 for the wire format.
 *
 * The base URL is the same `BuildConfig.SHAFT_EVENTS_BASE_URL` used by the
 * events stack — server hosts events and chat under `/api/v1/` on one port.
 */
interface ShaftChatApi {

    /**
     * Pull history. `before` is the `id` of the **oldest** item in the
     * previous page (= `items[0].id`); omit for the most recent page.
     * Empty `items` means top reached. `limit` caps server-side at 200.
     */
    @GET("/api/v1/chat/history")
    suspend fun history(
        @Query("room") room: String = "global",
        @Query("limit") limit: Int = 50,
        @Query("before") before: Long? = null,
    ): ChatHistoryResponse

    /** Public lookup of any client's current display name (for back-filling history rows with unfamiliar ids). */
    @GET("/api/v1/chat/profile")
    suspend fun getProfile(@Query("client_id") clientId: String): ChatProfileResponse

    /**
     * Rename self. `sig` = `HMAC_SHA256(secret_ascii, "${clientId}|${ts}")`
     * hex lowercase — same scheme as the WS upgrade URL.
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
 * Server-side row from `/chat/history`. Note `id` is **only** present on
 * HTTP fetches; WS broadcasts of new messages don't carry an id (see
 * `docs/ws-chat-integration.md` §1.3 msg). Client storage uses `ts` as the
 * stable key everywhere so dedup between HTTP backfill and WS push works.
 */
data class ChatHistoryItem(
    val id: Long,
    val client_id: String,
    val display_name: String?,
    val text: String?,
    val illust_id: Long?,
    val ts: Long,
)

data class ChatProfileResponse(
    val client_id: String,
    val display_name: String?,
)

data class SetProfileRequest(
    val client_id: String,
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
    val total_messages: Long,
)
