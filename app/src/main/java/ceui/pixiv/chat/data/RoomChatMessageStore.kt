package ceui.pixiv.chat.data

import ceui.pixiv.chat.core.ChatMessageStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach
import timber.log.Timber

/**
 * Room-backed implementation of [ChatMessageStore]. Thin adapter that
 * delegates every call to [ChatMessageDao]. Swapping the storage engine
 * (e.g. SQLDelight, in-memory for tests) means replacing this class alone.
 */
class RoomChatMessageStore(
    private val dao: ChatMessageDao,
) : ChatMessageStore<ChatMessageEntity> {

    override fun observe(room: String, limit: Int): Flow<List<ChatMessageEntity>> =
        dao.observeMessages(room, limit).onEach { list ->
            Timber.tag(TAG).d(
                "observe: room=%s, limit=%d, emitted=%d rows",
                room, limit, list.size,
            )
        }

    override suspend fun upsert(messages: List<ChatMessageEntity>) {
        val t0 = System.nanoTime()
        dao.upsert(messages)
        val ms = (System.nanoTime() - t0) / 1_000_000.0
        Timber.tag(TAG).d("upsert: %d messages in %.1f ms", messages.size, ms)
    }

    override suspend fun countByRoom(room: String): Int {
        val t0 = System.nanoTime()
        val count = dao.countByRoom(room)
        val ms = (System.nanoTime() - t0) / 1_000_000.0
        Timber.tag(TAG).d("countByRoom: room=%s, count=%d in %.1f ms", room, count, ms)
        return count
    }

    override suspend fun deleteByRoom(room: String) {
        val t0 = System.nanoTime()
        dao.deleteByRoom(room)
        val ms = (System.nanoTime() - t0) / 1_000_000.0
        Timber.tag(TAG).d("deleteByRoom: room=%s in %.1f ms", room, ms)
    }

    companion object {
        private const val TAG = "Chat-Store"
    }
}
