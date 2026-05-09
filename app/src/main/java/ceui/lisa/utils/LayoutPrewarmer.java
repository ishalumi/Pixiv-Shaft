package ceui.lisa.utils;

import android.content.Context;
import android.os.SystemClock;
import android.view.View;

import androidx.annotation.LayoutRes;
import androidx.annotation.MainThread;
import androidx.annotation.Nullable;
import androidx.asynclayoutinflater.view.AsyncLayoutInflater;

/**
 * 单槽位的异步布局预热缓存。caller 在用户即将进入某个页面前调用 {@link #prewarm}
 * 把 layout 提前 inflate 出来,目标页 onCreateView 时调用 {@link #consume} 取走。
 *
 * 注意:为避免 Activity context 泄露,持有时间用 TTL 兜底。caller 也应该在自己 onPause
 * 时调用 {@link #clear} 主动释放。
 */
public final class LayoutPrewarmer {

    private static final long TTL_MS = 5_000L;

    @Nullable private static View sCachedView;
    @LayoutRes private static int sCachedLayoutId = 0;
    private static long sCachedAtMs = 0L;

    private LayoutPrewarmer() {}

    @MainThread
    public static void prewarm(Context context, @LayoutRes int layoutId) {
        if (sCachedView != null && sCachedLayoutId == layoutId
                && SystemClock.uptimeMillis() - sCachedAtMs < TTL_MS) {
            return;
        }
        new AsyncLayoutInflater(context).inflate(layoutId, null, (view, resid, parent) -> {
            sCachedView = view;
            sCachedLayoutId = resid;
            sCachedAtMs = SystemClock.uptimeMillis();
        });
    }

    @MainThread
    @Nullable
    public static View consume(@LayoutRes int layoutId) {
        View v = sCachedView;
        int id = sCachedLayoutId;
        long age = SystemClock.uptimeMillis() - sCachedAtMs;
        sCachedView = null;
        sCachedLayoutId = 0;
        if (v != null && id == layoutId && age < TTL_MS && v.getParent() == null) {
            return v;
        }
        return null;
    }

    @MainThread
    public static void clear() {
        sCachedView = null;
        sCachedLayoutId = 0;
    }
}
