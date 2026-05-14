package ceui.pixiv.chat.core

/**
 * One page of historical messages returned by [ChatHistorySource].
 *
 * @param M the message type — kept generic so the contracts in `:core`
 *   never depend on a concrete Room entity or serializable DTO.
 * @property messages ordered **newest-first** (descending `createdTime`).
 * @property nextPageToken opaque server cursor for the next (older) page;
 *   `null` when there are no more pages.
 */
data class MessagePage<M>(
    val messages: List<M>,
    val nextPageToken: String?,
)
