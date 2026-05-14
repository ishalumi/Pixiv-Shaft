package ceui.pixiv.chat.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import ceui.pixiv.chat.api.ChatFrameEncoder
import ceui.pixiv.chat.api.HttpChatHistorySource
import ceui.pixiv.chat.api.ShaftChatWsClient
import ceui.pixiv.chat.api.WsChatMessageStream
import ceui.pixiv.chat.data.ChatDatabase
import ceui.pixiv.chat.data.ChatMessageEntity
import ceui.pixiv.chat.data.RoomChatMessageStore
import ceui.pixiv.websocket.WebSocketClient
import ceui.pixiv.websocket.WebSocketState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber

/**
 * Production [ChatListViewModel] for the shaft-api-v2 chat. Owns a live
 * [WebSocketClient] connection for the lifetime of the ViewModel — matches
 * the integration guide's recommendation "App 进聊天页 start(),退出
 * ViewModel onCleared() stop()" (don't tie to onPause/onResume).
 *
 * The connection is opened in [init] and closed in [onCleared]. Outgoing
 * messages go through [sendText], which serialises a `msg` frame and pushes
 * it across the WS — **no optimistic Room write**: the server broadcasts the
 * message back to every subscriber (including the sender) and the echo is
 * what gets rendered. See `docs/ws-chat-integration.md` §1.3 ("应当以回声为
 * 准而不是 optimistic 渲染").
 */
class ShaftChatListViewModel(
    private val wsClient: WebSocketClient,
    private val wsStream: WsChatMessageStream,
    historySource: HttpChatHistorySource,
    store: RoomChatMessageStore,
) : ChatListViewModel<ChatMessageEntity>(
    threadId = HttpChatHistorySource.GLOBAL_THREAD_ID,
    store = store,
    historySource = historySource,
    stream = wsStream,
) {

    /** Connection state of the underlying [WebSocketClient]. */
    val wsState: StateFlow<WebSocketState> get() = wsClient.state

    /**
     * Server-pushed `hello` frame after every successful handshake. Subscribe
     * if you need to react to "we're really connected now" (the underlying
     * [WebSocketClient.state] flips to `Connected` on the OkHttp `onOpen` —
     * one step *earlier* than the chat-protocol's notion of ready).
     */
    val hello: Flow<*> get() = wsStream.helloFrames

    /** Server `err` frames (rate_limited, frame_too_large, …). Connection stays open. */
    val errors: Flow<*> get() = wsStream.errorFrames

    init {
        wsClient.connect()
    }

    /**
     * Send a text message via the WebSocket. Returns `true` if it made it into
     * the outgoing buffer, `false` if the client is closed/stopped. Note: a
     * `true` return is **not** an ACK from the server — the protocol has no
     * per-message ACK. Use the broadcast echo (your own message coming back
     * through [wsStream]) as the success signal; ~3–5s without echo = retry.
     *
     * Client-side cap is 2048 UTF-16 units per `docs/ws-chat-integration.md`
     * §1.2; longer messages are rejected before sending.
     */
    fun sendText(text: String, illustId: Long? = null): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed.length > MAX_TEXT_LENGTH) {
            Timber.tag(TAG).w("send rejected: text.length=%d > %d", trimmed.length, MAX_TEXT_LENGTH)
            return false
        }
        val frame = ChatFrameEncoder.msg(trimmed, illustId)
        val accepted = wsClient.send(frame)
        if (!accepted) {
            Timber.tag(TAG).w("send rejected by WS client (closed or buffer full)")
        }
        return accepted
    }

    override fun onCleared() {
        try {
            wsClient.close()
        } catch (t: Throwable) {
            Timber.tag(TAG).w(t, "ws close failed")
        }
        super.onCleared()
    }

    companion object {
        private const val TAG = "ShaftChatVM"
        const val MAX_TEXT_LENGTH = 2048

        fun factory(context: android.content.Context): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val appCtx = context.applicationContext
                    val wsClient = ShaftChatWsClient.create(appCtx)
                    val wsStream = WsChatMessageStream(wsClient)
                    val historySource = HttpChatHistorySource(
                        threadId = HttpChatHistorySource.GLOBAL_THREAD_ID,
                    )
                    val store = RoomChatMessageStore(
                        ChatDatabase.getInstance(appCtx).chatMessageDao()
                    )
                    return ShaftChatListViewModel(
                        wsClient = wsClient,
                        wsStream = wsStream,
                        historySource = historySource,
                        store = store,
                    ) as T
                }
            }
    }
}
