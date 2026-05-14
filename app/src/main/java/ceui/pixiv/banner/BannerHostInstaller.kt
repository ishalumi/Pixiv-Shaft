package ceui.pixiv.banner

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.lifecycle.LifecycleOwner
import timber.log.Timber
import java.util.WeakHashMap

/**
 * Installs a [BannerHost] overlay onto every [BannerHostOwner] Activity.
 * Register once at [Application.onCreate].
 */
class BannerHostInstaller(
    private val manager: BannerManager,
) : Application.ActivityLifecycleCallbacks {

    private val presenters = WeakHashMap<Activity, BannerPresenter>()

    // `onActivityPostCreated` only fires on API 29+. minSdk here is 24, so we
    // install during `onActivityCreated` which also runs after the Activity's
    // own onCreate has returned (and therefore after setContentView).
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        if (activity !is BannerHostOwner) return
        if (activity !is LifecycleOwner) {
            Timber.tag(TAG).e(
                "Activity %s implements BannerHostOwner but not LifecycleOwner; banner host will not be installed.",
                activity.javaClass.simpleName,
            )
            return
        }

        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        if (content == null) {
            Timber.tag(TAG).e("android.R.id.content not found on %s", activity.javaClass.simpleName)
            return
        }

        val host = BannerHost(activity).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ).apply { gravity = Gravity.TOP }
            elevation = 16f * activity.resources.displayMetrics.density
        }
        content.addView(host)

        val presenter = BannerPresenter(host, manager, activity.lifecycle)
        presenter.attach()
        presenters[activity] = presenter
        Timber.tag(TAG).d("Installed BannerHost on %s", activity.javaClass.simpleName)
    }

    override fun onActivityDestroyed(activity: Activity) {
        presenters.remove(activity)?.detach()
    }

    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    companion object {
        private const val TAG = "BannerInstaller"
    }
}
