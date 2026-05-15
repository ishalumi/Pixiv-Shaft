package ceui.lisa.network

import com.google.gson.JsonObject
import okhttp3.RequestBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.HTTP
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface ShaftApiV2 {

    data class HealthResponse(
        val ok: Boolean,
        val service: String,
        val ts: Long,
        val uptimeSec: Long,
    )

    @GET("health")
    suspend fun health(): HealthResponse

    /** Demo: 路径不存在,服务端返回 404 → Retrofit 抛 [retrofit2.HttpException]。 */
    @GET("does-not-exist")
    suspend fun probe404(): HealthResponse

    /** Demo: /ping 实际返回 200 plain text "pong",硬塞给 Gson 当 HealthResponse 解析必失败 → [com.google.gson.JsonSyntaxException]。 */
    @GET("ping")
    suspend fun pingAsHealth(): HealthResponse

    /**
     * 站长推荐数据源。每个 item 的 `bean` 字段直接就是 Pixiv 端的 IllustsBean（illust /
     * manga）或 NovelBean（novel）JSON，可以丢给 Gson 反序列化复用现成渲染管线。
     *
     * - type: illust | manga | novel
     * - window: day | week | month
     * - sort: score（加权） | bookmark（纯收藏数倒序）
     * - includeMeta=1 时服务端会过滤掉还没有客户端上传过 payload 的 id，保证返回的每个 item 都能渲染
     */
    @GET("api/v1/trending/works")
    suspend fun trendingWorks(
        @Query("type") type: String,
        @Query("window") window: String = "week",
        @Query("limit") limit: Int = 60,
        @Query("sort") sort: String = "bookmark",
        @Query("include_meta") includeMeta: Int = 1,
    ): TrendingWorksResponse

    data class TrendingWorksResponse(
        val type: String,
        val window: String,
        val limit: Int,
        val sort: String,
        val computed_at: Long,
        val items: List<TrendingWorkItem>,
    )

    data class TrendingWorkItem(
        val target_id: Long,
        val bookmark_count: Int,
        val unbookmark_count: Int,
        val download_count: Int,
        val unique_clients: Int,
        val score: Double,
        val computed_at: Long,
        /** 完整 IllustsBean / NovelBean JSON，仅 include_meta=1 时存在。 */
        val bean: JsonObject?,
    )

    /**
     * 当前客户端自己的操作日志。client_id 是本地生成的 sha256(UUID)，只能查到自己的事件。
     * - eventType: null=全部；bookmark / unbookmark / download / follow / unfollow
     * - before: 上一页最后一条的 id（服务端按 id DESC 排），首页传 null
     * 每条 item.meta 直接是当时上报的 IllustsBean / NovelBean / UserBean JSON。
     */
    @GET("api/v1/events/history")
    suspend fun eventsHistory(
        @Query("client_id") clientId: String,
        @Query("limit") limit: Int = 50,
        @Query("event_type") eventType: String? = null,
        @Query("before") before: Long? = null,
    ): EventsHistoryResponse

    data class EventsHistoryResponse(
        val client_id: String,
        val limit: Int,
        val event_type: String?,
        val items: List<EventHistoryItem>,
        val next_before: Long?,
    )

    data class EventHistoryItem(
        val id: Long,
        val ts: Long,
        val event_type: String,
        val target_type: String,
        val target_id: Long,
        val platform: String?,
        val channel: String?,
        val app_version: String?,
        val meta: JsonObject?,
    )

    // ── Plaza ─────────────────────────────────────────────────────────────────
    // 注意:write 请求 body 必须保持 canonical 形态(text/refs key 顺序固定 + 无空格,
    // 见 docs/shaft-plaza-api-android.md §1)。所以这里收 RequestBody 而不是 DTO
    // —— 让 [ShaftApiV2Client] 手拼 canonical wire body 之后传进来,避免 Gson 介入。
    // 高层入口 (含签名 / cache / SharedFlow) 见 [ShaftApiV2Client]。

    @POST("api/v1/plaza/posts")
    suspend fun createPlazaPost(@Body body: RequestBody): PlazaPost

    @GET("api/v1/plaza/posts")
    suspend fun listPlazaPosts(
        @Query("limit") limit: Int = 20,
        @Query("before") before: Long? = null,
        @Query("viewer_uid") viewerUid: String? = null,
        @Query("viewer_ts") viewerTs: String? = null,
        @Query("viewer_sig") viewerSig: String? = null,
    ): PlazaFeedResponse

    @GET("api/v1/plaza/posts/{id}")
    suspend fun getPlazaPost(
        @Path("id") id: Long,
        @Query("viewer_uid") viewerUid: String? = null,
        @Query("viewer_ts") viewerTs: String? = null,
        @Query("viewer_sig") viewerSig: String? = null,
    ): PlazaPost

    /** Retrofit 默认不允许 DELETE 带 body,用 @HTTP 强制开放;body 需要 Content-Type: application/json。 */
    @HTTP(method = "DELETE", path = "api/v1/plaza/posts/{id}", hasBody = true)
    suspend fun deletePlazaPost(
        @Path("id") id: Long,
        @Body body: RequestBody,
    ): PlazaDeleteResponse

    @GET("api/v1/plaza/users/{uid}/posts")
    suspend fun listUserPlazaPosts(
        @Path("uid") uid: Long,
        @Query("limit") limit: Int = 20,
        @Query("before") before: Long? = null,
        @Query("viewer_uid") viewerUid: String? = null,
        @Query("viewer_ts") viewerTs: String? = null,
        @Query("viewer_sig") viewerSig: String? = null,
    ): PlazaUserPostsResponse

    /**
     * "我的点赞" 列表。HMAC 鉴权 —— path uid 必须 == sig uid,所以只能拉
     * 自己的列表(server 强制)。cursor `before` 是 **like_id** 不是 post.id。
     * 每个 item 额外带 `liked_at` 毫秒时间戳。
     */
    @GET("api/v1/plaza/users/{uid}/likes")
    suspend fun listMyPlazaLikes(
        @Path("uid") uid: Long,
        @Query("ts") ts: String,
        @Query("sig") sig: String,
        @Query("limit") limit: Int = 20,
        @Query("before") before: Long? = null,
    ): PlazaLikesResponse

    // ── Likes / Comments ─────────────────────────────────────────────────
    // 同样 write 请求要 canonical body,wire 由 ShaftApiV2Client 手拼。

    @POST("api/v1/plaza/posts/{id}/like")
    suspend fun likePlazaPost(
        @Path("id") id: Long,
        @Body body: RequestBody,
    ): PlazaLikeResponse

    @HTTP(method = "DELETE", path = "api/v1/plaza/posts/{id}/like", hasBody = true)
    suspend fun unlikePlazaPost(
        @Path("id") id: Long,
        @Body body: RequestBody,
    ): PlazaLikeResponse

    @POST("api/v1/plaza/posts/{id}/comments")
    suspend fun createPlazaComment(
        @Path("id") id: Long,
        @Body body: RequestBody,
    ): PlazaComment

    @GET("api/v1/plaza/posts/{id}/comments")
    suspend fun listPlazaComments(
        @Path("id") id: Long,
        @Query("limit") limit: Int = 20,
        @Query("before") before: Long? = null,
    ): PlazaCommentsResponse

    @HTTP(method = "DELETE", path = "api/v1/plaza/comments/{cid}", hasBody = true)
    suspend fun deletePlazaComment(
        @Path("cid") cid: Long,
        @Body body: RequestBody,
    ): PlazaDeleteResponse
}
