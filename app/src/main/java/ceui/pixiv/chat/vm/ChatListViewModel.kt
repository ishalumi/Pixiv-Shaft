package ceui.pixiv.chat.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ceui.pixiv.chat.api.ChatThreadId
import ceui.pixiv.chat.base.LoadReason
import ceui.pixiv.chat.base.PageState
import ceui.pixiv.chat.base.PagingState
import ceui.pixiv.chat.core.AppResult
import ceui.pixiv.chat.core.ChatHistorySource
import ceui.pixiv.chat.core.ChatMessageStore
import ceui.pixiv.chat.core.ChatMessageStream
import ceui.pixiv.chat.data.ChatMessageEntity
import ceui.pixiv.chat.data.SendState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.UUID

/**
 * Local-first chat list ViewModel for shaft-api-v2 (uid-routing protocol).
 *
 * ```
 * RecyclerView ← observe ← [messages] ← Room Flow ← ChatMessageStore
 *                                            ↑ UPSERT (by localKey)
 *                            ┌───────────────┤
 *                            │               │
 *                  ChatHistorySource    ChatMessageStream (filtered by room)
 *                   (HTTP pagination)    (WS broadcast)
 * ```
 *
 * UI **only** reads [messages] (Room Flow). All writes — optimistic-send,
 * WS echo, history backfill — UPSERT by `localKey`. Doc §4 / §9.2.
 *
 * ## Room derivation
 *
 * - `toUid == null` → [room] = `"global"`
 * - `toUid != null` → [room] = `ChatThreadId.oneOnOneThreadId(selfUid, toUid)`
 *
 * The stream subscriber filters by `frame.room == room`, so a fragment
 * scoped to the global room never sees 1v1 traffic and vice versa.
 *
 * ## Send (doc §3.1 + §4.3)
 *
 * 1. Generate UUIDv4 `client_msg_id`
 * 2. Optimistic UPSERT local row `state=Sending`
 * 3. Build `msg` frame (`msgGlobal` or `msg1v1`) and dispatch via [sender]
 * 4. WS broadcast echo arrives → stream emits the same row → UPSERT
 *    keyed on the same localKey → `state=Delivered`
 * 5. On WS dispatch rejection → mark local row `state=Failed`
 */
