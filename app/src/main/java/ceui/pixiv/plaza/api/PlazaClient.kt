package ceui.pixiv.plaza.api

import ceui.lisa.BuildConfig
import ceui.lisa.network.ShaftApiV2Client
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.HttpException
import timber.log.Timber

/**
 * Plaza HTTP 客户端 + 错误处理仓库。
 *
 * 所有写请求(create / delete)在这里统一:
 *   1. 拼 canonical JSON body (key 顺序固定,不能过 Gson)
 *   2. PlazaSig 签 message,得 sig
 *   3. 包 uid/ts/sig + canonical body 成最终 wire body 上送
 *
 * 错误处理走 [PlazaResult],把 HTTP 异常 → 4xx error 字符串 → 业务可读
 * 状态。bad_sig / ts_skew 这种属于客户端 bug,只在 Timber 里 e() 一下方便排查。
 */
object PlazaClient {

    private val api: PlazaApi = ShaftApiV2Client.retrofit.create(PlazaApi::class.java)

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val gson = Gson()

    /**
     * 进程级事件总线 —— 任何路径成功发了帖,所有活的 PlazaViewModel 立即收到
     * 并 prepend 到自己 feed 顶,无需手动 navigate-back-refresh。跟 chat
     * `ShaftChatGateway.incoming` 是同一套 push-based 模式 (2026 best practice
     * for cross-fragment realtime sync over LocalBroadcastManager / setFragmentResult)。
     *
     * - replay=0:新创建的 ViewModel 不会收到上次的事件(下次刷新会包含,避免 stale prepend)
     * - extraBufferCapacity=8:发帖突发(用户连发) suspending emit 时缓冲,避免丢
     */
    private val _postsCreated = MutableSharedFlow<PlazaPost>(
        replay = 0,
        extraBufferCapacity = 8,
    )
    val postsCreated: SharedFlow<PlazaPost> = _postsCreated.asSharedFlow()

    suspend fun listFeed(limit: Int = 20, before: Long? = null): PlazaResult<PlazaFeedResponse> =
        runCatchingPlaza { api.listFeed(limit, before) }

    suspend fun getPost(id: Long): PlazaResult<PlazaPost> =
        runCatchingPlaza { api.getPost(id) }

    suspend fun listUserPosts(
        uid: Long,
        limit: Int = 20,
        before: Long? = null,
    ): PlazaResult<PlazaUserPostsResponse> =
        runCatchingPlaza { api.listUserPosts(uid, limit, before) }

    suspend fun createPost(
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

        // 严格拼 wire body —— 外层 uid/ts/sig 在前 + canonical body 在后,跟 server
        // 的解析路径无关 (server 单独 hash 内部 text/refs 部分),但拼字符串避开 Gson
        // 也能确保 illust 数组顺序与 sig 入参一致。
        val canonicalBody = PlazaSig.canonicalPostBody(text, illust, novel, user)
        val wireBody = buildString {
            append('{')
            append("\"uid\":").append(PlazaSig.jsonEscape(uidStr))
            append(",\"ts\":").append(PlazaSig.jsonEscape(tsStr))
            append(",\"sig\":").append(PlazaSig.jsonEscape(sig))
            // canonical body 是 `{"text":...,"refs":{...}}`,去掉两端 {} 嵌进来
            append(',')
            append(canonicalBody.substring(1, canonicalBody.length - 1))
            append('}')
        }
        val result = runCatchingPlaza { api.createPost(wireBody.toRequestBody(jsonMediaType)) }
        if (result is PlazaResult.Ok) {
            // tryEmit 不挂起 —— extraBufferCapacity=8 兜底,理论不会丢
            _postsCreated.tryEmit(result.value)
        }
        return result
    }

    suspend fun deletePost(uid: Long, postId: Long): PlazaResult<PlazaDeleteResponse> {
        val secret = BuildConfig.SHAFT_EVENTS_HMAC
        val uidStr = uid.toString()
        val tsStr = System.currentTimeMillis().toString()
        val sig = PlazaSig.signDelete(secret, uidStr, tsStr, postId)
        val body = "{\"uid\":\"$uidStr\",\"ts\":\"$tsStr\",\"sig\":\"$sig\"}"
        return runCatchingPlaza { api.deletePost(postId, body.toRequestBody(jsonMediaType)) }
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
            // ViewModelScope cancel (用户切走广场页 / 退出 Compose) 时正常向上冒,
            // 不能转成 Err —— 否则 Fragment 已 detach 还会 trySend Toast。
            throw ce
        } catch (t: Throwable) {
            Timber.tag("Plaza").w(t, "network failure")
            PlazaResult.Err(0, "network", null)
        }
    }
}

sealed class PlazaResult<out T> {
    data class Ok<T>(val value: T) : PlazaResult<T>()
    data class Err(
        val httpStatus: Int,
        val code: String,
        val body: PlazaErrorBody?,
    ) : PlazaResult<Nothing>()
}
