package ceui.loxia

import com.google.gson.JsonElement
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * pixshaft-api (https://pixshaft.com) — remote browse history for the logged-in
 * viewer. Field names match the server JSON exactly (snake_case) so the default
 * Gson converter maps them without @SerializedName.
 */
interface PixshaftApi {

    @POST("v1/history/{uid}")
    suspend fun reportHistory(
        @Path("uid") uid: Long,
        @Body body: HistoryReportBody,
    ): HistoryReportAck

    @GET("v1/history/{uid}")
    suspend fun listHistory(
        @Path("uid") uid: Long,
        @Query("type") type: String?,
        @Query("q") q: String?,
        @Query("before") before: String?,
        @Query("limit") limit: Int,
    ): HistoryListResponse

    @DELETE("v1/history/{uid}/{type}/{id}")
    suspend fun deleteHistory(
        @Path("uid") uid: Long,
        @Path("type") type: String,
        @Path("id") id: Long,
    ): HistoryDeleteAck

    @DELETE("v1/history/{uid}")
    suspend fun clearHistory(
        @Path("uid") uid: Long,
        @Query("type") type: String?,
    ): HistoryDeleteAck

    // ── email-bound account backup ──
    // All four are signed with X-Shaft-Sign by the OkHttp interceptor in
    // ClientManager (the `/v1/account/` path match). Server: src/account.js.

    /** Backup (logged-in): mail a 6-digit code to verify ownership of [email]. */
    @POST("v1/account/bind/request")
    suspend fun bindRequest(@Body body: EmailReq): EmailAck

    /** Backup: with a valid code, store the encrypted [BindConfirmReq.account]. */
    @POST("v1/account/bind/confirm")
    suspend fun bindConfirm(@Body body: BindConfirmReq): BindConfirmAck

    /** Restore (login page): mail a code IF [email] has a backup ([RestoreRequestAck.found]). */
    @POST("v1/account/restore/request")
    suspend fun restoreRequest(@Body body: EmailReq): RestoreRequestAck

    /** Restore: with a valid code, hand back the stored account JSON. */
    @POST("v1/account/restore/confirm")
    suspend fun restoreConfirm(@Body body: RestoreConfirmReq): RestoreConfirmResp
}

data class EmailReq(val email: String)

data class EmailAck(val ok: Boolean = false)

data class BindConfirmReq(
    val email: String,
    val code: String,
    val account: AccountResponse,
)

data class BindConfirmAck(
    val ok: Boolean = false,
    val uid: Long? = null,
)

data class RestoreRequestAck(
    val ok: Boolean = false,
    val found: Boolean = false,
)

data class RestoreConfirmReq(
    val email: String,
    val code: String,
)

data class RestoreConfirmResp(
    val uid: Long? = null,
    val account: AccountResponse? = null,
    val updatedAt: Long = 0L,
)

data class HistoryReportBody(
    val items: List<HistoryReportItem>,
)

data class HistoryReportItem(
    val target_type: String,
    val target_id: Long,
    val payload: JsonElement?,
)

data class HistoryReportAck(
    val upserted: Int = 0,
    val total: Int = 0,
)

data class HistoryListResponse(
    val uid: Long = 0L,
    val items: List<HistoryEntry> = emptyList(),
    val nextCursor: String? = null,
)

data class HistoryEntry(
    val target_type: String = "",
    val target_id: Long = 0L,
    val viewed_at: Long = 0L,
    val view_count: Int = 0,
    val title: String? = null,
    val name: String? = null,
    val thumb_url: String? = null,
    val payload: JsonElement? = null,
)

data class HistoryDeleteAck(
    val ok: Boolean = false,
    val deleted: Int = 0,
)