class ChatListViewModel(
    val selfUid: Long,
    /** `null` = global broadcast room; non-null = peer pixiv uid for 1v1. */
    val toUid: Long?,
    private val store: ChatMessageStore<ChatMessageEntity>,
    private val historySource: ChatHistorySource<ChatMessageEntity>,
    private val stream: ChatMessageStream<ChatMessageEntity>,
    private val sender: WsMsgSender,
    private val pageSize: Int = 30,
) : ViewModel() {

    /** Derived from `(selfUid, toUid)` per doc §3.2. Single source of truth for the filter. */
    val room: String = if (toUid == null) ChatThreadId.ROOM_GLOBAL
                       else ChatThreadId.oneOnOneThreadId(selfUid, toUid)

    // ── Page state (loading / error / empty / content-ready) ────────────

    private val _pageState = MutableStateFlow<PageState<Unit>>(PageState.Loading())
    val pageState: StateFlow<PageState<Unit>> = _pageState.asStateFlow()

    // ── Message list (from local store) ─────────────────────────────────

    private val windowSize = MutableStateFlow(pageSize)

    val messages: StateFlow<List<ChatMessageEntity>> = windowSize
        .flatMapLatest { limit ->
            Timber.tag(TAG_PERF).d("flatMapLatest: re-subscribe room=%s limit=%d", room, limit)
            store.observe(room, limit)
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

    /**
     * Self-sent messages awaiting their broadcast echo. Key = `clientMsgId`,
     * value = elapsed-realtime nanos at the optimistic-write moment. When
     * the matching WS echo flows through [startLiveSync] we read this entry
     * to log the round-trip latency — a direct ground-truth signal of WS
     * health (≈ network RTT + server queue depth).
     */
    private val inFlightSends = java.util.concurrent.ConcurrentHashMap<String, Long>()

    init {
        Timber.tag(TAG_PERF).d(
            "init: selfUid=%d toUid=%s room=%s pageSize=%d",
            selfUid, toUid?.toString() ?: "global", room, pageSize,
        )
        startLiveSync()
        viewModelScope.launch { initialLoad() }
    }

    // ── Public API ──────────────────────────────────────────────────────

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

    fun retry() {
        Timber.tag(TAG_PERF).d("retry")
        viewModelScope.launch {
            _pageState.value = PageState.Loading(LoadReason.Retry)
            fetchNewest()
        }
    }

    fun loadOlder() {
        if (_pagingState.value != PagingState.Idle) {
            Timber.tag(TAG_PERF).d("loadOlder: skip (pagingState=%s)", _pagingState.value)
            return
        }
        if (nextPageToken == null && messages.value.isNotEmpty()) {
            if (!firstPageLoaded) {
                Timber.tag(TAG_PERF).d("loadOlder: skip (firstPage not loaded yet)")
                return
            }
            Timber.tag(TAG_PERF).d("loadOlder: EndReached (no nextPageToken)")
            _pagingState.value = PagingState.EndReached
            return
        }
        pagingJob = viewModelScope.launch {
            _pagingState.value = PagingState.LoadingMore
            val t0 = System.nanoTime()
            Timber.tag(TAG_PERF).d(
                "loadOlder: start room=%s, pageToken=%s, pageSize=%d, windowSize=%d",
                room, nextPageToken, pageSize, windowSize.value,
            )
            when (val result = historySource.loadPage(room, nextPageToken, pageSize)) {
                is AppResult.Success -> {
                    val apiMs = (System.nanoTime() - t0) / 1_000_000.0
                    val page = result.data
                    Timber.tag(TAG_PERF).d(
                        "loadOlder: API %.1f ms, received=%d, nextToken=%s",
                        apiMs, page.messages.size, page.nextPageToken,
                    )
                    if (page.messages.isNotEmpty()) {
                        store.upsert(page.messages)
                        val oldWindow = windowSize.value
                        windowSize.value += page.messages.size
                        Timber.tag(TAG_PERF).d(
                            "loadOlder: windowSize %d → %d", oldWindow, windowSize.value,
                        )
                    }
                    nextPageToken = page.nextPageToken
                    _pagingState.value = if (nextPageToken == null) {
                        PagingState.EndReached
                    } else {
                        PagingState.Idle
                    }
                }
                is AppResult.Error -> {
                    Timber.tag(TAG_PERF).w("loadOlder: failed: %s", result.error)
                    _pagingState.value = PagingState.Error(result.error)
                }
            }
        }
    }

    fun retryPaging() {
        Timber.tag(TAG_PERF).d("retryPaging")
        _pagingState.value = PagingState.Idle
        loadOlder()
    }

    /**
     * Send a `msg` frame. Returns `true` if the frame was accepted into the
     * WS outgoing buffer (not an end-to-end ACK — broadcast echo is the
     * actual ACK; see doc §3.2 / §4).
     *
     * Lifecycle:
     *  1. Generate UUIDv4 `client_msg_id`
     *  2. Optimistic UPSERT row `state=Sending` so UI shows the message
     *     instantly with a "sending…" indicator
     *  3. Dispatch via [sender]; on rejection mark row `state=Failed`
     *  4. WS echo arrives later (via [stream]) → UPSERT same localKey
     *     `state=Delivered` (the echo path overwrites this row)
     */
    suspend fun sendText(text: String, illustId: Long? = null): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed.length > MAX_TEXT_LENGTH) {
            Timber.tag(TAG).w("send rejected: text.length=%d > %d", trimmed.length, MAX_TEXT_LENGTH)
            return false
        }

        val clientMsgId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val optimistic = ChatMessageEntity(
            localKey = clientMsgId,
            serverId = null,
            clientMsgId = clientMsgId,
            uid = selfUid,
            room = room,
            displayName = null,           // server fills on echo
            text = trimmed,
            illustId = illustId,
            ts = now,
            state = SendState.Sending,
        )

        // Optimistic UI first. windowSize++ so the new row doesn't push
        // an old one out of the LIMIT result set.
        val oldWindow = windowSize.value
        windowSize.value++
        store.upsert(listOf(optimistic))
        Timber.tag(TAG).i(
            "⇡ sendText optimistic cmid=%s room=%s to=%s len=%d windowSize %d→%d",
            clientMsgId, room, toUid?.toString() ?: "global",
            trimmed.length, oldWindow, windowSize.value,
        )

        // If we were in Empty state, flip to Content.
        if (_pageState.value is PageState.Empty) {
            _pageState.value = PageState.Content(Unit)
        }

        // Record send-start so [startLiveSync] can compute round-trip
        // latency when the echo arrives.
        inFlightSends[clientMsgId] = System.nanoTime()

        val accepted = sender.send(toUid = toUid, clientMsgId = clientMsgId, text = trimmed, illustId = illustId)
        if (!accepted) {
            inFlightSends.remove(clientMsgId)
            store.upsert(listOf(optimistic.copy(state = SendState.Failed)))
            Timber.tag(TAG).w(
                "⇡ sendText WS REJECTED cmid=%s → state=Failed (no active session?)",
                clientMsgId,
            )
        }
        return accepted
    }

    /**
     * Mark a Sending row Failed in response to a server `err` frame.
     *
     * Per doc §3.2 / §12: when the offending inbound frame carried a
     * `client_msg_id`, server echoes it on the err, and we anchor on that
     * exact cmid — no ambiguity, even with multiple in-flight sends.
     *
     * For frame-level errors that happen before per-msg parsing
     * (`bad_json`, `bad_envelope`, `frame_too_large` of an unparseable
     * envelope, …), [cmid] is `null`. Fall back to "mark the most recent
     * still-Sending row Failed" — the user's last action is overwhelmingly
     * the source of frame-level errors at this layer.
     */
    fun markFailedByClientMsgId(cmid: String?) {
        viewModelScope.launch {
            val target: String? = if (cmid != null) {
                cmid // exact localKey match (self-sent rows store cmid as localKey)
            } else {
                // Fallback: most recent Sending row in the visible window.
                messages.value.firstOrNull { it.state == SendState.Sending }?.localKey
            }
            if (target == null) {
                Timber.tag(TAG).w("markFailed: no in-flight Sending row to anchor (cmid=%s)", cmid ?: "-")
                return@launch
            }
            // Stop any pending echo correlation for this cmid — we now know
            // the server will never broadcast it.
            inFlightSends.remove(target)

            val row = messages.value.firstOrNull { it.localKey == target }
            if (row == null) {
                Timber.tag(TAG).w("markFailed: cmid=%s not in current window", target)
                return@launch
            }
            store.upsert(listOf(row.copy(state = SendState.Failed)))
            Timber.tag(TAG).i(
                "✗ markFailed cmid=%s (exact=%b) → state=Failed",
                target, cmid != null,
            )
        }
    }

    fun trimWindow() {
        if (windowSize.value > pageSize) {
            Timber.tag(TAG_PERF).d("trimWindow: %d → %d", windowSize.value, pageSize)
            windowSize.value = pageSize
        }
    }

    // ── Internal ────────────────────────────────────────────────────────

    private suspend fun initialLoad() {
        val cachedCount = try {
            store.countByRoom(room)
        } catch (t: Throwable) {
            Timber.tag(TAG_PERF).e(t, "initialLoad: countByRoom threw")
            0
        }
        Timber.tag(TAG_PERF).d("initialLoad: room=%s cached=%d", room, cachedCount)

        if (cachedCount > 0) {
            _pageState.value = PageState.Content(Unit)
            backgroundRefresh()
        } else {
            _pageState.value = PageState.Loading(LoadReason.Initial)
            fetchNewest()
        }
    }

    private suspend fun fetchNewest() {
        val t0 = System.nanoTime()
        when (val result = historySource.loadPage(room, null, pageSize)) {
            is AppResult.Success -> {
                val page = result.data
                if (page.messages.isNotEmpty()) {
                    store.upsert(page.messages)
                }
                nextPageToken = page.nextPageToken
                firstPageLoaded = true
                _pageState.value = if (page.messages.isEmpty() && messages.value.isEmpty()) {
                    PageState.Empty()
                } else {
                    PageState.Content(Unit)
                }
                val ms = (System.nanoTime() - t0) / 1_000_000.0
                Timber.tag(TAG_PERF).d("fetchNewest: %d msgs in %.1f ms", page.messages.size, ms)
            }
            is AppResult.Error -> {
                if (messages.value.isNotEmpty()) {
                    _pageState.value = PageState.Content(Unit)
                    Timber.tag(TAG_PERF).w("fetchNewest: failed but cache non-empty: %s", result.error)
                } else {
                    _pageState.value = PageState.Error(result.error)
                    Timber.tag(TAG_PERF).w("fetchNewest: failed: %s", result.error)
                }
            }
        }
    }

    private suspend fun backgroundRefresh() {
        when (val result = historySource.loadPage(room, null, pageSize)) {
            is AppResult.Success -> {
                val page = result.data
                if (page.messages.isNotEmpty()) store.upsert(page.messages)
                nextPageToken = page.nextPageToken
                firstPageLoaded = true
            }
            is AppResult.Error -> {
                Timber.tag(TAG_PERF).w("backgroundRefresh: failed: %s", result.error)
            }
        }
    }

    private fun startLiveSync() {
        syncJob = viewModelScope.launch {
            stream.observe(room).collect { msg ->
                // Grow window BEFORE inserting so LIMIT covers the new row
                // when Room's invalidation fires.
                val oldWindow = windowSize.value
                windowSize.value++
                store.upsert(listOf(msg))

                // Echo correlation: if this msg matches an in-flight
                // optimistic-send, log the round-trip and clean up.
                val cmid = msg.clientMsgId
                val sendStartNs = if (cmid != null) inFlightSends.remove(cmid) else null
                if (sendStartNs != null) {
                    val roundTripMs = (System.nanoTime() - sendStartNs) / 1_000_000.0
                    Timber.tag(TAG).i(
                        "✓ echo received cmid=%s rtt=%.1fms (Sending→Delivered) room=%s",
                        cmid, roundTripMs, room,
                    )
                } else {
                    Timber.tag(TAG_PERF).d(
                        "liveSync: cmid=%s uid=%d windowSize %d→%d",
                        cmid ?: "-", msg.uid, oldWindow, windowSize.value,
                    )
                }

                if (_pageState.value is PageState.Empty) {
                    _pageState.value = PageState.Content(Unit)
                }
            }
        }
    }

    override fun onCleared() {
        Timber.tag(TAG_PERF).d("onCleared: room=%s windowSize=%d", room, windowSize.value)
        syncJob?.cancel()
        pagingJob?.cancel()
        super.onCleared()
    }

    companion object {
        private const val TAG = "Chat-VM"
        private const val TAG_PERF = "Chat-Perf"
        /** doc §3.1 / §12. */
        const val MAX_TEXT_LENGTH = 2048
    }
}

/**
 * Wire-side send seam. Production is `ShaftChatGateway::send`; tests pass
 * a lambda that records calls.
 */
fun interface WsMsgSender {
    fun send(toUid: Long?, clientMsgId: String, text: String, illustId: Long?): Boolean
}
