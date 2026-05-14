package ceui.pixiv.chat.api

import ceui.pixiv.chat.core.ChatMessageStream
import ceui.pixiv.chat.data.ChatMessageEntity
import ceui.pixiv.websocket.IncomingMessage
import com.google.gson.Gson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import timber.log.Timber

/**
 * [ChatMessageStream] over a [Flow] of [IncomingMessage] frames. Constructor
 * takes the flow directly instead of a [ceui.pixiv.websocket.WebSocketClient]
 * so the stream can sit on top of either a fragment-owned client or — the
 * production path — [ceui.pixiv.websocket.WebSocketManager.incoming] which
 * auto-switches across reconnects.
 *
 * Filters text frames, decodes via [ChatFrameDecoder], maps `msg` frames to
 * [ChatMessageEntity] using the same field mapping as [HttpChatHistorySource]
 * so HTTP backfill and WS push upsert into Room without dup rows.
 *
 * Side channels for callers that need them:
 *  - [helloFrames] — server-pushed `hello` frame after every successful
 *    handshake (useful for "we're really connected now" UI state and for
 *    resetting reconnect counters per docs §10).
 *  - [errorFrames] — `err` frames (`rate_limited`, `frame_too_large`, …).
 *    Server does NOT close the connection on these — stream stays alive.
 */
class WsChatMessageStream(
    private val incoming: Flow<IncomingMessage>,
    private val threadId: Long = HttpChatHistorySource.GLOBAL_THREAD_ID,
    private val gson: Gson = Gson(),
) : ChatMessageStream<ChatMessageEntity> {

    private val _helloFrames = MutableSharedFlow<ChatFrame.Hello>(
        replay = 1, extraBufferCapacity = 1,
    )
    val helloFrames: Flow<ChatFrame.Hello> get() = _helloFrames

    private val _errorFrames = MutableSharedFlow<ChatFrame.Err>(
        replay = 0, extraBufferCapacity = 8,
    )
    val errorFrames: Flow<ChatFrame.Err> get() = _errorFrames

    override fun observe(threadId: Long): Flow<ChatMessageEntity> =
        incoming
            .filterIsInstance<IncomingMessage.Text>()
            .map { ChatFrameDecoder.decode(it.text) }
            .onEach { frame ->
                // Side-channel routing + visibility logging. Keep the side
                // effect inside the same upstream so ordering (hello → first
                // msg) is preserved upstream of the mapNotNull filter below.
                when (frame) {
                    is ChatFrame.Hello -> {
                        Timber.tag(TAG).i(
                            "⇣ hello name=%s room=%s server_ts=%d",
                            frame.displayName, frame.room, frame.serverTs,
                        )
                        _helloFrames.tryEmit(frame)
                    }
                    is ChatFrame.Msg -> {
                        // INFO-level so it's visible in default logcat alongside
                        // RobustWebSocketClient's "WS" tag. Truncate long text
                        // to keep logs scrollable.
                        Timber.tag(TAG).i(
                            "⇣ msg ts=%d cid=%s name=%s illust=%s text=%s",
                            frame.ts,
                            frame.clientId.take(8),
                            frame.displayName ?: "-",
                            frame.illustId?.toString() ?: "-",
                            frame.text?.take(80) ?: "-",
                        )
                    }
                    is ChatFrame.Err -> {
                        Timber.tag(TAG).w("⇣ err code=%s", frame.code)
                        _errorFrames.tryEmit(frame)
                    }
                    is ChatFrame.Pong -> {
                        // Server's app-level pong (not RFC 6455 pong which OkHttp
                        // handles transparently below). Logging it makes the
                        // app-level heartbeat audit-able if someone sends `{kind:ping}`.
                        Timber.tag(TAG).d("⇣ pong server_ts=%d", frame.serverTs)
                    }
                    is ChatFrame.Unknown -> {
                        Timber.tag(TAG).d("⇣ unknown frame dropped to dead-letter")
                    }
                }
            }
            .mapNotNull { (it as? ChatFrame.Msg)?.let(::toEntity) }

    private fun toEntity(f: ChatFrame.Msg): ChatMessageEntity {
        val ext = mutableMapOf<String, Any?>("client_id" to f.clientId)
        f.displayName?.let { ext["display_name"] = it }
        f.illustId?.let { ext["illust_id"] = it }
        return ChatMessageEntity(
            messageId = f.ts,
            threadId = threadId,
            uid = HttpChatHistorySource.clientIdToUid(f.clientId),
            createdTime = f.ts,
            type = HttpChatHistorySource.TYPE_USER_MESSAGE,
            content = f.text,
            extensions = gson.toJson(ext),
        )
    }

    companion object {
        private const val TAG = "Chat-Stream"
    }
}
