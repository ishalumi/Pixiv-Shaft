package ceui.pixiv.chat.api

import android.app.Application
import ceui.lisa.BuildConfig
import ceui.pixiv.chat.core.ChatMessageStream
import ceui.pixiv.chat.data.ChatDatabase
import ceui.pixiv.chat.data.ChatMessageDao
import ceui.pixiv.chat.data.ChatMessageEntity
import ceui.pixiv.websocket.IncomingMessage
import ceui.pixiv.websocket.WebSocketConfig
import ceui.pixiv.websocket.WebSocketManager
import ceui.pixiv.websocket.WebSocketState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import androidx.lifecycle.asFlow
import ceui.pixiv.session.SessionManager
import ceui.pixiv.websocket.WebSocketEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Application-scoped gateway for the shaft-api-v2 chat WebSocket.
 *
 * Wraps [WebSocketManager] (the Peanut-style session coordinator) — manager
 * owns ONE live [ceui.pixiv.websocket.WebSocketClient], multiplexes its
 * state/events/incoming flows across reconnects, and rebuilds the client on
 * "session activated". Chat callers consume the manager's flows; nothing
 * owns the client directly.
 *
 * ## Lifecycle
 *
 * - [bootstrap] is called once from
 *   [ceui.lisa.activities.Shaft] after
 *   [ceui.pixiv.events.EventReporter.init] (so `clientId` is available
 *   synchronously when the manager activates).
 * - Activation signal is a `StateFlow<Boolean>` pinned to `true` for the
 *   process lifetime. The chat is anonymous (uses `EventReporter.currentClientId()`
 *   not a pixiv login) so there's no "logout → close" event — the connection
 *   stays alive while the app process runs. **Cost**: idle radio/battery
 *   when the user is not on the chat screen.
 *
 * ## Heartbeat audit
 *
 * OkHttp's RFC 6455 ping/pong is internal to the transport; it does not
 * surface to the app layer. To make heartbeat _visible_ in logcat, this
 * class runs a 30-second probe coroutine that logs the current
 * [WebSocketState] on tag `"ChatHB"`. Connected = healthy. Reconnecting /
 * Idle / Disconnected = something to look at.
 */
object ShaftChatGateway {

    private const val TAG = "Chat-Gateway"
    private const val TAG_HB = "Chat-Heartbeat"
    private const val TAG_RAW = "Chat-Raw"

    private val bootstrapped = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var manager: WebSocketManager
    private lateinit var stream: WsChatMessageStream
    private lateinit var persistDao: ChatMessageDao

