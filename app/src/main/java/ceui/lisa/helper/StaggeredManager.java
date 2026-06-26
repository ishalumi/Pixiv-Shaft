package ceui.lisa.helper;

import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.view.View;

import androidx.recyclerview.widget.LinearSmoothScroller;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.StaggeredGridLayoutManager;

public class StaggeredManager extends StaggeredGridLayoutManager {

    public StaggeredManager(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    public StaggeredManager(int spanCount, int orientation) {
        super(spanCount, orientation);
    }

    @Override
    public void onScrollStateChanged(int state) {
        try {
            super.onScrollStateChanged(state);
        } catch (IndexOutOfBoundsException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onLayoutChildren(RecyclerView.Recycler recycler, RecyclerView.State state) {
        // SGLM 的 predictive-animation 预布局(dispatchLayoutStep1)在快速 fling
        // (ViewFlinger.run) + 列表插入新页同帧发生时，框架内部把 pending insert
        // 跟 scrap holder 偏移对不上，抛 "Inconsistency detected. Invalid view
        // holder adapter position" 的 IndexOutOfBoundsException。我们的 notify 计数
        // 是对的，host app 在数据层无法阻止这个 AOSP 内部 bug。在 LayoutManager 这一层
        // 兜住只丢掉这一次坏的布局，fling 不被打断，下一帧按 getItemCount() 干净重建——
        // 比让异常一路冒到 Shaft 主线程兜底(那会整帧 Choreographer 回调全废、fling 卡死)更精准。
        try {
            super.onLayoutChildren(recycler, state);
        } catch (IndexOutOfBoundsException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void smoothScrollToPosition(RecyclerView recyclerView, RecyclerView.State state, int position) {
        LinearSmoothScroller scroller = new LinearSmoothScroller(recyclerView.getContext()){
            @Override
            protected int getVerticalSnapPreference() {
                return SNAP_TO_START;
            }

            @Override
            protected void onTargetFound(View targetView, RecyclerView.State state, Action action) {
                try {
                    /*
                     * Android 判断一个 View 是否可见 getLocalVisibleRect(rect) 与 getGlobalVisibleRect(rect)
                     *
                     * https://www.bbsmax.com/A/ELPdow2d3a/
                     */

                    if (!targetView.getGlobalVisibleRect(new Rect())) {
                        Rect rect = new Rect();
                        recyclerView.getGlobalVisibleRect(rect);

                        int parentHeight = rect.bottom - rect.top;
                        int childHeight = targetView.getHeight();
                        int offset = (parentHeight - childHeight) / 2;

                        final int dx = calculateDxToMakeVisible(targetView, getHorizontalSnapPreference());
                        final int dy = calculateDyToMakeVisible(targetView, getVerticalSnapPreference()) + offset;
                        final int distance = (int) Math.sqrt(dx * dx + dy * dy);
                        final int time = calculateTimeForDeceleration(distance);
                        if (time > 0) {
                            action.update(-dx, -dy, time, mDecelerateInterpolator);
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

            @Override
            protected float calculateSpeedPerPixel(DisplayMetrics displayMetrics) {
                return 40f / displayMetrics.densityDpi;
            }
        };
        scroller.setTargetPosition(position);
        startSmoothScroll(scroller);
    }

}
