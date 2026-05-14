package ceui.pixiv.chat.core

import kotlinx.coroutines.flow.Flow

/**
 * Local source of truth for chat messages. The UI observes this and
 * **only** this — remote syncs write INTO the store, never bypass it.
 *
 * Hot-swappable: production wires a Room-backed implementation; tests
 * wire an in-memory fake. The ViewModel and Fragment never know which
 * one they're talking to.
 *
 * ## Idempotency
 *
 * [upsert] must be idempotent on message identity: inserting the same
 * message twice (e.g. from both the API page and a WS replay after
 * reconnect) must not produce duplicates. The default Room binding
 * uses `@Insert(onConflict = REPLACE)` on the message ID primary key.
 *
 * ## Ordering
 *
 * [observe] returns messages **newest-first** (descending timestamp).
 * Combined with `reverseLayout = true` on the RecyclerView, position 0
 * sits at the bottom of the screen — the natural chat orientation.
 */
interface ChatMessageStore<M> {

    /**
     * Reactive window of the most recent [limit] messages in a thread.
     * Re-emits whenever any message in the window changes (insert,
     * update, delete).
     */
    fun observe(threadId: Long, limit: Int): Flow<List<M>>

    /**
     * Idempotent insert-or-replace. Used by both the API history
     * fetcher and the WS live stream.
     */
    suspend fun upsert(messages: List<M>)

    /** Number of locally stored messages for a thread. */
    suspend fun countByThread(threadId: Long): Int

    /** Delete all messages for a thread (e.g. on cache clear). */
    suspend fun deleteByThread(threadId: Long)
}
