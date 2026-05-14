package ceui.pixiv.chat.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for chat messages. Maps 1:1 to the wire JSON shape.
 *
 * ## Primary key
 *
 * [messageId] is a server-assigned snowflake — globally unique and
 * monotonically increasing. Using it as the PK gives us free dedup:
 * the same message from both an API page and a WS replay just
 * overwrites (`INSERT OR REPLACE`).
 *
 * ## Index
 *
 * The `(threadId, createdTime)` composite index covers the main query
 * pattern: "newest N messages in thread X, ordered by time DESC".
 * Room uses this index for both the `ORDER BY` and the `WHERE` clause,
 * so the query is a single B-tree scan.
 */
@Entity(
    tableName = "chat_messages",
    indices = [Index(value = ["threadId", "createdTime"])],
)
data class ChatMessageEntity(
    @PrimaryKey val messageId: Long,
    val threadId: Long,
    val uid: Long,
    val createdTime: Long,
    val type: Int,
    val asSummary: Boolean = false,
    val content: String? = null,
    val seqId: Long? = null,
    /** Raw JSON string for extensible fields (`extensions` in the wire format). */
    val extensions: String? = null,
)
