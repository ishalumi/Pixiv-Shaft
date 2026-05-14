package ceui.pixiv.chat.core

import ceui.pixiv.chat.core.AppResult

/**
 * Remote source of historical messages (typically an HTTP endpoint).
 *
 * Hot-swappable: production wires a Retrofit implementation; tests
 * wire a fake that returns canned [MessagePage]s.
 *
 * The source **never** touches the local store — writing fetched
 * messages into Room is the ViewModel's / repository's job. This
 * keeps the interface pure and testable.
 */
fun interface ChatHistorySource<M> {

    /**
     * Fetch one page of messages for [threadId].
     *
     * @param pageToken `null` for the first (newest) page; pass
     *   [MessagePage.nextPageToken] for subsequent older pages.
     * @param pageSize requested page size (server may return fewer).
     * @return [MessagePage] wrapped in [AppResult].
     */
    suspend fun loadPage(
        threadId: Long,
        pageToken: String?,
        pageSize: Int,
    ): AppResult<MessagePage<M>>
}
