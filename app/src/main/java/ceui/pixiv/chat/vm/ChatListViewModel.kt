package ceui.pixiv.chat.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ceui.pixiv.chat.core.ChatHistorySource
import ceui.pixiv.chat.core.ChatMessageStore
import ceui.pixiv.chat.core.ChatMessageStream
import ceui.pixiv.chat.core.AppResult
import ceui.pixiv.chat.base.LoadReason
import ceui.pixiv.chat.base.PageState
import ceui.pixiv.chat.base.PagingState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Local-first chat list ViewModel.
 *
 * ## Data flow
 *
 * ```
 * RecyclerView  ← observe ←  [messages]  ← Room Flow ← ChatMessageStore
 *                                                          ↑ upsert
 *                              ┌───────────────────────────┤
 *                              │                           │
 *                     ChatHistorySource              ChatMessageStream
 *                      (API pagination)               (WS live push)
 * ```
 *
 * The UI **only** reads [messages], which comes from the local store.
 * Both remote sources (API and WS) write INTO the store. Room's Flow
 * re-emits on every insert, so the UI updates automatically via
 * DiffUtil — no list manipulation in the ViewModel.
 *
 * ## Cold-start behaviour
 *
 * 1. Check local store for cached messages.
 * 2. **Cache hit** → show instantly, background-refresh from API.
 * 3. **Cache miss** → show loading spinner, fetch first page from API.
 *
 * In both cases the WS live stream starts immediately.
 *
 * ## Paging (load older)
 *
 * Chat is reverse-chronological: newest at bottom, older at top.
 * Scrolling to the top triggers [loadOlder], which fetches the next
 * page from [ChatHistorySource], inserts into the store, and grows
 * [windowSize] so the Room query includes the newly-inserted rows.
 *
 * When a WS message arrives, [windowSize] is also incremented so
 * that no previously-visible old message drops off the query result.
 *
 * @param M the message type (generic so `:base` stays free of Room /
 *   serialisation dependencies; `:app` binds `M` to its concrete
 *   entity).
 */
