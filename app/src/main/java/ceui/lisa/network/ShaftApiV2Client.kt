package ceui.lisa.network

import ceui.lisa.BuildConfig
import com.google.gson.Gson
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import timber.log.Timber

/**
 * 统一的 shaft-api-v2 客户端单例。
 *
 * 包括:
 *   - Retrofit / OkHttp 基建
 *   - 高层入口 (trending / events / plaza)
 *   - plaza 写请求的 HMAC 签名 + canonical wire body 拼装(spec 见
 *     docs/shaft-plaza-api-android.md §1)
 *   - plaza posts 内存 cache (详情页 fallback,见 [cachedPlazaPost])
 *   - plaza 事件总线 ([plazaPostsCreated] / [plazaPostsDeleted] SharedFlow,
 *     跨 ViewModel 同步无需 setFragmentResult / LocalBroadcast)
 */
object ShaftApiV2Client {

    // Same server as ShaftEventsClient — read endpoints (trending / health) ride
    // the SHAFT_EVENTS_BASE_URL gradle property so custom builds pointing at a
    // private server work without two separate overrides.
    @JvmField val BASE_URL: String = run {
        val raw = BuildConfig.SHAFT_EVENTS_BASE_URL
        if (raw.endsWith('/')) raw else "$raw/"
    }

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .addInterceptor(
            HttpLoggingInterceptor { Timber.tag("ShaftApiV2").i(it) }
                .apply {
                    level = if (BuildConfig.IS_DEBUG_MODE)
                        HttpLoggingInterceptor.Level.BODY
                    else HttpLoggingInterceptor.Level.BASIC
                }
        )
        .build()

