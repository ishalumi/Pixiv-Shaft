package ceui.pixiv.chat.core

import kotlinx.coroutines.flow.Flow

/**
 * Local source of truth for chat messages. The UI observes this and
 * **only** this — remote syncs write INTO the store, never bypass it.
 *
 * Hot-swappable: production wires a Room-backed implementation; tests
 * wire an in-memory fake.
 *
 * ## Idempotency / UPSERT
 *
 * [upsert] must be idempotent on a stable client-derived key (per doc
 * §4 and §9.2 — `localKey = clientMsgId ?? "server:$serverId"`). Inserting
 * the same message twice (broker may broadcast the same `client_msg_id`
 * across retries before its DB INSERT-OR-IGNORE settles; /history backfill
 * may overlap with WS push) must produce one row.
 *
 * ## Ordering
 *
 * [observe] returns messages **newest-first** (descending `ts`). Combined
 * with `reverseLayout = true` on the RecyclerView, position 0 sits at the
 * bottom of the screen — natural chat orientation.
 */
interface ChatMessageStore<M> {

    /**
     * Reactive window of the most recent [limit] messages in [room].
     * Re-emits whenever any message in the window changes.
     *
     * @param room `"global"` for the public broadcast room, or a decimal
     *   uint64 string (≤ 20 chars) for a 1v1 thread.
     */
    fun observe(room: String, limit: Int): Flow<List<M>>

    /**
     * Idempotent insert-or-replace by `localKey`. Used by both the API
     * history fetcher and the WS live stream.
     */
    suspend fun upsert(messages: List<M>)

    /** Number of locally stored messages for [room]. */
    suspend fun countByRoom(room: String): Int

    /**
     * Delete a single message by its `localKey`. Used to drop an optimistic
     * self-sent row the server rejected non-retryably (e.g. the public room
     * is closed) — it was never accepted, so it shouldn't linger in the list.
     */
    suspend fun deleteByLocalKey(localKey: String)

    /** Delete all messages for [room] (e.g. on cache clear). */
    suspend fun deleteByRoom(room: String)
}
