package ceui.pixiv.chat.api

import android.content.Context
import ceui.lisa.BuildConfig
import ceui.pixiv.events.EventReporter
import ceui.pixiv.websocket.ExponentialBackoffWithJitter
import ceui.pixiv.websocket.NetworkMonitor
import ceui.pixiv.websocket.RobustWebSocketClient
import ceui.pixiv.websocket.WebSocketClient
import ceui.pixiv.websocket.WebSocketConfig
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Factory for the shaft-api-v2 chat [WebSocketClient]. Wires:
 *  - [ShaftHmacAuthProvider] (signs the upgrade URL per attempt)
 *  - [NetworkMonitor]        (fast-reconnect on OFFLINE → ONLINE)
 *  - OkHttp with `pingInterval=30s` + `readTimeout=0` (per docs §3.1)
 *  - [ExponentialBackoffWithJitter] reconnect strategy
 *
 * The placeholder URL in [WebSocketConfig] is required by the config's
 * `init` validator (must start with `ws://` / `wss://`) but is **always
 * overridden** at connect time via [ShaftHmacAuthProvider.dynamicUrl].
 */
object ShaftChatWsClient {

    /**
     * Build a fresh, not-yet-connected chat WS client. The caller owns its
     * lifecycle — call [WebSocketClient.connect] when the chat screen
     * appears, [WebSocketClient.close] when it goes away. Each instance is
     * a one-shot bound to a single fragment / session; don't try to share.
     */
    fun create(context: Context): WebSocketClient {
        val authProvider = ShaftHmacAuthProvider(
            baseHttpUrl = BuildConfig.SHAFT_EVENTS_BASE_URL,
            secretAscii = BuildConfig.SHAFT_EVENTS_HMAC,
            clientIdProvider = { EventReporter.currentClientId() },
        )

        val placeholderUrl = ShaftHmacAuthProvider.deriveWsBase(
            BuildConfig.SHAFT_EVENTS_BASE_URL
        ) + "/api/v1/chat/ws"

        val okHttp = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            // readTimeout is enforced inside RobustWebSocketClient (set to 0
            // there). writeTimeout is fine to set on the base client.
            .writeTimeout(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        val config = WebSocketConfig(
            url = placeholderUrl,
            pingIntervalMillis = TimeUnit.SECONDS.toMillis(30),
            reconnectStrategy = ExponentialBackoffWithJitter(
                initialDelayMillis = 1_000,
                maxDelayMillis = 30_000,
                multiplier = 2.0,
                jitterFactor = 0.2,
                maxAttempts = Int.MAX_VALUE,
            ),
            // 401 from this server is fatal (bad_sig / bad_ts / ts_skew / bad_client_id
            // all unfixable by retry with same credentials). ShaftHmacAuthProvider.onAuthFailure
            // already returns false; this just keeps the budget tight for safety.
            maxAuthRefreshAttempts = 0,
        )

        return RobustWebSocketClient(
            baseClient = okHttp,
            config = config,
            authProvider = authProvider,
            connectivityObserver = NetworkMonitor(context.applicationContext),
        )
    }
}
