package ceui.pixiv.chat.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Dedicated Room database for chat messages, separate from [AppDatabase].
 *
 * ## Why a separate database
 *
 * Same rationale as `AnalyticsDatabase`:
 *
 *  1. **Independent migration.** Chat schema will iterate fast (read
 *     receipts, reactions, attachments…). Keeping it in its own file
 *     means `fallbackToDestructiveMigration` only drops the message
 *     cache, not user data.
 *  2. **Independent lifecycle.** Clearing the chat cache (e.g. on
 *     logout) is a single `chatDb.clearAllTables()` — no risk of
 *     touching user or analytics tables.
 *  3. **WAL contention.** Chat inserts are high-frequency (WS messages
 *     + full-page upserts). A separate WAL file keeps the write lock
 *     from blocking the main database's reads.
 */
@Database(
    entities = [ChatMessageEntity::class],
    // v2 (2026-05-14): schema migration for uid-routing protocol (doc
    // §9.2). PK changed from Long messageId → String localKey, threadId
    // dropped in favour of String room, plus serverId/clientMsgId/displayName/state
    // columns added. fallbackToDestructiveMigration drops the old table on
    // the next open — chat had no real users on the old protocol so the
    // wipe is fine.
    version = 2,
    exportSchema = true,
)
abstract class ChatDatabase : RoomDatabase() {
    abstract fun chatMessageDao(): ChatMessageDao

    companion object {
        @Volatile
        private var instance: ChatDatabase? = null

        fun getInstance(context: Context): ChatDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    ChatDatabase::class.java,
                    "chat.db",
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
    }
}
