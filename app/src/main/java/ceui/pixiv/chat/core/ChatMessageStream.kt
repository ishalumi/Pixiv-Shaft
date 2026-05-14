package ceui.pixiv.chat.core

import kotlinx.coroutines.flow.Flow

/**
 * Live stream of new messages pushed by the server (typically a
 * WebSocket subscription filtered to one thread).
 *
 * Hot-swappable: production wires the shared
 * [ceui.pixiv.websocket.WebSocketManager.incoming] filtered by
 * `threadId`; tests wire a Channel-backed fake the test controls.
 *
 * The stream **never** writes to the local store — inserting received
 * messages into Room is the ViewModel's / repository's job.
 */
fun interface ChatMessageStream<M> {

    /**
     * Cold flow of incoming messages for [threadId]. The flow should
     * survive reconnects — cancellation only happens when the
     * collector unsubscribes.
     *
     * Implementations backed by a multiplexed transport (one WebSocket
     * serving N conversations) should filter internally and return a
     * thread-scoped sub-stream.
     */
    fun observe(threadId: Long): Flow<M>
}
