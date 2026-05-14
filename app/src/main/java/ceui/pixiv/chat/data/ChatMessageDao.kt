package ceui.pixiv.chat.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatMessageDao {

    /**
     * Reactive window of the [limit] most recent messages in a thread,
     * ordered newest-first. Re-emits whenever any row in the result
     * set changes (insert, update, delete).
     */
    @Query(
        """
        SELECT * FROM chat_messages
        WHERE threadId = :threadId
        ORDER BY createdTime DESC, messageId DESC
        LIMIT :limit
        """
    )
    fun observeMessages(threadId: Long, limit: Int): Flow<List<ChatMessageEntity>>

    /**
     * Idempotent insert-or-replace. Same message from API + WS just
     * overwrites — no duplicates.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(messages: List<ChatMessageEntity>)

    @Query("SELECT COUNT(*) FROM chat_messages WHERE threadId = :threadId")
    suspend fun countByThread(threadId: Long): Int

    @Query("DELETE FROM chat_messages WHERE messageId = :messageId")
    suspend fun deleteById(messageId: Long)

    @Query("DELETE FROM chat_messages WHERE threadId = :threadId")
    suspend fun deleteByThread(threadId: Long)
}
