package ceui.pixiv.chat.api

import android.app.Application
import ceui.pixiv.chat.data.ChatMessageEntity
import ceui.pixiv.chat.core.ChatMessageStream
import ceui.pixiv.websocket.IncomingMessage
import ceui.pixiv.websocket.WebSocketConfig
import ceui.pixiv.websocket.WebSocketManager
import ceui.pixiv.websocket.WebSocketState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterIsInstance
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

        startHeartbeatProbe()
        startAlwaysOnRawLog()
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
        is WebSocketState.Connected -> "Connected(since=${sinceMillis}ms)"
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
     * Send a chat `msg` frame. Returns `true` if it made it into the
     * outgoing buffer, `false` if the manager has no active session or the
     * client rejected it. **Not** an end-to-end ACK — the protocol has no
     * per-message receipt; treat the broadcast echo as the implicit ACK
     * (see docs §1.3 "应当以回声为准").
     *
     * Caps `text` at 2048 UTF-16 units client-side per docs §1.2 so we
     * don't trigger a server `err.bad_text_length` round-trip.
     */
    fun send(text: String, illustId: Long? = null): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed.length > MAX_TEXT_LENGTH) {
            Timber.tag(TAG).w("send rejected: text.length=%d > %d", trimmed.length, MAX_TEXT_LENGTH)
            return false
        }
        val frame = ChatFrameEncoder.msg(trimmed, illustId)
        val accepted = manager.send(frame)
        if (accepted) {
            Timber.tag(TAG).i(
                "⇡ msg sent illust=%s text=%s",
                illustId?.toString() ?: "-",
                trimmed.take(80),
            )
        } else {
            Timber.tag(TAG).w("⇡ send rejected by WebSocketManager (no active session?)")
        }
        return accepted
    }

    const val MAX_TEXT_LENGTH = 2048
}
