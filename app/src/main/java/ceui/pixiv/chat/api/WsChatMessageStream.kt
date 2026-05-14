package ceui.pixiv.chat.api

import ceui.pixiv.chat.core.ChatMessageStream
import ceui.pixiv.chat.data.ChatMessageEntity
import ceui.pixiv.websocket.IncomingMessage
import ceui.pixiv.websocket.WebSocketClient
import com.google.gson.Gson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import timber.log.Timber

/**
 * [ChatMessageStream] backed by a shaft-api-v2 WebSocket connection.
 *
 * Filters [WebSocketClient.incoming] for `msg` frames, decodes them via
 * [ChatFrameDecoder], and maps to [ChatMessageEntity] using the same
 * field mapping as [HttpChatHistorySource] so HTTP backfill and WS push
 * upsert into Room without producing duplicate rows.
 *
 * Side channels exposed for callers that need them:
 *  - [helloFrames] — server-pushed `hello` frame after every successful
 *    handshake; useful for "show display name" / "we're really connected
 *    now" UI states.
 *  - [errorFrames] — `err` frames (rate-limit, bad-request, etc.). Server
 *    does NOT close the connection on these, so the stream stays alive
 *    and the caller chooses how to surface them in the UI.
 */
class WsChatMessageStream(
    private val client: WebSocketClient,
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
        client.incoming
            .filterIsInstance<IncomingMessage.Text>()
            .map { ChatFrameDecoder.decode(it.text) }
            .onEach { frame ->
                // Route side-channel frames into their dedicated flows so the
                // emitter ordering (hello → first msg) is preserved upstream
                // of the mapNotNull filter.
                when (frame) {
                    is ChatFrame.Hello -> {
                        Timber.tag(TAG).i(
                            "HELLO received: name=%s room=%s",
                            frame.displayName, frame.room,
                        )
                        _helloFrames.tryEmit(frame)
                    }
                    is ChatFrame.Err -> {
                        Timber.tag(TAG).w("err.%s", frame.code)
                        _errorFrames.tryEmit(frame)
                    }
                    else -> Unit
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
        private const val TAG = "ChatStream"
    }
}
