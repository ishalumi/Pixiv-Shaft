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
    version = 1,
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
