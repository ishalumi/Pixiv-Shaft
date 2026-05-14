package ceui.pixiv.websocket

import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.channels.Channel
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString

/**
 * Test fixture wrapping [MockWebServer] for WebSocket integration tests.
 *
 * Each test typically does:
 *
 * ```
 * val server = TestWebSocketServer().apply { start(); enqueueEchoUpgrade() }
 * ...
 * server.shutdown()
 * ```
 *
 * The fixture exposes:
 *  - [url] — the `ws://` URL the client should connect to
 *  - [serverEvents] — a [Channel] of every event the server-side WebSocket
 *    listener observed (open / message / closing / closed / failure), so
 *    tests can assert on what the server actually saw without flaky sleeps
 *  - [activeSockets] — references to live server-side sockets, used to
 *    inject server-initiated closes / failures from the test
 */
class TestWebSocketServer {

    val server: MockWebServer = MockWebServer()

    /** Stream of every event observed by the server-side WebSocketListener. */
    val serverEvents: Channel<ServerEvent> = Channel(capacity = Channel.UNLIMITED)

    /**
     * Currently-open server-side sockets. Tests can call `.close()` /
     * `.cancel()` on these to inject server-initiated terminations.
     */
    val activeSockets: ConcurrentLinkedQueue<WebSocket> = ConcurrentLinkedQueue()

    sealed class ServerEvent {
        data class Open(val socket: WebSocket) : ServerEvent()
        data class Text(val text: String) : ServerEvent()
        data class Binary(val bytes: ByteString) : ServerEvent()
        data class Closing(val code: Int, val reason: String) : ServerEvent()
        data class Closed(val code: Int, val reason: String) : ServerEvent()
        data class Failure(val throwable: Throwable) : ServerEvent()
    }

    fun start() {
        server.start()
    }

    fun shutdown() {
        try {
            server.shutdown()
        } catch (_: Throwable) {
            // ignore — best-effort cleanup
        }
        serverEvents.close()
    }

    /** Returns the `ws://host:port/` URL clients should connect to. */
    fun url(path: String = "/"): String =
        server.url(path).toString().replace("http://", "ws://")

    /**
     * Enqueue an upgrade with an echoing server. Every text/binary frame the
     * client sends is echoed back unchanged. The fixture also records the
     * frame in [serverEvents] before echoing.
     */
    fun enqueueEchoUpgrade() {
        enqueueUpgrade(object : RecordingListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                super.onMessage(webSocket, text)
                webSocket.send(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                super.onMessage(webSocket, bytes)
                webSocket.send(bytes)
            }
        })
    }

    /**
     * Enqueue an upgrade with a silent server: it accepts the upgrade and
     * records frames but never sends anything back. Useful for testing
     * client → server flow without echo noise.
     */
    fun enqueueSilentUpgrade() {
        enqueueUpgrade(RecordingListener())
    }

    /**
     * Enqueue an upgrade that immediately closes the connection from the
     * server side after receiving the first message. Useful for testing
     * the reconnect path under server-initiated closure.
     */
    fun enqueueClosingUpgrade(code: Int = 1000, reason: String = "test close") {
        enqueueUpgrade(object : RecordingListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                super.onMessage(webSocket, text)
                webSocket.close(code, reason)
            }
        })
    }

    /** Reject the upgrade with [code] (defaults to HTTP 503). */
    fun enqueueRejection(code: Int = 503, body: String = "nope") {
        server.enqueue(MockResponse().setResponseCode(code).setBody(body))
    }

    /**
     * Pass a custom listener if none of the convenience methods fit. The
     * listener is wrapped so that lifecycle events still land in
     * [serverEvents] and [activeSockets].
     */
    fun enqueueUpgrade(delegate: WebSocketListener) {
        server.enqueue(MockResponse().withWebSocketUpgrade(delegate))
    }

    /** Force-close every active server socket with [code]/[reason]. */
    fun closeAll(code: Int = 1011, reason: String = "test forced close") {
        activeSockets.toList().forEach { it.close(code, reason) }
    }

    /**
     * Base [WebSocketListener] that records every event into [serverEvents]
     * and tracks live sockets in [activeSockets]. Subclasses can override to
     * add behavior on top.
     */
    open inner class RecordingListener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            activeSockets.add(webSocket)
            serverEvents.trySend(ServerEvent.Open(webSocket))
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            serverEvents.trySend(ServerEvent.Text(text))
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            serverEvents.trySend(ServerEvent.Binary(bytes))
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            serverEvents.trySend(ServerEvent.Closing(code, reason))
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            activeSockets.remove(webSocket)
            serverEvents.trySend(ServerEvent.Closed(code, reason))
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            activeSockets.remove(webSocket)
            serverEvents.trySend(ServerEvent.Failure(t))
        }
    }
}
