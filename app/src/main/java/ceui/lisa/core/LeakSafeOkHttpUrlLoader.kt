package ceui.lisa.core

import com.bumptech.glide.Priority
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.HttpException
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.data.DataFetcher
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.ModelLoader
import com.bumptech.glide.load.model.ModelLoaderFactory
import com.bumptech.glide.load.model.MultiModelLoaderFactory
import com.bumptech.glide.util.ContentLengthInputStream
import okhttp3.Call
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * 官方 okhttp3-integration `OkHttpUrlLoader` 的替代品，修复其 fetcher 的连接泄漏
 * （Glide 4.16 与 5.0-rc01 的 OkHttpStreamFetcher 完全相同，均未修）：
 *
 * 上游 bug 链：
 * 1. `OkHttpStreamFetcher.onResponse` 把打开的 response body 包成流交给引擎；
 *    远端图片默认可写盘缓存（DiskCacheStrategy.AUTOMATIC），流被暂存进
 *    `SourceGenerator.dataToCache` 并 reschedule 回 Glide 线程等待写缓存；
 * 2. 列表快速滑动把请求 clear 掉：`DecodeJob.cancel` 只调 `fetcher.cancel()`
 *    —— call 已经完成，`call.cancel()` 是 no-op，没有任何人 cleanup；
 * 3. 重新入队的 `DecodeJob.run` 看到 isCancelled 直接 notifyFailed 返回，
 *    finally 里的 currentFetcher 尚未赋值 —— dataToCache 里那条打开的 body
 *    被永久遗弃，GC 后 OkHttp 报 "A connection to https://i.pximg.net/ was leaked"。
 *
 * 修复：[LeakSafeOkHttpStreamFetcher.cancel] 时把已到达的 stream/body 一并关掉。
 * 取消后的加载结果本来就会被整体丢弃；极小概率 cancel 撞上写盘缓存正在读流，
 * 也只是让这次（反正已取消的）写入失败，DecodeJob 对失败路径全兜底。
 */
class LeakSafeOkHttpUrlLoader(private val client: Call.Factory) : ModelLoader<GlideUrl, InputStream> {

    override fun handles(model: GlideUrl): Boolean = true

    override fun buildLoadData(
        model: GlideUrl,
        width: Int,
        height: Int,
        options: Options,
    ): ModelLoader.LoadData<InputStream> {
        // fetcher 只拿纯字符串 url + headers，不依赖 GlideUrl（方便纯 JVM 单测）
        return ModelLoader.LoadData(model, LeakSafeOkHttpStreamFetcher(client, model.toStringUrl(), model.headers))
    }

    class Factory(private val client: Call.Factory) : ModelLoaderFactory<GlideUrl, InputStream> {

        override fun build(multiFactory: MultiModelLoaderFactory): ModelLoader<GlideUrl, InputStream> {
            return LeakSafeOkHttpUrlLoader(client)
        }

        override fun teardown() = Unit
    }
}

/**
 * 与上游 OkHttpStreamFetcher 行为对齐（含 [ContentLengthInputStream] 截断检测），
 * 仅额外做两件事：
 * - [cancel] 关闭已到达的 stream/body（见 [LeakSafeOkHttpUrlLoader] 的 bug 链说明），
 *   关流在 [cleanupExecutor] 后台线程做——[cancel] 由 Glide.clear() 在主线程触发，
 *   而关 OkHttp body 会读/写 socket（见 [cancel]），主线程直接关会 NetworkOnMainThreadException；
 * - cancel 之后才送达的响应直接关掉并按失败上报，不再把流交给已取消的引擎。
 */
class LeakSafeOkHttpStreamFetcher(
    private val client: Call.Factory,
    private val url: String,
    private val headers: Map<String, String>,
    // 关流需读/写 socket，只能后台执行；单测注入同线程 executor 保持关闭后可立即断言。
    private val cleanupExecutor: Executor = CLEANUP_EXECUTOR,
) : DataFetcher<InputStream>, okhttp3.Callback {

    private val lock = Any()

    /** guarded by [lock]（cancel 来自主线程，onResponse/cleanup 在 OkHttp / Glide 线程） */
    private var stream: InputStream? = null
    private var responseBody: ResponseBody? = null

    @Volatile
    private var callback: DataFetcher.DataCallback<in InputStream>? = null

    @Volatile
    private var call: Call? = null

    @Volatile
    private var isCancelled = false

    override fun loadData(priority: Priority, callback: DataFetcher.DataCallback<in InputStream>) {
        val requestBuilder = Request.Builder().url(url)
        for ((key, value) in headers) {
            requestBuilder.addHeader(key, value)
        }
        this.callback = callback
        val newCall = client.newCall(requestBuilder.build())
        call = newCall
        newCall.enqueue(this)
    }

    override fun onFailure(call: Call, e: IOException) {
        callback?.onLoadFailed(e)
    }

    override fun onResponse(call: Call, response: Response) {
        val localStream: InputStream?
        synchronized(lock) {
            responseBody = response.body
            if (isCancelled) {
                // cancel 与响应送达赛跑输了半步：引擎已放弃这次加载，流交出去
                // 也没人 cleanup（上游泄漏点），这里直接关掉。
                closeLocked()
                callback?.onLoadFailed(IOException("Request cancelled: $url"))
                return
            }
            localStream = if (response.isSuccessful) {
                val body = checkNotNull(responseBody)
                ContentLengthInputStream.obtain(body.byteStream(), body.contentLength()).also {
                    stream = it
                }
            } else {
                null
            }
        }
        if (localStream != null) {
            callback?.onDataReady(localStream)
        } else {
            callback?.onLoadFailed(HttpException(response.message, response.code))
        }
    }

    override fun cleanup() {
        synchronized(lock) {
            closeLocked()
        }
        callback = null
    }

    override fun cancel() {
        isCancelled = true
        // call.cancel() 是纯粹的 socket close（HTTP/2 走 closeLater），不触发主线程网络守卫，留在原线程。
        call?.cancel()
        // 上游遗漏的关键一步：响应已完整到达后取消，call.cancel() 是 no-op，
        // 暂存在 SourceGenerator.dataToCache 里的流再无人问津 —— 关闭它归还连接。
        // 但关 body 会 drain 剩余字节（HTTP/1）或发 RST_STREAM（HTTP/2）以复用/释放连接，都是 socket I/O；
        // cancel() 由 Glide.clear() 在主线程调用，直接关会抛 NetworkOnMainThreadException，故挪后台关。
        cleanupExecutor.execute {
            synchronized(lock) {
                closeLocked()
            }
        }
    }

    private fun closeLocked() {
        try {
            stream?.close()
        } catch (_: IOException) {
            // Ignored
        }
        responseBody?.close()
        stream = null
        responseBody = null
    }

    override fun getDataClass(): Class<InputStream> = InputStream::class.java

    override fun getDataSource(): DataSource = DataSource.REMOTE

    companion object {
        /**
         * cancel() 的后台关流线程池：cached 池吸收 fling 时的取消风暴、空闲即回收，
         * daemon 线程不拖住 JVM 退出。全 app 共享一份（loader 无状态、每次加载新建 fetcher）。
         */
        private val CLEANUP_EXECUTOR: Executor = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "glide-cancel-cleanup").apply { isDaemon = true }
        }
    }
}
