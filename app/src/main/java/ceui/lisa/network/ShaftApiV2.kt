package ceui.lisa.network

import com.google.gson.JsonObject
import retrofit2.http.GET
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
}
