package ceui.loxia

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface PixivWebApi {

    //

    @GET("/rpc/index.php?mode=latest_message_threads2&num=10&offset=0")
    suspend fun getMessageList()

    @GET("/ajax/illust/{illust_id}")
    suspend fun getWebIllust(@Path("illust_id") illust_id: Long)

    /**
     * 每一 P 的真实原图宽高(app-api 的 meta_pages 只给 image_urls、不带宽高)。详情页多 P 用它在
     * 下载前就把后续页展示高度摆准,消除首帧「兜底高→自然高」的跳。需要网页 cookie;缺失时接口
     * 会 error/403,调用方静默降级到图片就绪后的异步定高。见 IllustAdapter.seedPageDimensions。
     */
    @GET("/ajax/illust/{illust_id}/pages?lang=zh")
    suspend fun getIllustPages(
        @Path("illust_id") illust_id: Long,
    ): WebResponse<List<WebIllustPage>>

    @GET("/ajax/tags/frequent/illust")
    suspend fun getFrequentTags(
        @Query("ids[]") ids: List<Long>,
        @Query("lang") lang: String = "zh",
    ): WebResponse<List<FrequentTag>>

    @GET("/ajax/user/{user_id}")
    suspend fun getWebUserDetail(
        @Path("user_id") userId: Long,
        @Query("full") full: Int = 1,
        @Query("lang") lang: String = "zh",
    ): WebResponse<WebUserDetail>

    @GET("/ajax/top/{type}?mode=all&lang=zh")
    suspend fun getSquareContents(
        @Path("type") type: String,
    ): SquareResponse


    @GET("/touch/ajax/user/bookmarks?p=1&lang=zh&version=eb51bf32f166e48a193f081b66211ef5cc643d6e")
    suspend fun getBookmarkedIllust(
        @Query("id") id: Long,
        @Query("type") type: String,
        @Query("rest") rest: String,
    ): SquareResponse

    @GET("/touch/ajax/user/related?p=1&lang=zh&version=eb51bf32f166e48a193f081b66211ef5cc643d6e")
    suspend fun getRelatedUsers(
        @Query("id") id: Long,
        @Query("type") type: String,
        @Query("rest") rest: String,
    ): SquareResponse

    @GET("/touch/ajax/recommender/top_items?mode=safe&lang=zh")
    suspend fun getMessageListBBBB()


    @GET("/touch/ajax/search/illusts?include_meta=1&type=all&csw=0&s_mode=s_tag_full&lang=zh&version=eb51bf32f166e48a193f081b66211ef5cc643d6e")
    suspend fun getCircleDetail(
        @Query("word") word: String,
    ): CircleResponse

    @POST("/ajax/street/v2/main")
    suspend fun getStreetMain(
        @Header("x-csrf-token") csrfToken: String,
        @Body request: StreetRequest,
    ): StreetResponse

    /** 网页版小说详情「相关作品」：/ajax/novel/{id}/recommend/init */
    @GET("/ajax/novel/{novel_id}/recommend/init")
    suspend fun getNovelRecommend(
        @Path("novel_id") novelId: Long,
        @Query("limit") limit: Int = 18,
        @Query("lang") lang: String = "zh",
    ): WebResponse<NovelRecommendBody>

}
