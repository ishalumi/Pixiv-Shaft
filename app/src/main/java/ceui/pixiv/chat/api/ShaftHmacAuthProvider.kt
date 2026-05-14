package ceui.pixiv.chat.api

import ceui.pixiv.shaftapi.ShaftHmac
import ceui.pixiv.websocket.FailureContext
import ceui.pixiv.websocket.WebSocketAuthProvider
import timber.log.Timber

/**
 * shaft-api-v2 chat-WS auth provider. The server authenticates the WebSocket
 * upgrade by inspecting the **query string** (`client_id` / `ts` / `sig`) —
 * headers and cookies are ignored. So unlike a Bearer-token provider, the
 * URL itself must be re-signed for every reconnect attempt; that's the
 * [dynamicUrl] override.
 *
 * **Signature canonicalisation matters** (see `docs/ws-chat-integration.md`
 * §2.1 point 3): the literal decimal string of `ts` is signed and the
 * **same** string is embedded in the URL. We hold the string and reuse it
 * twice to avoid any `Long → "1.7e12"` formatting drift between sign-time
 * and put-on-wire that would otherwise look like a `bad_sig` 401 on the
 * server side.
 *
 * 401 close codes / handshake responses from this auth scheme are fatal —
 * either the key is misconfigured or the client's clock is too far skewed.
 * Returning `false` from [onAuthFailure] tells [RobustWebSocketClient][ceui.pixiv.websocket.RobustWebSocketClient]
 * to stop retrying (status moves to [FailureContext.Closed] / disconnect).
 */
class ShaftHmacAuthProvider(
    private val baseHttpUrl: String,
    private val secretAscii: String,
    private val clientIdProvider: () -> String,
) : WebSocketAuthProvider {

    /** Shaft-WS doesn't authenticate on headers — only the query string. */
    override fun headers(): Map<String, String> = emptyMap()

    override fun dynamicUrl(): String? {
        val cid = clientIdProvider()
        if (cid.isEmpty()) {
            // EventReporter hasn't initialised yet (very early app start).
            // Caller should retry the connect a bit later. Returning null
            // would fall back to WebSocketConfig.url, which is wrong here —
            // throw instead so RobustWebSocketClient's onFailure path kicks
            // in (logged and counted as a normal failure → reconnect with
            // backoff; by next attempt EventReporter has usually been
            // initialised).
            throw IllegalStateException("client_id not available yet (EventReporter.init pending)")
        }
        val ts = System.currentTimeMillis().toString()
        val sig = ShaftHmac.signClientIdTs(cid, ts, secretAscii)
        val signed = deriveWsBase(baseHttpUrl) +
            "/api/v1/chat/ws?client_id=$cid&ts=$ts&sig=$sig"
        if (LOG_URLS) Timber.tag(TAG).d("signed URL = %s", signed)
        return signed
    }

    /**
     * 401 on the handshake is fatal for this scheme — `bad_sig` means the
     * key is wrong, `bad_ts` / `ts_skew` means clock is broken, `bad_client_id`
     * means EventReporter produced something invalid. None of those can be
     * fixed by retrying the same request, so we tell [WebSocketAuthProvider]
     * to surrender to `FailureContext.Failure(..., httpCode=401)`.
     */
    override fun onAuthFailure(failedHeaders: Map<String, String>): Boolean {
        Timber.tag(TAG).w("auth failure — likely bad_sig / ts_skew / bad_client_id")
        return false
    }

    companion object {
        private const val TAG = "Chat-Auth"
        /** Set true to dump signed handshake URLs at DEBUG; off by default —
         *  URLs contain the rotating HMAC sig which is mildly sensitive. */
        private const val LOG_URLS = false

        fun deriveWsBase(httpBase: String): String =
            httpBase.trimEnd('/')
                .replaceFirst("https://", "wss://")
                .replaceFirst("http://", "ws://")
    }
}
