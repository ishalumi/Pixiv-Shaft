package ceui.pixiv.banner

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import ceui.lisa.activities.TemplateActivity
import ceui.pixiv.ui.notification.routeNotificationTargetUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-wide entry point for the in-app banner system.
 *
 * Constructs the [BannerManager] with the default text-card binder, registers
 * a [BannerHostInstaller] on the [Application] (so every
 * [BannerHostOwner] activity gets a host overlay automatically), and starts
 * the WS → banner bridge so chat msg frames surface as banners.
 *
 * Call [bootstrap] once from `Application.onCreate` *after*
 * `ShaftChatGateway.bootstrap` — the WS bridge subscribes to the gateway's
 * `incoming` flow and that flow is only safe to touch after bootstrap.
 */
object InAppBanners {

    private const val TAG = "InAppBanners"
    private const val SCHEME = "shaft"
    private const val HOST_CHAT = "chat"

    private val bootstrapped = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val foreground = ForegroundTracker()

    lateinit var manager: BannerManager
        private set

    /**
     * Currently-resumed Activity, or `null` if no Activity is in the foreground.
     * Exposed for the WS → banner bridge to suppress banners while the user is
     * already viewing the source room.
     */
    fun currentActivity(): Activity? = foreground.current()

    fun bootstrap(app: Application) {
        if (!bootstrapped.compareAndSet(false, true)) return

        val binders = mapOf<String, BannerViewBinder>(
            BannerViewBinder.DEFAULT_KEY to DefaultBannerViewBinder(),
        )
        manager = RealBannerManager(binders = binders)
        manager.start()

        app.registerActivityLifecycleCallbacks(BannerHostInstaller(manager))
        app.registerActivityLifecycleCallbacks(foreground)

        ChatBannerBridge(manager, scope).start()
        ArtistNewWorkBannerBridge(manager, scope).start()

        scope.launch {
            manager.events
                .filterIsInstance<BannerEvent.Tapped>()
                .collect { handleTap(app, it.deepLink) }
        }

        Timber.tag(TAG).i("InAppBanners bootstrap complete")
    }

    private suspend fun handleTap(app: Application, deepLink: String?) {
        deepLink ?: return
        val uri = runCatching { Uri.parse(deepLink) }.getOrNull() ?: return

        // pixiv:// (illusts/novels/users)— 复用通知路由层的统一出口,免得在这里
        // 重复维护"打开作品/小说/用户"的 Intent 拼装逻辑。banner 只在 app 前台
        // 时可见,所以 foreground activity 应该有;没有的话 (异常时序) 就放弃
        // navigate,不靠 Application + NEW_TASK 兜底 — banner 不该在背景态点。
        if (uri.scheme == "pixiv") {
            val activity = foreground.current() ?: run {
                Timber.tag(TAG).w("pixiv:// banner tapped but no foreground activity")
                return
            }
            withContext(Dispatchers.Main) {
                try {
                    activity.routeNotificationTargetUrl(deepLink)
                } catch (t: Throwable) {
                    Timber.tag(TAG).w(t, "Failed to route pixiv:// banner tap")
                }
            }
            return
        }

        if (uri.scheme != SCHEME || uri.host != HOST_CHAT) return

        val activity = foreground.current()
        val ctx: Context = activity ?: app
        val peer = uri.getQueryParameter("peer")?.toLongOrNull() ?: 0L
        val intent = Intent(ctx, TemplateActivity::class.java).apply {
            if (peer > 0L) {
                // 1v1: open the per-conversation chat fragment directly,
                // not the list — the user explicitly wants this thread.
                putExtra(TemplateActivity.EXTRA_FRAGMENT, "聊天室")
                putExtra(TemplateActivity.EXTRA_CHAT_PEER_UID, peer)
            } else {
                // Global: dispatch through the dedicated "open global" case
                // so we don't bounce to the new conversation list (which
                // now owns the bare "聊天室" route without a peer).
                putExtra(TemplateActivity.EXTRA_FRAGMENT, "聊天-全员公屏")
            }
            if (activity == null) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        withContext(Dispatchers.Main) {
            try {
                ctx.startActivity(intent)
            } catch (t: Throwable) {
                Timber.tag(TAG).w(t, "Failed to launch chat from banner tap")
            }
        }
    }

    /**
     * Cheapest possible foreground-activity tracker. WeakReference so we never
     * keep an Activity alive past `onDestroy`, and we only care about which
     * Activity is currently `RESUMED` for the purpose of launching the next
     * Activity off of it.
     */
    private class ForegroundTracker : Application.ActivityLifecycleCallbacks {
        @Volatile
        private var ref: WeakReference<Activity>? = null

        fun current(): Activity? = ref?.get()

        override fun onActivityResumed(activity: Activity) {
            ref = WeakReference(activity)
        }

        override fun onActivityPaused(activity: Activity) {
            if (ref?.get() === activity) ref = null
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }
}