class ChatListViewModel<M>(
    private val threadId: Long,
    private val store: ChatMessageStore<M>,
    private val historySource: ChatHistorySource<M>,
    private val stream: ChatMessageStream<M>,
    private val pageSize: Int = 30,
) : ViewModel() {

    // ── Page state (loading / error / empty / content-ready) ────────────

    private val _pageState = MutableStateFlow<PageState<Unit>>(PageState.Loading())
    val pageState: StateFlow<PageState<Unit>> = _pageState.asStateFlow()

    // ── Message list (from local store) ─────────────────────────────────

    private val windowSize = MutableStateFlow(pageSize)

    /**
     * The rendered message list, always newest-first. Backed by a Room
     * Flow that re-emits on every table change. Combined with
     * `reverseLayout = true` on the RecyclerView, position 0 renders
     * at the bottom of the screen.
     *
     * [windowSize] is a [StateFlow] which is already
     * `distinctUntilChanged` — `flatMapLatest` only triggers when the
     * LIMIT actually changes (from [loadOlder], WS arrivals, or
     * [trimWindow]). Room's own invalidation tracker handles
     * per-INSERT re-emission without a Flow switch.
     */
    val messages: StateFlow<List<M>> = windowSize
        .flatMapLatest { limit ->
            Timber.tag(TAG_PERF).d("flatMapLatest: re-subscribe with limit=%d", limit)
            store.observe(threadId, limit)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ── Paging state (for loading older history) ────────────────────────

    private val _pagingState = MutableStateFlow<PagingState>(PagingState.Idle)
    val pagingState: StateFlow<PagingState> = _pagingState.asStateFlow()

    // ── Internal ────────────────────────────────────────────────────────

    private var nextPageToken: String? = null
    private var firstPageLoaded = false
    private var pagingJob: Job? = null
    private var syncJob: Job? = null

    init {
        Timber.tag(TAG_PERF).d("init: threadId=%d, pageSize=%d", threadId, pageSize)
        startLiveSync()
        viewModelScope.launch { initialLoad() }
    }

    // ── Public API ──────────────────────────────────────────────────────

    /**
     * Pull-to-refresh: re-fetch the newest page and reset the window.
     * Cached data is NOT cleared — the user keeps seeing messages while
     * the refresh is in flight.
     */
    fun refresh() {
        Timber.tag(TAG_PERF).d("refresh: windowSize %d → %d", windowSize.value, pageSize)
        pagingJob?.cancel()
        _pagingState.value = PagingState.Idle
        viewModelScope.launch {
            _pageState.value = PageState.Loading(LoadReason.Swipe)
            windowSize.value = pageSize
            nextPageToken = null
            firstPageLoaded = false
            fetchNewest()
        }
    }

    /** Retry after an initial-load error. */
    fun retry() {
        Timber.tag(TAG_PERF).d("retry")
        viewModelScope.launch {
            _pageState.value = PageState.Loading(LoadReason.Retry)
            fetchNewest()
        }
    }

    /**
     * Load the next (older) page. Called by a scroll listener when the
     * user reaches the top of the list. Idempotent while a page is
     * already in flight or all pages have been loaded.
     */
    fun loadOlder() {
        if (_pagingState.value != PagingState.Idle) {
            Timber.tag(TAG_PERF).d("loadOlder: skip (pagingState=%s)", _pagingState.value)
            return
        }
        if (nextPageToken == null && messages.value.isNotEmpty()) {
            if (!firstPageLoaded) {
                Timber.tag(TAG_PERF).d("loadOlder: skip (firstPage not loaded yet)")
                return // backgroundRefresh still in flight; don't commit to EndReached
            }
            Timber.tag(TAG_PERF).d("loadOlder: EndReached (no nextPageToken)")
            _pagingState.value = PagingState.EndReached
            return
        }
        pagingJob = viewModelScope.launch {
            _pagingState.value = PagingState.LoadingMore
            val t0 = System.nanoTime()
            Timber.tag(TAG_PERF).d(
                "loadOlder: start threadId=%d, pageToken=%s, pageSize=%d, windowSize=%d",
                threadId, nextPageToken, pageSize, windowSize.value,
            )
            when (val result = historySource.loadPage(threadId, nextPageToken, pageSize)) {
                is AppResult.Success -> {
                    val apiMs = (System.nanoTime() - t0) / 1_000_000.0
                    val page = result.data
                    Timber.tag(TAG_PERF).d(
                        "loadOlder: API %.1f ms, received=%d, nextToken=%s",
                        apiMs, page.messages.size, page.nextPageToken,
                    )
                    if (page.messages.isNotEmpty()) {
                        val t1 = System.nanoTime()
                        store.upsert(page.messages)
                        val upsertMs = (System.nanoTime() - t1) / 1_000_000.0
                        val oldWindow = windowSize.value
                        windowSize.value += page.messages.size
                        Timber.tag(TAG_PERF).d(
                            "loadOlder: upsert %.1f ms, windowSize %d → %d",
                            upsertMs, oldWindow, windowSize.value,
                        )
                    }
                    nextPageToken = page.nextPageToken
                    _pagingState.value = if (nextPageToken == null) {
                        PagingState.EndReached
                    } else {
                        PagingState.Idle
                    }
                    val totalMs = (System.nanoTime() - t0) / 1_000_000.0
                    Timber.tag(TAG_PERF).d("loadOlder: done in %.1f ms total", totalMs)
                }
                is AppResult.Error -> {
                    val totalMs = (System.nanoTime() - t0) / 1_000_000.0
                    Timber.tag(TAG_PERF).w("loadOlder: failed in %.1f ms: %s", totalMs, result.error)
                    _pagingState.value = PagingState.Error(result.error)
                }
            }
        }
    }

    /** Retry a failed [loadOlder]. */
    fun retryPaging() {
        Timber.tag(TAG_PERF).d("retryPaging")
        _pagingState.value = PagingState.Idle
        loadOlder()
    }

    /**
     * Insert a locally-created message into the store. Grows the
     * window so the new message doesn't push an old one out of the
     * query result. The Room Flow re-emission + DiffUtil handles UI.
     */
    fun send(message: M) {
        viewModelScope.launch {
            val t0 = System.nanoTime()
            val oldWindow = windowSize.value
            windowSize.value++
            store.upsert(listOf(message))
            val ms = (System.nanoTime() - t0) / 1_000_000.0
            Timber.tag(TAG_PERF).d("send: upsert %.1f ms, windowSize %d → %d", ms, oldWindow, windowSize.value)
            if (_pageState.value is PageState.Empty) {
                _pageState.value = PageState.Content(Unit)
            }
        }
    }

    /**
     * Shrink the query window back to [pageSize]. Call when the user
     * has scrolled back to the newest messages and no longer needs
     * the full history in the result set. Old messages stay in Room
     * and will reappear if [loadOlder] is called again.
     */
    fun trimWindow() {
        if (windowSize.value > pageSize) {
            Timber.tag(TAG_PERF).d("trimWindow: %d → %d", windowSize.value, pageSize)
            windowSize.value = pageSize
        }
    }

    // ── Internal ────────────────────────────────────────────────────────

    /**
     * Cold-start decision: cache-first, then background refresh.
     */
    private suspend fun initialLoad() {
        val t0 = System.nanoTime()
        val cachedCount = try {
            val t1 = System.nanoTime()
            val count = store.countByThread(threadId)
            val ms = (System.nanoTime() - t1) / 1_000_000.0
            Timber.tag(TAG_PERF).d("initialLoad: countByThread=%.1f ms, cached=%d", ms, count)
            count
        } catch (t: Throwable) {
            Timber.tag(TAG_PERF).e(t, "initialLoad: countByThread threw")
            0
        }

        if (cachedCount > 0) {
            // Local cache hit — render immediately, refresh in background.
            Timber.tag(TAG_PERF).d("initialLoad: cache HIT (%d rows), showing cached + backgroundRefresh", cachedCount)
            _pageState.value = PageState.Content(Unit)
            backgroundRefresh()
        } else {
            // No cache — show spinner and block on the first API page.
            Timber.tag(TAG_PERF).d("initialLoad: cache MISS, fetching first page")
            _pageState.value = PageState.Loading(LoadReason.Initial)
            fetchNewest()
        }
        val totalMs = (System.nanoTime() - t0) / 1_000_000.0
        Timber.tag(TAG_PERF).d("initialLoad: done in %.1f ms total", totalMs)
    }

    /**
     * Fetch the newest page from the API, write into store, and update
     * [pageState]. Used for initial load, refresh, and retry.
     */
    private suspend fun fetchNewest() {
        val t0 = System.nanoTime()
        Timber.tag(TAG_PERF).d("fetchNewest: start threadId=%d, pageSize=%d", threadId, pageSize)
        when (val result = historySource.loadPage(threadId, null, pageSize)) {
            is AppResult.Success -> {
                val apiMs = (System.nanoTime() - t0) / 1_000_000.0
                val page = result.data
                Timber.tag(TAG_PERF).d("fetchNewest: API %.1f ms, received=%d, nextToken=%s", apiMs, page.messages.size, page.nextPageToken)
                if (page.messages.isNotEmpty()) {
                    val t1 = System.nanoTime()
                    store.upsert(page.messages)
                    val upsertMs = (System.nanoTime() - t1) / 1_000_000.0
                    Timber.tag(TAG_PERF).d("fetchNewest: upsert %.1f ms", upsertMs)
                }
                nextPageToken = page.nextPageToken
                firstPageLoaded = true
                _pageState.value = if (page.messages.isEmpty() && messages.value.isEmpty()) {
                    PageState.Empty()
                } else {
                    PageState.Content(Unit)
                }
                val totalMs = (System.nanoTime() - t0) / 1_000_000.0
                Timber.tag(TAG_PERF).d("fetchNewest: done in %.1f ms total", totalMs)
            }
            is AppResult.Error -> {
                val totalMs = (System.nanoTime() - t0) / 1_000_000.0
                // If we have cached data, stay in Content and let the
                // user see the stale list rather than an error screen.
                if (messages.value.isNotEmpty()) {
                    _pageState.value = PageState.Content(Unit)
                    Timber.tag(TAG_PERF).w("fetchNewest: failed in %.1f ms but cache non-empty; staying in Content", totalMs)
                } else {
                    _pageState.value = PageState.Error(result.error)
                    Timber.tag(TAG_PERF).w("fetchNewest: failed in %.1f ms: %s", totalMs, result.error)
                }
            }
        }
    }

    /**
     * Silent background refresh: same as [fetchNewest] but does not
     * change [pageState] on error (the user is already seeing cached
     * data — don't flash an error screen).
     */
    private suspend fun backgroundRefresh() {
        val t0 = System.nanoTime()
        Timber.tag(TAG_PERF).d("backgroundRefresh: start threadId=%d", threadId)
        when (val result = historySource.loadPage(threadId, null, pageSize)) {
            is AppResult.Success -> {
                val apiMs = (System.nanoTime() - t0) / 1_000_000.0
                val page = result.data
                Timber.tag(TAG_PERF).d("backgroundRefresh: API %.1f ms, received=%d", apiMs, page.messages.size)
                if (page.messages.isNotEmpty()) {
                    val t1 = System.nanoTime()
                    store.upsert(page.messages)
                    val upsertMs = (System.nanoTime() - t1) / 1_000_000.0
                    Timber.tag(TAG_PERF).d("backgroundRefresh: upsert %.1f ms", upsertMs)
                }
                nextPageToken = page.nextPageToken
                firstPageLoaded = true
                val totalMs = (System.nanoTime() - t0) / 1_000_000.0
                Timber.tag(TAG_PERF).d("backgroundRefresh: done in %.1f ms total", totalMs)
            }
            is AppResult.Error -> {
                val totalMs = (System.nanoTime() - t0) / 1_000_000.0
                Timber.tag(TAG_PERF).w("backgroundRefresh: failed in %.1f ms: %s", totalMs, result.error)
            }
        }
    }

    /**
     * Collect the WS live stream and write every message into the
     * local store.
     *
     * Window growth: [windowSize] is incremented **before** each
     * [store.upsert] so the Room query LIMIT is already large enough
     * when the insert's invalidation fires. This prevents older
     * messages from dropping off the result set when new ones arrive.
     * The cost is one extra Room re-query per WS message (old LIMIT
     * result → new LIMIT result), which DiffUtil absorbs as a no-op.
     *
     * Use [trimWindow] to shrink the window back to [pageSize] when
     * the user scrolls to the bottom and no longer needs the full
     * history in the result set.
     */
    private fun startLiveSync() {
        syncJob = viewModelScope.launch {
            stream.observe(threadId).collect { msg ->
                val t0 = System.nanoTime()
                // Grow the window BEFORE inserting so the LIMIT is
                // already large enough when Room's invalidation fires.
                val oldWindow = windowSize.value
                windowSize.value++
                store.upsert(listOf(msg))
                val ms = (System.nanoTime() - t0) / 1_000_000.0
                Timber.tag(TAG_PERF).d("liveSync: WS message upserted in %.1f ms, windowSize %d → %d", ms, oldWindow, windowSize.value)

                // If we were in Empty state, flip to Content.
                if (_pageState.value is PageState.Empty) {
                    _pageState.value = PageState.Content(Unit)
                }
            }
        }
    }

    override fun onCleared() {
        Timber.tag(TAG_PERF).d("onCleared: windowSize=%d", windowSize.value)
        syncJob?.cancel()
        pagingJob?.cancel()
        super.onCleared()
    }

    companion object {
        private const val TAG = "ChatList"
        private const val TAG_PERF = "ChatPerf"
    }
}
