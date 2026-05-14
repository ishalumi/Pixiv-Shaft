package ceui.pixiv.chat.api

import android.app.Application
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
import ceui.pixiv.websocket.WebSocketEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
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

    /**
     * Idempotent. Safe to call from `Application.onCreate` after
     * `EventReporter.init`. Subsequent calls are no-ops.
     */
    fun bootstrap(app: Application) {
        if (!bootstrapped.compareAndSet(false, true)) return

        // Chat is anonymous — there's no "logout" signal. Pin activation to
        // `true` and let the manager treat the process lifetime as one
        // continuous session.
        val ready: StateFlow<Boolean> = MutableStateFlow(true)

        manager = WebSocketManager(
            loggedIn = ready,
            createClient = { _ ->
                Timber.tag(TAG).i("createClient: building shaft chat WS")
                ShaftChatWsClient.create(app)
            },
            // WebSocketConfig is required by the manager's signature but
            // ShaftHmacAuthProvider.dynamicUrl() rewrites the URL per
            // connect attempt, so this placeholder URL is never used.
            config = WebSocketConfig(url = "ws://placeholder.invalid/"),
        )
        manager.start()
        Timber.tag(TAG).i("bootstrap complete — manager.start() invoked")

        // Wire the shared stream that the chat fragment consumes. Built
        // here so its hello/err side flows are app-scoped (a fragment that
        // arrives mid-session can still see the last hello via replay=1).
        stream = WsChatMessageStream(manager.incoming)
        persistDao = ChatDatabase.getInstance(app).chatMessageDao()

        startHeartbeatProbe()
        startAlwaysOnRawLog()
        startAlwaysOnPersister()
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
}
