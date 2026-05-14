package ceui.pixiv.chat.core

import kotlinx.coroutines.flow.Flow

/**
 * Live stream of new messages pushed by the server (a WebSocket connection
 * shared with the app, see [ceui.pixiv.websocket.WebSocketManager.incoming]).
 *
 * Hot-swappable: production wires
 * [ceui.pixiv.chat.api.WsChatMessageStream]; tests wire a Channel-backed
 * fake the test controls.
 *
 * The stream **never** writes to the local store — inserting received
 * messages into Room is the ViewModel's / repository's job.
 */
fun interface ChatMessageStream<M> {

    /**
     * Cold flow of incoming messages whose server-stamped `room` matches
     * [room]. The flow survives reconnects — cancellation only happens when
     * the collector unsubscribes.
     *
     * @param room `"global"` for public broadcasts, or a decimal uint64
     *   string (≤ 20 chars) for a 1v1 thread.
     */
    fun observe(room: String): Flow<M>
}