    private val _fatalAuth = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
    )

    /**
     * Idempotent. Safe to call from `Application.onCreate` after
     * `EventReporter.init`. Subsequent calls are no-ops.
     */
    fun bootstrap(app: Application) {
        if (!bootstrapped.compareAndSet(false, true)) return

        // Activation tracks pixiv login. Server requires `uid > 0` for the
        // HMAC handshake; without it ShaftHmacAuthProvider throws and
        // RobustWebSocketClient enters a permanent backoff loop. Driving
        // the manager off SessionManager solves two cases:
        //
        //   1. **Fresh launch, not logged in** — flow emits `false`,
        //      manager stays Idle, no wasted handshake attempts
        //   2. **Logout** — flow flips to `false`, manager closes the
        //      socket; no more "uid=0 → IllegalStateException → retry"
        //      thrashing
        //   3. **Re-login** — flow flips back to `true`, manager builds a
        //      fresh client with the new uid in the auth provider
        //
        // `LiveData.asFlow()` emits the current value to each subscriber
        // (manager only subscribes once via its single internal collect).
        // `distinctUntilChanged` makes sure the manager's `flatMapLatest`
        // doesn't tear down + rebuild on every irrelevant LiveData write
        // (e.g. token refresh that keeps the same uid).
        //
        // Fork builds compile with `SHAFT_EVENTS_HMAC=""` (see app/build.gradle
        // — "Forks build with empty secret"). The HTTP `/events/batch` path
        // already gates itself via `EventReporter.hmacEnabled`, but the WS path
        // doesn't: `ShaftHmacAuthProvider.dynamicUrl` would call
        // `SecretKeySpec(emptyBytes, "HmacSHA256")` which throws
        // `IllegalArgumentException: Empty key` inside the manager's coroutine
        // and crashes the app at launch. Pin `ready` to `false` here so the
        // manager never activates → no client built → no signing attempt.
        // Public getters still work (subscribers see Idle / never-emitting
        // flows), so banner bridge / chat fragments don't NPE.
        val ready: Flow<Boolean> = if (BuildConfig.SHAFT_EVENTS_HMAC.isEmpty()) {
            Timber.tag(TAG).i("HMAC secret not configured — chat WS disabled for this build (fork mode)")
            flowOf(false)
        } else {
            SessionManager.loggedInAccount.asFlow()
                .map { account -> (account?.user?.id ?: 0L) > 0L }
                .distinctUntilChanged()
        }

        manager = WebSocketManager(
            loggedIn = ready,
            createClient = { _ ->
                Timber.tag(TAG).i("createClient: building shaft chat WS (uid=%d)", SessionManager.loggedInUid)
                ShaftChatWsClient.create(app)
            },
            // WebSocketConfig is required by the manager's signature but
            // ShaftHmacAuthProvider.dynamicUrl() rewrites the URL per
            // connect attempt, so this placeholder URL is never used.
            config = WebSocketConfig(url = "ws://placeholder.invalid/"),
        )
        manager.start()
        Timber.tag(TAG).i("bootstrap complete — manager.start() invoked (loggedInUid=%d)", SessionManager.loggedInUid)

        // Wire the shared stream that the chat fragment consumes. Built
        // here so its hello/err side flows are app-scoped (a fragment that
        // arrives mid-session can still see the last hello via replay=1).
        stream = WsChatMessageStream(manager.incoming)
        persistDao = ChatDatabase.getInstance(app).chatMessageDao()

        startHeartbeatProbe()
        startAlwaysOnRawLog()
        startAlwaysOnPersister()
        startFatalAuthMonitor()
        startArtistSubscriber()
        startArtistNewWorkFanout()

        // 收到 DM 消息时短震一下。subscribe 到同一份 manager.incoming SharedFlow,
        // 跟 persister / banner bridge 共用单 socket,无额外开销。
        ChatHapticBridge(app, manager.incoming, scope).start()
    }

    // ── 画师订阅推送(测试中) ─────────────────────────────────────────────

    /**
     * 测试期硬编码:订阅单画师 uid=11103082。后续接入 ProfileManager 或用户手动
     * 选画师时替换这里。
     *
     * 列表写在 const 而不是 List<Long>(11103082) 字面量是为了改测试 uid 时
     * 一处搞定。
     *
     * TODO: 真上前删掉,改成由 ProfileManager / 设置页注入,Gateway 只暴露
     *  `submitSubscription(uids)` API,业务上"该订阅谁"不该在 Gateway 内。
     */
    private val TEST_SUBSCRIBE_ARTIST_IDS = listOf(11103082L)

    /**
     * 出站 artist_new_work 流。订阅方(UI bridge / 测试 logger)通过这个 Flow
     * 拿到结构化事件,不需要自己再 decode 一遍。
     *
     * extraBufferCapacity=16:测试场景一波突发(限流上限 5/min 单画师)远低于
     * 16,正常 collector 跟得上;不挂订阅者时 SharedFlow 默认丢弃,不阻塞 ws。
     */
    private val _artistNewWork = MutableSharedFlow<ChatFrame.ArtistNewWork>(
        replay = 0,
        extraBufferCapacity = 16,
    )

    /**
     * 每次 WS 进入 Connected 状态就重发一次 `sub_artists`。server 端的订阅状态
     * 是 in-memory,server 重启 / 客户端重连后必须重申。manager.state 是
     * StateFlow,新订阅者会立刻收到当前值,所以加这个 collector 时若已 Connected
     * 也会立即 fire 一次。
     *
     * 不在 incoming 收到 hello 时发,因为 Connected → hello 之间客户端就有理由
     * 已经能 send 了(底层 socket 通了)。等 hello 多一个 RTT 没必要。
     *
     * log 不打完整列表:测试期 1 个 id 可以接受,真上几百到上千个 id 全展开会爆
     * log。size + 前 3 个 sample 足够 debug 判断"对不对"。
     */
    private fun startArtistSubscriber() {
        scope.launch {
            manager.state
                .filterIsInstance<WebSocketState.Connected>()
                .collect {
                    val ids = TEST_SUBSCRIBE_ARTIST_IDS
                    val frame = ChatFrameEncoder.subArtists(ids)
                    val accepted = manager.send(frame)
                    Timber.tag(TAG).i(
                        "⇡ sub_artists sent size=%d sample=%s accepted=%b",
                        ids.size, ids.take(3), accepted,
                    )
                }
        }
    }

    /**
     * 把 incoming SharedFlow 里所有 artist_new_work 帧解码后 fanout 到
     * [_artistNewWork]。tryEmit 理论上不会失败(extraBufferCapacity=16 +
     * server 5/min 限流),但失败时静默丢帧很难查,记一条 warn 兜底。
     */
    private fun startArtistNewWorkFanout() {
        scope.launch {
            manager.incoming
                .filterIsInstance<IncomingMessage.Text>()
                .map { ChatFrameDecoder.decode(it.text) }
                .filterIsInstance<ChatFrame.ArtistNewWork>()
                .collect { frame ->
                    Timber.tag(TAG).i(
                        "⇣ artist_new_work uid=%d target=%s/%d title=%s",
                        frame.userId, frame.targetType, frame.targetId,
                        frame.title?.take(40) ?: "-",
                    )
                    if (!_artistNewWork.tryEmit(frame)) {
                        Timber.tag(TAG).w(
                            "⚠ _artistNewWork.tryEmit dropped frame target=%s/%d (buffer full?)",
                            frame.targetType, frame.targetId,
                        )
                    }
                }
        }
    }

    /**
     * Doc §0 ("自动接收 ... 无论自己有没有打开那个聊天") requires the
     * local store to mirror every msg the WS receives, not just the ones
     * arriving while a chat fragment happens to be open.
     *
     * This subscriber decodes every incoming msg frame and UPSERTs into
     * Room — independent of any fragment's lifecycle. Result:
     *  - Messages from a peer who DMs you while you're on the home feed
     *    land in `chat_messages` immediately; when you later open the
     *    1v1 thread, the row is already there
     *  - When the fragment's `ChatListViewModel.startLiveSync` also
     *    observes the same frame, it just touches windowSize + echo
     *    correlation; the actual UPSERT is no-op since the row already
     *    exists with identical `localKey` content
     *
     * Underlying `manager.incoming` is a SharedFlow inside
     * `RobustWebSocketClient`, so adding this collector does **not** open
     * a second WS — both this persister and the per-fragment stream
     * subscription fan out from the one socket.
     */
    private fun startAlwaysOnPersister() {
        scope.launch {
            manager.incoming
                .filterIsInstance<IncomingMessage.Text>()
                .map { ChatFrameDecoder.decode(it.text) }
                .filterIsInstance<ChatFrame.Msg>()
                .collect { frame ->
                    val entity = frame.toChatMessageEntity() ?: return@collect
                    persistDao.upsert(listOf(entity))
                    Timber.tag(TAG).d(
                        "⇣ persisted cmid=%s room=%s uid=%d",
                        entity.clientMsgId, entity.room, entity.uid,
                    )
                }
        }
    }

    /**
     * Always-on raw-text logger. The chat fragment's [WsChatMessageStream]
     * also decodes + logs frames, but only while the fragment is active;
     * this app-scoped collector ensures WS traffic stays auditable in
     * logcat even when the user has never opened the chat screen.
     *
     * Underlying transport's `incoming` is a SharedFlow inside
     * `RobustWebSocketClient`, so adding this consumer does **not** open a
     * second WS — both subscribers fan out from the same single socket.
     */
    private fun startAlwaysOnRawLog() {
        scope.launch {
            manager.incoming
                .filterIsInstance<IncomingMessage.Text>()
                .collect { frame ->
                    Timber.tag(TAG_RAW).d("⇣ raw: %s", frame.text.take(200))
                }
        }
    }

    /**
     * Surface 401 handshake failures (`bad_sig` / `bad_uid` / `bad_ts` /
     * `ts_skew`) as a side flow the UI can latch onto. Per doc §7.3 these
     * are **fatal** — `ReconnectStrategy.DEFAULT_SHOULD_RECONNECT` already
     * stops auto-retries, and `ShaftHmacAuthProvider.onAuthFailure`
     * returns `false`. But the user still needs a hint that "the input
     * is greyed out because the configured key is wrong / your clock is
     * off", not because the network blipped.
     *
     * `replay=0` (don't replay stale failures to a fragment that opens
     * after the user has already re-logged in and recovered) +
     * `extraBufferCapacity=1` so a single fast 401 isn't lost between
     * `tryEmit` and the fragment's first `collect`.
     */
    private fun startFatalAuthMonitor() {
        scope.launch {
            manager.events
                .filterIsInstance<WebSocketEvent.Failure>()
                .filter { it.responseCode == 401 }
                .collect { failure ->
                    Timber.tag(TAG).w(
                        "⚠ auth fatal — 401 on WS handshake (cause=%s)",
                        failure.throwable.message ?: failure.throwable.javaClass.simpleName,
                    )
                    _fatalAuth.tryEmit(Unit)
                }
        }
    }

    /**
     * Periodic state log so OkHttp's invisible RFC ping/pong has a
     * visible app-level analogue. 30s aligns with the configured
     * `pingInterval` (docs §3.1). One line every cycle while the app
     * is up.
     */
    private fun startHeartbeatProbe() {
        scope.launch {
            while (isActive) {
                val s = manager.state.value
                Timber.tag(TAG_HB).i("WS state=%s", s.simpleName())
                delay(30_000L)
            }
        }
    }

    private fun WebSocketState.simpleName(): String = when (this) {
        is WebSocketState.Idle -> "Idle"
        is WebSocketState.Connecting -> "Connecting"
        is WebSocketState.Connected -> {
            // sinceMillis = system uptime at handshake; subtract from now for
            // actual connection duration (the raw value reads like "89 hours"
            // because it's just the device's boot-relative clock at open).
            val durationMs = android.os.SystemClock.uptimeMillis() - sinceMillis
            "Connected(uptime=${durationMs / 1_000}s)"
        }
        is WebSocketState.Reconnecting -> "Reconnecting(attempt=$attempt, in=${delayMillis}ms)"
        is WebSocketState.Disconnected -> "Disconnected"
    }

    // ── Public API ────────────────────────────────────────────────────────

    val state: StateFlow<WebSocketState> get() = manager.state
    val incoming: Flow<IncomingMessage> get() = manager.incoming
    val chatStream: ChatMessageStream<ChatMessageEntity> get() = stream
    val helloFrames: Flow<ChatFrame.Hello> get() = stream.helloFrames
    val errorFrames: Flow<ChatFrame.Err> get() = stream.errorFrames
    val typingFrames: Flow<ChatFrame.Typing> get() = stream.typingFrames

    /**
     * 画师订阅推送(测试中)。订阅方:
     *  - 测试 logger / Toast bridge — 验证链路通
     *  - 未来:Banner bridge,跳作品详情页
     *
     * 没人订阅时 SharedFlow 默认丢弃帧(replay=0),不会内存累积。
     */
    val artistNewWork: Flow<ChatFrame.ArtistNewWork> get() = _artistNewWork

    /**
     * 401 handshake failure — `bad_sig` / `bad_ts` / `ts_skew` / `bad_uid`
     * (server can't tell them apart from this side; see doc §2.2). Server
     * 401 is **fatal**: `ShaftHmacAuthProvider.onAuthFailure` returns
     * `false` and `ReconnectStrategy.DEFAULT_SHOULD_RECONNECT` treats 401
     * as terminal. Emit so the UI can prompt the user with a concrete
     * recovery action ("check system clock" / "contact dev").
     */
    val fatalAuth: Flow<Unit> get() = _fatalAuth

    /**
     * Fires once per `onClose(1008, "replaced")` — doc §1.1 "同 uid 顶号"
     * (server kicked this connection because the user logged in elsewhere
     * and we exceeded the 5-connection-per-uid cap as the oldest).
     *
     * Per doc §7.3 the client **must not auto-reconnect** in this case;
     * `ReconnectStrategy.DEFAULT_SHOULD_RECONNECT` already excludes 1008
     * from `FATAL_CLOSE_CODES`. This flow exists so the UI can surface a
     * dedicated message ("another device just logged in") instead of a
     * generic "disconnected".
     */
    // `get()` accessor (not eager `val =`) so the property doesn't touch
    // [manager] until after [bootstrap] has set it — eager evaluation runs
    // inside the gateway's `<clinit>` and crashed with
    // UninitializedPropertyAccessException ("manager has not been initialized")
    // the first time Shaft.onCreate referenced the `object`.
    val replacedByOtherDevice: Flow<Unit>
        get() = manager.events
            .filterIsInstance<WebSocketEvent.Closed>()
            .filter { it.code == 1008 && it.reason == "replaced" }
            .map { Unit }

    /**
     * Dispatch a `msg` frame. Routes by [toUid]:
     *  - `null` → public global frame (`{kind:"msg", room:"global", …}`)
     *  - non-null peer uid → 1v1 frame (`{kind:"msg", to_uid:X, …}`)
     *
     * Returns `true` if the frame entered the outgoing buffer, `false` if
     * the manager has no active session or the client rejected it.
     * **Not an end-to-end ACK** — the broadcast echo is the actual ACK
     * (doc §4.3); the VM correlates by `clientMsgId` and flips local
     * `state=Sending` → `Delivered` on echo.
     *
     * Caller (`ChatListViewModel.sendText`) is responsible for the
     * 2048-UTF-16-unit cap and for generating a fresh
     * `clientMsgId` per call. This method makes no further validation
     * beyond non-empty text.
     */
    fun send(toUid: Long?, clientMsgId: String, text: String, illustId: Long? = null): Boolean {
        if (text.isEmpty()) return false
        val frame = if (toUid == null) {
            ChatFrameEncoder.msgGlobal(clientMsgId, text, illustId)
        } else {
            ChatFrameEncoder.msg1v1(toUid, clientMsgId, text, illustId)
        }
        val accepted = manager.send(frame)
        if (accepted) {
            Timber.tag(TAG).i(
                "⇡ msg sent to=%s cmid=%s illust=%s text=%s",
                toUid?.toString() ?: "global",
                clientMsgId,
                illustId?.toString() ?: "-",
                text.take(80),
            )
        } else {
            Timber.tag(TAG).w("⇡ send rejected by WebSocketManager (no active session?)")
        }
        return accepted
    }

    /**
     * Dispatch a `typing` frame. DM-only — server rejects `room:"global"`
     * with `typing_forbidden_for_global`, so we don't expose a global-room
     * overload (the VM also short-circuits when toUid == null).
     *
     * Returns `true` if buffered for send, `false` if no active session.
     * **Not an end-to-end ACK** — typing is fire-and-forget; if the WS
     * is mid-reconnect the frame is silently dropped and the peer's 5-second
     * client-side timeout handles the stale "正在输入..." indicator on its end.
     *
     * [state] is `"start"` (default) or `"stop"`. Caller (VM) gates start
     * frames behind a ~4s debounce so we don't flood the per-conn typing
     * bucket (10 frames / 10s on server). `stop` is always sent unbucketed
     * from the VM's perspective; if it gets rate-limited server-side, peer
     * self-heals via timeout.
     */
    fun sendTyping(toUid: Long, state: String? = null): Boolean {
        val frame = ChatFrameEncoder.typing1v1(toUid, state)
        val accepted = manager.send(frame)
        Timber.tag(TAG).d(
            "⇡ typing to=%d state=%s accepted=%b",
            toUid, state ?: "start", accepted,
        )
        return accepted
    }
}
