package ceui.pixiv.plaza.api

import okhttp3.RequestBody
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.HTTP
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Retrofit 接口。base URL = `BuildConfig.SHAFT_EVENTS_BASE_URL` + `/api/v1/plaza/...`,
 * 走 [ceui.lisa.network.ShaftApiV2Client.retrofit]。
 *
 * **注意 POST/DELETE 用 `RequestBody`** 而不是 DTO —— 因为请求体必须跟
 * canonical body 逐字节一致用于签名,过 Gson 序列化会丢 key 顺序。Repository
 * 层用 [PlazaSig.canonicalPostBody] 拼好 + 外面包 uid/ts/sig 后手送。
 */
interface PlazaApi {

    @retrofit2.http.POST("/api/v1/plaza/posts")
    suspend fun createPost(@Body body: RequestBody): PlazaPost

    @GET("/api/v1/plaza/posts")
    suspend fun listFeed(
        @Query("limit") limit: Int = 20,
        @Query("before") before: Long? = null,
    ): PlazaFeedResponse

    @GET("/api/v1/plaza/posts/{id}")
    suspend fun getPost(@Path("id") id: Long): PlazaPost

    /**
     * Retrofit 默认不允许 DELETE 带 body,这里用 @HTTP 强制开放。
     * server 要求 Content-Type: application/json,Repository 拼 RequestBody 时带上。
     */
    @HTTP(method = "DELETE", path = "/api/v1/plaza/posts/{id}", hasBody = true)
    suspend fun deletePost(
        @Path("id") id: Long,
        @Body body: RequestBody,
    ): PlazaDeleteResponse

    @GET("/api/v1/plaza/users/{uid}/posts")
    suspend fun listUserPosts(
        @Path("uid") uid: Long,
        @Query("limit") limit: Int = 20,
        @Query("before") before: Long? = null,
    ): PlazaUserPostsResponse
}
