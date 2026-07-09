package ceui.lisa.view;

import android.graphics.Rect;
import android.view.View;

import androidx.recyclerview.widget.RecyclerView;

/**
 * 横向货架专用间距:只在卡片之间留右间距,不加任何上下 inset,首卡也不加左 inset。
 * 首卡左缘统一由 RecyclerView 的 paddingStart 控制,这样所有货架第一张卡都对齐到同一条 20dp 竖线;
 * 上下 inset 交给 section 的 header padding 统一管,避免 {@link LinearItemHorizontalDecoration}
 * 那样每张卡上下各塞一截、把纵向间距撑得忽大忽小。
 */
public class HorizontalSpaceDecoration extends RecyclerView.ItemDecoration {

    private final int gap;

    public HorizontalSpaceDecoration(int gap) {
        this.gap = gap;
    }

    @Override
    public void getItemOffsets(Rect outRect, View view,
                               RecyclerView parent, RecyclerView.State state) {
        outRect.right = gap;
    }
}
