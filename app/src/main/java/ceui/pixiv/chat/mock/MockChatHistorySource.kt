package ceui.pixiv.chat.mock

import ceui.pixiv.chat.core.AppResult
import ceui.pixiv.chat.core.ChatHistorySource
import ceui.pixiv.chat.core.MessagePage
import ceui.pixiv.chat.data.ChatMessageEntity
import kotlinx.coroutines.delay
import timber.log.Timber

/**
 * Fake [ChatHistorySource] backed by [MockChatData].
 *
 * Converts the mock data maps into [ChatMessageEntity] instances and
 * wraps them in [MessagePage]. Adds a 400 ms delay to simulate
 * network latency.
 */
class MockChatHistorySource : ChatHistorySource<ChatMessageEntity> {

    override suspend fun loadPage(
        threadId: Long,
        pageToken: String?,
        pageSize: Int,
    ): AppResult<MessagePage<ChatMessageEntity>> {
        val t0 = System.nanoTime()
        Timber.tag(TAG).d("loadPage: start threadId=%d, pageToken=%s, pageSize=%d", threadId, pageToken, pageSize)
        delay(400) // simulate network
        val networkMs = (System.nanoTime() - t0) / 1_000_000.0

        val t1 = System.nanoTime()
        val raw = MockChatData.getPage(pageToken)
        val pagination = raw["pagination"] as Map<*, *>
        val nextToken = pagination["nextPageToken"] as? String

        @Suppress("UNCHECKED_CAST")
        val list = raw["list"] as List<Map<String, Any?>>
        val entities = list.map { it.toEntity() }
        val parseMs = (System.nanoTime() - t1) / 1_000_000.0
        val totalMs = (System.nanoTime() - t0) / 1_000_000.0

        Timber.tag(TAG).d(
            "loadPage: done in %.1f ms (network=%.1f ms, parse=%.1f ms), returned=%d, nextToken=%s",
            totalMs, networkMs, parseMs, entities.size, nextToken,
        )
        return AppResult.Success(MessagePage(entities, nextToken))
    }

    companion object {
        private const val TAG = "ChatPerf"
    }
}

/**
 * Convert a [MockChatData] raw map to a [ChatMessageEntity].
 */
internal fun Map<String, Any?>.toEntity(): ChatMessageEntity = ChatMessageEntity(
    messageId = (this["messageId"] as Number).toLong(),
    threadId = (this["threadId"] as Number).toLong(),
    uid = (this["uid"] as Number).toLong(),
    createdTime = (this["createdTime"] as Number).toLong(),
    type = (this["type"] as Number).toInt(),
    asSummary = this["asSummary"] as? Boolean ?: false,
    content = this["content"] as? String,
    seqId = (this["seqId"] as? Number)?.toLong(),
    extensions = (this["extensions"] as? Map<*, *>)?.toString(),
)