    val retrofit: Retrofit = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(httpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    val service: ShaftApiV2 = retrofit.create(ShaftApiV2::class.java)

    // ── Plaza state ───────────────────────────────────────────────────────────

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val gson = Gson()

    /**
     * 进程级事件总线 —— 任何路径(feed / detail / 分享深链)成功发了帖,所有
     * 活的 PlazaViewModel 立即收到并 prepend 到自己 feed 顶,无需手动 reload。
     * 跟 chat ShaftChatGateway.incoming 同一套 push-based 模式。
     *
     * - replay=0:新创建的 ViewModel 不会收到上次的事件 (下次刷新会包含,避免 stale)
     * - extraBufferCapacity=8:连发突发时 suspending emit 缓冲
     */
    private val _plazaPostsCreated = MutableSharedFlow<PlazaPost>(
        replay = 0,
        extraBufferCapacity = 8,
    )
    val plazaPostsCreated: SharedFlow<PlazaPost> = _plazaPostsCreated.asSharedFlow()

    /** 删帖事件总线。任何路径成功删一条 / server 404 都广播,VM 同步 filter。 */
    private val _plazaPostsDeleted = MutableSharedFlow<Long>(
        replay = 0,
        extraBufferCapacity = 8,
    )
    val plazaPostsDeleted: SharedFlow<Long> = _plazaPostsDeleted.asSharedFlow()

    /**
     * 进程级内存缓存。list/get/create 自动填充,详情页 / 深链可以先从这里
     * 拿快照避免空白闪烁。process death 自然清空,单进程量级有限,不做 LRU 限制。
     */
    private val plazaPostCache = ConcurrentHashMap<Long, PlazaPost>()

    fun cachedPlazaPost(id: Long): PlazaPost? = plazaPostCache[id]

    private fun cachePlazaPost(post: PlazaPost) { plazaPostCache[post.id] = post }
    private fun cachePlazaPosts(posts: List<PlazaPost>) { posts.forEach(::cachePlazaPost) }

    // ── Plaza high-level entrypoints ─────────────────────────────────────────

    suspend fun listPlazaFeed(limit: Int = 20, before: Long? = null): PlazaResult<PlazaFeedResponse> {
        val r = runCatchingPlaza { service.listPlazaPosts(limit, before) }
        if (r is PlazaResult.Ok) cachePlazaPosts(r.value.items)
        return r
    }

    suspend fun getPlazaPost(id: Long): PlazaResult<PlazaPost> {
        val r = runCatchingPlaza { service.getPlazaPost(id) }
        if (r is PlazaResult.Ok) cachePlazaPost(r.value)
        return r
    }

    suspend fun listUserPlazaPosts(
        uid: Long,
        limit: Int = 20,
        before: Long? = null,
    ): PlazaResult<PlazaUserPostsResponse> {
        val r = runCatchingPlaza { service.listUserPlazaPosts(uid, limit, before) }
        if (r is PlazaResult.Ok) cachePlazaPosts(r.value.items)
        return r
    }

    suspend fun createPlazaPost(
        uid: Long,
        text: String,
        illust: List<Long> = emptyList(),
        novel: List<Long> = emptyList(),
        user: List<Long> = emptyList(),
    ): PlazaResult<PlazaPost> {
        val secret = BuildConfig.SHAFT_EVENTS_HMAC
        val uidStr = uid.toString()
        val tsStr = System.currentTimeMillis().toString()
        val sig = PlazaSig.signPost(secret, uidStr, tsStr, text, illust, novel, user)

        // 严格拼 wire body。canonical body 跟 sig 是绑定的,不能过 Gson(key 顺序
        // 由 hash table 决定 → bodyHash mismatch → 401 bad_sig)。
        val canonicalBody = PlazaSig.canonicalPostBody(text, illust, novel, user)
        val wireBody = buildString {
            append('{')
            append("\"uid\":").append(PlazaSig.jsonEscape(uidStr))
            append(",\"ts\":").append(PlazaSig.jsonEscape(tsStr))
            append(",\"sig\":").append(PlazaSig.jsonEscape(sig))
            // canonical body 形如 `{"text":...,"refs":{...}}`,去掉外层 {} 嵌进来
            append(',')
            append(canonicalBody.substring(1, canonicalBody.length - 1))
            append('}')
        }
        val result = runCatchingPlaza { service.createPlazaPost(wireBody.toRequestBody(jsonMediaType)) }
        if (result is PlazaResult.Ok) {
            cachePlazaPost(result.value)
            _plazaPostsCreated.tryEmit(result.value)
        }
        return result
    }

    suspend fun deletePlazaPost(uid: Long, postId: Long): PlazaResult<PlazaDeleteResponse> {
        val secret = BuildConfig.SHAFT_EVENTS_HMAC
        val uidStr = uid.toString()
        val tsStr = System.currentTimeMillis().toString()
        val sig = PlazaSig.signDelete(secret, uidStr, tsStr, postId)
        val body = "{\"uid\":\"$uidStr\",\"ts\":\"$tsStr\",\"sig\":\"$sig\"}"
        val r = runCatchingPlaza { service.deletePlazaPost(postId, body.toRequestBody(jsonMediaType)) }
        // server 200 / 404 (not_found 防 enumeration) 都视为"已经不在了"。
        val isGone = r is PlazaResult.Ok || (r is PlazaResult.Err && r.code == "not_found")
        if (isGone) {
            plazaPostCache.remove(postId)
            _plazaPostsDeleted.tryEmit(postId)
        }
        return r
    }

    private suspend fun <T> runCatchingPlaza(block: suspend () -> T): PlazaResult<T> {
        return try {
            PlazaResult.Ok(block())
        } catch (e: HttpException) {
            val raw = try { e.response()?.errorBody()?.string() } catch (_: Throwable) { null }
            val parsed = raw?.let {
                try { gson.fromJson(it, PlazaErrorBody::class.java) } catch (_: Throwable) { null }
            }
            Timber.tag("Plaza").w("http %d %s err=%s detail=%s", e.code(), e.message(), parsed?.error, parsed?.detail)
            PlazaResult.Err(e.code(), parsed?.error ?: "http_${e.code()}", parsed)
        } catch (ce: kotlinx.coroutines.CancellationException) {
            // ViewModelScope cancel 时正常向上冒,不能转成 Err —— 否则
            // Fragment 已 detach 还会 trySend Toast。
            throw ce
        } catch (t: Throwable) {
            Timber.tag("Plaza").w(t, "network failure")
            PlazaResult.Err(0, "network", null)
        }
    }
}
