package ceui.pixiv.chat.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Lightweight projection of "one row per room with the latest message
 * preview" — feeds the chat conversation list (Figma 3201:18523 adapted).
 *
 * We compose this in SQL via a correlated subquery rather than a
 * `GROUP BY room` because we want the latest row's *text/uid/display_name*
 * specifically (MIN/MAX on those columns wouldn't be the row matching
 * MAX(ts)). The (room, ts) index makes the inner lookup an index seek.
 */
data class ChatRoomPreview(
    val room: String,
    val uid: Long,
    val displayName: String?,
    val text: String?,
    val illustId: Long?,
    val ts: Long,
)

@Dao
interface ChatMessageDao {

    /**
     * Reactive window of the [limit] most recent messages in `room`,
     * ordered newest-first. Re-emits whenever any row in the result set
     * changes (insert, update, delete).
     */
    @Query(
        """
        SELECT * FROM chat_messages
        WHERE room = :room
        ORDER BY ts DESC, server_id DESC
        LIMIT :limit
        """
    )
    fun observeMessages(room: String, limit: Int): Flow<List<ChatMessageEntity>>

    /**
     * One row per distinct room: the latest message's preview fields
     * + its ts, ordered newest-room-first. Reactive — re-emits whenever
     * any row that affects the per-room max changes.
     *
     * The `WHERE ts = (SELECT MAX(ts) ...)` ties the projected fields to
     * the actual latest message, not the alphabetical MIN/MAX of each
     * column. The outer `GROUP BY room` is a safety net for the edge case
     * where two rows in the same room share an identical ts (insertion
     * race in the local store); SQLite's "first row of the group"
     * deterministically picks one and the list stays stable.
     */
    @Query(
        """
        SELECT m.room                      AS room,
               m.uid                       AS uid,
               m.display_name              AS displayName,
               m.text                      AS text,
               m.illust_id                 AS illustId,
               m.ts                        AS ts
          FROM chat_messages m
         WHERE m.ts = (
                 SELECT MAX(ts) FROM chat_messages
                  WHERE room = m.room
               )
         GROUP BY m.room
         ORDER BY m.ts DESC
        """
    )
    fun observeRoomPreviews(): Flow<List<ChatRoomPreview>>

    /**
     * Insert or replace by `localKey` (the PK). The dedup contract lives
     * here: optimistic-send writes `(localKey=clientMsgId, state=Sending)`,
     * WS broadcast echo overwrites the same row with `state=Delivered`.
     * Duplicate broadcasts across retries collapse to a single row.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(messages: List<ChatMessageEntity>)

    @Query("SELECT COUNT(*) FROM chat_messages WHERE room = :room")
    suspend fun countByRoom(room: String): Int

    @Query("DELETE FROM chat_messages WHERE local_key = :localKey")
    suspend fun deleteByLocalKey(localKey: String)

    @Query("DELETE FROM chat_messages WHERE room = :room")
    suspend fun deleteByRoom(room: String)
}
