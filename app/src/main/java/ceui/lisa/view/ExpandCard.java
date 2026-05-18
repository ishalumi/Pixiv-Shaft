package ceui.lisa.view;

import android.content.Context;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.cardview.widget.CardView;
import androidx.recyclerview.widget.RecyclerView;

/**
 * 折叠语义已迁移到 IllustDetailAdapter（按 itemCount 折叠，不再裁容器高度）。
 * 该类保留为薄 CardView 壳，仅同步内层 RecyclerView 的滚动开关，避免 xml/binding 类型改动。
 * open()/close() 不再修改 layoutParams.height —— 那是 issue #549 白边的根因。
 */
public class ExpandCard extends CardView {

    private boolean isExpand = true;

    public ExpandCard(@NonNull Context context) {
        super(context);
    }

    public ExpandCard(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public ExpandCard(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void open() {
        if (isExpand) return;
        setInnerScrollEnabled(true);
        isExpand = true;
    }

    public void close() {
        if (!isExpand) return;
        setInnerScrollEnabled(false);
        isExpand = false;
    }

    public boolean isExpand() {
        return isExpand;
    }

    private void setInnerScrollEnabled(boolean enabled) {
        for (int i = 0; i < getChildCount(); i++) {
            if (getChildAt(i) instanceof RecyclerView) {
                RecyclerView rv = (RecyclerView) getChildAt(i);
                if (rv.getLayoutManager() instanceof ScrollChange) {
                    ((ScrollChange) rv.getLayoutManager()).setScrollEnabled(enabled);
                    break;
                }
            }
        }
    }
}
