package ceui.pixiv.chat.core

/**
 * Remote source of historical messages (typically an HTTP endpoint).
 *
 * Hot-swappable: production wires a Retrofit implementation; tests
 * wire a fake that returns canned [MessagePage]s.
 *
 * The source **never** touches the local store — writing fetched
 * messages into Room is the ViewModel's / repository's job.
 */
fun interface ChatHistorySource<M> {

    /**
     * Fetch one page of messages for [room].
     *
     * @param room `"global"` for public broadcasts, or a decimal uint64
     *   string for a 1v1 thread.
     * @param pageToken `null` for the first (newest) page; pass
     *   [MessagePage.nextPageToken] for subsequent older pages.
     * @param pageSize requested page size (server caps at 200).
     * @return [MessagePage] wrapped in [AppResult].
     */
    suspend fun loadPage(
        room: String,
        pageToken: String?,
        pageSize: Int,
    ): AppResult<MessagePage<M>>
}
