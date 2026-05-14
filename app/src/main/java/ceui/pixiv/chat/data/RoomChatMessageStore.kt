package ceui.pixiv.chat.data

import ceui.pixiv.chat.core.ChatMessageStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach
import timber.log.Timber

/**
 * Room-backed implementation of [ChatMessageStore]. Thin adapter that
 * delegates every call to [ChatMessageDao].
 *
 * This is the single concrete binding between the `:core` contract and
 * Room. Swapping storage engines (e.g. SQLDelight, in-memory for tests)
 * means replacing this class alone.
 */
class RoomChatMessageStore(
    private val dao: ChatMessageDao,
) : ChatMessageStore<ChatMessageEntity> {

    override fun observe(threadId: Long, limit: Int): Flow<List<ChatMessageEntity>> =
        dao.observeMessages(threadId, limit).onEach { list ->
            Timber.tag(TAG).d(
                "observe: threadId=%d, limit=%d, emitted=%d rows, ids=[%s..%s]",
                threadId, limit, list.size,
                list.lastOrNull()?.messageId ?: "-",
                list.firstOrNull()?.messageId ?: "-",
            )
        }

    override suspend fun upsert(messages: List<ChatMessageEntity>) {
        val t0 = System.nanoTime()
        dao.upsert(messages)
        val ms = (System.nanoTime() - t0) / 1_000_000.0
        Timber.tag(TAG).d(
            "upsert: %d messages in %.1f ms, ids=[%s..%s]",
            messages.size, ms,
            messages.firstOrNull()?.messageId ?: "-",
            messages.lastOrNull()?.messageId ?: "-",
        )
    }

    override suspend fun countByThread(threadId: Long): Int {
        val t0 = System.nanoTime()
        val count = dao.countByThread(threadId)
        val ms = (System.nanoTime() - t0) / 1_000_000.0
        Timber.tag(TAG).d("countByThread: threadId=%d, count=%d in %.1f ms", threadId, count, ms)
        return count
    }

    override suspend fun deleteByThread(threadId: Long) {
        val t0 = System.nanoTime()
        dao.deleteByThread(threadId)
        val ms = (System.nanoTime() - t0) / 1_000_000.0
        Timber.tag(TAG).d("deleteByThread: threadId=%d in %.1f ms", threadId, ms)
    }

    companion object {
        private const val TAG = "ChatPerf"
    }
}
