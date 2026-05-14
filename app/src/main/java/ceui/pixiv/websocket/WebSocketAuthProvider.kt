package ceui.pixiv.websocket

/**
 * Pluggable credentials hook for [RobustWebSocketClient]. Owns three things:
 *
 * 1. **Computing fresh headers per connect attempt** ([headers]) — so a
 *    background token refresh is automatically picked up on the next
 *    (re)connect without anyone manually rebuilding the [WebSocketConfig].
 * 2. **Recognising auth failures** ([isAuthFailure]) — the default rule
 *    treats HTTP 401 as auth failure, but app-specific schemes (e.g. WS
 *    close code 4401, or 1008 with a magic reason) can override.
 * 3. **Performing the refresh** ([onAuthFailure]) — called by the client
 *    after an auth failure, on a background coroutine and *outside* the
 *    client's lifecycle lock, so it is safe to do blocking I/O here.
 *
 * The default Bearer-token implementation is [BearerTokenAuthProvider],
 * which delegates refresh to a `network.IToken` (the same interface the
 * REST `TokenAuthenticator` uses, so a single `SessionManager` instance
 * can serve both REST and WebSocket clients with one shared refresh lock).
 *
 * Custom implementations can carry any auth scheme: query params (return a
 * map and have the caller embed in [WebSocketConfig.url] via a wrapper),
 * cookies (`Cookie` header), `Sec-WebSocket-Protocol` token, etc.
 */
interface WebSocketAuthProvider {

    /**
     * Compute the headers to attach to the next (re)connect attempt. Called
     * by [RobustWebSocketClient] every time it builds the upgrade request,
     * so a value refreshed in the background by [onAuthFailure] (or by any
     * other code path that updates the underlying credential store) is
     * automatically applied on the next attempt.
     *
     * **Threading.** Called on whichever thread is driving the connect —
     * typically the client's lifecycle lock holder. Implementations must be
     * cheap and **must not** block on I/O. Pull from an in-memory cache;
     * don't decrypt, don't network. The default
     * [BearerTokenAuthProvider] respects this by relying on
     * `IToken.getAuthToken()`'s in-memory cache.
     */
    fun headers(): Map<String, String>

    /**
     * Decide whether [failure] indicates that the credentials are no longer
     * valid and a refresh should be attempted before the next reconnect.
     *
     * The default treats HTTP 401 on the upgrade response as the canonical
     * auth failure signal. Override to add app-specific signals — e.g. a
     * server that closes the socket with WebSocket code 4401 instead of
     * answering the upgrade with HTTP 401:
     *
     * ```
     * override fun isAuthFailure(failure: FailureContext): Boolean = when (failure) {
     *     is FailureContext.Failure -> failure.httpCode == 401
     *     is FailureContext.Closed  -> failure.code == 4401
     * }
     * ```
     */
    fun isAuthFailure(failure: FailureContext): Boolean =
        failure is FailureContext.Failure && failure.httpCode == 401

    /**
     * Refresh credentials in response to an auth failure. Called by
     * [RobustWebSocketClient] after [isAuthFailure] returns `true`, on a
     * background coroutine, **without holding any lock**. Implementations
     * are free to do blocking I/O (e.g. an HTTP refresh call).
     *
     * @param failedHeaders the exact headers that were sent on the failing
     *                      attempt — typically used to extract the rejected
     *                      token so the underlying refresh primitive can
     *                      dedup concurrent refreshes (e.g.
     *                      `IToken.refreshTokenSync(failedToken)`).
     * @return `true` if the refresh succeeded and the next call to
     *         [headers] will return updated credentials. The client will
     *         immediately attempt one more reconnect.
     *         `false` if the refresh failed (refresh token rejected, network
     *         error, …). The client will treat the auth failure as fatal
     *         and transition to [WebSocketState.Disconnected].
     */
    fun onAuthFailure(failedHeaders: Map<String, String>): Boolean
}
