package ceui.pixiv.websocket

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.shareIn

/**
 * Application-scoped source of network connectivity state, backed by
 * [android.net.ConnectivityManager].
 *
 * ## Shared subscription
 *
 * [observeConnectivity] is exposed as a [SharedFlow] instead of a cold
 * `callbackFlow`. That's deliberate: a cold flow re-executes its builder
 * block on every `collect { }`, which for this class would mean registering
 * a brand-new [ConnectivityManager.NetworkCallback] per subscriber. Multiple
 * consumers (the WebSocket client, UI code that wants to grey out an
 * offline button, etc.) would each install their own system callback,
 * which is both wasteful and — at enough subscribers — bounded by the
 * Android-side hard cap on callback registrations.
 *
 * With `shareIn(WhileSubscribed)` this class registers **one** system
 * callback the first time anyone subscribes, keeps it alive while there is
 * at least one subscriber (plus a 5 s grace period to absorb
 * configuration-change churn), and tears it down after the last
 * subscription goes away. Subsequent subscriptions re-register.
 *
 * @param context        any [Context]; only the application context is held
 *                       internally.
 * @param parentContext  coroutine context for the internal scope. Defaults
 *                       to [Dispatchers.Default]; pass a test dispatcher in
 *                       unit tests.
 */
class NetworkMonitor(
    context: Context,
    parentContext: CoroutineContext = Dispatchers.Default,
) : ConnectivityObserver {

    private val cm = context.applicationContext
        .getSystemService(ConnectivityManager::class.java)

    private val scope = CoroutineScope(SupervisorJob() + parentContext)

    val isConnected: Boolean
        get() {
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }

    // Cold source — one execution of this builder block registers exactly
    // one Android NetworkCallback. shareIn() below guarantees we only ever
    // execute it once at a time regardless of subscriber count.
    private val source: Flow<Boolean> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { trySend(true) }
            override fun onLost(network: Network) {
                // A specific Network was lost, but another (e.g. cellular after
                // WiFi disconnect) may still be active. Re-check the active network
                // instead of blindly emitting false.
                trySend(isConnected)
            }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                trySend(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        cm.registerNetworkCallback(request, callback)
        // Emit initial state
        trySend(isConnected)
        awaitClose { cm.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()

    /**
     * Hot, replay-1, multi-subscriber view of connectivity. See the class
     * KDoc for why this is shared instead of cold.
     */
    override val observeConnectivity: SharedFlow<Boolean> = source.shareIn(
        scope = scope,
        started = SharingStarted.WhileSubscribed(
            stopTimeoutMillis = 5_000,
            replayExpirationMillis = 0,
        ),
        replay = 1,
    )
}
