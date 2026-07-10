package ceui.lisa.core

import com.bumptech.glide.Priority
import com.bumptech.glide.load.data.DataFetcher
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * [LeakSafeOkHttpStreamFetcher] 的行为验证，核心是上游 OkHttpStreamFetcher 的泄漏场景：
 * 响应到达后流被 Glide 引擎暂存（SourceGenerator.dataToCache），随后请求被取消
 * —— 上游没有任何人关闭 body（OkHttp 报 "connection was leaked"），本实现必须在
 * cancel() 里就地关闭。
 */
class LeakSafeOkHttpStreamFetcherTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OkHttpClient()
    }

    @After
    fun tearDown() {
        server.shutdown()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    private fun newFetcher(): LeakSafeOkHttpStreamFetcher {
        return LeakSafeOkHttpStreamFetcher(
            client,
            server.url("/img/test.jpg").toString(),
            mapOf("Referer" to "https://app-api.pixiv.net/"),
        )
    }

    /** 模拟 SourceGenerator：拿到流只暂存不消费（dataToCache 的 park 行为）。 */
    private class ParkingCallback : DataFetcher.DataCallback<InputStream> {
        val ready = CountDownLatch(1)
        var stream: InputStream? = null
        var error: Exception? = null

        override fun onDataReady(data: InputStream?) {
            stream = data
            ready.countDown()
        }

        override fun onLoadFailed(e: Exception) {
            error = e
            ready.countDown()
        }
    }

    @Test
    fun `正常加载 - 送达的流可读出完整 body 且带请求头`() {
        server.enqueue(MockResponse().setBody("hello pixiv"))
        val fetcher = newFetcher()
        val callback = ParkingCallback()

        fetcher.loadData(Priority.NORMAL, callback)
        assertTrue(callback.ready.await(5, TimeUnit.SECONDS))

        assertNull(callback.error)
        val bytes = checkNotNull(callback.stream).readBytes()
        assertEquals("hello pixiv", String(bytes))
        assertEquals("https://app-api.pixiv.net/", server.takeRequest().getHeader("Referer"))
        fetcher.cleanup()
    }

    /**
     * 泄漏复现场景：响应已到达、流被引擎暂存，随后 cancel。
     * 上游实现此时无人关 body（连接被 GC 后 OkHttp 报 leak），
     * 本实现必须在 cancel() 里关闭 —— 关闭后的流再读直接抛异常。
     */
    @Test
    fun `响应到达后取消 - body 被关闭不泄漏连接`() {
        server.enqueue(MockResponse().setBody("0123456789"))
        val fetcher = newFetcher()
        val callback = ParkingCallback()

        fetcher.loadData(Priority.NORMAL, callback)
        assertTrue(callback.ready.await(5, TimeUnit.SECONDS))
        val stream = checkNotNull(callback.stream)

        // 引擎视角：数据 park 在 dataToCache，还没人读 —— 此刻请求被 clear
        fetcher.cancel()

        val readAfterCancel = runCatching { stream.read() }
        assertTrue(
            "cancel 后流必须已被关闭（读取应抛异常），否则连接泄漏",
            readAfterCancel.isFailure,
        )
    }

    @Test
    fun `响应到达后正常 cleanup - 幂等且不影响 cancel 再次关闭`() {
        server.enqueue(MockResponse().setBody("abc"))
        val fetcher = newFetcher()
        val callback = ParkingCallback()

        fetcher.loadData(Priority.NORMAL, callback)
        assertTrue(callback.ready.await(5, TimeUnit.SECONDS))
        checkNotNull(callback.stream).readBytes()

        // cleanup / cancel 以任意顺序重复调用都不该抛异常
        fetcher.cleanup()
        fetcher.cancel()
        fetcher.cleanup()
    }

    @Test
    fun `请求中途取消 - 以失败上报且不挂起`() {
        // 不 enqueue 响应：MockWebServer 挂住请求，模拟慢网络下的取消
        val fetcher = newFetcher()
        val callback = ParkingCallback()

        fetcher.loadData(Priority.NORMAL, callback)
        fetcher.cancel()

        assertTrue(callback.ready.await(5, TimeUnit.SECONDS))
        assertNull(callback.stream)
        assertNotNull(callback.error)
    }

    @Test
    fun `HTTP 错误码 - 按失败上报`() {
        server.enqueue(MockResponse().setResponseCode(404).setBody("not found"))
        val fetcher = newFetcher()
        val callback = ParkingCallback()

        fetcher.loadData(Priority.NORMAL, callback)
        assertTrue(callback.ready.await(5, TimeUnit.SECONDS))

        assertNull(callback.stream)
        assertNotNull(callback.error)
        fetcher.cleanup()
    }
}
