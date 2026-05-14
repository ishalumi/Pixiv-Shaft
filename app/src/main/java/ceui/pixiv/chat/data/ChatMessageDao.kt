package ceui.pixiv.chat.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

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
