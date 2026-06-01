package ceui.pixiv.download.aria2

import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * Aria2Client 的 JSON-RPC 协议层测试（#692）。
 *
 * 用 MockWebServer 充当 aria2 RPC 端点，验证：
 *   - addUri 的 payload 结构（token / uris / options）符合 aria2 协议
 *   - secret / dir 为空时对应字段正确省略
 *   - RPC error 响应被翻译成带 message 的 IOException
 */
class Aria2ClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: Aria2Client

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = Aria2Client(OkHttpClient())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun rpcUrl(): String = server.url("/jsonrpc").toString()

    private fun enqueueResult(resultJson: String) {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"id":"shaft","jsonrpc":"2.0","result":$resultJson}""")
        )
    }

    // --- addUri payload ------------------------------------------------------

    @Test
    fun `addUri sends token uris and options in aria2 param order`() {
        enqueueResult("\"gid123\"")

        val gid = client.addUri(
            rpcUrl = rpcUrl(),
            secret = "mySecret",
            fileUrl = "https://i.pximg.net/img-original/img/2024/01/01/00/00/00/123_p0.png",
            out = "ShaftImages/title_123_p1.png",
            dir = "/downloads/pixiv",
            headers = listOf("Referer: https://app-api.pixiv.net/"),
        )

        assertEquals("gid123", gid)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        val payload = JsonParser.parseString(recorded.body.readUtf8()).asJsonObject
        assertEquals("aria2.addUri", payload.get("method").asString)
        assertEquals("2.0", payload.get("jsonrpc").asString)

        val params = payload.getAsJsonArray("params")
        assertEquals(3, params.size())
        // param[0]: token
        assertEquals("token:mySecret", params[0].asString)
        // param[1]: uris
        val uris = params[1].asJsonArray
        assertEquals(1, uris.size())
        assertTrue(uris[0].asString.startsWith("https://i.pximg.net/"))
        // param[2]: options
        val options = params[2].asJsonObject
        assertEquals("ShaftImages/title_123_p1.png", options.get("out").asString)
        assertEquals("/downloads/pixiv", options.get("dir").asString)
        val headers = options.getAsJsonArray("header")
        assertEquals("Referer: https://app-api.pixiv.net/", headers[0].asString)
    }

    @Test
    fun `addUri omits token when secret is empty`() {
        enqueueResult("\"gid456\"")

        client.addUri(
            rpcUrl = rpcUrl(),
            secret = "",
            fileUrl = "https://example.com/a.png",
            out = "a.png",
            dir = "",
            headers = emptyList(),
        )

        val payload = JsonParser.parseString(server.takeRequest().body.readUtf8()).asJsonObject
        val params = payload.getAsJsonArray("params")
        // 没有 token 时第一个参数直接是 uris 数组
        assertEquals(2, params.size())
        assertTrue(params[0].isJsonArray)
    }

    @Test
    fun `addUri omits dir option when dir is empty`() {
        enqueueResult("\"gid789\"")

        client.addUri(
            rpcUrl = rpcUrl(),
            secret = "s",
            fileUrl = "https://example.com/a.png",
            out = "a.png",
            dir = "",
            headers = emptyList(),
        )

        val payload = JsonParser.parseString(server.takeRequest().body.readUtf8()).asJsonObject
        val options = payload.getAsJsonArray("params")[2].asJsonObject
        assertFalse(options.has("dir"))
        assertTrue(options.has("out"))
    }

    // --- error handling ------------------------------------------------------

    @Test
    fun `addUri throws IOException with aria2 error message on rpc error`() {
        // aria2 对密钥错误返回 HTTP 401 + JSON-RPC error 结构
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setBody("""{"id":"shaft","jsonrpc":"2.0","error":{"code":1,"message":"Unauthorized"}}""")
        )

        try {
            client.addUri(rpcUrl(), "wrong", "https://example.com/a.png", "a.png", "", emptyList())
            fail("expected IOException")
        } catch (e: IOException) {
            assertEquals("Unauthorized", e.message)
        }
    }

    @Test
    fun `addUri throws IOException on non-json response`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html>not aria2</html>"))

        try {
            client.addUri(rpcUrl(), "", "https://example.com/a.png", "a.png", "", emptyList())
            fail("expected IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("unexpected response"))
        }
    }

    @Test
    fun `addUri throws IOException with http code on plain http error`() {
        server.enqueue(MockResponse().setResponseCode(502).setBody(""))

        try {
            client.addUri(rpcUrl(), "", "https://example.com/a.png", "a.png", "", emptyList())
            fail("expected IOException")
        } catch (e: IOException) {
            assertEquals("HTTP 502", e.message)
        }
    }

    // --- getVersion -----------------------------------------------------------

    @Test
    fun `getVersion returns version string and sends token`() {
        enqueueResult("""{"version":"1.36.0","enabledFeatures":[]}""")

        val version = client.getVersion(rpcUrl(), "mySecret")

        assertEquals("1.36.0", version)
        val payload = JsonParser.parseString(server.takeRequest().body.readUtf8()).asJsonObject
        assertEquals("aria2.getVersion", payload.get("method").asString)
        assertEquals("token:mySecret", payload.getAsJsonArray("params")[0].asString)
    }

    @Test
    fun `getVersion sends empty params without secret`() {
        enqueueResult("""{"version":"1.37.0"}""")

        client.getVersion(rpcUrl(), "")

        val payload = JsonParser.parseString(server.takeRequest().body.readUtf8()).asJsonObject
        assertEquals(0, payload.getAsJsonArray("params").size())
    }
}
